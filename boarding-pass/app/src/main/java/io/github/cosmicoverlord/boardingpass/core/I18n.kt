package io.github.cosmicoverlord.boardingpass.core

import java.util.Locale

enum class Lang { RU, EN }

/**
 * Two-language UI text. Strings live next to the code that uses them as `tr("…", "…")`, which
 * keeps the pure-Kotlin core (statuses, notifications, errors) translatable without Android
 * resources. The activity is recreated when the language changes, so plain reads are enough.
 */
object I18n {
    @Volatile
    var lang: Lang = systemLang()

    val locale: Locale get() = if (lang == Lang.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH

    fun systemLang(): Lang = if (Locale.getDefault().language == "ru") Lang.RU else Lang.EN
}

fun tr(ru: String, en: String): String = if (I18n.lang == Lang.RU) ru else en
