package io.github.cosmicoverlord.boardingpass.core.model

import io.github.cosmicoverlord.boardingpass.core.bcbp.Bcbp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

@Serializable
enum class CodeFormat { QR_CODE, AZTEC, PDF_417, DATA_MATRIX, CODE_128, OTHER }

/** A barcode found in a camera frame, picture, PDF or Wallet pass. */
data class DecodedCode(val raw: String, val format: CodeFormat)

@Serializable
data class BoardingPass(
    val id: String,
    val createdAt: Long,
    val barcode: String,
    val format: CodeFormat = CodeFormat.QR_CODE,
    val legIndex: Int = 0,
    val legCount: Int = 1,
    val passengerName: String = "",
    val passengerType: String? = null,
    val pnr: String = "",
    val from: String = "",
    val to: String = "",
    val carrier: String = "",
    val flightNumber: String = "",
    /** Local departure date, ISO yyyy-MM-dd. */
    val flightDate: String? = null,
    val compartment: String = "",
    val seat: String = "",
    val sequence: String = "",
    val ticketNumber: String? = null,
    val marketingCarrier: String? = null,
    val frequentFlyer: String? = null,
    val baggageAllowance: String? = null,
    /** False when the barcode is not IATA BCBP and the fields were typed in by hand. */
    val parsed: Boolean = true,
    val status: FlightStatus? = null,
    val statusError: String? = null,
    val statusCheckedAt: Long? = null,
) {
    val date: LocalDate? get() = flightDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val flightLabel: String get() = listOf(carrier, flightNumber).filter { it.isNotBlank() }.joinToString(" ")

    /** Same physical flight (used to share one status request between passengers). */
    val flightKey: String get() = "$carrier|$flightNumber|$flightDate|$from"

    /** Best known departure moment, for sorting and "upcoming/past" split. */
    fun departureTime(): OffsetDateTime? =
        status?.let { it.depActual.toOdt() ?: it.depEstimated.toOdt() ?: it.depScheduled.toOdt() }

    fun arrivalTime(): OffsetDateTime? =
        status?.let { it.arrActual.toOdt() ?: it.arrEstimated.toOdt() ?: it.arrScheduled.toOdt() }
}

@Serializable
enum class StatusKind(val label: String, val isFinal: Boolean = false) {
    SCHEDULED("По расписанию"),
    DELAYED("Задерживается"),
    CHECK_IN("Регистрация"),
    BOARDING("Посадка"),
    GATE_CLOSED("Посадка окончена"),
    DEPARTED("Вылетел"),
    EN_ROUTE("В полёте"),
    LANDED("Приземлился", isFinal = true),
    ARRIVED("Прибыл", isFinal = true),
    CANCELLED("Отменён", isFinal = true),
    DIVERTED("Перенаправлен", isFinal = true),
    UNKNOWN("Неизвестно"),
}

@Serializable
data class FlightStatus(
    val provider: String,
    val fetchedAt: Long,
    val kind: StatusKind = StatusKind.UNKNOWN,
    val statusText: String? = null,
    val airlineName: String? = null,
    val depTerminal: String? = null,
    val depGate: String? = null,
    val depCheckIn: String? = null,
    val arrTerminal: String? = null,
    val arrGate: String? = null,
    val baggageBelt: String? = null,
    /** ISO-8601 date-times with the airport's local offset. */
    val depScheduled: String? = null,
    val depEstimated: String? = null,
    val depActual: String? = null,
    val arrScheduled: String? = null,
    val arrEstimated: String? = null,
    val arrActual: String? = null,
    val aircraftModel: String? = null,
    val aircraftReg: String? = null,
    val callSign: String? = null,
)

fun String?.toOdt(): OffsetDateTime? = this?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

object PassFactory {
    fun fromBcbp(bcbp: Bcbp, format: CodeFormat, now: Long = System.currentTimeMillis()): List<BoardingPass> =
        bcbp.legs.mapIndexed { index, leg ->
            BoardingPass(
                id = UUID.randomUUID().toString(),
                createdAt = now + index,
                barcode = bcbp.raw,
                format = format,
                legIndex = index,
                legCount = bcbp.legs.size,
                passengerName = bcbp.passengerName,
                passengerType = passengerType(bcbp.passengerDescription),
                pnr = leg.pnr,
                from = leg.from,
                to = leg.to,
                carrier = leg.carrier,
                flightNumber = leg.flightNumber,
                flightDate = leg.flightDate?.toString(),
                compartment = leg.compartment,
                seat = leg.seat,
                sequence = leg.sequence,
                ticketNumber = leg.ticketNumber,
                marketingCarrier = leg.marketingCarrier?.takeIf { it != leg.carrier },
                frequentFlyer = listOfNotNull(leg.frequentFlyerAirline, leg.frequentFlyerNumber)
                    .joinToString(" ").ifBlank { null },
                baggageAllowance = leg.baggageAllowance?.let(::formatBaggage),
                parsed = true,
            )
        }

    fun manual(code: DecodedCode, now: Long = System.currentTimeMillis()) = BoardingPass(
        id = UUID.randomUUID().toString(),
        createdAt = now,
        barcode = code.raw,
        format = code.format,
        parsed = false,
    )

    private fun passengerType(code: String?): String? = when (code) {
        "0" -> "Взрослый"
        "1" -> "Взрослый (м)"
        "2" -> "Взрослый (ж)"
        "3" -> "Ребёнок"
        "4" -> "Младенец"
        "6" -> "Взрослый с младенцем"
        "7" -> "Несопровождаемый ребёнок"
        else -> null
    }

    /** "20K" -> "20 кг", "2PC" -> "2 места", "1PC" -> "1 место". */
    fun formatBaggage(raw: String): String {
        val t = raw.trim()
        Regex("^0*(\\d+)\\s*K$").find(t)?.let { return "${it.groupValues[1]} кг" }
        Regex("^0*(\\d+)\\s*L$").find(t)?.let { return "${it.groupValues[1]} фунтов" }
        Regex("^0*(\\d+)\\s*PC$").find(t)?.let {
            val n = it.groupValues[1].toInt()
            return "$n " + when {
                n % 10 == 1 && n % 100 != 11 -> "место"
                n % 10 in 2..4 && n % 100 !in 12..14 -> "места"
                else -> "мест"
            }
        }
        return t
    }
}

object PassText {
    fun cabinClass(code: String): String? = when (code.uppercase()) {
        "" -> null
        "F", "A", "P" -> "Первый"
        "J", "C", "D", "I", "Z", "R" -> "Бизнес"
        "W", "E" -> "Премиум-эконом"
        else -> "Эконом"
    }

    /** "IVANOV/IVAN MR" -> "IVANOV IVAN MR". */
    fun passenger(name: String): String = name.replace('/', ' ').replace(Regex("\\s+"), " ").trim()
}
