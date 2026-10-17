package io.github.cosmicoverlord.boardingpass

import android.app.Application
import android.content.Context
import io.github.cosmicoverlord.boardingpass.core.data.AirlineDb
import io.github.cosmicoverlord.boardingpass.core.data.AirportDb
import io.github.cosmicoverlord.boardingpass.data.PassRepository
import io.github.cosmicoverlord.boardingpass.data.SettingsStore
import io.github.cosmicoverlord.boardingpass.data.StatusUpdater
import io.github.cosmicoverlord.boardingpass.importer.BarcodeDecoder
import io.github.cosmicoverlord.boardingpass.importer.PassImporter
import io.github.cosmicoverlord.boardingpass.tracking.Notifications
import io.github.cosmicoverlord.boardingpass.tracking.TrackingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BoardingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.createChannel(this)
        TrackingScheduler.apply(this, AppGraph.settings.state.value)
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
}
