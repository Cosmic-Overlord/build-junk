package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.LiveFlight
import io.github.cosmicoverlord.boardingpass.core.model.LiveFlight.Phase
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LiveFlightTest {
    @Before
    fun russian() {
        I18n.lang = Lang.RU
    }

    private val lead = Duration.ofHours(3)

    // SVO 10:00 MSK (07:00Z) -> AER 12:00 MSK (09:00Z), two hours in the air.
    private fun pass(status: FlightStatus? = FlightStatus(provider = "X", fetchedAt = 0), hidden: Boolean = false) = BoardingPass(
        id = "x", createdAt = 0, barcode = "", carrier = "SU", flightNumber = "1234", flightDate = "2026-10-17",
        from = "SVO", to = "AER", liveHidden = hidden,
        status = status?.copy(depScheduled = status.depScheduled ?: "2026-10-17T10:00+03:00", arrScheduled = status.arrScheduled ?: "2026-10-17T12:00+03:00"),
    )

    private fun st(kind: StatusKind = StatusKind.SCHEDULED, gate: String? = null, depEst: String? = null, arrActual: String? = null, fetchedAt: Long = 0) =
        FlightStatus(provider = "X", fetchedAt = fetchedAt, kind = kind, depGate = gate, depEstimated = depEst, arrActual = arrActual)

    private fun at(t: String) = Instant.parse(t)

    @Test
    fun followsTheFlight() {
        val p = pass(st(gate = "B12"))
        assertNull(LiveFlight.state(p, at("2026-10-17T03:59:00Z"), lead))

        val before = LiveFlight.state(p, at("2026-10-17T04:00:00Z"), lead)!!
        assertEquals(Phase.BEFORE, before.phase)
        assertEquals(at("2026-10-17T07:00:00Z"), before.countdownTo)
        val card = LiveFlight.card(p, before)
        assertEquals("SU 1234  SVO → AER", card.title)
        assertEquals("Вылет 10:00 · выход B12", card.text)
        assertEquals("до вылета", card.subText)
        assertEquals("B12", card.chip)

        val air = LiveFlight.state(p, at("2026-10-17T08:30:00Z"), lead)!!
        assertEquals(Phase.IN_FLIGHT, air.phase)
        assertEquals(0.75f, air.progress, 0.001f)
        assertEquals(at("2026-10-17T09:00:00Z"), air.countdownTo)
        assertEquals("10:00 → 12:00 · местное время", LiveFlight.card(p, air).text)
        assertEquals("до прилёта", LiveFlight.card(p, air).subText)
        assertNull(LiveFlight.card(p, air).chip)

        val landed = LiveFlight.state(p, at("2026-10-17T09:10:00Z"), lead)!!
        assertEquals(Phase.LANDED, landed.phase)
        assertNull(landed.countdownTo)
        assertEquals("Прилетел в 12:00", LiveFlight.card(p, landed).text)
        assertNull(LiveFlight.state(p, at("2026-10-17T09:30:00Z"), lead))
    }

    @Test
    fun usesEstimatesAndActuals() {
        val delayed = pass(st(kind = StatusKind.DELAYED, depEst = "2026-10-17T10:40+03:00"))
        val s = LiveFlight.state(delayed, at("2026-10-17T07:10:00Z"), lead)!!
        assertEquals(Phase.BEFORE, s.phase)
        assertEquals("Вылет 10:40 (по расписанию 10:00) · задерживается", LiveFlight.card(delayed, s).text)

        // Landed early: the actual arrival ends the flight and starts the 30 minutes.
        val early = pass(st(kind = StatusKind.LANDED, arrActual = "2026-10-17T11:45+03:00"))
        val e = LiveFlight.state(early, at("2026-10-17T08:50:00Z"), lead)!!
        assertEquals(Phase.LANDED, e.phase)
        assertEquals(at("2026-10-17T09:15:00Z"), e.endsAt)
        assertEquals("Прилетел в 11:45", LiveFlight.card(early, e).text)

        // Night flight into the next day.
        val night = pass(FlightStatus(provider = "X", fetchedAt = 0, depScheduled = "2026-10-17T23:10+03:00", arrScheduled = "2026-10-18T06:05+05:00"))
        val n = LiveFlight.state(night, at("2026-10-17T21:00:00Z"), lead)!!
        assertEquals("23:10 → 06:05 (+1) · местное время", LiveFlight.card(night, n).text)
    }

    @Test
    fun noCardWithoutTimesOrWhenDismissed() {
        val now = at("2026-10-17T06:00:00Z")
        assertNull(LiveFlight.state(pass(status = null), now, lead))
        assertNull(LiveFlight.state(pass(st(), hidden = true), now, lead))
        assertNull(LiveFlight.state(pass(st(kind = StatusKind.CANCELLED)), now, lead))
    }

    @Test
    fun wakesForTicksPhasesAndStarts() {
        val p = pass(st())
        assertEquals(at("2026-10-17T04:00:00Z"), LiveFlight.nextWake(listOf(p), at("2026-10-17T01:00:00Z"), lead))
        assertEquals(at("2026-10-17T07:00:00Z"), LiveFlight.nextWake(listOf(p), at("2026-10-17T05:00:00Z"), lead))
        assertEquals(at("2026-10-17T08:05:00Z"), LiveFlight.nextWake(listOf(p), at("2026-10-17T08:00:00Z"), lead))
        assertEquals(at("2026-10-17T09:00:00Z"), LiveFlight.nextWake(listOf(p), at("2026-10-17T08:58:00Z"), lead))
        assertEquals(at("2026-10-17T09:30:00Z"), LiveFlight.nextWake(listOf(p), at("2026-10-17T09:10:00Z"), lead))
        assertNull(LiveFlight.nextWake(listOf(p), at("2026-10-17T09:30:00Z"), lead))
    }
}
