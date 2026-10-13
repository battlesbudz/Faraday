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
 * M1d task progress notifications (D35, T04, T15).
 *
 * Progress for admitted work continues into chat and notifications after a
 * call ends. The channel is IMPORTANCE_LOW, which delivers silently on its
 * own: during Do Not Disturb the notification is posted immediately and
 * silently, never deferred until DND ends and never given separate
 * quiet-hours handling. Tapping opens the conversation.
 *
 * Posting is best-effort and honest: when notification permission is denied
 * or notifications are disabled, the call is a no-op returning false and the
 * chat projection remains the source of truth.
 */
object TaskProgressNotification {
    private const val CHANNEL_ID = "jarvis_task_progress"

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return manager(context)?.areNotificationsEnabled() == true
    }

    private fun notificationId(groupId: String): Int =
        0x7a000000 or (groupId.hashCode() and 0x00ffffff)

    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, notificationId("open"),
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun channel(context: Context) {
        val manager = manager(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for Jarvis phone tasks. Silent during Do Not Disturb."
            })
        }
    }

    /** Progress update for a running group. Silent; replaces the previous post for the group. */
    fun postProgress(context: Context, projection: TaskStatusProjection): Boolean {
        if (!canPost(context)) return false
        return runCatching {
            channel(context)
            val manager = manager(context) ?: return false
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Jarvis: ${projection.label}")
                .setContentText(projection.statusLine)
                .setOnlyAlertOnce(true)
                // No setSilent: the IMPORTANCE_LOW channel delivers silently.
                .setOngoing(true)
                .apply { openApp(context)?.let { setContentIntent(it) } }
            if (projection.totalSteps > 0) {
                builder.setProgress(projection.totalSteps, projection.completedSteps, false)
            }
            manager.notify(notificationId(projection.groupId), builder.build())
            true
        }.getOrDefault(false)
    }

    /** Terminal update for a finished/cancelled/failed group. Silent; auto-cancels on tap. */
    fun postFinished(context: Context, projection: TaskStatusProjection): Boolean =
        postResult(context, projection.groupId, "Jarvis: ${projection.label}",
            (listOf(projection.statusLine) + projection.stepReceipts).joinToString("\n"))

    /**
     * M1d call-end continuity (T04): an admitted voice task that finishes after
     * its call ended surfaces its result here. Chat already persists the same
     * text through the voice call store; this is the notification half.
     */
    fun postCallEndResult(context: Context, taskId: String, title: String, text: String): Boolean =
        postResult(context, "call-end:$taskId", title, text)

    private fun postResult(context: Context, key: String, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        return runCatching {
            channel(context)
            val manager = manager(context) ?: return false
            manager.notify(notificationId(key), Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText(text.take(256))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                // No setSilent: the IMPORTANCE_LOW channel delivers silently.
                .apply { openApp(context)?.let { setContentIntent(it) } }
                .build())
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, groupId: String) {
        runCatching { manager(context)?.cancel(notificationId(groupId)) }
    }
}
