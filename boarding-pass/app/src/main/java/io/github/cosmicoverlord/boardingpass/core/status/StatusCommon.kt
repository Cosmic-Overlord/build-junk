package io.github.cosmicoverlord.boardingpass.core.status

import io.github.cosmicoverlord.boardingpass.core.model.AppJson
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.model.toOdt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ProviderType(val title: String, val description: String, val signupUrl: String?) {
    NONE(
        "Не отслеживать",
        "Статус, терминал и выход не запрашиваются. Данные берутся только из посадочного талона.",
        null,
    ),
    AERODATABOX(
        "AeroDataBox (RapidAPI)",
        "Статус, терминал, выход, стойки регистрации, лента багажа, модель и бортовой номер самолёта. " +
            "Есть бесплатный тариф Basic на RapidAPI. Ключ — X-RapidAPI-Key.",
        "https://rapidapi.com/aedbx-aedbx/api/aerodatabox",
    ),
    FLIGHTAWARE(
        "FlightAware AeroAPI",
        "Точные статусы, терминалы и выходы, тип и бортовой номер самолёта. " +
            "Тариф Personal с бесплатным лимитом. Данные — не раньше чем за 2 дня до вылета.",
        "https://www.flightaware.com/aeroapi/portal/",
    ),
    AVIATIONSTACK(
        "Aviationstack",
        "Статус, терминал, выход, задержка, лента багажа. Бесплатно 100 запросов в месяц, " +
            "но бесплатный тариф работает только по HTTP — ключ передаётся без шифрования.",
        "https://aviationstack.com/signup/free",
    ),
}

data class FlightQuery(
    val carrier: String,
    val number: String,
    val date: LocalDate,
    val from: String,
    val to: String,
    val fromZone: ZoneId? = null,
    val toZone: ZoneId? = null,
) {
    val designator: String get() = carrier.trim() + number.trim()
}

class StatusException(message: String) : Exception(message)

interface FlightStatusProvider {
    val type: ProviderType
    suspend fun fetch(q: FlightQuery): FlightStatus
}

fun createProvider(type: ProviderType, apiKey: String, http: HttpClient = UrlConnectionHttp): FlightStatusProvider? {
    val key = apiKey.trim()
    if (type == ProviderType.NONE) return null
    if (key.isEmpty()) throw StatusException("Не указан API-ключ для ${type.title} — откройте настройки")
    return when (type) {
        ProviderType.AERODATABOX -> AeroDataBoxProvider(key, http)
        ProviderType.FLIGHTAWARE -> FlightAwareProvider(key, http)
        ProviderType.AVIATIONSTACK -> AviationStackProvider(key, http)
        ProviderType.NONE -> null
    }
}

// ---------------------------------------------------------------- HTTP

data class HttpResponse(val code: Int, val body: String)

fun interface HttpClient {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

object UrlConnectionHttp : HttpClient {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BoardingPass-Android/1.0")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(code, body)
        } catch (e: IOException) {
            throw StatusException("Нет соединения с сервером: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }
}

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

internal fun checkHttp(resp: HttpResponse) {
    if (resp.code in 200..299) {
        if (resp.code == 204 || resp.body.isBlank()) throw StatusException("Рейс не найден у источника данных")
        return
    }
    val msg = serverMessage(resp.body)?.let { ": $it" }.orEmpty()
    throw StatusException(
        when (resp.code) {
            404 -> "Рейс не найден у источника данных"
            401, 403 -> "API-ключ не принят (HTTP ${resp.code})$msg"
            429 -> "Превышен лимит запросов API (HTTP 429)$msg"
            400 -> "Источник отклонил запрос (HTTP 400)$msg"
            else -> "Ошибка сервера (HTTP ${resp.code})$msg"
        },
    )
}

private fun serverMessage(body: String): String? {
    val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() ?: return body.take(120).ifBlank { null }
    return root.str("message") ?: root.str("detail") ?: root.str("title") ?: root.obj("error").str("message")
}

internal fun parseJson(body: String): JsonElement =
    try {
        AppJson.parseToJsonElement(body)
    } catch (e: Exception) {
        throw StatusException("Некорректный ответ сервера")
    }

// ---------------------------------------------------------------- JSON helpers

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

internal fun JsonElement?.bool(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull

internal fun String?.zone(): ZoneId? = this?.let { runCatching { ZoneId.of(it) }.getOrNull() }

// ---------------------------------------------------------------- time helpers

internal object Times {
    private val localPrefix = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}(:\\d{2})?)")

    /** "2024-01-01 10:00+03:00", "2024-01-01T10:00:00Z", "2024-01-01 07:00Z". */
    fun parseOffset(s: String?): OffsetDateTime? {
        val t = s?.trim()?.replace(' ', 'T') ?: return null
        return runCatching { OffsetDateTime.parse(t) }.getOrNull()
    }

    fun utcToZone(s: String?, zone: ZoneId?): OffsetDateTime? {
        val odt = parseOffset(s) ?: return null
        return if (zone != null) odt.atZoneSameInstant(zone).toOffsetDateTime() else odt
    }

    /** Wall-clock local time (offset in the string, if any, is ignored) placed into [zone]. */
    fun localInZone(s: String?, zone: ZoneId?): OffsetDateTime? {
        val t = s?.trim() ?: return null
        if (zone == null) return parseOffset(t)
        val m = localPrefix.find(t) ?: return parseOffset(t)
        val ldt = runCatching { LocalDateTime.parse(m.groupValues[1] + "T" + m.groupValues[2]) }.getOrNull()
            ?: return parseOffset(t)
        return ldt.atZone(zone).toOffsetDateTime()
    }
}

/** Promote "scheduled" to "delayed" when the estimate is 15+ minutes late. */
internal fun FlightStatus.withDelayDetection(): FlightStatus {
    if (kind != StatusKind.SCHEDULED && kind != StatusKind.UNKNOWN) return this
    val sched = depScheduled.toOdt() ?: return this
    val est = depEstimated.toOdt() ?: return this
    return if (Duration.between(sched, est).toMinutes() >= 15) copy(kind = StatusKind.DELAYED) else this
}

// ---------------------------------------------------------------- change detection

object StatusDiff {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Human readable list of changes worth a notification. Empty for the very first status. */
    fun changes(old: FlightStatus?, new: FlightStatus): List<String> {
        if (old == null) return emptyList()
        val out = mutableListOf<String>()
        if (new.kind != old.kind && new.kind != StatusKind.UNKNOWN) out += "Статус: ${new.kind.label}"
        if (new.depTerminal != null && new.depTerminal != old.depTerminal) {
            out += "Терминал: " + (old.depTerminal?.let { "$it → " } ?: "") + new.depTerminal
        }
        if (new.depGate != null && new.depGate != old.depGate) {
            out += "Выход: " + (old.depGate?.let { "$it → " } ?: "") + new.depGate
        }
        // A new estimate is compared with the previous best guess; a missing estimate is not a change.
        val newEst = new.depEstimated.toOdt()
        val (before, after) = if (newEst != null) {
            (old.depEstimated.toOdt() ?: old.depScheduled.toOdt()) to newEst
        } else {
            old.depScheduled.toOdt() to new.depScheduled.toOdt()
        }
        if (before != null && after != null && kotlin.math.abs(Duration.between(before, after).toMinutes()) >= 5) {
            out += "Вылет: ${before.format(hm)} → ${after.format(hm)}"
        }
        if (new.baggageBelt != null && new.baggageBelt != old.baggageBelt) out += "Лента выдачи багажа: ${new.baggageBelt}"
        return out
    }
}
