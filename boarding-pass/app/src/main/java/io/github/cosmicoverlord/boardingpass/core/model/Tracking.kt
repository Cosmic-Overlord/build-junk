package io.github.cosmicoverlord.boardingpass.core.model

import java.time.Instant
import java.time.LocalDate

object Tracking {
    /** Flights worth polling in the background: from the day before departure until arrival. */
    fun isTrackable(p: BoardingPass, today: LocalDate): Boolean {
        val d = p.date ?: return false
        if (p.carrier.isBlank() || p.flightNumber.isBlank()) return false
        if (d.isBefore(today.minusDays(1)) || d.isAfter(today.plusDays(1))) return false
        return p.status?.kind?.isFinal != true
    }

    /** Upcoming = not yet arrived and not more than a day in the past. */
    fun isUpcoming(p: BoardingPass, today: LocalDate): Boolean {
        if (p.status?.kind?.isFinal == true) return false
        val d = p.date ?: return true
        return !d.isBefore(today.minusDays(1)) && !(d.isBefore(today) && p.status == null)
    }
}

/**
 * The hour before departure, when gates and boarding change at short notice and the status is
 * refreshed every couple of minutes. For a 10:00 departure (boarding ~9:20, gate closes ~9:40)
 * the window is 9:00–10:00, and it moves with the estimated time when the flight is delayed.
 */
object Rush {
    const val WINDOW_MINUTES = 60L

    /** Estimated departure if known, else scheduled; null without a status (no time on the pass). */
    fun departure(p: BoardingPass): Instant? =
        (p.status?.depEstimated.toOdt() ?: p.status?.depScheduled.toOdt())?.toInstant()

    fun window(p: BoardingPass): ClosedRange<Instant>? {
        val dep = departure(p) ?: return null
        return dep.minusSeconds(WINDOW_MINUTES * 60)..dep
    }

    fun isDone(p: BoardingPass): Boolean =
        p.rushMuted || p.status?.kind?.hasLeft == true || p.carrier.isBlank() || p.flightNumber.isBlank()

    fun isActive(p: BoardingPass, now: Instant): Boolean {
        if (isDone(p)) return false
        val w = window(p) ?: return false
        return now >= w.start && now < w.endInclusive
    }

    /** When the next window opens (now, if one is already open), or null when nothing is ahead. */
    fun nextStart(passes: List<BoardingPass>, now: Instant): Instant? =
        passes.asSequence()
            .filterNot(::isDone)
            .mapNotNull(::window)
            .filter { it.endInclusive > now }
            .map { maxOf(it.start, now) }
            .minOrNull()
}
