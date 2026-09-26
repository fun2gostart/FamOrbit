package com.familycontrol.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

object NotificationEngine {
    private const val CHANNEL_ID = "familycontrol_alerts_v2"
    private const val CHANNEL_NAME = "FamilyControl Alerts"
    private val recentNotifications = ConcurrentHashMap<String, Long>()

    fun notify(context: Context, id: Int, title: String, message: String) {
        if (title.contains("APP_CATALOG") || title.contains("DEV_APPS") ||
            message.contains("APP_CATALOG") || message.contains("DEV_APPS") ||
            message.contains("DEV_INFO") || message.contains("APP_CATALOG:") ||
            message.contains("Parent declined extra time request for APP_CATALOG") ||
            message.contains("Parent declined request for APP_CATALOG")) {
            return
        }

        // Deduplicate notifications with the same ID or content within 60 seconds
        val dedupKeyId = "$id"
        val dedupKeyContent = "${title.trim()}|${message.trim()}"
        val now = System.currentTimeMillis()
        val lastTimeId = recentNotifications[dedupKeyId] ?: 0L
        val lastTimeContent = recentNotifications[dedupKeyContent] ?: 0L
        if ((now - lastTimeId < 60_000L) || (now - lastTimeContent < 60_000L)) {
            return
        }
        recentNotifications[dedupKeyId] = now
        recentNotifications[dedupKeyContent] = now

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant alerts for time requests and approvals"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
        }
    }
}
