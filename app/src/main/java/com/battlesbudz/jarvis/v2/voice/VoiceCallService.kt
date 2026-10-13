package com.battlesbudz.jarvis.v2.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.MainActivity

/** Keeps a user-started call eligible for microphone and playback after pressing Home. */
class VoiceCallService : Service() {
    private var monitor: MicrophoneInterruptionMonitor? = null
    private val runtime get() = com.battlesbudz.jarvis.v2.JarvisRuntime.get(applicationContext)
    private var wakeLock: PowerManager.WakeLock? = null
    private var status = "Preparing Jarvis session — microphone not yet armed"

    override fun onCreate() {
        super.onCreate()
        instance = this
        stopRequested.value = false
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Voice Calls", NotificationManager.IMPORTANCE_LOW
        ))
        startForeground(481, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:voice-call")
            .apply { acquire() }
        monitor = MicrophoneInterruptionMonitor(applicationContext, runtime::onMicrophoneInterruption)
        // Video capture is call-scoped now: the runtime starts it per call
        // via VideoCallService.startCapture when a call begins, so a
        // merely-armed wake session never holds the camera. Audio path untouched.
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, VoiceCallService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2,
            Intent(this, VoiceCallService::class.java).setAction(TOGGLE_MIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis session")
            .setContentText(status)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null,
                if (VoiceSessionUi.paused.value) "Resume mic" else "Pause mic", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop session", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TOGGLE_MIC) VoiceSessionUi.controls.trySend(
            if (VoiceSessionUi.paused.value) VoiceControl.RESUME else VoiceControl.PAUSE)
        if (intent?.action == STOP) { stopRequested.value = true; stopSelf() }
        if (intent?.action == null) runtime.runVoiceTurn()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { stopRequested.value = true; stopSelf() }
    override fun onDestroy() {
        if (instance === this) instance = null
        runtime.onServiceStopped()
        monitor?.close()
        monitor = null
        stopRequested.value = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_voice_calls"
        private const val TOGGLE_MIC = "com.battlesbudz.jarvis.v2.TOGGLE_MIC"
        private const val STOP = "com.battlesbudz.jarvis.v2.STOP_SESSION"
        private var instance: VoiceCallService? = null
        val stopRequested = kotlinx.coroutines.flow.MutableStateFlow(false)
        fun updateStatus(message: String) {
            VoiceSessionUi.report(message)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                instance?.let {
                    if (it.status != message) {
                        it.status = message
                        it.getSystemService(NotificationManager::class.java).notify(481, it.notification())
                    }
                }
            }
        }
    }
}
