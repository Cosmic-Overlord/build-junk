package io.github.cosmicoverlord.boardingpass.core.model

import io.github.cosmicoverlord.boardingpass.core.tr
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The flight's live card (Android's "Live Updates", like Live Activities on iPhone): from a few
 * hours before departure until shortly after landing, on the lock screen and as a chip in the
 * status bar. Before departure it counts down to the departure and shows the gate; in the air it
 * counts down to the arrival and shows how far along the flight is.
 */
object LiveFlight {
    enum class Phase { BEFORE, IN_FLIGHT, LANDED }

    /** How long the card stays up after landing. */
    val LANDED_FOR: Duration = Duration.ofMinutes(30)

    /** The progress bar is redrawn this often in flight; the countdowns tick by themselves. */
    val TICK: Duration = Duration.ofMinutes(5)

    data class State(
        val phase: Phase,
        /** Best known times, in each airport's local offset. */
        val departure: OffsetDateTime,
        val arrival: OffsetDateTime?,
        /** Share of the flight time elapsed, 0..1. */
        val progress: Float,
        /** What the countdown counts down to; null shows none. */
        val countdownTo: Instant?,
        /** When the card changes by itself: the next phase starts or the card goes away. */
        val endsAt: Instant,
    )

    data class Card(val title: String, val text: String, val subText: String?, val chip: String?)

    fun state(p: BoardingPass, now: Instant, lead: Duration): State? {
        if (p.liveHidden) return null
        val st = p.status ?: return null
        if (st.kind == StatusKind.CANCELLED || st.kind == StatusKind.DIVERTED) return null
        val dep = p.departureTime() ?: return null
        val arr = p.arrivalTime()
        val depAt = dep.toInstant()
        val arrAt = arr?.toInstant()

        val landedAt = st.arrActual.toOdt()?.toInstant()
            ?: arrAt?.takeIf { !now.isBefore(it) }
            ?: Instant.ofEpochMilli(st.fetchedAt).takeIf { st.kind == StatusKind.LANDED || st.kind == StatusKind.ARRIVED }
        if (landedAt != null) {
            val until = landedAt.plus(LANDED_FOR)
            return if (now.isBefore(until)) State(Phase.LANDED, dep, arr, 1f, null, until) else null
        }

        val departed = st.kind == StatusKind.DEPARTED || st.kind == StatusKind.EN_ROUTE ||
            st.depActual.toOdt() != null || !now.isBefore(depAt)
        if (departed) {
            if (arrAt == null) {
                // Nothing to count down to; give up after the longest flights.
                val until = depAt.plus(Tracking.MAX_FLIGHT)
                return if (now.isBefore(until)) State(Phase.IN_FLIGHT, dep, null, 0f, null, until) else null
            }
            val total = Duration.between(depAt, arrAt).toMillis().coerceAtLeast(1)
            val progress = (Duration.between(depAt, now).toMillis().toFloat() / total).coerceIn(0f, 1f)
            return State(Phase.IN_FLIGHT, dep, arr, progress, arrAt, arrAt)
        }

        if (now.isBefore(depAt.minus(lead))) return null
        return State(Phase.BEFORE, dep, arr, 0f, depAt, depAt)
    }

    /** When the cards next need redrawing: a tick in flight, a phase change, or a card to start. */
    fun nextWake(passes: List<BoardingPass>, now: Instant, lead: Duration): Instant? =
        passes.mapNotNull { p ->
            val s = state(p, now, lead)
            when {
                s == null -> p.departureTime()?.toInstant()?.minus(lead)?.takeIf {
                    it.isAfter(now) && !p.liveHidden && p.status?.kind != StatusKind.CANCELLED && p.status?.kind != StatusKind.DIVERTED
                }
                s.phase == Phase.IN_FLIGHT && s.arrival != null -> minOf(now.plus(TICK), s.endsAt)
                else -> s.endsAt
            }
        }.minOrNull()

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun card(p: BoardingPass, s: State): Card {
        val st = p.status
        val title = "${p.flightLabel}  ${p.from} → ${p.to}"
        return when (s.phase) {
            Phase.BEFORE -> {
                val scheduled = st?.depScheduled.toOdt()
                val late = scheduled != null && Duration.between(scheduled, s.departure).toMinutes() >= 5
                val stage = st?.kind?.takeIf {
                    it in setOf(StatusKind.DELAYED, StatusKind.CHECK_IN, StatusKind.BOARDING, StatusKind.GATE_CLOSED)
                }
                val text = listOfNotNull(
                    tr("Вылет", "Departs") + " " + s.departure.format(hm) +
                        (if (late) " (" + tr("по расписанию", "scheduled") + " " + scheduled!!.format(hm) + ")" else ""),
                    st?.depGate?.let { tr("выход", "gate") + " $it" },
                    st?.depTerminal?.let { tr("терминал", "terminal") + " $it" },
                    stage?.label?.lowercase(),
                ).joinToString(" · ")
                Card(title, text, tr("до вылета", "until departure"), st?.depGate)
            }
            Phase.IN_FLIGHT -> {
                val arr = s.arrival
                if (arr == null) {
                    Card(title, tr("Вылетел в", "Departed at") + " " + s.departure.format(hm), null, null)
                } else {
                    val days = ChronoUnit.DAYS.between(s.departure.toLocalDate(), arr.toLocalDate())
                    val text = s.departure.format(hm) + " → " + arr.format(hm) +
                        (if (days != 0L) " (%+d)".format(days) else "") + " · " + tr("местное время", "local times")
                    Card(title, text, tr("до прилёта", "until arrival"), null)
                }
            }
            Phase.LANDED -> {
                val at = st?.arrActual.toOdt() ?: s.arrival
                val text = listOfNotNull(
                    at?.let { tr("Прилетел в", "Landed at") + " " + it.format(hm) } ?: tr("Прилетел", "Landed"),
                    st?.arrTerminal?.let { tr("терминал", "terminal") + " $it" },
                    st?.baggageBelt?.let { tr("лента", "belt") + " $it" },
                ).joinToString(" · ")
                Card(title, text, null, null)
            }
        }
    }
}
