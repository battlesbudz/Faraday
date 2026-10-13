package com.battlesbudz.jarvis.v2.actions

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * The notification a one-shot reminder posts when its M2 occurrence fires
 * (D34: reminders alert at the requested time and follow the phone's Do Not
 * Disturb, with no separate Jarvis quiet-hours schedule).
 *
 * Posting is best-effort and honest like the other notification helpers:
 * when the notification permission is denied or notifications are disabled,
 * the call returns false without posting and the executor reports the
 * failure truthfully instead of claiming the reminder fired.
 */
object ReminderNotification {
    private const val CHANNEL_ID = "jarvis_reminders"

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager(context)?.areNotificationsEnabled() == true
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0x7a000001,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun channel(context: Context) {
        val manager = manager(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Time-critical reminders. Follows the phone's Do Not Disturb setting."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
        }
    }

    /** Post the reminder alert. Returns true only when the notification was posted. */
    fun post(context: Context, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        return runCatching {
            channel(context)
            val manager = manager(context) ?: return false
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text.take(256))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .apply { openApp(context)?.let { setContentIntent(it) } }
                .build()
            manager.notify(0x7a000000 or (text.hashCode() and 0x00ffffff), notification)
            true
        }.getOrDefault(false)
    }
}
