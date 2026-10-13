package com.battlesbudz.jarvis.v2.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.runBlocking

/**
 * Owns model setup outside the Activity lifecycle. A user can leave Jarvis,
 * lock the phone, or rotate the app while the large local models continue.
 */
class JarvisModelSetupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // A model download is long-running work. Keeping it foreground prevents
        // Android from killing it when the user opens the notification shade or
        // leaves Jarvis, while the unique WorkManager name prevents duplicates.
        setForeground(createForegroundInfo())

        val models = ModelStore(applicationContext)
        // Capture the requested model, including when this work resumes after process death.
        val requestedId = inputData.getString("model_id")
        val spec = if (requestedId == null) models.selectedModel() else ModelCatalog.find(requestedId)
            ?: return Result.failure(workDataOf("error" to "Unknown requested model."))
        val voice = TtsModelStore(applicationContext)
        var downloaded = 0L
        var total = -1L
        var stage = "Preparing local Jarvis…"
        val progressLock = Any()
        var lastProgressAt = 0L
        fun publishProgress(force: Boolean = false) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastProgressAt < 250L && downloaded != total) return
            lastProgressAt = now
            runBlocking { setProgress(workDataOf(
                "model_id" to spec.id,
                "stage" to stage,
                "downloaded" to downloaded,
                "total" to total
            )) }
        }
        val gemma = models.downloadOrReuse(
            spec = spec,
            onProgress = { bytes, length ->
                synchronized(progressLock) {
                    downloaded = bytes
                    total = length
                    // Retain the actual phase: hashing starts a new byte count,
                    // but is verification, not a second download.
                    publishProgress()
                }
            },
            onStatus = { status ->
                synchronized(progressLock) {
                    stage = status
                    downloaded = 0L
                    total = -1L
                    publishProgress(force = true)
                }
            }
        ).getOrElse { error ->
            return Result.failure(workDataOf("error" to (error.message ?: "AI model setup failed.")))
        }
        check(gemma.isFile) { "AI model setup did not produce a model file." }
        // The user may have chosen an installed model while this file transferred.
        // Voice setup now belongs to that current session, not this old request.
        if (!inputData.getBoolean("prepare_voice", true) || spec.id != models.selectedModel().id) return Result.success()
        try {
            val piper = voice.ensureReady(TtsEngine.PIPER_NORTHERN) { status ->
                synchronized(progressLock) { stage = status; downloaded = 0L; total = -1L; publishProgress() }
            }
            check(piper.isDirectory) { "Piper setup did not produce a model directory." }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return Result.failure(workDataOf("error" to (error.message ?: "Voice model setup failed.")))
        }
        try {
            AsrEngine.selected(applicationContext).prepare(applicationContext) { status ->
                synchronized(progressLock) { stage = status; publishProgress() }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return Result.failure(workDataOf("error" to (error.message ?: "Speech recognition setup failed.")))
        }
        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Jarvis model setup",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Jarvis model setup")
            .setContentText("Preparing local models…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private companion object {
        const val CHANNEL_ID = "jarvis_model_setup"
        const val NOTIFICATION_ID = 4202
    }
}
