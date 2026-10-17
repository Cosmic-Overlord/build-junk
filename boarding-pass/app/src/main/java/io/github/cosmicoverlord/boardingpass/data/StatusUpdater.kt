package io.github.cosmicoverlord.boardingpass.data

import io.github.cosmicoverlord.boardingpass.core.data.AirportDb
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import io.github.cosmicoverlord.boardingpass.core.status.FlightQuery
import io.github.cosmicoverlord.boardingpass.core.status.StatusDiff
import io.github.cosmicoverlord.boardingpass.core.status.StatusException
import io.github.cosmicoverlord.boardingpass.core.status.createProvider
import io.github.cosmicoverlord.boardingpass.core.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate

/** Result of one status request; [changes] is empty for the first fetch or when nothing moved. */
data class FlightUpdate(val pass: BoardingPass, val changes: List<String>)

data class RefreshSummary(val flights: Int, val ok: Int, val failed: List<Pair<String, String>>)

private const val PACE_MS = 1_200L

class StatusUpdater(
    private val repo: PassRepository,
    private val settings: SettingsStore,
    private val airports: () -> AirportDb,
) {
    /** Refresh one pass and every other pass on the same flight (e.g. travelling together). */
    suspend fun refresh(id: String): FlightUpdate {
        val pass = repo.get(id) ?: throw StatusException(tr("Талон не найден", "Boarding pass not found"))
        return refreshGroup(repo.passes.value.filter { it.flightKey == pass.flightKey })
    }

    /** Background tracking: flights around departure every run, later ones every few hours. */
    suspend fun refreshTrackable(): List<FlightUpdate> {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        return paced(repo.passes.value.filter { Tracking.needsBackgroundRefresh(it, today, now) })
            .mapNotNull { (_, result) -> result.getOrNull() }
    }

    /** Pre-departure refresh of specific flights; failures are kept on the passes, not thrown. */
    suspend fun refreshFlights(keys: Set<String>): List<FlightUpdate> =
        paced(repo.passes.value.filter { it.flightKey in keys }).mapNotNull { (_, result) -> result.getOrNull() }

    /** Pull-to-refresh: every upcoming flight, whatever its date, with a summary of what failed. */
    suspend fun refreshUpcoming(): RefreshSummary {
        if (!settings.state.value.trackingEnabled) return RefreshSummary(0, 0, emptyList())
        val today = LocalDate.now()
        val results = paced(repo.passes.value.filter { Tracking.isRefreshable(it, today) })
        val failed = results.mapNotNull { (pass, result) -> result.exceptionOrNull()?.let { pass.flightLabel to (it.message ?: "?") } }
        return RefreshSummary(results.size, results.size - failed.size, failed)
    }

    /** Opening the list: flights near departure whose status is older than [maxAgeMs]. */
    suspend fun refreshStale(maxAgeMs: Long) {
        if (!settings.state.value.trackingEnabled) return
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        paced(repo.passes.value.filter { Tracking.isTrackable(it, today) && now - (it.statusCheckedAt ?: 0L) > maxAgeMs })
    }

    /**
     * One request per flight (passengers on the same flight share it), spaced out so that
     * back-to-back calls don't trip the providers' per-second limits.
     */
    private suspend fun paced(passes: List<BoardingPass>): List<Pair<BoardingPass, Result<FlightUpdate>>> =
        passes.groupBy { it.flightKey }.values.mapIndexed { i, group ->
            if (i > 0) delay(PACE_MS)
            val result = try {
                Result.success(refreshGroup(group))
            } catch (e: StatusException) {
                Result.failure(e)
            }
            group.first() to result
        }

    private suspend fun refreshGroup(group: List<BoardingPass>): FlightUpdate {
        val first = group.first()
        val s = settings.state.value
        val provider = createProvider(s.provider, s.key())
            ?: throw StatusException(tr("Источник статуса рейса не выбран — откройте настройки", "No flight status source — open Settings"))
        val date = first.date ?: throw StatusException(tr("Неизвестна дата рейса", "The flight date is unknown"))
        if (first.carrier.isBlank() || first.flightNumber.isBlank()) {
            throw StatusException(tr("Не указан номер рейса", "The flight number is missing"))
        }

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
            val error = e as? StatusException ?: StatusException(tr("Ошибка", "Error") + ": ${e.message ?: e.javaClass.simpleName}")
            repo.update { list ->
                list.map { if (it.id in ids) it.copy(statusError = error.message, statusCheckedAt = now) else it }
            }
            throw error
        }
    }
}
