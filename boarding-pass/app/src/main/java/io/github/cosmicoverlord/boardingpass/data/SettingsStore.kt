package io.github.cosmicoverlord.boardingpass.data

import android.content.Context
import io.github.cosmicoverlord.boardingpass.core.status.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val provider: ProviderType = ProviderType.NONE,
    val keys: Map<ProviderType, String> = emptyMap(),
    /** Background refresh period in minutes, 0 = off. */
    val refreshMinutes: Int = 60,
    val notifications: Boolean = true,
    val askedNotificationPermission: Boolean = false,
    /** Keeps the full-screen code off the curved display edges, in millimetres. */
    val edgeMarginMm: Int = DEFAULT_EDGE_MARGIN_MM,
) {
    fun key(type: ProviderType = provider): String = keys[type].orEmpty()
    val trackingEnabled: Boolean get() = provider != ProviderType.NONE && key().isNotBlank()

    companion object {
        /** Clears the side curve of the Motorola Edge 50 Ultra's 6.7" pOLED panel with a little spare. */
        const val DEFAULT_EDGE_MARGIN_MM = 4
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
    )

    private fun write(s: AppSettings) {
        prefs.edit().apply {
            putString("provider", s.provider.name)
            s.keys.forEach { (type, key) -> putString("key_${type.name}", key.trim()) }
            putInt("refresh_minutes", s.refreshMinutes)
            putBoolean("notifications", s.notifications)
            putBoolean("asked_notifications", s.askedNotificationPermission)
            putInt("edge_margin_mm", s.edgeMarginMm)
        }.apply()
    }
}
