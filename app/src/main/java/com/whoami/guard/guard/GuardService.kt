package com.whoami.guard.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.whoami.guard.MainActivity
import com.whoami.guard.R
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.prefs.AppPrefs
import com.whoami.guard.setup.QuietCheckActivity

/**
 * The always-on watcher. Listens for:
 *   • phone unlocked            → quiet face check
 *   • WhatsApp brought to front → quiet face check (if enabled)
 *
 * Runs as a low-noise foreground service (type specialUse). Uses almost no battery:
 * two broadcast receivers + a 1s usage-stats poll that only runs while the screen is on.
 *
 * The actual camera work happens in [QuietCheckActivity], launched from here —
 * the SYSTEM_ALERT_WINDOW permission granted in setup makes that legal from background.
 */
class GuardService : Service() {

    private lateinit var prefs: AppPrefs
    private val handler = Handler(Looper.getMainLooper())
    private var receiversRegistered = false
    private var lastCheckAt = 0L
    private var lastPollToken = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    DecisionLog.append(
                        context, DecisionLog.Trigger.UNLOCK,
                        DecisionLog.Outcome.INFO, "screen unlocked"
                    )
                    maybeCheck(DecisionLog.Trigger.UNLOCK)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    startPolling()
                }
            }
        }
    }

    private val usagePoll = object : Runnable {
        override fun run() {
            if (isScreenOn()) {
                checkWhatsAppForeground()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground() fast on Android 12+.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            DecisionLog.append(
                this, DecisionLog.Trigger.UNLOCK, DecisionLog.Outcome.ERROR,
                "foreground start failed: ${e.message}"
            )
        }

        if (!receiversRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
            receiversRegistered = true
            startPolling()
            DecisionLog.append(
                this, DecisionLog.Trigger.UNLOCK, DecisionLog.Outcome.INFO,
                "guard started"
            )
        }

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiversRegistered = false
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- triggers ---------------------------------------------------------

    private fun maybeCheck(trigger: DecisionLog.Trigger) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < DEBOUNCE_MS) return
        lastCheckAt = now

        // Don't check when our own app is open (enrollment, test screen, home).
        if (foregroundPackage() == packageName) return

        val intent = Intent(this, QuietCheckActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(QuietCheckActivity.EXTRA_TRIGGER, trigger.name)
        }
        runCatching {
            startActivity(intent)
        }.onFailure { e ->
            Log.e(TAG, "Cannot launch quiet check (overlay missing?)", e)
            DecisionLog.append(
                this, trigger, DecisionLog.Outcome.ERROR,
                "check launch failed: ${e.message}"
            )
        }
    }

    private fun checkWhatsAppForeground() {
        if (!prefs.whatsappCheckEnabled) return

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return
        val now = System.currentTimeMillis()
        val windowStart = lastPollToken
        lastPollToken = now - POLL_INTERVAL_MS / 2

        val events = usm.queryEvents(windowStart, now)
        val event = UsageEvents.Event()
        var whatsappSeen = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                (event.packageName == "com.whatsapp" ||
                    event.packageName == "com.whatsapp.w4b")
            ) {
                whatsappSeen = true
            }
        }
        if (whatsappSeen) {
            DecisionLog.append(
                this, DecisionLog.Trigger.WHATSAPP,
                DecisionLog.Outcome.INFO, "WhatsApp opened"
            )
            maybeCheck(DecisionLog.Trigger.WHATSAPP)
        }
    }

    // --- helpers ----------------------------------------------------------

    private fun startPolling() {
        handler.removeCallbacks(usagePoll)
        lastPollToken = System.currentTimeMillis() - POLL_INTERVAL_MS
        handler.postDelayed(usagePoll, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        handler.removeCallbacks(usagePoll)
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun foregroundPackage(): String? = runCatching {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 3_000, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = event.packageName
            }
        }
        pkg
    }.getOrNull()

    private fun buildNotification(): Notification {
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("WhoAmI")
            .setContentText("Protection active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Protection", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps WhoAmI watching for unlocks"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 101
        private const val POLL_INTERVAL_MS = 1_200L
        private const val DEBOUNCE_MS = 4_000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var instance: GuardService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GuardService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
