package com.whoami.guard.health

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.whoami.guard.prefs.AppPrefs
import java.util.Locale

/**
 * Builds the home-screen "can the guard actually watch?" report and
 * Motorola-specific advice when something is off.
 */
object HealthReport {

    data class Report(
        val headline: String,   // ✓ or ✗
        val lines: List<String> // one per check
    )

    fun build(context: Context, prefs: AppPrefs, guardRunning: Boolean): Report {
        val lines = mutableListOf<String>()
        var allGood = true

        // 1. Guard process
        if (guardRunning && prefs.protectionArmed) {
            lines.add("✓ Guard service running")
        } else if (!prefs.protectionArmed) {
            lines.add("✗ Protection DISARMED — flip the switch above")
            allGood = false
        } else {
            lines.add("✗ Guard not running — open the app once, or check battery settings")
            allGood = false
        }

        // 2. Permission checks
        for (check in PermissionChecks.Check.entries) {
            val ok = PermissionChecks.isOk(context, check)
            if (!ok) allGood = false
            lines.add("${if (ok) "✓" else "✗"} ${check.label}")
        }

        // 3. Motorola / OEM battery saver hints
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) {
            lines.add("⚠ Battery saver is ON — captures may be delayed")
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        lines.add(
            String.format(
                Locale.US, "Device: %s (Android %d) · battery %d%%",
                Build.MANUFACTURER, Build.VERSION.RELEASE.split(".").firstOrNull()?.toIntOrNull()
                    ?: Build.VERSION.SDK_INT - 28,
                pct
            )
        )

        return Report(
            headline = if (allGood) "✓ Protected" else "✗ Needs attention",
            lines = lines
        )
    }

    /** Plain-English Motorola guidance shown when battery check fails. */
    fun motorolaBatteryGuide(): String =
        "Motorola kills background apps to save power.\n\n" +
            "1. Settings → Apps → WhoAmI → Battery\n" +
            "2. Choose Unrestricted\n" +
            "3. If you see 'Adaptive Battery', leave it on — Unrestricted overrides it for WhoAmI\n\n" +
            "Also switch off any 'Battery saver' while you're out (it pauses background work)."
}
