package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.PkPass
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.model.CodeFormat
import io.github.cosmicoverlord.boardingpass.core.status.AeroDataBoxProvider
import io.github.cosmicoverlord.boardingpass.core.status.AviationStackProvider
import io.github.cosmicoverlord.boardingpass.core.status.FlightAwareProvider
import io.github.cosmicoverlord.boardingpass.core.status.FlightQuery
import io.github.cosmicoverlord.boardingpass.core.status.HttpResponse
import io.github.cosmicoverlord.boardingpass.core.status.StatusDiff
import io.github.cosmicoverlord.boardingpass.core.status.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StatusProvidersTest {
    private val q = FlightQuery(
        carrier = "SU", number = "12", date = LocalDate.of(2026, 10, 17), from = "SVO", to = "LED",
        fromZone = ZoneId.of("Europe/Moscow"), toZone = ZoneId.of("Europe/Moscow"),
    )

    @Test
    fun parsesAeroDataBox() {
        val body = """
            [{
              "departure": {
                "airport": {"icao":"UUEE","iata":"SVO","name":"Moscow Sheremetyevo","timeZone":"Europe/Moscow"},
                "scheduledTime": {"utc":"2026-10-17 07:00Z","local":"2026-10-17 10:00+03:00"},
                "revisedTime": {"utc":"2026-10-17 07:40Z","local":"2026-10-17 10:40+03:00"},
                "terminal": "B", "gate": "B12", "checkInDesk": "201-215", "quality": ["Basic","Live"]
              },
              "arrival": {
                "airport": {"iata":"LED","timeZone":"Europe/Moscow"},
                "scheduledTime": {"utc":"2026-10-17 08:30Z","local":"2026-10-17 11:30+03:00"},
                "terminal": "1", "baggageBelt": "5"
              },
              "number": "SU 12", "callSign": "AFL12", "status": "Expected",
              "aircraft": {"reg": "RA-89001", "model": "Sukhoi Superjet 100"},
              "airline": {"name": "Aeroflot", "iata": "SU", "icao": "AFL"}
            }]
        """.trimIndent()
        val s = AeroDataBoxProvider.parse(body, q, 1L)
        assertEquals(StatusKind.DELAYED, s.kind)
        assertEquals("B", s.depTerminal)
        assertEquals("B12", s.depGate)
        assertEquals("201-215", s.depCheckIn)
        assertEquals("5", s.baggageBelt)
        assertEquals("2026-10-17T10:00+03:00", s.depScheduled)
        assertEquals("2026-10-17T10:40+03:00", s.depEstimated)
        assertEquals("Sukhoi Superjet 100", s.aircraftModel)
        assertEquals("RA-89001", s.aircraftReg)
        assertEquals("Aeroflot", s.airlineName)
    }

    @Test
    fun parsesFlightAware() {
        val body = """
            {"flights":[
              {"ident":"AFL12","ident_iata":"SU12","status":"Scheduled","cancelled":false,"diverted":false,
               "origin":{"code":"UUEE","code_iata":"SVO","timezone":"Europe/Moscow"},
               "destination":{"code":"ULLI","code_iata":"LED","timezone":"Europe/Moscow"},
               "departure_delay":0,"scheduled_out":"2026-10-16T07:00:00Z","estimated_out":"2026-10-16T07:00:00Z",
               "actual_out":"2026-10-16T07:05:00Z","actual_off":"2026-10-16T07:20:00Z","actual_on":"2026-10-16T08:20:00Z",
               "actual_in":"2026-10-16T08:30:00Z","gate_origin":"B1"},
              {"ident":"AFL12","ident_iata":"SU12","status":"Scheduled","cancelled":false,"diverted":false,
               "origin":{"code":"UUEE","code_iata":"SVO","timezone":"Europe/Moscow"},
               "destination":{"code":"ULLI","code_iata":"LED","timezone":"Europe/Moscow"},
               "departure_delay":1200,"scheduled_out":"2026-10-17T07:00:00Z","estimated_out":"2026-10-17T07:20:00Z",
               "scheduled_in":"2026-10-17T08:30:00Z","gate_origin":"B12","terminal_origin":"B",
               "aircraft_type":"SU95","registration":"RA-89001"}
            ]}
        """.trimIndent()
        val s = FlightAwareProvider.parse(body, q, 1L)
        assertEquals(StatusKind.DELAYED, s.kind)
        assertEquals("B12", s.depGate)
        assertEquals("B", s.depTerminal)
        assertEquals("2026-10-17T10:00+03:00", s.depScheduled)
        assertEquals("2026-10-17T10:20+03:00", s.depEstimated)
        assertEquals("2026-10-17T11:30+03:00", s.arrScheduled)
        assertEquals("Sukhoi Superjet 100", s.aircraftModel)
    }

    @Test
    fun flightAwareRefusesFarFutureDates() {
        val p = FlightAwareProvider("key", { _, _ -> HttpResponse(200, "{}") }, today = { LocalDate.of(2026, 10, 1) })
        try {
            runBlocking { p.fetch(q) }
            fail()
        } catch (e: StatusException) {
            assertTrue(e.message!!.contains("2 дня"))
        }
    }

    @Test
    fun parsesAviationStack() {
        val body = """
            {"pagination":{"limit":100,"offset":0,"count":2,"total":2},"data":[
              {"flight_date":"2026-10-16","flight_status":"landed",
               "departure":{"airport":"Sheremetyevo","timezone":"Europe/Moscow","iata":"SVO","terminal":"B","gate":"B3",
                 "delay":null,"scheduled":"2026-10-16T10:00:00+00:00"},
               "arrival":{"iata":"LED","timezone":"Europe/Moscow","scheduled":"2026-10-16T11:30:00+00:00"},
               "airline":{"name":"Aeroflot","iata":"SU"},"flight":{"number":"12","iata":"SU12","icao":"AFL12"}},
              {"flight_date":"2026-10-17","flight_status":"scheduled",
               "departure":{"airport":"Sheremetyevo","timezone":"Europe/Moscow","iata":"SVO","terminal":"B","gate":"B12",
                 "delay":25,"scheduled":"2026-10-17T10:00:00+00:00","estimated":"2026-10-17T10:25:00+00:00"},
               "arrival":{"iata":"LED","timezone":"Europe/Moscow","terminal":"1","baggage":"7",
                 "scheduled":"2026-10-17T11:30:00+00:00"},
               "airline":{"name":"Aeroflot","iata":"SU"},"flight":{"number":"12","iata":"SU12","icao":"AFL12"},
               "aircraft":{"registration":"RA-89001","iata":"SU9","icao":"SU95"}}
            ]}
        """.trimIndent()
        val s = AviationStackProvider.parse(body, q, 1L)
        assertEquals(StatusKind.DELAYED, s.kind)
        assertEquals("B12", s.depGate)
        assertEquals("7", s.baggageBelt)
        assertEquals("2026-10-17T10:00+03:00", s.depScheduled)
        assertEquals("2026-10-17T10:25+03:00", s.depEstimated)
        assertEquals("Sukhoi Superjet 100", s.aircraftModel)
        assertEquals("Aeroflot", s.airlineName)
    }

    @Test
    fun aviationStackErrorsAreReadable() {
        val body = """{"error":{"code":"invalid_access_key","message":"You have not supplied a valid API Access Key."}}"""
        try {
            AviationStackProvider.parse(body, q, 1L)
            fail()
        } catch (e: StatusException) {
            assertEquals("API-ключ не принят", e.message)
        }
    }

    @Test
    fun aeroDataBoxNoContentMeansNotFound() {
        val p = AeroDataBoxProvider("key") { _, _ -> HttpResponse(204, "") }
        try {
            runBlocking { p.fetch(q) }
            fail()
        } catch (e: StatusException) {
            assertTrue(e.message!!.contains("не найден"))
        }
    }

    @Test
    fun diffReportsGateAndTimeChanges() {
        val old = FlightStatus(provider = "X", fetchedAt = 0, kind = StatusKind.SCHEDULED, depGate = "B3",
            depScheduled = "2026-10-17T10:00+03:00")
        val new = old.copy(kind = StatusKind.BOARDING, depGate = "B12", depEstimated = "2026-10-17T10:30+03:00")
        val changes = StatusDiff.changes(old, new)
        assertEquals(listOf("Статус: Посадка", "Выход: B3 → B12", "Вылет: 10:00 → 10:30"), changes)
        assertTrue(StatusDiff.changes(null, new).isEmpty())
        assertTrue(StatusDiff.changes(new, new.copy(fetchedAt = 5)).isEmpty())
    }

    @Test
    fun readsWalletPass() {
        val passJson = """
            {"formatVersion":1,"barcodes":[{"message":"M1TEST/PAX           EABC123 SVOLEDSU 0012 290Y012A0045 100",
              "format":"PKBarcodeFormatAztec","messageEncoding":"iso-8859-1"}],
             "barcode":{"message":"M1TEST/PAX           EABC123 SVOLEDSU 0012 290Y012A0045 100","format":"PKBarcodeFormatAztec"}}
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("icon.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("pass.json")); zip.write(passJson.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(PkPass.isZip(bytes))
        val codes = PkPass.extract(bytes)
        assertEquals(1, codes.size)
        assertEquals(CodeFormat.AZTEC, codes[0].format)
        assertTrue(codes[0].raw.startsWith("M1TEST/PAX"))
    }
}
