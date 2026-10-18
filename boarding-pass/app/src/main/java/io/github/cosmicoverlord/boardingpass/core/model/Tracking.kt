package io.github.cosmicoverlord.boardingpass.core.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Tracking {
    /** A provider that never says "landed" still lets go this long after the arrival time. */
    val ARRIVAL_GRACE: Duration = Duration.ofHours(3)

    /** With a departure but no arrival time, give up after the longest scheduled flights. */
    val MAX_FLIGHT: Duration = Duration.ofHours(20)

    /**
     * The single rule every automatic refresh checks: a finished flight is never polled again.
     * Finished = the provider reported landed/arrived/cancelled/diverted, or its times are well in
     * the past even though the status got stuck, or the departure day is over and nothing is known.
     */
    fun isFinished(p: BoardingPass, today: LocalDate, now: Instant = Instant.now()): Boolean {
        if (p.status?.kind?.isFinal == true) return true
        p.arrivalTime()?.let { if (now.isAfter(it.toInstant().plus(ARRIVAL_GRACE))) return true }
        if (p.arrivalTime() == null) {
            p.departureTime()?.let { if (now.isAfter(it.toInstant().plus(MAX_FLIGHT))) return true }
        }
        val d = p.date ?: return false
        if (p.status == null && d.isBefore(today)) return true
        return d.isBefore(today.minusDays(1))
    }

    /** Flights worth polling in the background: from the day before departure until arrival. */
    fun isTrackable(p: BoardingPass, today: LocalDate, now: Instant = Instant.now()): Boolean {
        val d = p.date ?: return false
        if (p.carrier.isBlank() || p.flightNumber.isBlank()) return false
        if (d.isBefore(today.minusDays(1)) || d.isAfter(today.plusDays(1))) return false
        return !isFinished(p, today, now)
    }

    /** A manual refresh covers every upcoming flight that has a number and a date. */
    fun isRefreshable(p: BoardingPass, today: LocalDate, now: Instant = Instant.now()): Boolean =
        p.date != null && p.carrier.isNotBlank() && p.flightNumber.isNotBlank() && isUpcoming(p, today, now)

    const val LOOKAHEAD_DAYS = 3L
    const val SLOW_REFRESH_MS = 6 * 60 * 60 * 1000L

    /**
     * Background refresh: flights around departure on every run; later ones (up to
     * [LOOKAHEAD_DAYS] ahead) every [SLOW_REFRESH_MS], enough for schedule changes without
     * spending the API quota.
     */
    fun needsBackgroundRefresh(p: BoardingPass, today: LocalDate, now: Long, at: Instant = Instant.ofEpochMilli(now)): Boolean {
        if (isTrackable(p, today, at)) return true
        val d = p.date ?: return false
        if (!isRefreshable(p, today, at) || d.isAfter(today.plusDays(LOOKAHEAD_DAYS))) return false
        return now - (p.statusCheckedAt ?: 0L) >= SLOW_REFRESH_MS
    }

    /** Upcoming = not finished (see [isFinished]); undated passes stay upcoming. */
    fun isUpcoming(p: BoardingPass, today: LocalDate, now: Instant = Instant.now()): Boolean = !isFinished(p, today, now)
}

/**
 * The hour before departure, when gates and boarding change at short notice and the status is
 * refreshed every couple of minutes. For a 10:00 departure (boarding ~9:20, gate closes ~9:40)
 * the window opens at 9:00 and moves with the estimated time when the flight is delayed. It
 * lasts until the flight has actually left, so a delay on the ground is followed too, but no
 * later than 11:00.
 */
object Rush {
    const val WINDOW_MINUTES = 60L

    /** A delay on the ground keeps the refresh going this long past the expected departure. */
    const val AFTER_MINUTES = 60L

    /** Estimated departure if known, else scheduled; null without a status (no time on the pass). */
    fun departure(p: BoardingPass): Instant? =
        (p.status?.depEstimated.toOdt() ?: p.status?.depScheduled.toOdt())?.toInstant()

    /** From an hour before the expected departure until it actually leaves (see [isDone]), an hour late at most. */
    fun window(p: BoardingPass): ClosedRange<Instant>? {
        val dep = departure(p) ?: return null
        return dep.minusSeconds(WINDOW_MINUTES * 60)..dep.plusSeconds(AFTER_MINUTES * 60)
    }

    fun isDone(p: BoardingPass, now: Instant = Instant.now()): Boolean =
        p.rushMuted || p.status?.kind?.hasLeft == true || p.status?.depActual.toOdt() != null || p.takeoffAt != null ||
            p.carrier.isBlank() || p.flightNumber.isBlank() ||
            Tracking.isFinished(p, now.atZone(ZoneId.systemDefault()).toLocalDate(), now)

    fun isActive(p: BoardingPass, now: Instant): Boolean {
        if (isDone(p, now)) return false
        val w = window(p) ?: return false
        return now >= w.start && now < w.endInclusive
    }

    /** When the next window opens (now, if one is already open), or null when nothing is ahead. */
    fun nextStart(passes: List<BoardingPass>, now: Instant): Instant? =
        passes.asSequence()
            .filterNot { isDone(it, now) }
            .mapNotNull(::window)
            .filter { it.endInclusive > now }
            .map { maxOf(it.start, now) }
            .minOrNull()
}
