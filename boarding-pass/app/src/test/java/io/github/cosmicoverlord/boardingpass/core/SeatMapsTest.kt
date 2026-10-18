package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import io.github.cosmicoverlord.boardingpass.core.seatmap.AircraftMatcher
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapLogic
import io.github.cosmicoverlord.boardingpass.core.seatmap.SeatMapsParser
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmAircraft
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmBox
import io.github.cosmicoverlord.boardingpass.core.seatmap.SmOutline
import io.github.cosmicoverlord.boardingpass.core.seatmap.SvgPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

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

    private fun carpet(width: Int, left: Int, viewBox: String, shape: String) =
        "<div id=\"plane-vector-carpet\" class=\"comp-plane_carpet plane-wrap\" style=\"width: ${width}px; margin: 0px 0px 0px ${left}px; transform: scale(0.365);\">" +
            "<svg viewBox=\"$viewBox\">$shape</svg></div>"

    private fun bounds(o: SmOutline): List<Float> {
        val xs = o.rings.flatMap { r -> r.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = o.rings.flatMap { r -> r.filterIndexed { i, _ -> i % 2 == 1 } }
        return listOf(xs.min(), xs.max(), ys.min(), ys.max())
    }

    @Test
    fun parsesCabinOutline() {
        // 1088 units wide from -544: viewBox x 94..994 is -450..450 around the centre line.
        val path = SeatMapsParser.parseSeatMap(
            mapV1 + carpet(1088, -544, "0 0 1088 4000", "<path id=\"floor\" class=\"plane-vector_carpet\" d=\"M94 1000 H994 V3000 H94 Z\"></path>"), "v1",
        ).outline!!
        assertEquals(listOf(-450f, 450f, 1000f, 3000f), bounds(path))
        assertEquals(listOf(listOf(-450f, 1000f, 450f, 1000f, 450f, 3000f, -450f, 3000f)), path.rings)
        assertTrue(path.contains(0f, 2000f))
        assertFalse(path.contains(500f, 2000f))

        // Group transforms apply; a closed sibling group's transform does not; viewBox origin shifts.
        val grouped = SeatMapsParser.parseSeatMap(
            mapV1 + carpet(
                1088, -544, "100 0 1088 4000",
                "<g transform=\"rotate(90)\"></g><g transform=\"translate(100 0)\"><g transform=\"scale(2)\">" +
                    "<path class=\"plane-vector_carpet\" d=\"m47 500 h450 v1000 h-450 z\"/></g></g>",
            ), "v1",
        ).outline!!
        assertEquals(listOf(-450f, 450f, 1000f, 3000f), bounds(grouped))

        val polygon = SeatMapsParser.parseSeatMap(
            mapV2 + carpet(1100, -548, "0 0 1100 3000", "<polygon class=\"plane-vector_carpet\" points=\"98 1000 998 1000 998 3000 98 3000\"></polygon>"), "v2",
        ).outline!!
        assertEquals(listOf(listOf(-450f, 1000f, 450f, 1000f, 450f, 3000f, -450f, 3000f)), polygon.rings)

        // An outline that misses the seats, or is skewed, is dropped rather than drawn wrong.
        val off = mapV1 + carpet(1088, -544, "0 0 1088 4000", "<path class=\"plane-vector_carpet\" d=\"M0 0 H100 V100 H0 Z\"/>")
        assertNull(SeatMapsParser.parseSeatMap(off, "v1").outline)
        val skewed = mapV1 + carpet(1088, -544, "0 0 1088 4000", "<path class=\"plane-vector_carpet\" d=\"M94 1000 L994 1300 V3000 H94 Z\"/>")
        assertEquals(0f, path.asymmetry(), 0.001f)
        assertNull(SeatMapsParser.parseSeatMap(skewed, "v1").outline)
        assertNull(SeatMapsParser.parseSeatMap(mapV1, "v1").outline)
    }

    @Test
    fun flattensCurves() {
        val ring = SvgPath.flatten("M0 0 C0 10 10 10 10 0 Q5 -5 0 0 Z", steps = 4).single()
        assertEquals(9, ring.size)
        assertEquals(10.0 to 0.0, ring[4])
        assertEquals(7.5, ring[2].second, 1e-9)
        assertEquals(0.0 to 0.0, ring.last())
    }

    @Test
    fun putsIconsOnTheirMonuments() {
        val map = SeatMapsParser.parseSeatMap(mapV1, "v1")
        val icon = SeatMapLogic.icons(map).single()
        assertEquals("l", icon.kind)
        assertEquals(-434.8f + 286.5f / 2, icon.x, 0.01f)
        assertEquals(1250f + 66f, icon.y, 0.01f)

        // Two icons on one block sit side by side; a far-away one stays where it is.
        val two = map.copy(
            stickers = listOf(SmBox(-300f, 1100f, 80f, 80f, "g"), SmBox(-420f, 1100f, 80f, 80f, "l"), SmBox(300f, 100f, 80f, 80f, "l")),
        )
        val icons = SeatMapLogic.icons(two).sortedBy { it.x }
        assertEquals(listOf("l", "g", "l"), icons.map { it.kind })
        assertEquals(-434.8f + 286.5f / 3, icons[0].x, 0.01f)
        assertEquals(-434.8f + 286.5f * 2 / 3, icons[1].x, 0.01f)
        assertEquals(340f to 140f, icons[2].x to icons[2].y)
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
        // Still on the ground after the departure time: keep refreshing, for an hour at most.
        assertTrue(Rush.isActive(p, at("2026-10-17T07:30:00Z")))
        assertFalse(Rush.isActive(p, at("2026-10-17T08:00:00Z")))
        assertFalse(Rush.isActive(p.copy(takeoffAt = at("2026-10-17T07:10:00Z").toEpochMilli()), at("2026-10-17T07:30:00Z")))
        // A delay moves the window with the estimate.
        val delayed = pass("2026-10-17T10:00+03:00", est = "2026-10-17T10:45+03:00")
        assertTrue(Rush.isActive(delayed, at("2026-10-17T08:30:00Z")))
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
    fun refreshCoversLaterFlightsToo() {
        val today = LocalDate.of(2026, 10, 17)
        val now = 1_000_000_000_000L
        fun p(date: String, checkedAgoH: Long?) = BoardingPass(
            id = date, createdAt = 0, barcode = "", carrier = "SU", flightNumber = "12", flightDate = date,
            statusCheckedAt = checkedAgoH?.let { now - it * 3_600_000L },
        )
        val tomorrow = p("2026-10-18", 0)
        val inTwoDays = p("2026-10-19", 1)
        val inTwoDaysStale = p("2026-10-19", 7)
        val nextWeek = p("2026-10-24", null)
        // Pull-to-refresh: every upcoming flight.
        assertTrue(listOf(tomorrow, inTwoDays, nextWeek).all { Tracking.isRefreshable(it, today) })
        // Background: near flights always, later ones only when 6 h stale and within 3 days.
        val bg = { x: BoardingPass -> Tracking.needsBackgroundRefresh(x, today, now) }
        assertTrue(bg(tomorrow))
        assertFalse(bg(inTwoDays))
        assertTrue(bg(inTwoDaysStale))
        assertFalse(bg(nextWeek))
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
