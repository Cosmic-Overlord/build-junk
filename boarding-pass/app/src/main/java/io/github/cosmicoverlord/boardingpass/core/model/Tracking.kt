package io.github.cosmicoverlord.boardingpass.core.model

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
