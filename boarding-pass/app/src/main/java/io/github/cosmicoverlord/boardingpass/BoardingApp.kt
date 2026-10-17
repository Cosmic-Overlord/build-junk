package io.github.cosmicoverlord.boardingpass

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import io.github.cosmicoverlord.boardingpass.core.I18n
import io.github.cosmicoverlord.boardingpass.core.data.AirlineDb
import io.github.cosmicoverlord.boardingpass.core.data.AirportDb
import io.github.cosmicoverlord.boardingpass.data.AppLanguage
import io.github.cosmicoverlord.boardingpass.data.PassRepository
import io.github.cosmicoverlord.boardingpass.data.SeatMapRepository
import io.github.cosmicoverlord.boardingpass.data.SettingsStore
import io.github.cosmicoverlord.boardingpass.data.StatusUpdater
import io.github.cosmicoverlord.boardingpass.importer.BarcodeDecoder
import io.github.cosmicoverlord.boardingpass.importer.PassImporter
import io.github.cosmicoverlord.boardingpass.tracking.Notifications
import io.github.cosmicoverlord.boardingpass.tracking.RushScheduler
import io.github.cosmicoverlord.boardingpass.tracking.TrackingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class BoardingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val settings = AppGraph.settings
        I18n.lang = settings.state.value.language.resolve()
        Notifications.createChannels(this)
        TrackingScheduler.apply(this, settings.state.value)
        AppGraph.scope.launch {
            // Language follows the setting; channel names are re-created in the new language.
            settings.state.map { it.language }.distinctUntilChanged().collect {
                I18n.lang = it.resolve()
                Notifications.createChannels(this@BoardingApp)
            }
        }
        AppGraph.scope.launch {
            // Any change to passes, statuses or settings can move the next pre-departure window.
            combine(AppGraph.repository.passes, settings.state) { _, _ -> }.collect {
                RushScheduler.reschedule(this@BoardingApp)
            }
        }
        // Warm up the reference data off the main thread.
        AppGraph.scope.launch(Dispatchers.Default) {
            AppGraph.airports
            AppGraph.airlines
        }
    }
}

/** Tiny service locator: the app is small enough not to need a DI framework. */
object AppGraph {
    private lateinit var context: Context

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(app: Context) {
        context = app.applicationContext
    }

    val repository by lazy { PassRepository(context) }
    val settings by lazy { SettingsStore(context) }
    val airports: AirportDb by lazy { context.assets.open("airports.tsv").use { AirportDb.parse(it) } }
    val airlines: AirlineDb by lazy { context.assets.open("airlines.tsv").use { AirlineDb.parse(it) } }
    val decoder by lazy { BarcodeDecoder(context) }
    val importer by lazy { PassImporter(context, repository, decoder) }
    val statusUpdater by lazy { StatusUpdater(repository, settings) { airports } }
    val seatMaps by lazy { SeatMapRepository(context) }
}

/** Applies the in-app language to an activity's resources (date pickers, system strings). */
object LocaleContext {
    fun wrap(base: Context): Context {
        val lang = SettingsStore.peekLanguage(base)
        if (lang == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(lang.tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
