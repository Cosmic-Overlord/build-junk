package io.github.cosmicoverlord.boardingpass.tracking

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.cosmicoverlord.boardingpass.AppGraph
import io.github.cosmicoverlord.boardingpass.R
import io.github.cosmicoverlord.boardingpass.core.model.BoardingPass
import io.github.cosmicoverlord.boardingpass.core.model.LiveFlight
import io.github.cosmicoverlord.boardingpass.core.model.Rush
import io.github.cosmicoverlord.boardingpass.core.tr
import io.github.cosmicoverlord.boardingpass.data.AppSettings
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The flight's live card (see [LiveFlight]). On Android 16+ it asks to be a promoted ongoing
 * notification ("Live Update"): a progress bar with the plane on the lock screen and a chip next
 * to the clock with the gate or a running countdown. Older versions get the same card as an
 * ordinary ongoing notification. Countdowns tick by themselves; an alarm only moves the progress
 * bar and switches phases, so nothing here needs the network.
 */
object LiveUpdates {
    const val CHANNEL = "live_flight"
    const val ACTION_HIDE = "io.github.cosmicoverlord.boardingpass.LIVE_HIDE"
    const val EXTRA_FLIGHT = "flight"
    private const val REQUEST_TICK = 7302
    private const val MAX = 1000

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16 QPR1). */
    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val TAG = "LiveUpdates"

    fun id(p: BoardingPass): Int = 0x4C000000 or (p.flightKey.hashCode() and 0x00FFFFFF)

    private fun lead(s: AppSettings): Duration = Duration.ofHours(s.liveLeadHours.coerceIn(1, 12).toLong())

    /** One card per flight, for passengers travelling together too. */
    private fun cards(settings: AppSettings, now: Instant): List<Pair<BoardingPass, LiveFlight.State>> =
        AppGraph.repository.passes.value.groupBy { it.flightKey }.values.mapNotNull { group ->
            group.firstNotNullOfOrNull { p -> LiveFlight.state(p, now, lead(settings))?.let { p to it } }
        }

    /** Posts, updates and removes the cards, then arms the alarm for the next change. */
    fun refresh(context: Context) {
        val settings = AppGraph.settings.state.value
        val now = Instant.now()
        val nm = context.getSystemService(NotificationManager::class.java)
        val cards = if (settings.liveUpdates && Notifications.canNotify(context)) cards(settings, now) else emptyList()
        val ids = cards.map { id(it.first) }.toSet()
        try {
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL && it.id !in ids }.forEach { nm.cancel(it.id) }
            cards.forEach { (p, s) -> nm.notify(id(p), build(context, p, s, settings, now)) }
        } catch (e: SecurityException) {
            // Notification permission revoked meanwhile.
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't post the live card", e)
        }
        // The pre-departure service runs under a card that is gone (hidden, turned off): let it
        // switch to its own notice, since the system won't remove a service's notification.
        val fg = RushService.foregroundId
        if (RushService.running && fg != 0 && fg != Notifications.RUSH_ID && fg !in ids) {
            try {
                context.startService(Intent(context, RushService::class.java).setAction(RushService.ACTION_UPDATE))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Can't reach the pre-departure service", e)
            }
        }
        schedule(context, settings, now)
    }

    /**
     * The card to run the pre-departure service under, so the flight has one notification instead
     * of two. Keeps [currentId] while that card exists; null means use the plain service notice.
     */
    fun foreground(context: Context, active: List<BoardingPass>, currentId: Int): Pair<Int, Notification>? {
        val settings = AppGraph.settings.state.value
        if (currentId == Notifications.RUSH_ID || !settings.liveUpdates || !Notifications.canNotify(context)) return null
        val now = Instant.now()
        val keys = active.map { it.flightKey }.toSet()
        val cards = cards(settings, now).filter { it.first.flightKey in keys }
        val (p, s) = cards.firstOrNull { id(it.first) == currentId } ?: cards.firstOrNull() ?: return null
        return id(p) to build(context, p, s, settings, now)
    }

    private fun build(context: Context, p: BoardingPass, s: LiveFlight.State, settings: AppSettings, now: Instant): Notification {
        val card = LiveFlight.card(p, s)
        val hide = PendingIntent.getBroadcast(
            context, id(p), Intent(context, LiveActionReceiver::class.java).setAction(ACTION_HIDE).putExtra(EXTRA_FLIGHT, p.flightKey),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flight)
            .setContentTitle(card.title)
            .setContentText(card.text)
            .setSubText(card.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(Notifications.openPass(context, p, id(p)))
            .setDeleteIntent(hide)
            // A safety net if the alarms stop: the card can't outlive its flight for long.
            .setTimeoutAfter(Duration.between(now, s.endsAt).toMillis().coerceAtLeast(0L) + if (s.phase == LiveFlight.Phase.LANDED) 0L else 3_600_000L)
        val target = s.countdownTo
        if (target != null) {
            b.setWhen(target.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (Rush.isActive(p, now) && RushService.running && settings.trackingEnabled && settings.rushEnabled) {
            b.addAction(Notification.Action.Builder(null as Icon?, tr("Не обновлять", "Stop refreshing"), Notifications.rushStop(context)).build())
        }
        b.addAction(Notification.Action.Builder(null as Icon?, tr("Скрыть", "Hide"), hide).build())

        val indeterminate = s.phase == LiveFlight.Phase.IN_FLIGHT && s.arrival == null
        val progress = (s.progress * MAX).roundToInt()
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(progressStyle(context, progress, indeterminate))
            b.setShortCriticalText(card.chip)
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true) })
        } else {
            b.setProgress(MAX, progress, indeterminate)
        }
        return b.build()
    }

    @RequiresApi(36)
    private fun progressStyle(context: Context, progress: Int, indeterminate: Boolean): Notification.ProgressStyle =
        Notification.ProgressStyle()
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(MAX)))
            .setProgressStartIcon(Icon.createWithResource(context, R.drawable.ic_flight_takeoff))
            .setProgressEndIcon(Icon.createWithResource(context, R.drawable.ic_flight_land))
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_progress_plane))
            .setProgressIndeterminate(indeterminate)
            .setProgress(progress)

    private fun schedule(context: Context, settings: AppSettings, now: Instant) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_TICK, Intent(context, LiveTickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = if (settings.liveUpdates) LiveFlight.nextWake(AppGraph.repository.passes.value, now, lead(settings)) else null
        if (next == null) {
            am.cancel(pi)
            return
        }
        am.wakeAt(maxOf(next.toEpochMilli(), System.currentTimeMillis() + 1_000), pi)
    }

    /** Android 16+: whether the system shows the card as a Live Update (the user can turn it off). */
    fun canPromote(context: Context): Boolean =
        Build.VERSION.SDK_INT < 36 || context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** The app's Live Updates switch, or its notification settings where there is none. */
    fun openPromotionSettings(context: Context) {
        for (action in listOf(ACTION_PROMOTION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            }
        }
    }

    suspend fun hide(context: Context, flightKey: String) {
        AppGraph.repository.update { list -> list.map { if (it.flightKey == flightKey) it.copy(liveHidden = true) else it } }
        refresh(context)
    }
}

class LiveTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveUpdates.refresh(context)
    }
}

/** "Hide" on the card, or the card swiped away. */
class LiveActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdates.ACTION_HIDE) return
        val key = intent.getStringExtra(LiveUpdates.EXTRA_FLIGHT) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                LiveUpdates.hide(app, key)
            } finally {
                pending.finish()
            }
        }
    }
}
