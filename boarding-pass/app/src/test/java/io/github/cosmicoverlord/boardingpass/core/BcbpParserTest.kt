package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.bcbp.BcbpParser
import io.github.cosmicoverlord.boardingpass.core.model.CodeFormat
import io.github.cosmicoverlord.boardingpass.core.model.PassFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BcbpParserTest {
    private val today = LocalDate.of(2026, 10, 17)

    // Two-leg example from IATA Resolution 792 (BCBP implementation guide).
    private val iataSample =
        "M2DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 14D>6181WW6225BAC 00141234560032A0141234567890 1AC AC 1234567890123    20KYLX58ZDEF456 FRAGVALH 3664 327C012C0002 12E2A0140987654321 1AC AC 1234567890123    2PCNWQ^164GIWVC5EH7JNT684FVNJ91W2QA4DVN5J8K4F0L0GEQ3DF5TGBN8709HKT5D3DW3GBHFCVHMY7J5T6HFR41W2QA4DVN5J8K4F0L0GE"

    @Test
    fun parsesMandatoryOnlyPass() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val p = BcbpParser.parse(raw, today)
        assertEquals("DESMARAIS/LUC", p.passengerName)
        assertTrue(p.electronicTicket)
        assertEquals(1, p.legs.size)
        val leg = p.legs[0]
        assertEquals("ABC123", leg.pnr)
        assertEquals("YUL", leg.from)
        assertEquals("FRA", leg.to)
        assertEquals("AC", leg.carrier)
        assertEquals("834", leg.flightNumber)
        assertEquals(326, leg.julianDay)
        assertEquals("J", leg.compartment)
        assertEquals("1A", leg.seat)
        assertEquals("25", leg.sequence)
        assertEquals("1", leg.passengerStatus)
        // No issue date: closest day 326 to 2026-10-17 is 2026-11-22.
        assertEquals(LocalDate.of(2026, 11, 22), leg.flightDate)
    }

    @Test
    fun parsesIataTwoLegSample() {
        val p = BcbpParser.parse(iataSample, today)
        assertEquals(6, p.version)
        assertEquals(2, p.legs.size)
        assertEquals("1", p.passengerDescription)
        assertEquals("W", p.checkInSource)
        assertEquals("B", p.documentType)
        assertEquals("AC", p.issuer)
        assertEquals(LocalDate.of(2026, 8, 13), p.issueDate)
        assertEquals(listOf("0014123456003"), p.bagTags)

        val l1 = p.legs[0]
        assertEquals("0141234567890", l1.ticketNumber)
        assertEquals("AC", l1.marketingCarrier)
        assertEquals("1234567890123", l1.frequentFlyerNumber)
        assertEquals("20K", l1.baggageAllowance)
        assertEquals("Y", l1.fastTrack)
        assertEquals("LX58Z", l1.airlineData)
        assertEquals(LocalDate.of(2026, 11, 22), l1.flightDate)

        val l2 = p.legs[1]
        assertEquals("DEF456", l2.pnr)
        assertEquals("FRA", l2.from)
        assertEquals("GVA", l2.to)
        assertEquals("LH", l2.carrier)
        assertEquals("3664", l2.flightNumber)
        assertEquals("12C", l2.seat)
        assertEquals("2", l2.sequence)
        assertEquals("0140987654321", l2.ticketNumber)
        assertEquals("2PC", l2.baggageAllowance)
        assertEquals("WQ", l2.airlineData)
        assertEquals(LocalDate.of(2026, 11, 23), l2.flightDate)
        assertNotNull(p.securityData)
        assertEquals(100, p.securityData!!.length)
    }

    @Test
    fun factoryCreatesOnePassPerLeg() {
        val passes = PassFactory.fromBcbp(BcbpParser.parse(iataSample, today), CodeFormat.PDF_417, 1000L)
        assertEquals(2, passes.size)
        assertEquals("2026-11-22", passes[0].flightDate)
        assertEquals("AC 834", passes[0].flightLabel)
        assertEquals("20 кг", passes[0].baggageAllowance)
        assertEquals("2 места", passes[1].baggageAllowance)
        assertEquals("Взрослый (м)", passes[0].passengerType)
        assertEquals(1, passes[1].legIndex)
        assertEquals(2, passes[1].legCount)
    }

    @Test
    fun rejectsNonBoardingPassData() {
        assertNull(BcbpParser.parseOrNull("https://example.com/some/qr", today))
        assertNull(BcbpParser.parseOrNull("M1SHORT", today))
        assertNull(BcbpParser.parseOrNull("X1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100", today))
        assertNull(BcbpParser.parseOrNull("M1DESMARAIS/LUC       EABC123 Y1LFRAAC 0834 326J001A0025 100", today))
    }

    @Test
    fun handlesBuiltPassWithConditionalFields() {
        fun hex(n: Int) = String.format("%02X", n)
        val mandatory = "K7XQ2L " + "SVO" + "LED" + "SU " + "0012 " + "290" + "Y" + "012A" + "0045 " + "3"
        val unique = "1" + "W" + "W" + "6289" + "B" + "SU "
        val repeated = "555" + "1234567890" + " " + "0" + "SU " + "SU " + "1234567890".padEnd(16) + " " + "1PC"
        val conditional = ">5" + hex(unique.length) + unique + hex(repeated.length) + repeated + "AIRLINE"
        val raw = "M1" + "IVANOV/IVAN MR".padEnd(20) + "E" + mandatory + hex(conditional.length) + conditional
        val p = BcbpParser.parse(raw, today)
        val leg = p.legs.single()
        assertEquals("IVANOV/IVAN MR", p.passengerName)
        assertEquals(5, p.version)
        assertEquals(LocalDate.of(2026, 10, 16), p.issueDate)
        assertEquals("K7XQ2L", leg.pnr)
        assertEquals("SVO", leg.from)
        assertEquals("LED", leg.to)
        assertEquals("SU", leg.carrier)
        assertEquals("12", leg.flightNumber)
        assertEquals("12A", leg.seat)
        assertEquals("45", leg.sequence)
        assertEquals("5551234567890", leg.ticketNumber)
        assertEquals("1234567890", leg.frequentFlyerNumber)
        assertEquals("1PC", leg.baggageAllowance)
        assertEquals("AIRLINE", leg.airlineData)
        assertEquals(LocalDate.of(2026, 10, 17), leg.flightDate)
        val pass = PassFactory.fromBcbp(p, CodeFormat.AZTEC).single()
        assertEquals("1 место", pass.baggageAllowance)
        assertNull(pass.marketingCarrier)
        assertEquals("SU 1234567890", pass.frequentFlyer)
    }

    @Test
    fun flightDateCrossesNewYear() {
        // Scanned on Dec 30 for a flight on Jan 2 (day 2).
        assertEquals(LocalDate.of(2027, 1, 2), BcbpParser.resolveFlightDate(2, null, LocalDate.of(2026, 12, 30)))
        // Issued on 2026-12-20 ("6354"), flight on day 5 -> next year.
        val issue = BcbpParser.resolveIssueDate("6354", LocalDate.of(2026, 12, 21))
        assertEquals(LocalDate.of(2026, 12, 20), issue)
        assertEquals(LocalDate.of(2027, 1, 5), BcbpParser.resolveFlightDate(5, issue, LocalDate.of(2026, 12, 21)))
        // Day 366 only exists in leap years.
        assertEquals(LocalDate.of(2028, 12, 31), BcbpParser.resolveFlightDate(366, null, LocalDate.of(2028, 12, 1)))
    }

    @Test
    fun normalizesFields() {
        assertEquals("1234A", BcbpParser.normalizeFlightNumber("1234A"))
        assertEquals("7", BcbpParser.normalizeFlightNumber("0007 "))
        assertEquals("INF", BcbpParser.normalizeSeat("INF "))
        assertEquals("3C", BcbpParser.normalizeSeat("003C"))
        assertEquals("125", BcbpParser.normalizeSequence("0125 "))
    }
}
