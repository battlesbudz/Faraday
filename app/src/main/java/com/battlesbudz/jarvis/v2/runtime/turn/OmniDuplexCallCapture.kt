package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.res.AssetManager
import android.os.SystemClock
import com.battlesbudz.jarvis.v2.ai.OmniAudioEncoder
import com.battlesbudz.jarvis.v2.voice.AudioInput
import com.battlesbudz.jarvis.v2.voice.OmniDuplexCapture
import com.battlesbudz.jarvis.v2.voice.SileroSpeechDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [OmniTurnCapture] over the U6 duplex pump (LFM3-Omni U6 integration).
 * Replaces the per-turn U3 wrapper: one microphone borrow for the whole call,
 * from the call's communication route (Android AEC/noise suppression enabled
 * by the route), with VAD-only barge-in while Omni generates.
 *
 * - [OmniDuplexCapture] runs for the full call lifetime, including while Omni
 *   generates; [OmniDuplexCapture.Event.TurnReady] supplies the complete user
 *   turn to U3's audio encoder as 16 kHz float PCM.
 * - On a confirmed barge-in the pump's `onBargeIn` hook cancels generation at
 *   its next step boundary (the session's `shouldStop` observes the flag) and
 *   flushes U4's AudioTrack. She cannot answer while mid-answer.
 * - U4's rendered PCM reaches the echo guard at the playback-head position:
 *   the output queues written audio and this adapter polls its rendered
 *   position, registering only audio the sink has actually played — never
 *   future queued chunks.
 * - Endpointing is deadline-first at 300 ms. The Smart Turn hold is not
 *   wired yet (`smartTurnWantsMore` stays false): no live verdict source
 *   exists on this path, and the hook must never invoke Whisper/ASR. A
 *   background Smart Turn verdict over the duplex stream is follow-up work.
 */
internal class OmniDuplexCallCapture(
    private val scope: CoroutineScope,
    private val assets: AssetManager,
    private val borrowMicrophone: suspend (String) -> AudioInput,
    private val output: OmniAudioTrackOutput,
    private val log: (String) -> Unit = {},
) : OmniTurnCapture, OmniDuplexSessionHooks {
    private var duplex: OmniDuplexCapture? = null
    private var releaseTicker: Job? = null
    private var started = false
    @Volatile private var assistantActiveFlag = false
    @Volatile private var bargeInFlag = false

    override suspend fun startCall() {
        check(!started) { "omni_duplex_capture_already_started" }
        started = true
        // One borrow for the whole call, from the communication route: the
        // route owner (CommunicationAudioSession) and AEC/noise suppression
        // come with the borrow, exactly as the per-turn U3 capture borrowed.
        val input = borrowMicrophone("omni-duplex")
        val capture = OmniDuplexCapture(
            input = input,
            scope = scope,
            createVad = { SileroSpeechDetector.create(assets) },
            assistantActive = { assistantActiveFlag },
            onBargeIn = {
                // Non-suspending hook, called on the capture pump: stop the
                // answer at its next step boundary and flush what is playing.
                bargeInFlag = true
                output.stopAndFlush()
            },
            smartTurnWantsMore = { false },
            onLog = log,
        )
        duplex = capture
        capture.start()
        // The echo guard's reference must track what the speaker actually
        // rendered, not what was written: poll the playback head and release
        // rendered PCM at ~100 ms cadence, inside the guard's 300 ms window.
        releaseTicker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(RELEASE_POLL_MS)
                runCatching { output.releaseRendered(::rememberPlayback) }
            }
        }
    }

    override suspend fun awaitTurn(): OmniCapturedTurn {
        val capture = checkNotNull(duplex) { "omni_duplex_capture_not_started" }
        val ready = capture.events.first { event ->
            when (event) {
                is OmniDuplexCapture.Event.TurnReady -> true
                is OmniDuplexCapture.Event.CaptureFailed -> throw event.cause
                else -> false // SpeechTurnStarted is informational here.
            }
        } as OmniDuplexCapture.Event.TurnReady
        return OmniCapturedTurn(
            OmniAudioEncoder.pcm16ToFloat(ready.pcm),
            SystemClock.elapsedRealtime(),
            null,
        )
    }

    override fun close() {
        if (!started) return
        started = false
        releaseTicker?.cancel()
        releaseTicker = null
        val capture = duplex
        duplex = null
        if (capture != null) {
            // Stop is suspending and close() is not: start it undispatched so
            // the capture is marked closed synchronously, then let the pump
            // join and the microphone release finish off this thread.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { capture.stop() }
            }
        }
    }

    // OmniDuplexSessionHooks: driven by the session around generation+playback.
    override fun setAssistantActive(active: Boolean) {
        assistantActiveFlag = active
    }

    override fun bargeInRequested(): Boolean = bargeInFlag

    override fun clearBargeInRequest() {
        bargeInFlag = false
    }

    /** U4's rendered PCM, mono 24 kHz, in rendered playback order. */
    private fun rememberPlayback(pcm: ByteArray) {
        duplex?.rememberPlayback(pcm)
    }

    companion object {
        const val RELEASE_POLL_MS = 100L
    }
}
