package io.github.cosmicoverlord.boardingpass.core

import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.FlightStatus
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import io.github.cosmicoverlord.boardingpass.core.model.StatusKind
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
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
        // Scheduled arrival 08:30Z: still polled within the 3 h grace, not after.
        assertTrue(polled(stuck, at("2026-10-17T10:00:00Z")))
        assertFalse(polled(stuck, at("2026-10-17T11:31:00Z")))
        // Only a departure time: give up 20 h later.
        val noArrival = pass(kind = StatusKind.DEPARTED, arr = null)
        assertTrue(Tracking.isFinished(noArrival, LocalDate.of(2026, 10, 18), at("2026-10-18T03:01:00Z")))
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
