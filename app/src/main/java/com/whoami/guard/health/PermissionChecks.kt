package com.whoami.guard.health

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * "Is WhoAmI actually able to watch right now?" — four independent health
 * checks, each with a one-tap deep link to fix it. Shown on the home screen.
 */
object PermissionChecks {

    enum class Check(val label: String, val fixedLabel: String) {
        CAMERA("Camera access", "Grant camera"),
        OVERLAY("Display-over-apps (needed at unlock)", "Grant overlay"),
        USAGE("Usage access (watches for WhatsApp)", "Grant usage"),
        BATTERY("Battery set to Unrestricted", "Unrestrict battery"),
        NOTIFICATIONS("Notifications allowed", "Allow notifications")
    }

    fun isOk(context: Context, check: Check): Boolean = when (check) {
        Check.CAMERA ->
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        Check.OVERLAY -> Settings.canDrawOverlays(context)
        Check.USAGE -> hasUsageAccess(context)
        Check.BATTERY -> {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        Check.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    /** Open the exact settings screen for a failing check. */
    fun fixIntent(context: Context, check: Check): Intent = when (check) {
        Check.CAMERA ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        Check.OVERLAY ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        Check.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Check.BATTERY -> runCatching {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        }.getOrElse { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        Check.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun allOk(context: Context): Boolean =
        Check.entries.all { isOk(context, it) }

    fun failures(context: Context): List<Check> =
        Check.entries.filter { !isOk(context, it) }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
