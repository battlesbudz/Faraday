package com.battlesbudz.jarvis.v2.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the app process alive while sandbox sessions are open (Phase 1
 * scope §7). The vendored session layer's contract is that sessions die
 * with the process — there is no cross-process restore — so while a
 * session or run is active this foreground service is the only thing
 * standing between the guest and an LMK kill. It is started when the
 * first session opens and stopped when the last one closes; it does no
 * work of its own.
 *
 * specialUse, matching the model-setup precedent: a session can outlive
 * every other foreground-service budget, and the manifest entry carries
 * the required subtype explanation.
 */
class SandboxKeepaliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sandbox session",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Sandbox session running")
            .setContentText("A Linux sandbox session is active in Faraday.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "faraday_sandbox_session"
        private const val NOTIFICATION_ID = 4207

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SandboxKeepaliveService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SandboxKeepaliveService::class.java))
        }
    }
}
