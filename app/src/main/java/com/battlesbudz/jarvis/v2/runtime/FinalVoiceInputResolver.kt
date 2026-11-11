package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.TranscriptContent
import com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import kotlinx.coroutines.ensureActive

internal data class ResolvedVoiceInput(val text: String, val recognitionIssue: String?)

/**
 * The transcription surface [FinalVoiceInputResolver] needs from the model
 * engine, as an interface so the resolver's conversation-reset policy can be
 * unit-tested against a fake engine.
 */
internal interface FinalVoiceTranscriptionEngine {
    val modelId: String
    val audioEnabled: Boolean
    val hasFixedVoicePreface: Boolean
    var benchmarkPurpose: PipelineBenchmarkPurpose
    suspend fun setToolsEnabled(enabled: Boolean)
    suspend fun generateAudio(prompt: String, audioPcm: ByteArray,
                              onStreamToken: (String) -> Unit = {}): GenerationResult
}

/** Bridges the concrete engine to [FinalVoiceTranscriptionEngine]. */
internal class LiteRtFinalVoiceTranscriptionEngine(
    private val delegate: LiteRtLmEngine
) : FinalVoiceTranscriptionEngine {
    override val modelId: String get() = delegate.modelId
    override val audioEnabled: Boolean get() = delegate.audioEnabled
    override val hasFixedVoicePreface: Boolean get() = delegate.hasFixedVoicePreface
    override var benchmarkPurpose: PipelineBenchmarkPurpose
        get() = delegate.benchmarkPurpose
        set(value) { delegate.benchmarkPurpose = value }
    override suspend fun setToolsEnabled(enabled: Boolean) {
        delegate.setToolsEnabled(enabled)
    }
    override suspend fun generateAudio(prompt: String, audioPcm: ByteArray,
        onStreamToken: (String) -> Unit): GenerationResult =
        delegate.generateAudio(prompt, audioPcm, onStreamToken)
}

/**
 * Resolves final recognition evidence before any action or answer can be authorized.
 * Empty text may use a bounded audio transcription fallback. On the isolated path model
 * tools remain disabled during that fallback, and the exact native operation is reset
 * before returning; on the resident call path the confirmation instead transcribes on
 * the live call conversation and keeps only a clean, tool-free completion.
 * A direct-audio turn flagged requireWordConfirmation (a follow-up admitted with
 * no word evidence) is authorized only once a transcription confirms real words.
 */
internal class FinalVoiceInputResolver(
    private val engine: FinalVoiceTranscriptionEngine,
    private val resetConversation: suspend () -> Unit,
    private val benchmark: PipelineBenchmarkCapture,
    private val turnTrace: VoiceTurnTrace,
    private val comparison: LiveComparison.Trial?,
    private val recordDiagnostic: (String) -> Unit,
    private val reportStatus: (String) -> Unit,
    private val transcriptionTimeoutMs: Long = 12_000L
) {
    suspend fun resolve(asrTranscript: String, audioBytes: ByteArray,
                        directAudioTurn: Boolean, initialIssue: String?, asrLabel: String,
                        requireWordConfirmation: Boolean = false,
                        residentCallContext: Boolean = false): ResolvedVoiceInput {
        var recognitionIssue = initialIssue
        if (asrTranscript.isBlank() && !engine.audioEnabled && recognitionIssue == null) {
            recognitionIssue = "selected_model_has_no_audio_fallback"
            recordDiagnostic("Voice recognition empty: model=${engine.modelId} action=clarify no_audio_submission=true")
        }
        // The one Gemma transcription pass over retained audio, shared by the
        // ordinary fallback below and the follow-up word confirmation. On the
        // isolated path model tools remain disabled during it and the exact
        // native operation is reset before returning; on the resident call
        // path (see below) the transcription runs on the live conversation
        // instead, and only a clean, tool-free completion is kept.
        suspend fun hearAudio(audio: ByteArray): String {
            turnTrace.mark(VoiceTurnTrace.Stage.AUDIO_FALLBACK_STARTED)
            benchmark.mark("audio_fallback_started")
            benchmark.transcriptionFallback()
            engine.benchmarkPurpose = PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK
            reportStatus("Listening to your recorded speech with Gemma…")
            recordDiagnostic("Voice audio fallback: ${asrLabel} empty; Gemma receiving ${audio.size} bytes")
            // Resident confirmation: on a persistent call turn (the caller only
            // passes residentCallContext for a direct-audio turn whose session
            // is reusable), the confirmation transcribes ON the resident
            // conversation — no tool-policy flip (flipping resets the
            // conversation) and no reset around the attempts — so the exchange
            // joins the call history exactly like the post-answer resident
            // caption does, and the answer that follows keeps its small delta
            // prompt. On build 1785 the resets here were what forced the next
            // answer back to a full-size prompt. The exchange is kept only
            // when the confirmation completes normally with no tool-call
            // output; any failure, timeout, cancellation, or tool call falls
            // back to the reset below, so a partial or tool exchange can never
            // survive into the call context. A fixed-voice-preface conversation
            // excludes itself, as in the generation layer's reuse policy: its
            // audio path rewrites the prompt, so it is never the resident one.
            val resident = residentCallContext && !engine.hasFixedVoicePreface
            var completedCleanly = false
            var sawToolCalls = false
            return try {
                val heardText = kotlinx.coroutines.withTimeout(transcriptionTimeoutMs) {
                    if (!resident) engine.setToolsEnabled(false)
                    VoiceTranscriptResolver.retryEmptyAudio { attempt ->
                        if (!resident) resetConversation()
                        recordDiagnostic("Voice audio fallback: stage=submit attempt=$attempt deadlineMs=$transcriptionTimeoutMs " +
                            if (resident) "policy=resident_confirmation_no_reset" else "toolsEnabled=false")
                        comparison?.put("asr_attempts", attempt)
                        comparison?.mark("asr_submit")
                        val heard = engine.generateAudio(
                            VoiceTranscriptResolver.instructions, audio, { token ->
                                if (token.isNotBlank()) comparison?.mark("asr_first_token")
                            })
                        comparison?.put("asr_attempt_${attempt}_text", heard.text)
                        comparison?.put("asr_attempt_${attempt}_stream_events", heard.streamEvents)
                        comparison?.put("asr_attempt_${attempt}_tool_calls", heard.toolCalls.size)
                        comparison?.put("asr_attempt_${attempt}_duration_ms", heard.totalGenerationTimeMs)
                        recordDiagnostic("Voice audio fallback: attempt=$attempt chars=${heard.text.length} " +
                            "streamEvents=${heard.streamEvents} toolCalls=${heard.toolCalls.size} durationMs=${heard.totalGenerationTimeMs} " +
                            "text=${heard.text.take(1000)}")
                        if (heard.toolCalls.isNotEmpty()) sawToolCalls = true
                        // Recognition never dispatches tools or speaks model output.
                        if (heard.toolCalls.isEmpty()) heard.text else ""
                    }
                }
                completedCleanly = true
                heardText
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
                recognitionIssue = "audio_fallback_timeout"
                reportStatus("I couldn't make out that request. Please say it again.")
                recordDiagnostic("Voice audio fallback: result=timeout action=clarify")
                ""
            } finally {
                // Native cancellation joins its owner before resetting the conversation.
                // Its cleanup can extend the deadline; never close a model concurrently.
                // A clean resident confirmation is the one case with nothing to
                // clean: its exchange stays as call history.
                if (!resident || !completedCleanly || sawToolCalls) resetConversation()
                turnTrace.mark(VoiceTurnTrace.Stage.AUDIO_FALLBACK_FINISHED)
                benchmark.mark("audio_fallback_finished")
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
            }
        }
        val resolvedTranscript = when {
            directAudioTurn && recognitionIssue == null && requireWordConfirmation -> {
                // A follow-up admitted on strong audio alone — no live words,
                // no corroboration — may not be answered from its retained
                // audio until a transcription confirms real words. On build
                // 1771 the Smart Turn caption skip left exactly such a turn
                // with no transcription at all (finalAsrStatus=
                // skipped_smart_turn_caption) and no [NO_SPEECH] gate, and
                // the answer model answered noise. A final ASR transcript
                // confirms without a new decode; otherwise the bounded Gemma
                // transcription above is the confirmation. Confirmed words
                // proceed as an ordinary direct-audio turn; no words take
                // the existing clarify path (audio_fallback_empty).
                val heard = VoiceTranscriptResolver.resolve(asrTranscript, audioBytes) { hearAudio(it) }
                when {
                    recognitionIssue != null -> asrTranscript
                    VoiceTranscriptResolver.hasTranscript(heard) -> {
                        recordDiagnostic("Voice follow-up confirmation: result=words_confirmed chars=${heard.length} action=answer_from_audio")
                        GemmaAudioInputPolicy.REQUEST
                    }
                    else -> {
                        recognitionIssue = "audio_fallback_empty"
                        recordDiagnostic("Voice follow-up confirmation: result=no_words action=clarify answer_generation=false")
                        asrTranscript
                    }
                }
            }
            directAudioTurn && recognitionIssue == null -> GemmaAudioInputPolicy.REQUEST
            recognitionIssue != null -> asrTranscript
            else -> VoiceTranscriptResolver.resolve(asrTranscript, audioBytes) { hearAudio(it) }
        }
        if (recognitionIssue == null &&
            !VoiceTranscriptResolver.hasTranscript(resolvedTranscript) &&
            !TranscriptContent.isSoundOnly(resolvedTranscript)) {
            recognitionIssue = "audio_fallback_empty"
            recordDiagnostic("Voice audio fallback: result=empty action=clarify answer_generation=false")
        }
        return ResolvedVoiceInput(resolvedTranscript, recognitionIssue)
    }
}
