package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Communication speaker ownership, serialized with microphone priority and recorder creation. */
internal class CommunicationAudioSession private constructor(
    private val audio: AudioManager, private val log: (String) -> Unit
) : AutoCloseable {
    private lateinit var lease: DuplexRouteLease
    private var target = -1
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) MicrophoneHandoff.withRecorderLock {
                if (current === this@CommunicationAudioSession) MicrophoneHandoff.requestInterruption("duplex_audio_focus_lost")
            }
        }, Handler(Looper.getMainLooper())).build()

    val valid: Boolean get() = MicrophoneHandoff.withRecorderLock { current === this@CommunicationAudioSession && lease.valid }
    suspend fun awaitReady() = withTimeout(2000) {
        while (!valid) {
            check(MicrophoneHandoff.withRecorderLock { current === this@CommunicationAudioSession } && !MicrophoneHandoff.shouldYield) {
                "Communication route ownership was lost."
            }
            delay(25)
        }
        log("duplex_route_ready mode=${audio.mode} deviceId=$target volume=${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
    }

    override fun close() = MicrophoneHandoff.withRecorderLock {
        if (current === this@CommunicationAudioSession) {
            current = null // Focus callbacks cannot recursively release this owner.
            try {
                lease.close()
                log("duplex_route_released mode=${audio.mode} ownRequestCleared=true")
            } catch (error: Throwable) {
                log("duplex_route_release_failed mode=${audio.mode} reason=${error.javaClass.simpleName}")
                throw error
            }
        }
    }

    companion object {
        // Read/write only with MicrophoneHandoff's recorder lock, including during setMode().
        private var current: CommunicationAudioSession? = null
        fun ownsMode(): Boolean = MicrophoneHandoff.withRecorderLock { current?.lease?.ownsCommunication == true }
        fun releaseForInterruption() = MicrophoneHandoff.withRecorderLock {
            current?.let { owner -> runCatching { owner.close() }.onFailure { owner.log("duplex_release_failed reason=${it.javaClass.simpleName}") } }
        }
        fun openSpeaker(audio: AudioManager, log: (String) -> Unit): CommunicationAudioSession = MicrophoneHandoff.withRecorderLock {
            check(Build.VERSION.SDK_INT >= 31) { "Communication route comparison requires Android 12 or later." }
            check(current == null && !MicrophoneHandoff.shouldYield && !audio.isMicrophoneMute &&
                audio.activeRecordingConfigurations.isEmpty() && MicrophoneHandoff.backgroundRecorders.get() == 0) {
                "Another microphone user has priority over the communication route."
            }
            val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: error("No communication speaker is available.")
            val owner = CommunicationAudioSession(audio, log)
            owner.target = speaker.id
            owner.lease = DuplexRouteLease(object : DuplexRouteLease.Port {
                override var mode: Int
                    get() = audio.mode
                    set(value) { audio.mode = value }
                override val device get() = audio.communicationDevice?.id
                override fun selectDevice(id: Int) = audio.setCommunicationDevice(speaker)
                override fun clearDevice() = audio.clearCommunicationDevice()
                override fun requestFocus() = audio.requestAudioFocus(owner.focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                override fun abandonFocus() { audio.abandonAudioFocusRequest(owner.focus) }
            }, speaker.id)
            current = owner // Claim before changing mode; the monitor cannot observe a half-owned transition.
            try {
                log("duplex_route_acquire priorMode=${audio.mode} priorObservedDevice=${audio.communicationDevice?.id} targetDevice=${speaker.id}")
                owner.lease.start()
                owner
            } catch (error: Throwable) {
                runCatching { owner.close() }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
    }
}
