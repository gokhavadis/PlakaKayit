package com.ogul.plakakayit.settings

import android.content.Context
import android.util.Base64
import com.ogul.plakakayit.data.MovementType
import java.security.MessageDigest
import java.security.SecureRandom

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

    var accessMode: MovementType
        get() = MovementType.fromValue(
            preferences.getString(KEY_ACCESS_MODE, MovementType.OBSERVATION.value)
        )
        set(value) = preferences.edit().putString(KEY_ACCESS_MODE, value.value).apply()

    var automaticUpdateCheck: Boolean
        get() = preferences.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var securityAnalysisEnabled: Boolean
        get() = preferences.getBoolean(KEY_SECURITY_ANALYSIS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_SECURITY_ANALYSIS_ENABLED, value).apply()

    var restrictedZoneEnabled: Boolean
        get() = preferences.getBoolean(KEY_RESTRICTED_ZONE_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_RESTRICTED_ZONE_ENABLED, value).apply()

    var securityDwellSeconds: Int
        get() = preferences.getInt(KEY_SECURITY_DWELL_SECONDS, 20).coerceIn(5, 120)
        set(value) = preferences.edit()
            .putInt(KEY_SECURITY_DWELL_SECONDS, value.coerceIn(5, 120))
            .apply()

    var securityEnabled: Boolean
        get() = preferences.getBoolean(KEY_SECURITY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_SECURITY_ENABLED, value).apply()

    var biometricEnabled: Boolean
        get() = preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    val hasPin: Boolean
        get() = !preferences.getString(KEY_PIN_HASH, null).isNullOrBlank() &&
            !preferences.getString(KEY_PIN_SALT, null).isNullOrBlank()

    fun setPin(pin: String) {
        require(pin.matches(Regex("\\d{4,6}"))) { "PIN 4-6 rakam olmalı" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        preferences.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltText = preferences.getString(KEY_PIN_SALT, null) ?: return false
        val expectedText = preferences.getString(KEY_PIN_HASH, null) ?: return false
        return runCatching {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(expectedText, Base64.NO_WRAP)
            MessageDigest.isEqual(hashPin(pin, salt), expected)
        }.getOrDefault(false)
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    enum class ThemeMode(val value: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark"),
        AMOLED("amoled");

        companion object {
            fun fromValue(value: String?): ThemeMode =
                entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "plaka_kayit_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_THRESHOLD = "ai_threshold"
        private const val KEY_ACCESS_MODE = "access_mode"
        private const val KEY_AUTO_UPDATE = "automatic_update_check"
        private const val KEY_SECURITY_ANALYSIS_ENABLED = "security_analysis_enabled"
        private const val KEY_RESTRICTED_ZONE_ENABLED = "restricted_zone_enabled"
        private const val KEY_SECURITY_DWELL_SECONDS = "security_dwell_seconds"
        private const val KEY_SECURITY_ENABLED = "security_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
    }
}
