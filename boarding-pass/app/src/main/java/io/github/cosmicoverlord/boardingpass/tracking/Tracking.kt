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
import io.github.cosmicoverlord.boardingpass.data.AppSettings
import io.github.cosmicoverlord.boardingpass.data.FlightUpdate
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
    const val EXTRA_PASS_ID = "pass_id"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Изменения рейсов", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Смена выхода, терминала, времени вылета и статуса рейса"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showChange(context: Context, update: FlightUpdate) {
        if (!canNotify(context)) return
        val pass = update.pass
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PASS_ID, pass.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val requestCode = pass.flightKey.hashCode()
        val pending = PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "${pass.flightLabel}  ${pass.from} → ${pass.to}"
        val text = update.changes.joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle(title)
            .setContentText(update.changes.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
