package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the microphone session, replay boundary and resident model session for a call.
 * Microphone handoff is serialized independently of the turn's native-model work.
 * Model methods are called only by the runtime's single, operation-locked turn,
 * and closeModels must run after all native borrowers have joined.
 */
internal class VoiceCallResources(
    private val createAudio: (Boolean) -> VoiceAudioSession,
    private val createModels: () -> VoiceModelSession,
    /** A profile/source/effect change must replace the retained recorder at handoff. */
    private val captureIdentity: () -> String = { "default" }
) {
    private val audioLock = Mutex()
    private var audio: VoiceAudioSession? = null
    private var communication = false
    private var microphoneIdentity: String? = null
    private val followupEcho = FollowupPlaybackEcho()
    private var models: VoiceModelSession? = null
    private var modelsKey: String? = null
    private val playbackEndedAt = AtomicLong(0)

    fun playbackEnded(atMs: Long) { playbackEndedAt.set(atMs); followupEcho.ended(atMs) }
    fun rememberPlayback(text: String) { followupEcho.remember(text) }
    fun rejectsFollowupEcho(transcript: String, speechStartedAtMs: Long?): Boolean =
        followupEcho.rejects(transcript, speechStartedAtMs)
    fun needsFollowupTranscript(speechStartedAtMs: Long?): Boolean =
        followupEcho.needsFinalTranscript(speechStartedAtMs)
    fun consumeFollowupBoundary(): Long? = playbackEndedAt.getAndSet(0).takeIf { it > 0 }

    suspend fun borrowMicrophone(label: String, replayAfterMs: Long? = null, communication: Boolean = false): AudioInput = audioLock.withLock {
        val identity = captureIdentity()
        val reused = audio?.usable == true && this.communication == communication && microphoneIdentity == identity
        if (!reused) {
            audio?.close()
            playbackEndedAt.set(0)
            audio = createAudio(communication)
            this.communication = communication
            microphoneIdentity = identity
        }
        requireNotNull(audio).borrow(label, if (reused) replayAfterMs else null)
    }

    suspend fun closeMicrophone(reason: Throwable? = null) = audioLock.withLock {
        audio?.close(reason)
        audio = null
        microphoneIdentity = null
        playbackEndedAt.set(0)
        followupEcho.clear()
    }

    fun modelsFor(key: String): VoiceModelSession {
        if (modelsKey != key) {
            models?.close()
            models = createModels()
            modelsKey = key
        }
        return requireNotNull(models)
    }

    fun closeModels() {
        val resident = models
        models = null
        modelsKey = null
        resident?.close()
    }
}
