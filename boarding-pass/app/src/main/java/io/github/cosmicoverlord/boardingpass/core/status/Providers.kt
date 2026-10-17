package io.github.cosmicoverlord.boardingpass.core.status

import io.github.cosmicoverlord.boardingpass.core.data.AircraftTypes
import io.github.cosmicoverlord.boardingpass.core.model.AppJson
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

private fun notOnDate(q: FlightQuery, source: String) =
    StatusException("$source пока не отдаёт рейс ${q.designator} на ${q.date} — статус появится ближе к вылету")

// ================================================================ AeroDataBox

class AeroDataBoxProvider(private val apiKey: String, private val http: HttpClient) : FlightStatusProvider {
    override val type = ProviderType.AERODATABOX

    override suspend fun fetch(q: FlightQuery): FlightStatus = withContext(Dispatchers.IO) {
        val url = "https://aerodatabox.p.rapidapi.com/flights/number/${enc(q.designator)}/${q.date}" +
            "?withAircraftImage=false&withLocation=false&dateLocalRole=Departure"
        val resp = http.get(
            url,
            mapOf("X-RapidAPI-Key" to apiKey, "X-RapidAPI-Host" to "aerodatabox.p.rapidapi.com"),
        )
        checkHttp(resp)
        parse(resp.body, q, System.currentTimeMillis())
    }

    companion object {
        fun parse(body: String, q: FlightQuery, now: Long): FlightStatus {
            val root = parseJson(body)
            val items = when (root) {
                is JsonArray -> root
                is JsonObject -> root.arr("items") ?: JsonArray(listOf(root))
                else -> JsonArray(emptyList())
            }.mapNotNull { it as? JsonObject }
            if (items.isEmpty()) throw StatusException("Рейс не найден у источника данных")

            val f = items.firstOrNull { it.obj("departure").obj("airport").str("iata") == q.from }
                ?: items.first()
            val dep = f.obj("departure")
            val arr = f.obj("arrival")
            val depZone = dep.obj("airport").str("timeZone").zone() ?: q.fromZone
            val arrZone = arr.obj("airport").str("timeZone").zone() ?: q.toZone

            val kind = when (f.str("status")?.lowercase()) {
                "expected", "scheduled" -> StatusKind.SCHEDULED
                "checkin" -> StatusKind.CHECK_IN
                "boarding" -> StatusKind.BOARDING
                "gateclosed" -> StatusKind.GATE_CLOSED
                "departed" -> StatusKind.DEPARTED
                "enroute", "approaching" -> StatusKind.EN_ROUTE
                "delayed" -> StatusKind.DELAYED
                "arrived" -> StatusKind.ARRIVED
                "canceled", "cancelled", "canceleduncertain" -> StatusKind.CANCELLED
                "diverted" -> StatusKind.DIVERTED
                else -> StatusKind.UNKNOWN
            }
            return FlightStatus(
                provider = ProviderType.AERODATABOX.name,
                fetchedAt = now,
                kind = kind,
                statusText = f.str("status"),
                airlineName = f.obj("airline").str("name"),
                depTerminal = dep.str("terminal"),
                depGate = dep.str("gate"),
                depCheckIn = dep.str("checkInDesk"),
                arrTerminal = arr.str("terminal"),
                arrGate = arr.str("gate"),
                baggageBelt = arr.str("baggageBelt"),
                depScheduled = time(dep, "scheduledTime", depZone)?.toString(),
                depEstimated = (time(dep, "revisedTime", depZone) ?: time(dep, "predictedTime", depZone)
                    ?: time(dep, "actualTime", depZone))?.toString(),
                depActual = time(dep, "runwayTime", depZone)?.toString(),
                arrScheduled = time(arr, "scheduledTime", arrZone)?.toString(),
                arrEstimated = (time(arr, "revisedTime", arrZone) ?: time(arr, "predictedTime", arrZone)
                    ?: time(arr, "actualTime", arrZone))?.toString(),
                arrActual = time(arr, "runwayTime", arrZone)?.toString(),
                aircraftModel = f.obj("aircraft").str("model"),
                aircraftReg = f.obj("aircraft").str("reg"),
                callSign = f.str("callSign"),
            ).withDelayDetection()
        }

        /** Supports both the current `{utc, local}` objects and the older flat `xxxLocal/xxxUtc` fields. */
        private fun time(m: JsonObject?, key: String, zone: ZoneId?): OffsetDateTime? {
            m ?: return null
            m.obj(key)?.let { t ->
                Times.parseOffset(t.str("local"))?.let { return it }
                Times.utcToZone(t.str("utc"), zone)?.let { return it }
            }
            Times.parseOffset(m.str(key + "Local"))?.let { return it }
            return Times.utcToZone(m.str(key + "Utc"), zone)
        }
    }
}

// ================================================================ FlightAware AeroAPI v4

class FlightAwareProvider(
    private val apiKey: String,
    private val http: HttpClient,
    private val today: () -> LocalDate = { LocalDate.now() },
) : FlightStatusProvider {
    override val type = ProviderType.FLIGHTAWARE

    override suspend fun fetch(q: FlightQuery): FlightStatus = withContext(Dispatchers.IO) {
        val now = today()
        val start = maxOf(q.date.minusDays(1), now.minusDays(9))
        val end = minOf(q.date.plusDays(2), now.plusDays(2))
        if (!start.isBefore(end)) {
            throw StatusException("FlightAware отдаёт статус не раньше чем за 2 дня до вылета")
        }
        val url = "https://aeroapi.flightaware.com/aeroapi/flights/${enc(q.designator)}" +
            "?ident_type=designator&start=$start&end=$end"
        val resp = http.get(url, mapOf("x-apikey" to apiKey))
        checkHttp(resp)
        parse(resp.body, q, System.currentTimeMillis())
    }

    companion object {
        fun parse(body: String, q: FlightQuery, now: Long): FlightStatus {
            val flights = parseJson(body).arr("flights")?.mapNotNull { it as? JsonObject }.orEmpty()
            if (flights.isEmpty()) throw StatusException("Рейс не найден у источника данных")

            fun depZone(f: JsonObject) = f.obj("origin").str("timezone").zone() ?: q.fromZone
            fun schedOut(f: JsonObject) =
                Times.utcToZone(f.str("scheduled_out") ?: f.str("scheduled_off"), depZone(f))

            // Never fall back to another day's flight: its "arrived" status would end tracking of ours.
            val sameOrigin = flights.filter { it.obj("origin").str("code_iata") == q.from }.ifEmpty { flights }
            val f = sameOrigin.firstOrNull { schedOut(it)?.toLocalDate() == q.date }
                ?: throw notOnDate(q, "FlightAware")
            val dz = depZone(f)
            val az = f.obj("destination").str("timezone").zone() ?: q.toZone

            val kind = when {
                f.bool("cancelled") == true -> StatusKind.CANCELLED
                f.bool("diverted") == true -> StatusKind.DIVERTED
                f.str("actual_in") != null -> StatusKind.ARRIVED
                f.str("actual_on") != null -> StatusKind.LANDED
                f.str("actual_off") != null -> StatusKind.EN_ROUTE
                f.str("actual_out") != null -> StatusKind.DEPARTED
                (f.long("departure_delay") ?: 0L) >= 15 * 60 -> StatusKind.DELAYED
                else -> StatusKind.SCHEDULED
            }
            return FlightStatus(
                provider = ProviderType.FLIGHTAWARE.name,
                fetchedAt = now,
                kind = kind,
                statusText = f.str("status"),
                depTerminal = f.str("terminal_origin"),
                depGate = f.str("gate_origin"),
                arrTerminal = f.str("terminal_destination"),
                arrGate = f.str("gate_destination"),
                baggageBelt = f.str("baggage_claim"),
                depScheduled = Times.utcToZone(f.str("scheduled_out") ?: f.str("scheduled_off"), dz)?.toString(),
                depEstimated = Times.utcToZone(f.str("estimated_out") ?: f.str("estimated_off"), dz)?.toString(),
                depActual = Times.utcToZone(f.str("actual_out") ?: f.str("actual_off"), dz)?.toString(),
                arrScheduled = Times.utcToZone(f.str("scheduled_in") ?: f.str("scheduled_on"), az)?.toString(),
                arrEstimated = Times.utcToZone(f.str("estimated_in") ?: f.str("estimated_on"), az)?.toString(),
                arrActual = Times.utcToZone(f.str("actual_in") ?: f.str("actual_on"), az)?.toString(),
                aircraftModel = AircraftTypes.name(f.str("aircraft_type")),
                aircraftReg = f.str("registration"),
                callSign = f.str("ident_icao") ?: f.str("ident"),
            ).withDelayDetection()
        }
    }
}

// ================================================================ Aviationstack

class AviationStackProvider(private val apiKey: String, private val http: HttpClient) : FlightStatusProvider {
    override val type = ProviderType.AVIATIONSTACK

    override suspend fun fetch(q: FlightQuery): FlightStatus = withContext(Dispatchers.IO) {
        val query = "v1/flights?access_key=${enc(apiKey)}&flight_iata=${enc(q.designator)}"
        var resp = http.get("${if (httpOnly) "http" else "https"}://api.aviationstack.com/$query", emptyMap())
        // The free plan only works over plain HTTP; remember that so each refresh costs one request.
        if (!httpOnly && resp.body.contains("https_access_restricted")) {
            httpOnly = true
            resp = http.get("http://api.aviationstack.com/$query", emptyMap())
        }
        if (resp.code !in 200..299 && parseError(resp.body) == null) checkHttp(resp)
        parse(resp.body, q, System.currentTimeMillis())
    }

    companion object {
        @Volatile
        private var httpOnly = false

        private fun parseError(body: String): String? {
            val root = runCatching { AppJson.parseToJsonElement(body) }
                .getOrNull() ?: return null
            val err = root.obj("error") ?: return null
            return when (err.str("code")) {
                "invalid_access_key", "missing_access_key", "inactive_user" -> "API-ключ не принят"
                "usage_limit_reached" -> "Исчерпан месячный лимит запросов Aviationstack"
                "rate_limit_reached" -> "Слишком много запросов, попробуйте позже"
                "function_access_restricted" -> "Функция недоступна на вашем тарифе Aviationstack"
                else -> err.str("message") ?: "Ошибка Aviationstack"
            }
        }

        fun parse(body: String, q: FlightQuery, now: Long): FlightStatus {
            parseError(body)?.let { throw StatusException(it) }
            val data = parseJson(body).arr("data")?.mapNotNull { it as? JsonObject }.orEmpty()
            if (data.isEmpty()) throw StatusException("Рейс не найден у источника данных")

            // The request can't be filtered by date on the free plan, so match it here and never
            // substitute another day's flight (its final status would stop tracking ours).
            val date = q.date.toString()
            val sameOrigin = data.filter { it.obj("departure").str("iata") == q.from }.ifEmpty { data }
            val f = sameOrigin.firstOrNull { fl ->
                val d = fl.str("flight_date")
                d == date || (d == null && Times.localInZone(fl.obj("departure").str("scheduled"),
                    fl.obj("departure").str("timezone").zone() ?: q.fromZone)?.toLocalDate() == q.date)
            } ?: throw notOnDate(q, "Aviationstack")
            val dep = f.obj("departure")
            val arr = f.obj("arrival")
            val dz = dep.str("timezone").zone() ?: q.fromZone
            val az = arr.str("timezone").zone() ?: q.toZone

            var kind = when (f.str("flight_status")?.lowercase()) {
                "scheduled" -> StatusKind.SCHEDULED
                "active" -> StatusKind.EN_ROUTE
                "landed" -> StatusKind.LANDED
                "cancelled" -> StatusKind.CANCELLED
                "diverted" -> StatusKind.DIVERTED
                else -> StatusKind.UNKNOWN
            }
            if (kind == StatusKind.SCHEDULED && (dep.long("delay") ?: 0L) >= 15) kind = StatusKind.DELAYED

            // Aviationstack reports airport-local wall-clock times labelled as +00:00.
            return FlightStatus(
                provider = ProviderType.AVIATIONSTACK.name,
                fetchedAt = now,
                kind = kind,
                statusText = f.str("flight_status"),
                airlineName = f.obj("airline").str("name"),
                depTerminal = dep.str("terminal"),
                depGate = dep.str("gate"),
                arrTerminal = arr.str("terminal"),
                arrGate = arr.str("gate"),
                baggageBelt = arr.str("baggage"),
                depScheduled = Times.localInZone(dep.str("scheduled"), dz)?.toString(),
                depEstimated = Times.localInZone(dep.str("estimated"), dz)?.toString(),
                depActual = Times.localInZone(dep.str("actual") ?: dep.str("actual_runway"), dz)?.toString(),
                arrScheduled = Times.localInZone(arr.str("scheduled"), az)?.toString(),
                arrEstimated = Times.localInZone(arr.str("estimated"), az)?.toString(),
                arrActual = Times.localInZone(arr.str("actual") ?: arr.str("actual_runway"), az)?.toString(),
                aircraftModel = AircraftTypes.name(f.obj("aircraft").str("iata") ?: f.obj("aircraft").str("icao")),
                aircraftReg = f.obj("aircraft").str("registration"),
                callSign = f.obj("flight").str("icao"),
            ).withDelayDetection()
        }
    }
}
