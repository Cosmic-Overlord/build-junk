package io.github.cosmicoverlord.boardingpass.data

import io.github.cosmicoverlord.boardingpass.core.data.AirportDb
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import io.github.cosmicoverlord.boardingpass.core.status.FlightQuery
import io.github.cosmicoverlord.boardingpass.core.status.StatusDiff
import io.github.cosmicoverlord.boardingpass.core.status.StatusException
import io.github.cosmicoverlord.boardingpass.core.status.createProvider
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** Result of one status request; [changes] is empty for the first fetch or when nothing moved. */
data class FlightUpdate(val pass: BoardingPass, val changes: List<String>)

class StatusUpdater(
    private val repo: PassRepository,
    private val settings: SettingsStore,
    private val airports: () -> AirportDb,
) {
    /** Refresh one pass and every other pass on the same flight (e.g. travelling together). */
    suspend fun refresh(id: String): FlightUpdate {
        val pass = repo.get(id) ?: throw StatusException("Талон не найден")
        return refreshGroup(repo.passes.value.filter { it.flightKey == pass.flightKey })
    }

    /** Background tracking: flights from the day before departure until they land. */
    suspend fun refreshTrackable(): List<FlightUpdate> {
        val today = LocalDate.now()
        return repo.passes.value
            .filter { Tracking.isTrackable(it, today) }
            .groupBy { it.flightKey }
            .values
            .mapNotNull { group -> runCatching { refreshGroup(group) }.getOrNull() }
    }

    /** Foreground refresh of trackable flights whose status is older than [maxAgeMs]. */
    suspend fun refreshStale(maxAgeMs: Long) {
        if (!settings.state.value.trackingEnabled) return
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        repo.passes.value
            .filter { Tracking.isTrackable(it, today) && now - (it.statusCheckedAt ?: 0L) > maxAgeMs }
            .groupBy { it.flightKey }
            .values
            .forEach { group -> runCatching { refreshGroup(group) } }
    }

    private suspend fun refreshGroup(group: List<BoardingPass>): FlightUpdate {
        val first = group.first()
        val s = settings.state.value
        val provider = createProvider(s.provider, s.key())
            ?: throw StatusException("Источник статуса рейса не выбран — откройте настройки")
        val date = first.date ?: throw StatusException("Неизвестна дата рейса")
        if (first.carrier.isBlank() || first.flightNumber.isBlank()) throw StatusException("Не указан номер рейса")

        val db = airports()
        val q = FlightQuery(first.carrier, first.flightNumber, date, first.from, first.to, db[first.from]?.zone, db[first.to]?.zone)
        val ids = group.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        try {
            val status = provider.fetch(q)
            val changes = StatusDiff.changes(first.status, status)
            repo.update { list ->
                list.map { if (it.id in ids) it.copy(status = status, statusError = null, statusCheckedAt = now) else it }
            }
            return FlightUpdate(first.copy(status = status), changes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e as? StatusException ?: StatusException("Ошибка: ${e.message ?: e.javaClass.simpleName}")
            repo.update { list ->
                list.map { if (it.id in ids) it.copy(statusError = error.message, statusCheckedAt = now) else it }
            }
            throw error
        }
    }
}
