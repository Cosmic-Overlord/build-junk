package io.github.cosmicoverlord.boardingpass.core.bcbp

import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Parser for IATA Bar Coded Boarding Pass data (Resolution 792, versions 1–8).
 * This is the payload of the PDF417 / Aztec / QR code printed on boarding passes.
 */
data class Bcbp(
    val raw: String,
    val formatCode: Char,
    val passengerName: String,
    val electronicTicket: Boolean,
    val legs: List<BcbpLeg>,
    val version: Int? = null,
    val passengerDescription: String? = null,
    val checkInSource: String? = null,
    val issuanceSource: String? = null,
    val issueDate: LocalDate? = null,
    val documentType: String? = null,
    val issuer: String? = null,
    val bagTags: List<String> = emptyList(),
    val securityData: String? = null,
)

data class BcbpLeg(
    val pnr: String,
    val from: String,
    val to: String,
    val carrier: String,
    val flightNumber: String,
    val julianDay: Int?,
    val flightDate: LocalDate?,
    val compartment: String,
    val seat: String,
    val sequence: String,
    val passengerStatus: String,
    val airlineNumericCode: String? = null,
    val documentNumber: String? = null,
    val selectee: String? = null,
    val internationalDocVerification: String? = null,
    val marketingCarrier: String? = null,
    val frequentFlyerAirline: String? = null,
    val frequentFlyerNumber: String? = null,
    val idAdIndicator: String? = null,
    val baggageAllowance: String? = null,
    val fastTrack: String? = null,
    val airlineData: String? = null,
) {
    /** 13-digit e-ticket number (airline numeric code + document serial), when present. */
    val ticketNumber: String?
        get() {
            val code = airlineNumericCode ?: return null
            val doc = documentNumber ?: return null
            val n = code + doc
            return if (n.length == 13 && n.all { it.isDigit() }) n else null
        }
}

class BcbpException(message: String) : Exception(message)

object BcbpParser {
    private const val HEADER = 23
    private const val LEG_MANDATORY = 37

    fun parseOrNull(raw: String, reference: LocalDate = LocalDate.now()): Bcbp? =
        try {
            parse(raw, reference)
        } catch (e: BcbpException) {
            null
        }

    fun parse(input: String, reference: LocalDate = LocalDate.now()): Bcbp {
        val raw = input.trimEnd('\r', '\n', '\u0000')
        if (raw.length < HEADER + LEG_MANDATORY) throw BcbpException("Слишком короткие данные (${raw.length} символов)")
        val format = raw[0]
        if (format != 'M' && format != 'S') throw BcbpException("Неизвестный формат '$format'")
        val legCount = raw[1].digitToIntOrNull() ?: throw BcbpException("Неверное число сегментов")
        if (legCount !in 1..4) throw BcbpException("Неверное число сегментов: $legCount")

        val name = raw.substring(2, 22).trim()
        val eTicket = raw[22] == 'E'

        var version: Int? = null
        var paxDescription: String? = null
        var checkInSource: String? = null
        var issuanceSource: String? = null
        var issueDateRaw: String? = null
        var documentType: String? = null
        var issuer: String? = null
        val bagTags = mutableListOf<String>()

        data class RawLeg(val mandatory: String, val repeated: Map<String, String?>, val airlineData: String?)

        val rawLegs = mutableListOf<RawLeg>()
        var pos = HEADER
        for (i in 0 until legCount) {
            if (pos + LEG_MANDATORY > raw.length) {
                if (rawLegs.isEmpty()) throw BcbpException("Обрезанные данные сегмента")
                break
            }
            val m = raw.substring(pos, pos + LEG_MANDATORY)
            if (!m.substring(7, 10).isAirportCode() || !m.substring(10, 13).isAirportCode()) {
                if (rawLegs.isEmpty()) throw BcbpException("Не найдены коды аэропортов")
                break
            }
            pos += LEG_MANDATORY
            val varSize = m.substring(35, 37).toIntOrNull(16) ?: 0
            val varEnd = minOf(pos + varSize, raw.length)
            val variable = raw.substring(pos, varEnd)
            pos = varEnd

            val r = Reader(variable)
            var conditional = i > 0
            if (i == 0 && r.peek() == '>') {
                conditional = true
                r.take(1)
                version = r.take(1).firstOrNull()?.digitToIntOrNull()
                val uniqueSize = r.hex2() ?: 0
                val u = Reader(r.take(uniqueSize))
                paxDescription = u.take(1).clean()
                checkInSource = u.take(1).clean()
                issuanceSource = u.take(1).clean()
                issueDateRaw = u.take(4).clean()
                documentType = u.take(1).clean()
                issuer = u.take(3).clean()
                repeat(3) { u.take(13).clean()?.let(bagTags::add) }
            }
            val repeated = mutableMapOf<String, String?>()
            if (conditional && r.remaining >= 2) {
                val repSize = r.hex2()
                if (repSize != null) {
                    val rr = Reader(r.take(repSize))
                    repeated["airlineNumeric"] = rr.take(3).clean()
                    repeated["document"] = rr.take(10).clean()
                    repeated["selectee"] = rr.take(1).clean()
                    repeated["intlDoc"] = rr.take(1).clean()
                    repeated["marketing"] = rr.take(3).clean()
                    repeated["ffAirline"] = rr.take(3).clean()
                    repeated["ffNumber"] = rr.take(16).clean()
                    repeated["idAd"] = rr.take(1).clean()
                    repeated["bags"] = rr.take(3).clean()
                    repeated["fastTrack"] = rr.take(1).clean()
                }
            }
            rawLegs += RawLeg(m, repeated, r.rest().clean())
        }

        var security: String? = null
        if (pos < raw.length && raw[pos] == '^') {
            val s = Reader(raw.substring(pos + 1))
            s.take(1)
            val len = s.hex2()
            security = if (len != null) s.take(len).clean() else s.rest().clean()
        }

        val issueDate = issueDateRaw?.let { resolveIssueDate(it, reference) }
        val legs = rawLegs.map { leg ->
            val m = leg.mandatory
            val julian = m.substring(21, 24).trim().toIntOrNull()
            BcbpLeg(
                pnr = m.substring(0, 7).trim(),
                from = m.substring(7, 10),
                to = m.substring(10, 13),
                carrier = m.substring(13, 16).trim(),
                flightNumber = normalizeFlightNumber(m.substring(16, 21)),
                julianDay = julian,
                flightDate = julian?.let { resolveFlightDate(it, issueDate, reference) },
                compartment = m.substring(24, 25).trim(),
                seat = normalizeSeat(m.substring(25, 29)),
                sequence = normalizeSequence(m.substring(29, 34)),
                passengerStatus = m.substring(34, 35).trim(),
                airlineNumericCode = leg.repeated["airlineNumeric"],
                documentNumber = leg.repeated["document"],
                selectee = leg.repeated["selectee"],
                internationalDocVerification = leg.repeated["intlDoc"],
                marketingCarrier = leg.repeated["marketing"],
                frequentFlyerAirline = leg.repeated["ffAirline"],
                frequentFlyerNumber = leg.repeated["ffNumber"],
                idAdIndicator = leg.repeated["idAd"],
                baggageAllowance = leg.repeated["bags"],
                fastTrack = leg.repeated["fastTrack"],
                airlineData = leg.airlineData,
            )
        }

        return Bcbp(
            raw = raw,
            formatCode = format,
            passengerName = name,
            electronicTicket = eTicket,
            legs = legs,
            version = version,
            passengerDescription = paxDescription,
            checkInSource = checkInSource,
            issuanceSource = issuanceSource,
            issueDate = issueDate,
            documentType = documentType,
            issuer = issuer,
            bagTags = bagTags,
            securityData = security,
        )
    }

    /** "0834 " -> "834", "0012A" -> "12A". */
    fun normalizeFlightNumber(s: String): String {
        val t = s.trim()
        val m = Regex("^(\\d+)\\s*([A-Z]?)$").find(t) ?: return t
        val digits = m.groupValues[1].trimStart('0').ifEmpty { "0" }
        return digits + m.groupValues[2]
    }

    /** "001A" -> "1A", "INF " -> "INF". */
    fun normalizeSeat(s: String): String {
        val t = s.trim()
        val m = Regex("^0*(\\d+)([A-Z])$").find(t) ?: return t
        return m.groupValues[1] + m.groupValues[2]
    }

    fun normalizeSequence(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        val m = Regex("^(\\d+)([A-Z]?)$").find(t) ?: return t
        return m.groupValues[1].trimStart('0').ifEmpty { "0" } + m.groupValues[2]
    }

    /** Date of issue is encoded as the last digit of the year + day of year, e.g. "6225". */
    fun resolveIssueDate(raw: String, reference: LocalDate): LocalDate? {
        if (raw.length != 4 || !raw.all { it.isDigit() }) return null
        val yearDigit = raw[0].digitToInt()
        val day = raw.substring(1).toInt()
        if (day !in 1..366) return null
        val candidates = (reference.year - 10..reference.year + 1)
            .filter { Math.floorMod(it, 10) == yearDigit }
            .mapNotNull { ofYearDay(it, day) }
        return candidates.filter { !it.isAfter(reference.plusDays(2)) }.maxOrNull() ?: candidates.minOrNull()
    }

    /**
     * The flight date only carries the day of year. With an issue date the flight is the first
     * matching day on/after it; otherwise pick the matching day closest to [reference].
     */
    fun resolveFlightDate(day: Int, issueDate: LocalDate?, reference: LocalDate): LocalDate? {
        if (day !in 1..366) return null
        if (issueDate != null) {
            for (y in issueDate.year..issueDate.year + 1) {
                val d = ofYearDay(y, day) ?: continue
                if (!d.isBefore(issueDate.minusDays(1))) return d
            }
        }
        return (reference.year - 1..reference.year + 1)
            .mapNotNull { ofYearDay(it, day) }
            .minByOrNull { abs(ChronoUnit.DAYS.between(reference, it)) }
    }

    private fun ofYearDay(year: Int, day: Int): LocalDate? =
        if (day <= Year.of(year).length()) LocalDate.ofYearDay(year, day) else null

    private fun String.isAirportCode() = length == 3 && all { it in 'A'..'Z' }

    private fun String.clean(): String? = trim().ifEmpty { null }

    private class Reader(private val s: String) {
        private var pos = 0
        val remaining get() = s.length - pos
        fun peek(): Char? = s.getOrNull(pos)
        fun take(n: Int): String {
            val end = minOf(s.length, pos + maxOf(0, n))
            val r = s.substring(pos, end)
            pos = end
            return r
        }

        fun hex2(): Int? {
            if (remaining < 2) return null
            val v = s.substring(pos, pos + 2).toIntOrNull(16) ?: return null
            pos += 2
            return v
        }

        fun rest(): String = take(remaining)
    }
}
