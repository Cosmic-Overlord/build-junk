package io.github.cosmicoverlord.boardingpass.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.cosmicoverlord.boardingpass.AppGraph
import io.github.cosmicoverlord.boardingpass.MainActivity
import io.github.cosmicoverlord.boardingpass.R
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.toOdt
import io.github.cosmicoverlord.boardingpass.core.tr
import io.github.cosmicoverlord.boardingpass.data.AppSettings
import io.github.cosmicoverlord.boardingpass.data.FlightUpdate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TrackingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AppGraph.settings.state.value
        if (!settings.trackingEnabled || settings.refreshMinutes <= 0) return Result.success()
        val updates = AppGraph.statusUpdater.refreshTrackable()
        if (settings.notifications) {
            updates.filter { it.changes.isNotEmpty() }.forEach { Notifications.showChange(applicationContext, it) }
        }
        return Result.success()
    }
}

object TrackingScheduler {
    private const val WORK_NAME = "flight-tracking"

    fun apply(context: Context, settings: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (!settings.trackingEnabled || settings.refreshMinutes <= 0) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrackingWorker>(
            settings.refreshMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

object Notifications {
    private const val CHANNEL = "flight_updates"
    const val RUSH_CHANNEL = "pre_departure"
    const val RUSH_ID = 1001
    const val EXTRA_PASS_ID = "pass_id"
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Also called after a language change: re-creating a channel only updates its name. */
    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Изменения рейсов", "Flight changes"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(
                    "Смена выхода, терминала, времени вылета и статуса рейса",
                    "Gate, terminal, departure time and status changes",
                )
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(RUSH_CHANNEL, tr("Перед вылетом", "Before departure"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr(
                    "Постоянное уведомление, пока статус обновляется каждые пару минут перед вылетом",
                    "Ongoing notification while the status refreshes every few minutes before departure",
                )
                setShowBadge(false)
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openPass(context: Context, pass: BoardingPass, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PASS_ID, pass.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showChange(context: Context, update: FlightUpdate) {
        if (!canNotify(context)) return
        val pass = update.pass
        val requestCode = pass.flightKey.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle("${pass.flightLabel}  ${pass.from} → ${pass.to}")
            .setContentText(update.changes.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(update.changes.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openPass(context, pass, requestCode))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    /** "SU 12 · SVO → LED" / "Gate B12 · Terminal B · departs 10:05 · updated 09:12". */
    fun rush(context: Context, active: List<BoardingPass>): android.app.Notification {
        val first = active.firstOrNull()
        val lines = active.distinctBy { it.flightKey }.map { p ->
            val st = p.status
            val dep = (st?.depEstimated.toOdt() ?: st?.depScheduled.toOdt())?.format(hm)
            listOfNotNull(
                p.flightLabel,
                st?.depGate?.let { tr("выход", "gate") + " $it" },
                st?.depTerminal?.let { tr("терм.", "term.") + " $it" },
                dep?.let { tr("вылет", "dep.") + " $it" },
                st?.kind?.label,
            ).joinToString(" · ")
        }
        val stop = PendingIntent.getService(
            context, 0, Intent(context, RushService::class.java).setAction(RushService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = first?.let { "${it.flightLabel}  ${it.from} → ${it.to}" } ?: tr("Перед вылетом", "Before departure")
        val updated = tr("обновлено", "updated") + " " + LocalTime.now().format(hm)
        return NotificationCompat.Builder(context, RUSH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull()?.let { "$it · $updated" } ?: tr("Обновляю статус…", "Refreshing status…"))
            .setStyle(NotificationCompat.BigTextStyle().bigText((lines + updated).joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (first != null) setContentIntent(openPass(context, first, RUSH_ID)) }
            .addAction(0, tr("Остановить", "Stop"), stop)
            .build()
    }

    fun updateRush(context: Context, active: List<BoardingPass>) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(RUSH_ID, rush(context, active))
        } catch (e: SecurityException) {
            // Ignored, see showChange.
        }
    }
}
