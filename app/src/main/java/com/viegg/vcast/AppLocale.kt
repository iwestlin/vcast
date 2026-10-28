package com.viegg.vcast

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CHINESE = "zh-CN"

    private const val PREFS = "vcast"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)
            ?: SYSTEM

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun wrap(base: Context): Context {
        val language = current(base)
        if (language == SYSTEM) return base
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
