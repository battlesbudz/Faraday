package com.battlesbudz.jarvis.v2.runtime

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
 * Resolves final recognition evidence before any action or answer can be authorized.
 * Empty text may use a bounded audio transcription fallback. Model tools remain disabled
 * during that fallback, and the exact native operation is reset before returning.
 */
internal class FinalVoiceInputResolver(
    private val engine: LiteRtLmEngine,
    private val resetConversation: suspend () -> Unit,
    private val benchmark: PipelineBenchmarkCapture,
    private val turnTrace: VoiceTurnTrace,
    private val comparison: LiveComparison.Trial?,
    private val recordDiagnostic: (String) -> Unit,
    private val reportStatus: (String) -> Unit
) {
    suspend fun resolve(asrTranscript: String, audioBytes: ByteArray,
                        directAudioTurn: Boolean, initialIssue: String?, asrLabel: String): ResolvedVoiceInput {
        var recognitionIssue = initialIssue
        if (asrTranscript.isBlank() && !engine.audioEnabled && recognitionIssue == null) {
            recognitionIssue = "selected_model_has_no_audio_fallback"
            recordDiagnostic("Voice recognition empty: model=${engine.modelId} action=clarify no_audio_submission=true")
        }
        val resolvedTranscript = if (directAudioTurn && recognitionIssue == null)
            GemmaAudioInputPolicy.REQUEST
        else if (recognitionIssue != null) asrTranscript else VoiceTranscriptResolver.resolve(
            asrTranscript, audioBytes
        ) { audio ->
            turnTrace.mark(VoiceTurnTrace.Stage.AUDIO_FALLBACK_STARTED)
            benchmark.mark("audio_fallback_started")
            benchmark.transcriptionFallback()
            engine.benchmarkPurpose = PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK
            reportStatus("Listening to your recorded speech with Gemma…")
            recordDiagnostic("Voice audio fallback: ${asrLabel} empty; Gemma receiving ${audio.size} bytes")
            try {
                kotlinx.coroutines.withTimeout(12_000L) {
                    engine.setToolsEnabled(false)
                    VoiceTranscriptResolver.retryEmptyAudio { attempt ->
                        resetConversation()
                        recordDiagnostic("Voice audio fallback: stage=submit attempt=$attempt deadlineMs=12000 toolsEnabled=false")
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
                        // Recognition never dispatches tools or speaks model output.
                        if (heard.toolCalls.isEmpty()) heard.text else ""
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                kotlin.coroutines.coroutineContext.ensureActive()
                recognitionIssue = "audio_fallback_timeout"
                reportStatus("I couldn't make out that request. Please say it again.")
                recordDiagnostic("Voice audio fallback: result=timeout action=clarify")
                ""
            } finally {
                // Native cancellation joins its owner before resetting the conversation.
                // Its cleanup can extend the deadline; never close a model concurrently.
                resetConversation()
                turnTrace.mark(VoiceTurnTrace.Stage.AUDIO_FALLBACK_FINISHED)
                benchmark.mark("audio_fallback_finished")
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
            }
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
