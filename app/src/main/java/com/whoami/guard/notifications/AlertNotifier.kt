package com.whoami.guard.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.whoami.guard.MainActivity
import com.whoami.guard.R

/**
 * The "tap to see what was captured" notification, posted the moment the
 * real owner unlocks after an intrusion.
 */
object AlertNotifier {

    private const val CHANNEL_ID = "alerts"
    private const val RESTORED_ID = 201

    fun notifyRestored(context: Context) {
        ensureChannel(context)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val pending = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_SHOW_CAPTURES, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("You're back — wallpaper restored")
            .setContentText("An intrusion was captured. Tap to view photos, videos and audio.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(RESTORED_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Captures",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Alerts when a capture session ends and wallpaper is restored" }
            )
        }
    }
}
