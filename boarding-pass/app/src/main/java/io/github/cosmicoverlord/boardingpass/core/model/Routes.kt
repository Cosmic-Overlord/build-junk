package io.github.cosmicoverlord.boardingpass.core.model

import java.time.Duration
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Route lookups the app plugs in from its airport database, for flights the provider gives no
 * arrival time for.
 */
object Routes {
    @Volatile
    var distanceKm: (from: String, to: String) -> Int? = { _, _ -> null }

    @Volatile
    var zone: (airport: String) -> ZoneId? = { null }

    /** Gate to gate: about 800 km/h in cruise plus half an hour for taxiing, climb and descent. */
    fun flightTime(from: String, to: String): Duration? =
        distanceKm(from, to)?.takeIf { it > 0 }?.let { Duration.ofMinutes((it / 800.0 * 60).roundToLong() + 30) }
}
