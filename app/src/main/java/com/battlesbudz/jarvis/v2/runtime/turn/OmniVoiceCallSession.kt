package com.battlesbudz.jarvis.v2.runtime.turn

import kotlinx.coroutines.CancellationException

/** Stable catalog identity for routing call turns away from the Gemma bridge. */
internal object OmniVoiceCallRoute {
    const val MODEL_ID = "LFM3-Omni-2B"
    fun isOmni(modelId: String): Boolean = modelId == MODEL_ID
}

/** Installed by runtime composition; owns the per-call loop and binds to VoiceSessionController. */
internal interface OmniVoiceCallOwner {
    fun start()
    fun stop(callId: String?)

    /** Model selection owns closing the runtime: cancel any call loop, then close the graphs. Never blocks the caller. */
    fun closeRuntime()
}

/** A completed U3 audio turn. Samples are mono 16 kHz float PCM, ready for Omni's audio encoder. */
internal data class OmniCapturedTurn(
    val pcm16kMono: FloatArray,
    val endpointAtMs: Long,
    /** Optional text supplied by the call UI/input adapter; never produced by Whisper on this route. */
    val transcriptText: String? = null,
)

/** U3 call-scoped capture seam. Implementations keep microphone capture alive between turns. */
internal interface OmniTurnCapture : AutoCloseable {
    suspend fun startCall()
    suspend fun awaitTurn(): OmniCapturedTurn
    override fun close()
}

/** U4 streaming sink. Each chunk is 24 kHz mono PCM and is played as generation produces it. */
internal interface OmniCallSpeechOutput : AutoCloseable {
    /** Actual first-audible timestamp in monotonic milliseconds for the active turn. */
    val firstSoundAtMonotonicMs: Long?
    fun beginTurn()
    suspend fun playChunk(pcm24kMono: FloatArray)
    fun stopAndFlush()
    override fun close()
}

/** Narrow U2 runtime adapter used by the voice call. All methods run on OmniRuntime's worker. */
internal interface OmniCallEngine {
    /** Number of decoder positions currently occupied, including speech frames. */
    val contextPositions: Int

    /** Number of positions system + transcript would use when re-prefilled as text only. */
    suspend fun estimateTextPositions(transcript: List<OmniCallMessage>): Int

    /** New call boundary: position zero and every host-owned state buffer zeroed. */
    suspend fun resetConversation()

    /** Clear the call conversation while retaining the selected model runtime in memory. */
    suspend fun endCall()

    /** Rebuild a fresh text-only prompt from the provided retained transcript. */
    suspend fun prefillText(transcript: List<OmniCallMessage>)

    /** U3 PCM enters the audio encoder and the U4 interleaved loop streams output to [output]. */
    suspend fun answerAudio(
        pcm16kMono: FloatArray,
        output: OmniCallSpeechOutput,
        shouldStop: () -> Boolean,
    ): OmniCallAnswer

}

internal enum class OmniCallRole { USER, ASSISTANT }

internal data class OmniCallMessage(val role: OmniCallRole, val text: String)

internal data class OmniCallAnswer(
    val text: String,
    /** Per-graph durations from U2/U4, in milliseconds. */
    val graphTimingsMs: Map<String, Long> = emptyMap(),
)

/**
 * Owns one persistent Omni conversation for one Voice Call. The host transcript
 * survives runtime resets; at a boundary near 3,800 positions it is re-prefilled
 * as text, trimming oldest complete user/assistant pairs to fit the 4,096 cap.
 */
internal class OmniVoiceCallSession(
    private val engine: OmniCallEngine,
    private val capture: OmniTurnCapture,
    private val output: OmniCallSpeechOutput,
    private val diagnostics: (Event) -> Unit,
    private val isCallActive: () -> Boolean,
) {
    sealed interface Event {
        data class Turn(val endpointAtMs: Long, val firstSoundAtMs: Long?, val graphTimingsMs: Map<String, Long>) : Event
        data class ContextRollover(val oldPositions: Int, val prefilledPositions: Int, val droppedPairs: Int) : Event
        data class MissingInputTranscript(val endpointAtMs: Long) : Event
    }

    private val transcript = mutableListOf<OmniCallMessage>()
    private var started = false
    private var closed = false

    suspend fun start() {
        check(!closed) { "omni_call_session_closed" }
        check(!started) { "omni_call_session_already_started" }
        engine.resetConversation()
        capture.startCall()
        started = true
    }

    /** Runs one clean sequential turn. Barge-in remains owned by U6. */
    suspend fun runTurn(): OmniCallAnswer {
        check(started && !closed) { "omni_call_session_not_active" }
        if (!isCallActive()) throw CancellationException("omni_voice_call_ended")
        rolloverIfNeeded()
        val captured = capture.awaitTurn()
        if (!isCallActive()) throw CancellationException("omni_voice_call_ended")

        output.beginTurn()
        val answer = try {
            engine.answerAudio(captured.pcm16kMono, output, shouldStop = {
                !isCallActive() || engine.contextPositions >= MAX_CONTEXT_POSITIONS
            })
        } catch (cancelled: CancellationException) {
            output.stopAndFlush()
            throw cancelled
        }
        if (!isCallActive()) {
            output.stopAndFlush()
            throw CancellationException("omni_voice_call_ended")
        }
        val userText = captured.transcriptText?.takeIf { it.isNotBlank() }
        if (userText == null) {
            diagnostics(Event.MissingInputTranscript(captured.endpointAtMs))
            transcript += OmniCallMessage(OmniCallRole.USER, "[spoken audio; transcript unavailable]")
        } else transcript += OmniCallMessage(OmniCallRole.USER, userText)
        transcript += OmniCallMessage(OmniCallRole.ASSISTANT, answer.text)
        diagnostics(Event.Turn(captured.endpointAtMs, output.firstSoundAtMonotonicMs, answer.graphTimingsMs))
        return answer
    }

    private suspend fun rolloverIfNeeded() {
        val occupied = engine.contextPositions
        if (occupied < ROLLOVER_AT) return
        var retained = transcript.toMutableList()
        var droppedPairs = 0
        while (retained.isNotEmpty() && engine.estimateTextPositions(retained) > MAX_CONTEXT_POSITIONS) {
            // History is always appended as complete user/assistant pairs.
            if (retained.size >= 2) retained = retained.drop(2).toMutableList() else retained.clear()
            droppedPairs++
        }
        engine.resetConversation()
        engine.prefillText(retained)
        check(engine.contextPositions <= MAX_CONTEXT_POSITIONS) { "omni_text_rollover_exceeded_context" }
        transcript.clear()
        transcript.addAll(retained)
        diagnostics(Event.ContextRollover(occupied, engine.contextPositions, droppedPairs))
    }

    suspend fun closeCall() {
        if (closed) return
        closed = true
        output.stopAndFlush()
        runCatching { capture.close() }
        runCatching { output.close() }
        // Model residency follows selection, not call duration. Clear only the
        // host-owned conversation; the model setup owner closes OmniRuntime on swap.
        try {
            engine.endCall()
        } finally {
            transcript.clear()
        }
    }

    internal fun transcriptSnapshot(): List<OmniCallMessage> = transcript.toList()

    companion object {
        const val MAX_CONTEXT_POSITIONS = 4096
        const val ROLLOVER_AT = 3800
    }
}

/** Sequential call-duration driver; the installed owner supplies call identity and cancellation. */
internal class OmniVoiceCallLoop(
    private val session: OmniVoiceCallSession,
    private val isCallActive: () -> Boolean,
    private val onAnswer: (OmniCallAnswer) -> Unit,
) {
    suspend fun run() {
        try {
            session.start()
            while (isCallActive()) onAnswer(session.runTurn())
        } finally {
            session.closeCall()
        }
    }
}
