package io.github.cosmicoverlord.boardingpass.data

import android.content.Context
import io.github.cosmicoverlord.boardingpass.core.I18n
import io.github.cosmicoverlord.boardingpass.core.Lang
import io.github.cosmicoverlord.boardingpass.core.status.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    RU("ru"),
    EN("en"),
    ;

    fun resolve(): Lang = when (this) {
        SYSTEM -> I18n.systemLang()
        RU -> Lang.RU
        EN -> Lang.EN
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val provider: ProviderType = ProviderType.NONE,
    val keys: Map<ProviderType, String> = emptyMap(),
    /** Background refresh period in minutes, 0 = off. */
    val refreshMinutes: Int = 60,
    val notifications: Boolean = true,
    val askedNotificationPermission: Boolean = false,
    /** Keeps the full-screen code off the curved display edges, in millimetres. */
    val edgeMarginMm: Int = DEFAULT_EDGE_MARGIN_MM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val animations: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Frequent refresh during the hour before departure. */
    val rushEnabled: Boolean = true,
    val rushIntervalMinutes: Int = 2,
    /** The flight's live card on the lock screen and in the status bar. */
    val liveUpdates: Boolean = true,
    /** How many hours before departure the live card appears. */
    val liveLeadHours: Int = 3,
) {
    fun key(type: ProviderType = provider): String = keys[type].orEmpty()
    val trackingEnabled: Boolean get() = provider != ProviderType.NONE && key().isNotBlank()

    companion object {
        /** Clears the side curve of the Motorola Edge 50 Ultra's 6.7" pOLED panel with a little spare. */
        const val DEFAULT_EDGE_MARGIN_MM = 4
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_state.value)
        write(next)
        _state.value = next
        return next
    }

    private fun read() = AppSettings(
        provider = runCatching { ProviderType.valueOf(prefs.getString("provider", null) ?: "NONE") }
            .getOrDefault(ProviderType.NONE),
        keys = ProviderType.entries.associateWith { prefs.getString("key_${it.name}", "").orEmpty() },
        refreshMinutes = prefs.getInt("refresh_minutes", 60),
        notifications = prefs.getBoolean("notifications", true),
        askedNotificationPermission = prefs.getBoolean("asked_notifications", false),
        edgeMarginMm = prefs.getInt("edge_margin_mm", AppSettings.DEFAULT_EDGE_MARGIN_MM),
        language = language(prefs.getString(KEY_LANGUAGE, null)),
        animations = prefs.getBoolean("animations", true),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        rushEnabled = prefs.getBoolean("rush_enabled", true),
        rushIntervalMinutes = prefs.getInt("rush_interval", 2),
        liveUpdates = prefs.getBoolean("live_updates", true),
        liveLeadHours = prefs.getInt("live_lead_hours", 3),
    )

    private fun write(s: AppSettings) {
        prefs.edit().apply {
            putString("provider", s.provider.name)
            s.keys.forEach { (type, key) -> putString("key_${type.name}", key.trim()) }
            putInt("refresh_minutes", s.refreshMinutes)
            putBoolean("notifications", s.notifications)
            putBoolean("asked_notifications", s.askedNotificationPermission)
            putInt("edge_margin_mm", s.edgeMarginMm)
            putString(KEY_LANGUAGE, s.language.name)
            putBoolean("animations", s.animations)
            putString("theme", s.theme.name)
            putBoolean("rush_enabled", s.rushEnabled)
            putInt("rush_interval", s.rushIntervalMinutes)
            putBoolean("live_updates", s.liveUpdates)
            putInt("live_lead_hours", s.liveLeadHours)
        }.apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_LANGUAGE = "language"

        private fun language(name: String?) =
            runCatching { AppLanguage.valueOf(name ?: "SYSTEM") }.getOrDefault(AppLanguage.SYSTEM)

        /** For attachBaseContext, which runs before the app graph is ready. */
        fun peekLanguage(context: Context): AppLanguage =
            language(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null))
    }
}
