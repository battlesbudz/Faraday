package com.battlesbudz.jarvis.v2.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Runner registers the live controls; service actions call that runner directly. */
object OperatedTaskControls {
    interface Runner {
        fun pause(taskId: String)
        fun stop(taskId: String)
        fun handBack(taskId: String)
    }
    @Volatile var runner: Runner? = null
}

class OperatedTaskService : Service() {
    private var taskId: String? = null
    private var appName: String = "App"
    private var taskName: String = "Task"
    private var startedAt: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TASK_ID)
        if (id.isNullOrBlank()) { stopSelf(startId); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_PAUSE -> OperatedTaskControls.runner?.pause(id)
            ACTION_STOP -> OperatedTaskControls.runner?.stop(id)
            ACTION_HAND_BACK -> OperatedTaskControls.runner?.handBack(id)
            ACTION_END -> {
                if (taskId == id) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            else -> {
                taskId = id
                appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { "App" }
                taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty().ifBlank { "Task" }
                startedAt = System.currentTimeMillis()
            }
        }
        if (taskId == id) startForeground(NOTIFICATION_ID, notification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { taskId = null; super.onDestroy() }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Operated task", NotificationManager.IMPORTANCE_LOW))
        val id = checkNotNull(taskId)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("$appName · $taskName")
            .setContentText("Running · ${((System.currentTimeMillis() - startedAt) / 1000)} sec")
            .setOngoing(true)
            .addAction(0, "Pause", controlIntent(this, id, ACTION_PAUSE, 1))
            .addAction(0, "Stop", controlIntent(this, id, ACTION_STOP, 2))
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            builder.setContentIntent(PendingIntent.getActivity(this, 0, intent, pendingFlags()))
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_TASK_NAME = "task_name"
        const val ACTION_PAUSE = "com.battlesbudz.jarvis.v2.display.PAUSE"
        const val ACTION_STOP = "com.battlesbudz.jarvis.v2.display.STOP"
        const val ACTION_HAND_BACK = "com.battlesbudz.jarvis.v2.display.HAND_BACK"
        private const val ACTION_END = "com.battlesbudz.jarvis.v2.display.END"
        private const val CHANNEL = "operated-task"
        private const val NOTIFICATION_ID = 9216
        private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private fun controlIntent(context: Context, id: String, action: String, request: Int) =
            PendingIntent.getService(context, request, Intent(context, OperatedTaskService::class.java)
                .setAction(action).putExtra(EXTRA_TASK_ID, id), pendingFlags())

        fun start(context: Context, taskId: String, appName: String, taskName: String) {
            context.startForegroundService(Intent(context, OperatedTaskService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId).putExtra(EXTRA_APP_NAME, appName).putExtra(EXTRA_TASK_NAME, taskName))
        }
        fun end(context: Context, taskId: String) {
            context.startService(Intent(context, OperatedTaskService::class.java)
                .setAction(ACTION_END).putExtra(EXTRA_TASK_ID, taskId))
        }
    }
}
