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
    private val transcribePcm: suspend (ByteArray) -> String?,
    /**
     * U8: receives accepted and rejected barge-in gate decisions for the
     * Omni diagnostics evidence. Null leaves the capture behavior unchanged.
     * No instrumentation test constructs this adapter (verified against the
     * branch's test tree), so a defaulted callback here is safe.
     */
    private val onBargeInEvent: ((event: String, confirmedSpeechMs: Long, stopLatencyMs: Long?) -> Unit)? = null,
) : OmniTurnCapture, OmniDuplexSessionHooks {
    private var duplex: OmniDuplexCapture? = null
    private var releaseTicker: Job? = null
    private var started = false
    @Volatile private var callClosed = false
    private val transcriptionJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
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
                val stopStart = SystemClock.elapsedRealtime()
                val stopped = runCatching { output.stopAndFlush() }.isSuccess
                // U8: the accepted gate decision and the time the stop took;
                // null when no stop completed. The pump reports the measured
                // confirmed-speech duration (the gate only confirms after its
                // playback confirmation window). The duplex pump is always
                // assigned by the time this hook can fire.
                onBargeInEvent?.invoke(
                    "accepted",
                    duplex?.lastConfirmedSpeechMs ?: 0L,
                    if (stopped) SystemClock.elapsedRealtime() - stopStart else null,
                )
            },
            smartTurnWantsMore = { false },
            onLog = log,
            nowMs = SystemClock::elapsedRealtime,
        )
        duplex = capture
        // U8: a candidate the echo guard rejects never stops playback or
        // capture — only the gate decision is recorded, for device tuning.
        capture.onEchoRejected = {
            onBargeInEvent?.invoke("rejected-echo", capture.lastRejectedSpeechMs, null)
        }
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
            OmniAudioEncoder.pcm16ToFloat(ready.pcm.copyOf()),
            ready.decisionAtMs.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            null,
            ready.pcm.copyOf(),
            "omni-${ready.decisionAtMs}",
            ready.decisionAtMs,
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

    override fun submitTranscription(turn: OmniCapturedTurn, onResult: (String?) -> Unit) {
        val bytes = turn.pcm16kMonoBytes.copyOf()
        val job = scope.launch(Dispatchers.IO) {
            val recognized = try { transcribePcm(bytes) }
            catch (cancelled: kotlinx.coroutines.CancellationException) {
                log("asr_empty turn=${turn.turnId} reason=cancelled")
                throw cancelled
            }
            catch (failure: Throwable) {
                log("asr_empty turn=${turn.turnId} reason=${failure.javaClass.simpleName}")
                null
            }
            if (callClosed || !isActive) {
                log("asr_stale turn=${turn.turnId} reason=call_ended")
            } else if (recognized.isNullOrBlank()) {
                log("asr_empty turn=${turn.turnId} reason=blank_decode")
                onResult(null)
            } else {
                log("asr_complete turn=${turn.turnId} chars=${recognized.length} bytes=${bytes.size}")
                onResult(recognized.trim())
            }
        }
        transcriptionJobs += job
    }

    override suspend fun awaitTranscriptions() {
        transcriptionJobs.toList().forEach { it.join() }
        transcriptionJobs.removeAll { it.isCompleted }
    }

    override suspend fun closeCall() {
        callClosed = true
        transcriptionJobs.forEach { it.cancel() }
        transcriptionJobs.toList().forEach { it.join() }
        transcriptionJobs.clear()
        close()
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
