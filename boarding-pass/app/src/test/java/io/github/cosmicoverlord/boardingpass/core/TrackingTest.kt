package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.Routes
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** No automatic refresh may poll a flight that is over. */
class TrackingTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun pass(
        date: String? = "2026-10-17",
        kind: StatusKind? = StatusKind.SCHEDULED,
        dep: String? = "2026-10-17T10:00+03:00",
        arr: String? = "2026-10-17T11:30+03:00",
    ) = BoardingPass(
        id = "x", createdAt = 0, barcode = "", carrier = "SU", flightNumber = "12", flightDate = date,
        status = kind?.let { FlightStatus(provider = "X", fetchedAt = 0, kind = it, depScheduled = dep, arrScheduled = arr) },
    )

    private fun at(t: String) = Instant.parse(t)

    /** Every automatic path in one place: background, pull-to-refresh, list open, pre-departure. */
    private fun polled(p: BoardingPass, now: Instant): Boolean =
        Tracking.isTrackable(p, today, now) ||
            Tracking.isRefreshable(p, today, now) ||
            Tracking.needsBackgroundRefresh(p, today, now.toEpochMilli()) ||
            Rush.isActive(p, now)

    @Test
    fun finalStatusesAreNeverPolled() {
        val morning = at("2026-10-17T05:00:00Z")
        for (k in listOf(StatusKind.LANDED, StatusKind.ARRIVED, StatusKind.CANCELLED, StatusKind.DIVERTED)) {
            assertTrue(Tracking.isFinished(pass(kind = k), today, morning))
            assertFalse(polled(pass(kind = k), morning))
        }
        assertTrue(polled(pass(), morning))
    }

    @Test
    fun stuckStatusStopsAfterArrival() {
        val stuck = pass(kind = StatusKind.EN_ROUTE)
        // Scheduled arrival 08:30Z: still polled within the 2 h grace, not after.
        assertTrue(polled(stuck, at("2026-10-17T10:00:00Z")))
        assertFalse(polled(stuck, at("2026-10-17T10:31:00Z")))
        // Only a departure time: give up 20 h later.
        val noArrival = pass(kind = StatusKind.DEPARTED, arr = null)
        assertTrue(Tracking.isFinished(noArrival, LocalDate.of(2026, 10, 18), at("2026-10-18T03:01:00Z")))
    }

    @Test
    fun unreportedLandingIsAssumed() {
        val p = pass(kind = StatusKind.DEPARTED)
        assertEquals(StatusKind.DEPARTED, Tracking.shownKind(p, at("2026-10-17T08:40:00Z")))
        assertEquals(StatusKind.LANDED, Tracking.shownKind(p, at("2026-10-17T08:46:00Z")))
        assertTrue(Tracking.landingAssumed(p, at("2026-10-17T08:46:00Z")))
        // A status fetched after the due time that still says delayed: a long delay, not a missing report.
        val delayed = p.copy(status = p.status!!.copy(kind = StatusKind.DELAYED, fetchedAt = at("2026-10-17T08:50:00Z").toEpochMilli()))
        assertEquals(StatusKind.DELAYED, Tracking.shownKind(delayed, at("2026-10-17T09:00:00Z")))
        assertFalse(Tracking.landingAssumed(pass(kind = StatusKind.ARRIVED), at("2026-10-17T09:00:00Z")))
    }

    @Test
    fun arrivalFromRouteLengthWhenProviderHasNone() {
        Routes.distanceKm = { _, _ -> 1070 }
        try {
            val p = pass(kind = StatusKind.DEPARTED, arr = null).copy(from = "BUD", to = "SAW")
            // 1070 km at 800 km/h is 80 min, plus 30 min: due 08:50Z after a 07:00Z departure.
            assertEquals(at("2026-10-17T08:50:00Z"), p.expectedArrival()!!.toInstant())
            assertEquals(StatusKind.LANDED, Tracking.shownKind(p, at("2026-10-17T09:10:00Z")))
            assertFalse(Tracking.isFinished(p, today, at("2026-10-17T10:40:00Z")))
            assertTrue(Tracking.isFinished(p, today, at("2026-10-17T10:51:00Z")))
        } finally {
            Routes.distanceKm = { _, _ -> null }
        }
    }

    @Test
    fun pastDaysWithoutDataAreNotPolled() {
        assertTrue(Tracking.isFinished(pass(date = "2026-10-16", kind = null), today, at("2026-10-17T05:00:00Z")))
        assertFalse(polled(pass(date = "2026-10-16", kind = null), at("2026-10-17T05:00:00Z")))
        assertTrue(Tracking.isFinished(pass(date = "2026-10-15", kind = StatusKind.SCHEDULED, dep = null, arr = null), today))
        // Today's pass with no status yet is still worth asking about.
        assertTrue(polled(pass(kind = null), at("2026-10-17T05:00:00Z")))
    }
}
