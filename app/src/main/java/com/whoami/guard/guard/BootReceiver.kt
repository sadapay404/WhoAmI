package com.whoami.guard.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.prefs.AppPrefs

/**
 * Re-arm the guard after a reboot. specialUse-type services are still allowed
 * to start from BOOT_COMPLETED on Android 15 (unlike camera/mic types).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppPrefs(context)
        if (!prefs.setupComplete) return
        runCatching {
            GuardService.start(context)
            DecisionLog.append(
                context, DecisionLog.Trigger.UNLOCK,
                DecisionLog.Outcome.INFO, "guard re-armed after reboot"
            )
        }
    }
}
