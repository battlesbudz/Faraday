package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.first

/** Recording callbacks run independently of UI/model work. Polling never opens a recorder. */
class MicrophoneInterruptionMonitor(context: Context, private val changed: (Boolean, String) -> Unit) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val thread = HandlerThread("Jarvis-microphone-priority").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val policy = MicrophoneInterruptionPolicy()
    @Volatile private var active = true
    private var lastDetails = ""
    private var lastReported = false
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) { inspect() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            inspect()
            handler.postDelayed(this, 2000)
        }
    }
    init {
        MicrophoneHandoff.installMonitor { handler.post { inspect() } }
        audio.registerAudioRecordingCallback(callback, handler)
        handler.post(poll)
    }
    private fun inspect() {
        if (!active) return
        MicrophoneHandoff.withRecorderLock {
            if (!active) return@withRecorderLock
            // Lifecycle changes cannot race subtraction (including stop-before-release).
            val recordings = audio.activeRecordingConfigurations.size
            val own = MicrophoneHandoff.recordingCount()
            val external = (recordings - own).coerceAtLeast(0)
            val communication = MicrophonePolicy.externalCommunication(audio.mode, CommunicationAudioSession.ownsMode())
            val muted = audio.isMicrophoneMute
            val requested = MicrophoneHandoff.consumeRequest()
            val busy = external > 0 || communication || muted || MicrophoneHandoff.dictationRequested || MicrophoneHandoff.ownRecorderSilenced
            val held = policy.update(busy || requested, SystemClock.elapsedRealtime(),
                ownReleased = MicrophoneHandoff.backgroundRecorders.get() == 0)
            if (held && !MicrophoneHandoff.interrupted.value) {
                MicrophoneHandoff.requestInterruption("recording_state")
            }
            MicrophoneHandoff.interrupted.value = held
            val details = "external=$external own=$own silenced=${MicrophoneHandoff.ownRecorderSilenced} communication=$communication muted=$muted dictation=${MicrophoneHandoff.dictationRequested} held=$held"
            if (lastDetails != details) { MicrophoneHandoff.record("recording_state $details"); lastDetails = details }
            if (lastReported != held) {
                lastReported = held
                main.post { if (active) changed(held, details) }
            }
        }
    }
    override fun close() {
        MicrophoneHandoff.withRecorderLock {
            active = false
            MicrophoneHandoff.installMonitor(null)
        }
        audio.unregisterAudioRecordingCallback(callback)
        handler.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
    companion object {
        suspend fun awaitAvailable() { MicrophoneHandoff.interrupted.first { !it } }
    }
}
