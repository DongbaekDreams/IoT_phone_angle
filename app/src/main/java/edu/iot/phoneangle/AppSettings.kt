package edu.iot.phoneangle

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var smoothNumbers: Boolean
        get() = prefs.getBoolean(KEY_SMOOTH, DEFAULT_SMOOTH)
        set(v) = prefs.edit { putBoolean(KEY_SMOOTH, v) }

    var deadZone: Boolean
        get() = prefs.getBoolean(KEY_DEAD_ZONE, DEFAULT_DEAD_ZONE)
        set(v) = prefs.edit { putBoolean(KEY_DEAD_ZONE, v) }

    var showSparklines: Boolean
        get() = prefs.getBoolean(KEY_SPARKLINES, DEFAULT_SPARKLINES)
        set(v) = prefs.edit { putBoolean(KEY_SPARKLINES, v) }

    var showDetails: Boolean
        get() = prefs.getBoolean(KEY_DETAILS, DEFAULT_DETAILS)
        set(v) = prefs.edit { putBoolean(KEY_DETAILS, v) }

    var showHints: Boolean
        get() = prefs.getBoolean(KEY_HINTS, DEFAULT_HINTS)
        set(v) = prefs.edit { putBoolean(KEY_HINTS, v) }

    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, DEFAULT_HAPTIC)
        set(v) = prefs.edit { putBoolean(KEY_HAPTIC, v) }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, DEFAULT_KEEP_AWAKE)
        set(v) = prefs.edit { putBoolean(KEY_KEEP_AWAKE, v) }

    companion object {
        private const val PREFS_NAME = "phone_angle_settings"
        private const val KEY_SMOOTH = "smooth_numbers"
        private const val KEY_DEAD_ZONE = "dead_zone"
        private const val KEY_SPARKLINES = "sparklines"
        private const val KEY_DETAILS = "details"
        private const val KEY_HINTS = "hints"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_KEEP_AWAKE = "keep_awake"

        const val DEFAULT_SMOOTH = false
        const val DEFAULT_DEAD_ZONE = false
        const val DEFAULT_SPARKLINES = false
        const val DEFAULT_DETAILS = false
        const val DEFAULT_HINTS = false
        const val DEFAULT_HAPTIC = true
        const val DEFAULT_KEEP_AWAKE = false

        const val DEAD_ZONE_DEG = 0.8f
    }
}
