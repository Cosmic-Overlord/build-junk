package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.seatmap.AircraftMatcher
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapLogic
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapsParser
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmAircraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Fixtures are small hand-written fragments shaped like the site's markup, not copies of it. */
class SeatMapsTest {
    @Before
    fun russian() {
        I18n.lang = Lang.RU
    }

    private val index = """
        <ul><li class="list-item"><a href="https://seatmaps.com/airlines/su-aeroflot/" id="SU" title="aeroflot">Aeroflot</a></li>
        <li><a href="https://seatmaps.com/airlines/ei-aer-lingus/" id="EI" title="aer lingus">Aer Lingus</a></li>
        <li><a href="https://seatmaps.com/airlines/ei*-aer-lingus-regional/" id="EI*" title="x">Aer Lingus Regional</a></li>
        <li><a href="https://seatmaps.com/airlines/dak-4airways/" id="DAK" title="4airways">4Airways</a></li>
        <li><a href="https://seatmaps.com/about/" id="nav">About</a></li></ul>
    """.trimIndent()

    private val fleet = """
        <ul class="fleet-content"><li><h4>Sukhoi</h4><ul><li>
        <a href="https://seatmaps.com/airlines/su-aeroflot/sukhoi-ssj-100-95/" title="Sukhoi SSJ 100-95">Sukhoi SSJ 100-95</a><span class="aircraft-count">2 types</span></li></ul></li>
        <li><a href="https://seatmaps.com/airlines/su-aeroflot/airbus-a320/" title="Airbus A320">Airbus A320</a><span class="aircraft-count">2 types</span></li>
        <li><a href="https://seatmaps.com/airlines/su-aeroflot/airbus-a320neo/" title="Airbus A320neo">Airbus A320neo</a><span class="aircraft-count">1 type</span></li>
        <li><a href="https://seatmaps.com/airlines/su-aeroflot/airbus-a321/" title="Airbus A321">Airbus A321</a></li>
        <li><a href="https://seatmaps.com/airlines/su-aeroflot/boeing-737-800/" title="Boeing 737-800">Boeing 737-800</a></li>
        <li><a href="https://seatmaps.com/airlines/su-aeroflot/boeing-777-300er/" title="Boeing 777-300ER">Boeing 777-300ER</a></li></ul>
    """.trimIndent()

    private val aircraftPage = """
        <div class="tab" data-plane-id="aaaa1111aaaa1111aaaa1111aaaa1111" data-plane-name="Superjet 100-95 V.1">x</div>
        <div class="tab" data-plane-id="bbbb2222bbbb2222bbbb2222bbbb2222" data-plane-name="Superjet 100-95 V.2">y</div>
        <div class="modal" data-plane-id="aaaa1111aaaa1111aaaa1111aaaa1111"></div>
        <p>The Aeroflot Sukhoi Superjet 100-95 V.1 seat map shows 5 seats configured as: 2 Business, 3 Economy.</p>
        <p>The Aeroflot Sukhoi Superjet 100-95 V.2 seat map shows 4 seats configured as: 4 Economy.</p>
    """.trimIndent()

    private fun seat(n: String, row: Int, x: Int, y: Int, cls: String, label: String, features: String = "standardSeat\",\"value\":\"+") =
        "<div class=\"absolute comp-plane_seat ST-3\" style=\"top:${y}px;margin-left:${x}px;width:86px;height:140px\" " +
            "data-number=\"$n\" data-class=\"$cls\" data-label=\"$label\" " +
            "data-features=\"[{&quot;name&quot;:&quot;${features.replace("\"", "&quot;")}&quot;}]\" data-row-number=\"$row\" data-seat-uid=\"$n-x\" data-specs=\"{}\"></div>"

    // V.1: business row 1 (A, D), economy row 6 (A, C, D). V.2: economy rows 4 and 6 (A, C).
    private val mapV1 = """
        <div class="comp-plane" data-seat-class-features="{&quot;B&quot;:{&quot;seat_pitch&quot;:&quot;38\&quot;&quot;},&quot;E&quot;:{&quot;seat_pitch&quot;:&quot;28-29\&quot;&quot;}}"></div>
        <div class="absolute comp-plane_floor" data-level="1" style="z-index: 1; height: 3000px; transform: scale(0.365);"></div>
        <div class="absolute center sticker " style="top:900px;left:-200px;width:80px;height:80px" data-uid="bulk-sticker-l-l-1-2"></div>
        <div class="absolute center comp-plane_bulk " style="top:1250px;left:-434.8px;width:286.5px;height:132px" data-uid="bulk-16-1-0"></div>
        ${seat("1A", 1, -330, 1500, "B", "Business")}${seat("1D", 1, 160, 1500, "B", "Business")}
        ${seat("6A", 6, -413, 2200, "E", "Economy", "exitRow\",\"value\":\"+\"},{\"name\":\"doNotRecline\",\"value\":\"-")}
        ${seat("6C", 6, -307, 2200, "E", "Economy")}${seat("6D", 6, 95, 2200, "E", "Economy")}
    """.trimIndent()

    private val mapV2 = """
        ${seat("4A", 4, -413, 1700, "E", "Economy")}${seat("4C", 4, -307, 1700, "E", "Economy")}
        ${seat("6A", 6, -413, 2200, "E", "Economy")}${seat("6C", 6, -307, 2200, "E", "Economy")}
    """.trimIndent()

    @Test
    fun parsesAirlineIndex() {
        val airlines = SeatMapsParser.parseIndex(index)
        assertEquals(listOf("SU", "EI", "EI*", "DAK"), airlines.map { it.code })
        assertEquals("su-aeroflot", airlines.first().slug)
        assertEquals("ei*-aer-lingus-regional", airlines[2].slug)
        assertEquals("Aeroflot", airlines.first().name)
    }

    @Test
    fun parsesFleetAndVariants() {
        val aircraft = SeatMapsParser.parseFleet(fleet, "su-aeroflot")
        assertEquals(6, aircraft.size)
        assertEquals(SmAircraft("sukhoi-ssj-100-95", "Sukhoi SSJ 100-95", 2), aircraft.first())
        val variants = SeatMapsParser.parseVariants(aircraftPage)
        assertEquals(2, variants.size)
        assertEquals("Superjet 100-95 V.1", variants[0].name)
        assertEquals("5: 2 Business, 3 Economy", variants[0].summary)
        assertEquals("4: 4 Economy", variants[1].summary)
    }

    @Test
    fun parsesSeatMap() {
        val map = SeatMapsParser.parseSeatMap(mapV1, "v1")
        assertEquals(5, map.seats.size)
        val s = map.seat("6a")!!
        assertEquals(6, s.row)
        assertEquals(-413f, s.x)
        assertEquals(2200f, s.y)
        assertTrue(s.has("exitRow"))
        assertEquals(listOf("ряд у аварийного выхода" to true, "спинка не откидывается" to false), SeatMapLogic.featureLabels(s))
        assertEquals(1, map.stickers.size)
        assertEquals("l", map.stickers[0].kind)
        assertEquals(1, map.blocks.size)
        assertEquals(3000f, map.floorHeight)
        assertEquals("38\"", map.classSpecs["B"]?.pitch)
        assertEquals("28–29″ (71–74 см)", SeatMapLogic.inches(map.classSpecs["E"]?.pitch))
        assertEquals(SeatMapLogic.Position.WINDOW, SeatMapLogic.position(map, s))
        assertEquals(SeatMapLogic.Position.AISLE, SeatMapLogic.position(map, map.seat("6C")!!))
        assertEquals("левый борт", SeatMapLogic.sideLabel(s))
    }

    @Test
    fun parsesCabinOutline() {
        val withPath = mapV1 + """
            <div id="plane-vector-carpet" class="comp-plane_carpet plane-wrap" style="width: 1088px; margin: 0px 0px 0px -544px; transform: scale(0.365);">
            <svg viewBox="0 0 837.4 7726.5"><path id="floor" class="plane-vector_carpet" d="M745,995.9l7-2.1L86,995.9Z"></path></svg></div>
        """.trimIndent()
        val o = SeatMapsParser.parseSeatMap(withPath, "v1").outline!!
        assertEquals("M745,995.9l7-2.1L86,995.9Z", o.path)
        assertEquals(837.4f, o.viewBoxW)
        assertEquals(-544f, o.left)
        assertEquals(1088f / 837.4f, o.scale, 0.0001f)

        val withPolygon = mapV2 + """
            <div id="plane-vector-carpet" style="width: 1100px; margin: 0px 0px 0px -548px;"><svg viewBox="0 0 842.1 5799.7">
            <polygon class="plane-vector_carpet" points="666.3 632.1 691.2 774.2 170.9 632.1"></polygon></svg></div>
        """.trimIndent()
        val p = SeatMapsParser.parseSeatMap(withPolygon, "v2").outline!!
        assertEquals("M666.3 632.1 L691.2 774.2 L170.9 632.1 Z", p.path)
        assertEquals(-548f, p.left)
        assertNull(SeatMapsParser.parseSeatMap(mapV1, "v1").outline)
    }

    @Test
    fun picksVariantsBySeatAndCabin() {
        val maps = listOf(SeatMapsParser.parseSeatMap(mapV1, "v1"), SeatMapsParser.parseSeatMap(mapV2, "v2"))
        assertEquals(listOf("v1"), SeatMapLogic.candidates(maps, "1A", "J"))
        assertEquals(listOf("v2"), SeatMapLogic.candidates(maps, "4A", "Y"))
        assertEquals(listOf("v1", "v2"), SeatMapLogic.candidates(maps, "6A", "Y"))
        assertEquals(emptyList<String>(), SeatMapLogic.candidates(maps, "1A", "Y"))
        assertEquals(listOf("v1", "v2"), SeatMapLogic.candidates(maps, "6C", ""))
    }

    @Test
    fun matchesProviderModelsToFleet() {
        val aircraft = SeatMapsParser.parseFleet(fleet, "su-aeroflot")
        fun m(model: String) = AircraftMatcher.match(model, aircraft)?.slug
        assertEquals("sukhoi-ssj-100-95", m("Sukhoi Superjet 100"))
        assertEquals("airbus-a320", m("Airbus A320"))
        assertEquals("airbus-a320neo", m("Airbus A320 NEO"))
        assertEquals("airbus-a321", m("Airbus A321-200"))
        assertEquals("boeing-737-800", m("Boeing 737-800"))
        assertEquals("boeing-777-300er", m("Boeing 777-300ER"))
        assertNull(m("Airbus A350-900"))
        assertNull(m("Airbus A321neo"))
        assertNull(AircraftMatcher.match(null, aircraft))
    }

    private fun pass(dep: String?, est: String? = null, kind: StatusKind = StatusKind.SCHEDULED, muted: Boolean = false) =
        BoardingPass(
            id = dep ?: "none", createdAt = 0, barcode = "", carrier = "SU", flightNumber = "12", rushMuted = muted,
            status = dep?.let { FlightStatus(provider = "X", fetchedAt = 0, kind = kind, depScheduled = it, depEstimated = est) },
        )

    @Test
    fun rushWindowIsTheHourBeforeDeparture() {
        val p = pass("2026-10-17T10:00+03:00")
        val at = { t: String -> Instant.parse(t) }
        assertFalse(Rush.isActive(p, at("2026-10-17T05:59:00Z")))
        assertTrue(Rush.isActive(p, at("2026-10-17T06:00:00Z")))
        assertTrue(Rush.isActive(p, at("2026-10-17T06:59:00Z")))
        assertFalse(Rush.isActive(p, at("2026-10-17T07:00:00Z")))
        // A delay moves the window with the estimate.
        val delayed = pass("2026-10-17T10:00+03:00", est = "2026-10-17T10:45+03:00")
        assertTrue(Rush.isActive(delayed, at("2026-10-17T07:30:00Z")))
        assertFalse(Rush.isActive(pass("2026-10-17T10:00+03:00", kind = StatusKind.DEPARTED), at("2026-10-17T06:30:00Z")))
        assertFalse(Rush.isActive(pass("2026-10-17T10:00+03:00", muted = true), at("2026-10-17T06:30:00Z")))
        assertFalse(Rush.isActive(pass(null), at("2026-10-17T06:30:00Z")))
    }

    @Test
    fun rushNextStart() {
        val a = pass("2026-10-17T10:00+03:00")
        val b = pass("2026-10-17T15:00+03:00")
        assertEquals(Instant.parse("2026-10-17T06:00:00Z"), Rush.nextStart(listOf(b, a), Instant.parse("2026-10-17T05:00:00Z")))
        val inside = Instant.parse("2026-10-17T06:10:00Z")
        assertEquals(inside, Rush.nextStart(listOf(a, b), inside))
        assertEquals(Instant.parse("2026-10-17T11:00:00Z"), Rush.nextStart(listOf(a, b), Instant.parse("2026-10-17T08:00:00Z")))
        assertNull(Rush.nextStart(listOf(a, b), Instant.parse("2026-10-17T13:00:00Z")))
    }

    @Test
    fun translatesToEnglish() {
        I18n.lang = Lang.EN
        assertEquals("Boarding", StatusKind.BOARDING.label)
        val map = SeatMapsParser.parseSeatMap(mapV1, "v1")
        assertEquals("exit row", SeatMapLogic.featureLabels(map.seat("6A")!!).first().first)
        assertEquals("28–29″ (71–74 cm)", SeatMapLogic.inches("28-29\""))
    }
}
