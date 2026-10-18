package io.github.cosmicoverlord.boardingpass.core.model

import io.github.cosmicoverlord.boardingpass.core.bcbp.Bcbp
import io.github.cosmicoverlord.boardingpass.core.tr
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
    /** seatmaps.com aircraft page chosen for this pass when the model couldn't be matched. */
    val seatMapAircraft: String? = null,
    /** Cabin layout variant picked by the user when the seat fits several. */
    val seatMapVariant: String? = null,
    /** The user stopped the pre-departure frequent refresh for this flight. */
    val rushMuted: Boolean = false,
    /** The user dismissed this flight's live card. */
    val liveHidden: Boolean = false,
    /** When the user tapped "Took off" on the live card (epoch ms), for when the provider can't tell. */
    val takeoffAt: Long? = null,
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

    /** The provider's arrival, else the departure plus a flight time estimated from the route length. */
    fun expectedArrival(): OffsetDateTime? {
        arrivalTime()?.let { return it }
        val dep = departureTime() ?: return null
        val at = dep.toInstant().plus(Routes.flightTime(from, to) ?: return null)
        return Routes.zone(to)?.let { at.atZone(it).toOffsetDateTime() } ?: at.atOffset(dep.offset)
    }
}

@Serializable
enum class StatusKind(val isFinal: Boolean = false) {
    SCHEDULED,
    DELAYED,
    CHECK_IN,
    BOARDING,
    GATE_CLOSED,
    DEPARTED,
    EN_ROUTE,
    LANDED(isFinal = true),
    ARRIVED(isFinal = true),
    CANCELLED(isFinal = true),
    DIVERTED(isFinal = true),
    UNKNOWN,
    ;

    val label: String
        get() = when (this) {
            SCHEDULED -> tr("По расписанию", "Scheduled")
            DELAYED -> tr("Задерживается", "Delayed")
            CHECK_IN -> tr("Регистрация", "Check-in")
            BOARDING -> tr("Посадка", "Boarding")
            GATE_CLOSED -> tr("Посадка окончена", "Gate closed")
            DEPARTED -> tr("Вылетел", "Departed")
            EN_ROUTE -> tr("В полёте", "En route")
            LANDED -> tr("Приземлился", "Landed")
            ARRIVED -> tr("Прибыл", "Arrived")
            CANCELLED -> tr("Отменён", "Cancelled")
            DIVERTED -> tr("Перенаправлен", "Diverted")
            UNKNOWN -> tr("Неизвестно", "Unknown")
        }

    /** The aircraft has left the gate (or never will): nothing left to wait for at the airport. */
    val hasLeft: Boolean get() = this in setOf(DEPARTED, EN_ROUTE, LANDED, ARRIVED, CANCELLED, DIVERTED)
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
                baggageAllowance = leg.baggageAllowance?.trim()?.ifEmpty { null },
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

    /** Stored as a code so the label follows the UI language. */
    private fun passengerType(code: String?): String? = code?.takeIf { it in "0123467" && it.length == 1 }

    fun passengerTypeLabel(code: String?): String? = when (code) {
        "0" -> tr("Взрослый", "Adult")
        "1" -> tr("Взрослый (м)", "Adult (male)")
        "2" -> tr("Взрослый (ж)", "Adult (female)")
        "3" -> tr("Ребёнок", "Child")
        "4" -> tr("Младенец", "Infant")
        "6" -> tr("Взрослый с младенцем", "Adult with infant")
        "7" -> tr("Несопровождаемый ребёнок", "Unaccompanied minor")
        // Older saves kept the Russian label itself.
        else -> code
    }

    /** "20K" -> "20 кг", "2PC" -> "2 места" / "2 pieces". */
    fun formatBaggage(raw: String): String {
        val t = raw.trim()
        Regex("^0*(\\d+)\\s*K$").find(t)?.let { return "${it.groupValues[1]} " + tr("кг", "kg") }
        Regex("^0*(\\d+)\\s*L$").find(t)?.let { return "${it.groupValues[1]} " + tr("фунтов", "lb") }
        Regex("^0*(\\d+)\\s*PC$").find(t)?.let {
            val n = it.groupValues[1].toInt()
            return "$n " + tr(
                when {
                    n % 10 == 1 && n % 100 != 11 -> "место"
                    n % 10 in 2..4 && n % 100 !in 12..14 -> "места"
                    else -> "мест"
                },
                if (n == 1) "piece" else "pieces",
            )
        }
        return t
    }
}

object PassText {
    fun cabinClass(code: String): String? = when (code.uppercase()) {
        "" -> null
        "F", "A", "P" -> tr("Первый", "First")
        "J", "C", "D", "I", "Z", "R" -> tr("Бизнес", "Business")
        "W", "E" -> tr("Премиум-эконом", "Premium economy")
        else -> tr("Эконом", "Economy")
    }

    /** Baggage is stored raw ("20K") and formatted for display in the current language. */
    fun baggage(raw: String?): String? = raw?.let { PassFactory.formatBaggage(it) }

    /** "IVANOV/IVAN MR" -> "IVANOV IVAN MR". */
    fun passenger(name: String): String = name.replace('/', ' ').replace(Regex("\\s+"), " ").trim()
}
