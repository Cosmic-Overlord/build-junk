package io.github.cosmicoverlord.boardingpass.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.cosmicoverlord.boardingpass.core.model.AppJson
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.seatmap.AircraftMatcher
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMap
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapException
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapLogic
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapsParser
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapsUrls
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmAircraft
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmAirline
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmVariant
import io.github.cosmicoverlord.boardingpass.core.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

sealed interface SeatMapResult {
    data class Ready(
        val airlineSlug: String,
        val aircraft: SmAircraft,
        val fleet: List<SmAircraft>,
        val variants: List<SmVariant>,
        val maps: Map<String, SeatMap>,
        /** Variants on which the pass's seat exists in its cabin. */
        val candidates: List<String>,
        val selected: String,
    ) : SeatMapResult {
        val map: SeatMap get() = maps.getValue(selected)
        val pageUrl: String get() = SeatMapsUrls.variantPage(airlineSlug, aircraft.slug, selected)
    }

    data class ChooseAircraft(val airlineSlug: String, val fleet: List<SmAircraft>, val reason: String) : SeatMapResult

    data class Failed(val message: String, val webUrl: String?) : SeatMapResult
}

/**
 * Seat maps from seatmaps.com, downloaded only when the user opens one and cached on the device
 * (personal use, never bundled or shared). The aircraft is matched from the status provider's
 * model; the layout variant from the pass's seat and cabin.
 */
class SeatMapRepository(private val context: Context) {
    private val dir = File(context.cacheDir, "seatmaps").apply { mkdirs() }
    private val pages = PageFetcher(context)

    suspend fun load(pass: BoardingPass, force: Boolean = false): SeatMapResult {
        var webUrl: String? = SeatMapsUrls.INDEX
        return try {
            val carrier = pass.carrier.trim().uppercase()
            if (carrier.isEmpty()) return SeatMapResult.Failed(tr("В талоне нет авиакомпании", "The pass has no airline"), webUrl)
            val index = index(force)
            val airline = index.firstOrNull { it.code == carrier }
                ?: return SeatMapResult.Failed(
                    tr("На seatmaps.com нет авиакомпании $carrier", "seatmaps.com doesn't list airline $carrier"),
                    webUrl,
                )
            webUrl = SeatMapsUrls.airline(airline.slug)
            val fleet = fleet(airline.slug, force)

            val explicit = pass.seatMapAircraft?.let { slug -> fleet.firstOrNull { it.slug == slug } }
            val model = pass.status?.aircraftModel
            val aircraft = explicit
                ?: AircraftMatcher.match(model, fleet)
                ?: fleet.singleOrNull()
                ?: return SeatMapResult.ChooseAircraft(
                    airline.slug,
                    fleet,
                    if (model == null) {
                        tr("Тип самолёта пока неизвестен — выберите его из флота", "The aircraft type isn't known yet — pick it from the fleet")
                    } else {
                        tr("«$model» не нашёлся во флоте — выберите самолёт", "\"$model\" isn't in the fleet — pick the aircraft")
                    },
                )
            webUrl = SeatMapsUrls.aircraft(airline.slug, aircraft.slug)

            val variants = variants(airline.slug, aircraft.slug, force)
            val maps = variants.associate { it.id to seatMap(it.id, force) }
            val candidates = SeatMapLogic.candidates(maps.values.toList(), pass.seat, pass.compartment)
            val selected = pass.seatMapVariant?.takeIf { it in maps }
                ?: candidates.firstOrNull()
                ?: variants.first().id
            SeatMapResult.Ready(airline.slug, aircraft, fleet, variants, maps, candidates, selected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Seat map failed", e)
            val message = (e as? SeatMapException)?.message
                ?: tr("Не удалось загрузить схему с seatmaps.com", "Couldn't load the seat map from seatmaps.com")
            SeatMapResult.Failed(message, webUrl)
        }
    }

    private suspend fun index(force: Boolean): List<SmAirline> =
        cached("index.json", ListSerializer(SmAirline.serializer()), DAY * 30, force) {
            SeatMapsParser.parseIndex(pages.fetch(SeatMapsUrls.INDEX, SeatMapsUrls.INDEX_MARKER))
        }

    private suspend fun fleet(airline: String, force: Boolean): List<SmAircraft> =
        cached("fleet-${safe(airline)}.json", ListSerializer(SmAircraft.serializer()), DAY * 14, force) {
            SeatMapsParser.parseFleet(pages.fetch(SeatMapsUrls.airline(airline), SeatMapsUrls.FLEET_MARKER), airline)
        }

    private suspend fun variants(airline: String, aircraft: String, force: Boolean): List<SmVariant> =
        cached("variants-${safe(airline)}-${safe(aircraft)}.json", ListSerializer(SmVariant.serializer()), DAY * 14, force) {
            SeatMapsParser.parseVariants(pages.fetch(SeatMapsUrls.aircraft(airline, aircraft), SeatMapsUrls.AIRCRAFT_MARKER))
        }

    private suspend fun seatMap(id: String, force: Boolean): SeatMap =
        cached("map2-${safe(id)}.json", SeatMap.serializer(), DAY * 90, force) {
            SeatMapsParser.parseSeatMap(pages.fetch(SeatMapsUrls.seatMap(id), SeatMapsUrls.SEATMAP_MARKER), id)
        }

    private suspend fun <T> cached(name: String, serializer: KSerializer<T>, ttl: Long, force: Boolean, load: suspend () -> T): T {
        val file = File(dir, name)
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < ttl) {
            withContext(Dispatchers.IO) {
                runCatching { AppJson.decodeFromString(serializer, file.readText()) }.getOrNull()
            }?.let { return it }
        }
        val fresh = load()
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(AppJson.encodeToString(serializer, fresh)) }
        }
        return fresh
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val TAG = "SeatMapRepository"
        const val DAY = 24 * 60 * 60 * 1000L
    }
}

/**
 * Plain HTTP first; if the site answers with something other than the expected page (a bot
 * check, an error page), load it in an off-screen WebView like a browser would and read the DOM.
 */
private class PageFetcher(private val context: Context) {
    private val userAgent by lazy { runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("Mozilla/5.0") }

    suspend fun fetch(url: String, marker: String): String {
        val direct = withContext(Dispatchers.IO) { runCatching { http(url) }.getOrNull() }
        if (direct != null && marker in direct) return direct
        val rendered = try {
            withTimeout(WEBVIEW_TIMEOUT_MS) { webView(url, marker) }
        } catch (e: TimeoutCancellationException) {
            null
        }
        return rendered ?: throw SeatMapException(
            tr("seatmaps.com не отдал страницу — проверьте интернет или откройте сайт", "seatmaps.com didn't return the page — check the connection or open the site"),
        )
    }

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webView(url: String, marker: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val view = WebView(context.applicationContext)
            var done = false
            fun finish(html: String?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                view.stopLoading()
                view.destroy()
                if (cont.isActive) cont.resume(html)
            }
            fun poll() {
                if (done) return
                view.evaluateJavascript("document.documentElement.outerHTML") { json ->
                    val html = runCatching { AppJson.parseToJsonElement(json).jsonPrimitive.content }.getOrNull()
                    if (html != null && marker in html) finish(html) else handler.postDelayed({ poll() }, 800)
                }
            }
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.blockNetworkImage = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = poll()
            }
            view.loadUrl(url, mapOf("Accept-Language" to "en"))
            cont.invokeOnCancellation { handler.post { finish(null) } }
        }
    }

    private companion object {
        const val WEBVIEW_TIMEOUT_MS = 30_000L
    }
}
