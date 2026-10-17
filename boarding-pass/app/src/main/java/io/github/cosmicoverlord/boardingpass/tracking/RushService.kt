package io.github.cosmicoverlord.boardingpass.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.cosmicoverlord.boardingpass.AppGraph
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Refreshes the status every couple of minutes during the hour before departure, when gates
 * change at the last moment. A foreground service is the only thing that keeps polling this
 * often with the screen off; it's started by an exact alarm at the start of the window (which
 * is allowed from the background) and stops itself at departure.
 */
class RushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = activeNow()
        try {
            ServiceCompat.startForeground(
                this, Notifications.RUSH_ID, Notifications.rush(this, active),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Can't go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val keys = active.map { it.flightKey }.toSet()
            scope.launch {
                AppGraph.repository.update { list -> list.map { if (it.flightKey in keys) it.copy(rushMuted = true) else it } }
                finish()
            }
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoardingPass:pre-departure")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MS)
            }
        try {
            while (true) {
                val settings = AppGraph.settings.state.value
                if (!settings.trackingEnabled || !settings.rushEnabled) break
                val active = activeNow()
                if (active.isEmpty()) break
                val updates = AppGraph.statusUpdater.refreshFlights(active.map { it.flightKey }.toSet())
                if (settings.notifications) {
                    updates.filter { it.changes.isNotEmpty() }.forEach { Notifications.showChange(this, it) }
                }
                val still = activeNow()
                if (still.isEmpty()) break
                Notifications.updateRush(this, still)
                delay(settings.rushIntervalMinutes.coerceIn(1, 15) * 60_000L)
            }
        } finally {
            finish()
        }
    }

    private fun finish() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        RushScheduler.reschedule(applicationContext)
    }

    /** Android 15+ caps dataSync services; the hour before departure is well inside the limit. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        loop?.cancel()
        finish()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.cosmicoverlord.boardingpass.RUSH_STOP"
        private const val TAG = "RushService"
        private const val MAX_RUN_MS = 3 * 60 * 60 * 1000L

        @Volatile
        var running = false
            private set

        fun activeNow(): List<BoardingPass> {
            val now = Instant.now()
            return AppGraph.repository.passes.value.filter { Rush.isActive(it, now) }
        }
    }
}

object RushScheduler {
    private const val REQUEST = 7301

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, RushAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Arms one exact alarm for the next pre-departure window (or right away if one is open). */
    fun reschedule(context: Context) {
        val settings = AppGraph.settings.state.value
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        val next = if (settings.trackingEnabled && settings.rushEnabled) {
            Rush.nextStart(AppGraph.repository.passes.value, Instant.now())
        } else {
            null
        }
        if (next == null) {
            am.cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        if (RushService.running && next.toEpochMilli() <= now) return
        val at = maxOf(next.toEpochMilli(), now + 1_000)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Fallback when the system refuses the foreground service: single refreshes on alarms. */
    fun retryLater(context: Context) {
        val minutes = AppGraph.settings.state.value.rushIntervalMinutes.coerceIn(1, 15)
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<RushOnceWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + minutes * 60_000L
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        }
    }
}

class RushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (RushService.activeNow().isEmpty()) {
            RushScheduler.reschedule(context)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, RushService::class.java))
        } catch (e: Exception) {
            Log.w("RushAlarmReceiver", "Foreground service refused, falling back to work", e)
            RushScheduler.retryLater(context)
        }
    }
}

class RushOnceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.state.value
        val active = RushService.activeNow()
        if (active.isNotEmpty()) {
            val updates = AppGraph.statusUpdater.refreshFlights(active.map { it.flightKey }.toSet())
            if (settings.notifications) {
                updates.filter { it.changes.isNotEmpty() }.forEach { Notifications.showChange(applicationContext, it) }
            }
        }
        return Result.success()
    }
}

/** Alarms don't survive a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RushScheduler.reschedule(context)
    }
}
