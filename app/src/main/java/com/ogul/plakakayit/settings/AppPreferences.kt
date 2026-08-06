package com.ogul.plakakayit.settings

import android.content.Context

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromValue(preferences.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.value))
        set(value) = preferences.edit().putString(KEY_THEME_MODE, value.value).apply()

    var aiEnabled: Boolean
        get() = preferences.getBoolean(KEY_AI_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var aiThreshold: Float
        get() = preferences.getFloat(KEY_AI_THRESHOLD, 0.45f).coerceIn(0.30f, 0.90f)
        set(value) = preferences.edit().putFloat(KEY_AI_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    enum class ThemeMode(val value: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark"),
        AMOLED("amoled");

        companion object {
            fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "plaka_kayit_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_THRESHOLD = "ai_threshold"
    }
}
