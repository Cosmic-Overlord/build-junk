package io.github.cosmicoverlord.boardingpass.core.seatmap

import io.github.cosmicoverlord.boardingpass.core.model.AppJson
import io.github.cosmicoverlord.boardingpass.core.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/*
 * Reader for seatmaps.com pages, fetched on the device only when the user opens a seat map.
 * The chain is: airline index -> airline fleet -> aircraft page (layout variants) -> seat map.
 * Coordinates are the site's drawing units: x is relative to the fuselage centre line.
 */

@Serializable
data class SmAirline(val code: String, val slug: String, val name: String)

@Serializable
data class SmAircraft(val slug: String, val title: String, val variantCount: Int? = null)

@Serializable
data class SmVariant(val id: String, val name: String, val summary: String? = null)

@Serializable
data class SmFeature(val name: String, val positive: Boolean)

@Serializable
data class SmSeat(
    val number: String,
    val row: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val classCode: String,
    val label: String,
    val features: List<SmFeature> = emptyList(),
) {
    val letter: String get() = number.trimStart { it.isDigit() }
    fun has(feature: String) = features.any { it.name == feature }
}

@Serializable
data class SmBox(val x: Float, val y: Float, val w: Float, val h: Float, val kind: String = "")

/**
 * The cabin floor outline as the site draws it: SVG path data in a [viewBoxW]-wide box, placed
 * [width] units wide starting [left] units from the centre line (same units as the seats).
 */
@Serializable
data class SmOutline(val path: String, val viewBoxW: Float, val viewBoxH: Float, val width: Float, val left: Float) {
    val scale: Float get() = width / viewBoxW
}

@Serializable
data class SmClassSpec(val pitch: String? = null, val width: String? = null, val recline: String? = null)

@Serializable
data class SeatMap(
    val id: String,
    val seats: List<SmSeat>,
    /** Galleys, lavatories, closets and other cabin monuments. */
    val blocks: List<SmBox> = emptyList(),
    /** Icons on the monuments: kind "l" lavatory, "g" galley, others as the site names them. */
    val stickers: List<SmBox> = emptyList(),
    val floorHeight: Float? = null,
    val classSpecs: Map<String, SmClassSpec> = emptyMap(),
    val outline: SmOutline? = null,
) {
    fun seat(number: String): SmSeat? {
        val n = number.trim().uppercase()
        return seats.firstOrNull { it.number == n }
    }
}

class SeatMapException(message: String) : Exception(message)

object SeatMapsUrls {
    const val BASE = "https://seatmaps.com"
    const val INDEX = "$BASE/airlines/"
    fun airline(slug: String) = "$BASE/airlines/$slug/"
    fun aircraft(airlineSlug: String, aircraftSlug: String) = "$BASE/airlines/$airlineSlug/$aircraftSlug/"
    fun seatMap(id: String) = "$BASE/seatmaps/$id.html?seatbar=hide&tooltip_on_hover=true&measure=imperial"
    fun variantPage(airlineSlug: String, aircraftSlug: String, id: String) = aircraft(airlineSlug, aircraftSlug) + "#" + id

    /** Strings that only appear in the real page, to tell it apart from a bot challenge. */
    const val INDEX_MARKER = "/airlines/"
    const val FLEET_MARKER = "fleet-content"
    const val AIRCRAFT_MARKER = "data-plane-id"
    const val SEATMAP_MARKER = "comp-plane_seat"
}

object SeatMapsParser {
    private val aTag = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val divWithNumber = Regex("<div\\b[^>]*\\bdata-number=\"[^\"]*\"[^>]*>")
    private val stickerTag = Regex("<div\\b[^>]*\\bdata-uid=\"bulk-sticker-[^\"]*\"[^>]*>")
    private val blockTag = Regex("<div\\b[^>]*class=\"[^\"]*\\bcomp-plane_bulk\\b[^\"]*\"[^>]*>")

    fun parseIndex(html: String): List<SmAirline> {
        val href = Regex("^(?:https?://seatmaps\\.com)?/airlines/([^/\"#?]+)/$")
        val out = LinkedHashMap<String, SmAirline>()
        aTag.findAll(html).forEach { m ->
            val tag = m.value
            val slug = attr(tag, "href")?.let { href.find(it)?.groupValues?.get(1) } ?: return@forEach
            val code = attr(tag, "id")?.trim()?.uppercase()?.ifEmpty { null } ?: return@forEach
            if (!slug.lowercase().startsWith(code.lowercase() + "-")) return@forEach
            val name = textAfter(html, m.range.last + 1).ifEmpty { attr(tag, "title").orEmpty() }
            out.putIfAbsent(code, SmAirline(code, slug, name))
        }
        if (out.isEmpty()) throw SeatMapException(tr("Не удалось прочитать список авиакомпаний", "Couldn't read the airline list"))
        return out.values.toList()
    }

    fun parseFleet(html: String, airlineSlug: String): List<SmAircraft> {
        val href = Regex("^(?:https?://seatmaps\\.com)?/airlines/" + Regex.escape(airlineSlug) + "/([^/\"#?]+)/$")
        val out = LinkedHashMap<String, SmAircraft>()
        aTag.findAll(html).forEach { m ->
            val tag = m.value
            val slug = attr(tag, "href")?.let { href.find(it)?.groupValues?.get(1) } ?: return@forEach
            val title = attr(tag, "title")?.ifBlank { null } ?: textAfter(html, m.range.last + 1)
            if (title.isBlank()) return@forEach
            val tail = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 400))
            val count = Regex("aircraft-count\">\\s*(\\d+)").find(tail)?.groupValues?.get(1)?.toIntOrNull()
            val prev = out[slug]
            if (prev == null || (prev.variantCount == null && count != null)) out[slug] = SmAircraft(slug, title.trim(), count)
        }
        if (out.isEmpty()) throw SeatMapException(tr("Не удалось прочитать флот авиакомпании", "Couldn't read the airline fleet"))
        return out.values.toList()
    }

    fun parseVariants(html: String): List<SmVariant> {
        val out = LinkedHashMap<String, SmVariant>()
        Regex("<[^>]*\\bdata-plane-id=\"([0-9a-f]{16,64})\"[^>]*>").findAll(html).forEach { m ->
            val id = m.groupValues[1]
            val name = attr(m.value, "data-plane-name") ?: return@forEach
            if (id !in out) out[id] = SmVariant(id, name.trim())
        }
        if (out.isEmpty()) {
            // Single-layout pages may only reference the map by its id.
            Regex("seatmaps/([0-9a-f]{32})\\.(?:html|webp)").findAll(html).forEach { m ->
                val id = m.groupValues[1]
                if (id !in out) out[id] = SmVariant(id, "V.${out.size + 1}")
            }
        }
        if (out.isEmpty()) throw SeatMapException(tr("На странице самолёта нет схем", "No seat maps on the aircraft page"))
        val text = unescape(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ")
        return out.values.map { v ->
            val summary = Regex(Regex.escape(v.name) + " seat map shows (\\d+) seats configured as: ([^.]+)\\.")
                .find(text)?.let { "${it.groupValues[1]}: ${it.groupValues[2].trim()}" }
            v.copy(summary = summary)
        }
    }

    fun parseSeatMap(html: String, id: String): SeatMap {
        val seats = divWithNumber.findAll(html).mapNotNull { m ->
            val tag = m.value
            val number = attr(tag, "data-number")?.trim()?.uppercase() ?: return@mapNotNull null
            val style = style(attr(tag, "style") ?: return@mapNotNull null)
            val y = style["top"] ?: return@mapNotNull null
            val x = style["margin-left"] ?: style["left"] ?: return@mapNotNull null
            SmSeat(
                number = number,
                row = attr(tag, "data-row-number")?.toIntOrNull() ?: number.takeWhile { it.isDigit() }.toIntOrNull() ?: 0,
                x = x,
                y = y,
                w = style["width"] ?: 86f,
                h = style["height"] ?: 140f,
                classCode = attr(tag, "data-class").orEmpty(),
                label = attr(tag, "data-label").orEmpty(),
                features = features(attr(tag, "data-features")),
            )
        }.distinctBy { it.number }.toList()
        if (seats.isEmpty()) throw SeatMapException(tr("Не удалось прочитать схему салона", "Couldn't read the seat map"))

        val stickers = stickerTag.findAll(html).mapNotNull { m ->
            val kind = attr(m.value, "data-uid")?.removePrefix("bulk-sticker-")?.substringBefore('-') ?: ""
            box(attr(m.value, "style"), kind)
        }.toList()
        val blocks = blockTag.findAll(html).mapNotNull { box(attr(it.value, "style"), "") }.toList()
        val floor = Regex("comp-plane_floor\"[^>]*style=\"[^\"]*height:\\s*([\\d.]+)px").find(html)?.groupValues?.get(1)?.toFloatOrNull()
        val specs = attr(Regex("<[^>]*data-seat-class-features=\"[^\"]*\"[^>]*>").find(html)?.value ?: "", "data-seat-class-features")
            ?.let(::classSpecs).orEmpty()
        return SeatMap(id, seats, blocks, stickers, floor, specs, outline(html))
    }

    /** `<div id="plane-vector-carpet" style="width: 1088px; margin: 0 0 0 -544px"><svg viewBox=…><path|polygon class="plane-vector_carpet">`. */
    internal fun outline(html: String): SmOutline? {
        val at = html.indexOf("id=\"plane-vector-carpet\"").takeIf { it >= 0 } ?: return null
        val tagStart = html.lastIndexOf('<', at)
        val tagEnd = html.indexOf('>', at).takeIf { it > 0 } ?: return null
        val style = attr(html.substring(tagStart, tagEnd + 1), "style") ?: return null
        val width = Regex("width:\\s*([\\d.]+)px").find(style)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val margins = Regex("margin:([^;]+)").find(style)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.map { it.removeSuffix("px").toFloatOrNull() }
        val left = margins?.getOrNull(3) ?: margins?.getOrNull(1) ?: -width / 2
        val svgEnd = html.indexOf("</svg>", tagEnd).takeIf { it > 0 } ?: return null
        val svg = html.substring(tagEnd, svgEnd)
        val box = Regex("viewBox=\"\\s*[-\\d.]+[\\s,]+[-\\d.]+[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)\\s*\"").find(svg) ?: return null
        val vbW = box.groupValues[1].toFloatOrNull()?.takeIf { it > 0 } ?: return null
        val vbH = box.groupValues[2].toFloatOrNull() ?: return null
        val shape = Regex("<(path|polygon)\\b[^>]*>").findAll(svg).firstOrNull { m ->
            val cls = attr(m.value, "class").orEmpty()
            "plane-vector_carpet" in cls || attr(m.value, "id") == "floor"
        } ?: return null
        val path = if (shape.groupValues[1] == "path") {
            attr(shape.value, "d") ?: return null
        } else {
            val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(attr(shape.value, "points") ?: return null).map { it.value }.toList()
            if (nums.size < 6) return null
            nums.chunked(2).filter { it.size == 2 }.mapIndexed { i, (x, y) -> (if (i == 0) "M" else "L") + "$x $y" }
                .joinToString(" ") + " Z"
        }
        return SmOutline(path, vbW, vbH, width, left)
    }

    // ------------------------------------------------------------------ helpers

    private fun box(style: String?, kind: String): SmBox? {
        val s = style(style ?: return null)
        val y = s["top"] ?: return null
        val x = s["left"] ?: s["margin-left"] ?: return null
        return SmBox(x, y, s["width"] ?: return null, s["height"] ?: return null, kind)
    }

    private fun features(json: String?): List<SmFeature> {
        val arr = runCatching { AppJson.parseToJsonElement(json ?: return emptyList()) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SmFeature(name, (o["value"] as? JsonPrimitive)?.contentOrNull != "-")
        }
    }

    private fun classSpecs(json: String): Map<String, SmClassSpec> {
        val root = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyMap()
        return root.mapNotNull { (cls, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun f(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            cls to SmClassSpec(f("seat_pitch"), f("seat_width"), f("seat_recline"))
        }.toMap()
    }

    private fun style(s: String): Map<String, Float> =
        s.split(';').mapNotNull { part ->
            val k = part.substringBefore(':', "").trim().lowercase()
            val v = part.substringAfter(':', "").trim().removeSuffix("px").toFloatOrNull()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()

    internal fun attr(tag: String, name: String): String? =
        Regex("\\s" + Regex.escape(name) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)?.let(::unescape)

    private fun textAfter(html: String, from: Int): String {
        val end = html.indexOf("</a>", from).takeIf { it >= 0 } ?: return ""
        return unescape(html.substring(from, end).replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    }

    internal fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ")[e]
            } ?: m.value
        }
    }
}

/** Matches the model reported by the status provider ("Sukhoi Superjet 100") to a fleet entry ("Sukhoi SSJ 100-95"). */
object AircraftMatcher {
    private val makers = listOf(
        "airbus", "boeing", "sukhoi", "embraer", "bombardier", "de havilland", "canada", "atr", "comac",
        "mcdonnell douglas", "ilyushin", "tupolev", "irkut", "yakovlev",
    )

    fun key(model: String): String {
        var s = model.lowercase()
            .replace("superjet", "ssj")
            .replace("sukhoi ssj", "ssj")
            .replace("ilyushin il-", "il")
            .replace(Regex("\\bmax\\s*"), "max")
        makers.forEach { s = s.replace(it, " ") }
        return s.replace(Regex("[^a-z0-9]"), "")
    }

    /** neo/MAX are different aircraft from the same family, so they must agree. */
    private fun sameGeneration(a: String, b: String) = ("neo" in a) == ("neo" in b) && ("max" in a) == ("max" in b)

    fun match(model: String?, fleet: List<SmAircraft>): SmAircraft? {
        if (model.isNullOrBlank() || fleet.isEmpty()) return null
        val m = key(model)
        if (m.isEmpty()) return null
        val keyed = fleet.map { it to key(it.title) }
        keyed.firstOrNull { it.second == m }?.let { return it.first }
        // Prefix match covers "A321-200" ~ "A321" and "Superjet 100" ~ "SSJ 100-95".
        return keyed
            .filter { (_, k) -> k.length >= 3 && (m.startsWith(k) || k.startsWith(m)) && sameGeneration(k, m) }
            .maxByOrNull { (_, k) -> commonPrefix(k, m) * 10 - abs(k.length - m.length) }
            ?.first
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}

enum class Cabin { FIRST, BUSINESS, PREMIUM, ECONOMY }

object SeatMapLogic {
    /** Cabin of the BCBP compartment code, null when the pass has none. */
    fun cabinOfCompartment(code: String): Cabin? = when (code.trim().uppercase()) {
        "" -> null
        "F", "A", "P" -> Cabin.FIRST
        "J", "C", "D", "I", "Z", "R" -> Cabin.BUSINESS
        "W", "E" -> Cabin.PREMIUM
        else -> Cabin.ECONOMY
    }

    fun cabinOfSeat(s: SmSeat): Cabin {
        val label = s.label.lowercase()
        return when {
            s.classCode == "F" || "first" in label -> Cabin.FIRST
            s.classCode in setOf("B", "J", "C") || "business" in label -> Cabin.BUSINESS
            s.classCode in setOf("P", "W") || "premium" in label -> Cabin.PREMIUM
            else -> Cabin.ECONOMY
        }
    }

    /**
     * Variants on which the pass's seat exists in the pass's cabin. Business seat 2A rules out an
     * all-economy layout; economy 4A rules out a layout whose economy starts at row 6.
     */
    fun candidates(maps: List<SeatMap>, seat: String, compartment: String): List<String> {
        val cabin = cabinOfCompartment(compartment)
        return maps.filter { map ->
            val s = map.seat(seat) ?: return@filter false
            cabin == null || cabinOfSeat(s) == cabin ||
                // Premium economy is often drawn as economy with extra legroom.
                (cabin == Cabin.PREMIUM && cabinOfSeat(s) == Cabin.ECONOMY)
        }.map { it.id }
    }

    enum class Position { WINDOW, MIDDLE, AISLE }

    fun position(map: SeatMap, seat: SmSeat): Position {
        val row = map.seats.filter { it.row == seat.row && abs(it.y - seat.y) < seat.h }.sortedBy { it.x }
        val i = row.indexOfFirst { it.number == seat.number }
        if (i < 0 || row.size == 1) return Position.WINDOW
        if (i == 0 || i == row.lastIndex) return Position.WINDOW
        val gapLeft = seat.x - (row[i - 1].x + row[i - 1].w)
        val gapRight = row[i + 1].x - (seat.x + seat.w)
        val aisle = seat.w * 0.5f
        return if (gapLeft > aisle || gapRight > aisle) Position.AISLE else Position.MIDDLE
    }

    fun positionLabel(p: Position) = when (p) {
        Position.WINDOW -> tr("у окна", "window")
        Position.MIDDLE -> tr("посередине", "middle")
        Position.AISLE -> tr("у прохода", "aisle")
    }

    fun sideLabel(seat: SmSeat) = if (seat.x + seat.w / 2 < 0) tr("левый борт", "left side") else tr("правый борт", "right side")

    fun cabinLabel(c: Cabin) = when (c) {
        Cabin.FIRST -> tr("Первый класс", "First")
        Cabin.BUSINESS -> tr("Бизнес", "Business")
        Cabin.PREMIUM -> tr("Премиум-эконом", "Premium economy")
        Cabin.ECONOMY -> tr("Эконом", "Economy")
    }

    /** Human readable seat notes from the site's feature flags. */
    fun featureLabels(seat: SmSeat): List<Pair<String, Boolean>> = seat.features.mapNotNull { f ->
        val text = when (f.name) {
            "standardSeat" -> null
            "extraLegroom" -> tr("больше места для ног", "extra legroom")
            "exitRow" -> tr("ряд у аварийного выхода", "exit row")
            "bassinet" -> tr("место для детской люльки", "bassinet position")
            "nearLavatory" -> tr("рядом туалет", "near a lavatory")
            "nearGalley" -> tr("рядом кухня", "near a galley")
            "doNotRecline" -> tr("спинка не откидывается", "does not recline")
            "limitedRecline" -> tr("спинка откидывается не полностью", "limited recline")
            "wingInWindow" -> tr("в окне крыло", "wing in the window")
            "noWindow" -> tr("нет окна", "no window")
            "misalignedWindow" -> tr("окно смещено", "misaligned window")
            "getColdByExit" -> tr("у выхода может дуть", "can be cold near the exit")
            "noFloorStorage" -> tr("на взлёте нельзя держать вещи у ног", "no floor storage for take-off")
            "trayTableInArmrest" -> tr("столик в подлокотнике", "tray table in the armrest")
            else -> f.name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        } ?: return@mapNotNull null
        text to f.positive
    }

    /** `28-29"` -> `28–29″ (71–74 см)`. */
    fun inches(v: String?): String? {
        v ?: return null
        val nums = Regex("\\d+(?:\\.\\d+)?").findAll(v).map { it.value.toDouble() }.toList()
        if (nums.isEmpty()) return v
        val inch = nums.joinToString("–") { trimZero(it) } + "″"
        val cm = nums.joinToString("–") { Math.round(it * 2.54).toString() } + " " + tr("см", "cm")
        return "$inch ($cm)"
    }

    private fun trimZero(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
}
