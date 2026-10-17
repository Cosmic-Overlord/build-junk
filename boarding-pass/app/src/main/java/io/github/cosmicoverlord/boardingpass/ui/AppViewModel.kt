package io.github.cosmicoverlord.boardingpass.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cosmicoverlord.boardingpass.AppGraph
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.DecodedCode
import io.github.cosmicoverlord.boardingpass.core.model.Tracking
import io.github.cosmicoverlord.boardingpass.core.status.StatusException
import io.github.cosmicoverlord.boardingpass.data.AppSettings
import io.github.cosmicoverlord.boardingpass.importer.ImportOutcome
import io.github.cosmicoverlord.boardingpass.tracking.TrackingScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import io.github.cosmicoverlord.boardingpass.core.tr

sealed interface UiEvent {
    data class OpenPass(val id: String) : UiEvent
    data class OpenEdit(val id: String) : UiEvent
    data class Message(val text: String) : UiEvent
    data class AskSaveUnparsed(val code: DecodedCode) : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppGraph.repository
    private val settingsStore = AppGraph.settings
    val airports get() = AppGraph.airports
    val airlines get() = AppGraph.airlines

    val passes: StateFlow<List<BoardingPass>> = repo.passes
    val settings: StateFlow<AppSettings> = settingsStore.state

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun pass(id: String): BoardingPass? = repo.get(id)

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val outcomes = uris.map { AppGraph.importer.importUri(it) }
                handleOutcomes(outcomes)
            } finally {
                _importing.value = false
            }
        }
    }

    fun importUri(uri: Uri) = importUris(listOf(uri))

    /** Called by the camera scanner with a code that already parsed as BCBP (or the user forced it). */
    fun importScanned(code: DecodedCode) {
        viewModelScope.launch { handleOutcomes(listOf(AppGraph.importer.importCodes(listOf(code)))) }
    }

    fun saveUnparsed(code: DecodedCode) {
        viewModelScope.launch {
            val pass = AppGraph.importer.saveUnparsed(code)
            _events.send(UiEvent.OpenEdit(pass.id))
        }
    }

    private suspend fun handleOutcomes(outcomes: List<ImportOutcome>) {
        val added = outcomes.filterIsInstance<ImportOutcome.Added>()
        val ids = added.flatMap { it.ids }.distinct()
        val newCount = added.sumOf { it.newCount }
        if (ids.isNotEmpty()) {
            _events.send(UiEvent.OpenPass(ids.first()))
            _events.send(
                UiEvent.Message(
                    when {
                        newCount == 0 -> tr("Этот посадочный уже сохранён", "This boarding pass is already saved")
                        newCount == 1 -> tr("Посадочный талон добавлен", "Boarding pass added")
                        else -> tr("Добавлено талонов: $newCount", "Passes added: $newCount")
                    },
                ),
            )
            if (settings.value.trackingEnabled) {
                // One request per flight: refresh() already updates every pass on the same flight.
                // Never for passes of flights that are already over (old passes imported for the record).
                val today = LocalDate.now()
                ids.mapNotNull { repo.get(it) }
                    .filter { Tracking.isRefreshable(it, today) }
                    .distinctBy { it.flightKey }
                    .forEach { refresh(it.id, quiet = true) }
            }
            return
        }
        outcomes.filterIsInstance<ImportOutcome.NotBoardingPass>().firstOrNull()?.let {
            _events.send(UiEvent.AskSaveUnparsed(it.code))
            return
        }
        outcomes.filterIsInstance<ImportOutcome.Failed>().firstOrNull()?.let {
            _events.send(UiEvent.Message(it.message))
        }
    }

    fun refresh(id: String, quiet: Boolean = false) {
        if (id in _refreshing.value) return
        viewModelScope.launch {
            _refreshing.update { it + id }
            try {
                AppGraph.statusUpdater.refresh(id)
            } catch (e: StatusException) {
                if (!quiet) _events.send(UiEvent.Message(e.message ?: tr("Не удалось обновить статус", "Couldn't refresh the status")))
            } finally {
                _refreshing.update { it - id }
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _refreshing.update { it + ALL }
            try {
                if (!settings.value.trackingEnabled) {
                    _events.send(UiEvent.Message(tr("Чтобы получать статус рейсов, выберите источник в настройках", "To get flight status, choose a source in Settings")))
                } else {
                    val r = AppGraph.statusUpdater.refreshUpcoming()
                    val text = when {
                        r.flights == 0 -> tr("Нет предстоящих рейсов с номером и датой", "No upcoming flights with a number and date")
                        r.failed.isEmpty() -> tr("Обновлено рейсов: ${r.ok}", "Flights refreshed: ${r.ok}")
                        else -> tr("Обновлено ${r.ok} из ${r.flights}", "Refreshed ${r.ok} of ${r.flights}") + ". " +
                            r.failed.joinToString("; ") { (flight, error) -> "$flight — $error" }
                    }
                    _events.send(UiEvent.Message(text))
                }
            } finally {
                _refreshing.update { it - ALL }
            }
        }
    }

    /** Opportunistic refresh when the list is shown. */
    fun refreshStale() {
        viewModelScope.launch { AppGraph.statusUpdater.refreshStale(maxAgeMs = 10 * 60 * 1000L) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    /**
     * Applies user edits to the latest stored copy, so a status written by a refresh while the
     * edit screen was open is kept, and a pass deleted meanwhile is not brought back.
     */
    fun saveEdits(id: String, edit: (BoardingPass) -> BoardingPass) {
        viewModelScope.launch {
            repo.modify(id) { current ->
                val edited = edit(current)
                if (edited.flightKey != current.flightKey) {
                    edited.copy(status = null, statusError = null, statusCheckedAt = null)
                } else {
                    edited
                }
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = settingsStore.update(transform)
        TrackingScheduler.apply(getApplication<Application>(), next)
    }

    fun openPass(id: String) {
        viewModelScope.launch { _events.send(UiEvent.OpenPass(id)) }
    }

    companion object {
        const val ALL = "*"
    }
}
