package com.example.earautoanswer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.earautoanswer.MainActivity
import com.example.earautoanswer.R

/**
 * The one notification this app ever posts: the ongoing foreground-service badge.
 *
 * It is deliberately dull — low importance, silent, no badge, no timestamp — because
 * it is present for as long as auto-answer is on and must never behave like an alert.
 * Nothing call-identifying is ever put in it.
 */
object ServiceNotifications {

    const val CHANNEL_ID = "ear_auto_answer_status"
    const val NOTIFICATION_ID = 1001

    /** Idempotent: creating an existing channel is a no-op apart from name/description. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String): Notification {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(context, CHANNEL_ID)
            // The app's own handset glyph. A small icon is rendered from its alpha
            // channel as a flat silhouette, so the vector's colours are irrelevant —
            // only its shape, which is the handset-and-proximity-arcs outline.
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            // Silence comes from the channel (IMPORTANCE_LOW, no sound, no vibration);
            // on API 26+ per-notification sound settings are ignored entirely.
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Show the badge straight away rather than after the usual 10s grace
            // period — the user just toggled the switch and expects feedback.
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
