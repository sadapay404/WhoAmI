package com.whoami.guard.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * All persistent app settings. Private SharedPreferences — nothing leaves the device.
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("whoami_prefs", Context.MODE_PRIVATE)

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var faceEnrolled: Boolean
        get() = prefs.getBoolean(KEY_FACE_ENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FACE_ENROLLED, value).apply()

    var wallpaperBackedUp: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_BACKUP, false)
        set(value) = prefs.edit().putBoolean(KEY_WALLPAPER_BACKUP, value).apply()

    /**
     * What to do when the owner never appears within the 10-second grace period
     * (nobody visible, dark room, only strangers' faces).
     * "capture" = fire the capture pipeline (default — spec: "if I don't appear
     *             for 10 seconds at least, click")
     * "allow"   = let it slide; only strict group matches capture
     */
    var uncertainPolicy: String
        get() = prefs.getString(KEY_UNCERTAIN_POLICY, POLICY_CAPTURE) ?: POLICY_CAPTURE
        set(value) = prefs.edit().putString(KEY_UNCERTAIN_POLICY, value).apply()

    var whatsappCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_WHATSAPP_CHECK, value).apply()

    /** Cosine-similarity threshold for "same face". Tuned in Phase 6. */
    var matchThreshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD, value).apply()

    /** Number of enrolled face samples (for the status screen). */
    var enrolledSampleCount: Int
        get() = prefs.getInt(KEY_SAMPLE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SAMPLE_COUNT, value).apply()

    /** True while the intruder's photo is set as wallpaper (Phase 4+). */
    var wallpaperSwapped: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_SWAPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_WALLPAPER_SWAPPED, value).apply()

    /** Protection on/off master switch. */
    var protectionArmed: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION_ARMED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTION_ARMED, value).apply()

    companion object {
        const val POLICY_ALLOW = "allow"
        const val POLICY_CAPTURE = "capture"
        const val DEFAULT_THRESHOLD = 0.55f

        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_FACE_ENROLLED = "face_enrolled"
        private const val KEY_WALLPAPER_BACKUP = "wallpaper_backup"
        private const val KEY_UNCERTAIN_POLICY = "uncertain_policy"
        private const val KEY_WHATSAPP_CHECK = "whatsapp_check"
        private const val KEY_THRESHOLD = "match_threshold"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_WALLPAPER_SWAPPED = "wallpaper_swapped"
        private const val KEY_PROTECTION_ARMED = "protection_armed"
    }
}
