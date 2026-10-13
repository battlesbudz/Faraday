package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkAsr
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkTts
import com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.LiveReplyMetrics
import com.battlesbudz.jarvis.v2.voice.TtsComparisonStore
import com.battlesbudz.jarvis.v2.voice.TtsEngine
import com.battlesbudz.jarvis.v2.voice.TtsSessionMetrics
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison

/**
 * Per-turn telemetry owner. Capture work, synthesized PCM and actual playback stay separate,
 * and live metrics are activated only once this turn is accepted for user-visible delivery.
 * Shared atomics preserve callback ordering without giving telemetry capture/model ownership.
 */
internal class VoiceTurnTelemetry(
    private val asrTurnId: String,
    conversationId: String,
    private val asrEngine: AsrEngine,
    private val ttsEngine: TtsEngine,
    private val captionAsrEnabled: Boolean,
    private val benchmark: PipelineBenchmarkCapture,
    private val turnTrace: VoiceTurnTrace,
    private val comparison: LiveComparison.Trial?,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val asrComparisonStore: AsrComparisonStore,
    private val ttsComparisonStore: TtsComparisonStore
) {
    var hypothesis: String? = null
    val replyTtsMetrics = java.util.concurrent.atomic.AtomicReference<TtsSessionMetrics?>(null)
    val speechEndToReplyMs = java.util.concurrent.atomic.AtomicLong(-1)
    val finalReadyAt = java.util.concurrent.atomic.AtomicLong(0)
    val speechEndedAt = java.util.concurrent.atomic.AtomicLong(0)
    private val firstPlayback = java.util.concurrent.atomic.AtomicBoolean(true)
    private val nativeAudioTiming = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.NativeAudioCaptureTiming?>(null)
    private val liveMetrics = java.util.concurrent.atomic.AtomicReference(
        LiveReplyMetrics(asrTurnId, conversationId))
    private val liveMetricsActive = java.util.concurrent.atomic.AtomicBoolean(false)
    fun activateLiveMetrics() {
        if (liveMetricsActive.compareAndSet(false, true)) {
            speechEndedAt.get().takeIf { it != 0L }?.let { ended ->
                liveMetrics.updateAndGet { it.speechEnded(ended) }
            }
            VoiceSessionUi.beginLiveMetrics(liveMetrics.get())
        }
    }
    fun publishLiveMetrics(update: (LiveReplyMetrics) -> LiveReplyMetrics) {
        val value = liveMetrics.updateAndGet(update)
        if (liveMetricsActive.get())
            VoiceSessionUi.updateLiveMetrics(asrTurnId, value.conversationId) { value }
    }

    /** Called only for the final admitted candidate, after checked encoder close. */
    fun acceptNativeAudioTiming(timing: com.battlesbudz.jarvis.v2.voice.NativeAudioCaptureTiming) {
        if (!nativeAudioTiming.compareAndSet(null, timing)) return
        timing.metrics()?.let { measured ->
            publishLiveMetrics { it.copy(nativeAudioTiming = measured) }
            measured.observations().forEach { (name, value) -> benchmark.metric(name, value) }
            benchmark.configuration("native_audio_timing_scope", "final_candidate_native_admission_to_reply_playback_head")
            benchmark.configuration("native_audio_clock_contract", if (timing.receipt.verifiedClockContract)
                "reviewed_aosp_api30_36_source_assumption" else "unverified")
            benchmark.configuration("native_audio_installed_runtime_binary_attested", "false")
        }
    }

    fun recordAsr(metrics: AsrCaptureMetrics, text: String, capture: AudioTurnCapture?) {
        hypothesis = text
        if (captionAsrEnabled) benchmark.asr(PipelineBenchmarkAsr(
            if (captionAsrEnabled) asrEngine.id else "none", loadMs = metrics.modelLoadMs, captureReadyMs = metrics.captureReadyMs,
            inputAudioMs = metrics.recognitionWork?.takeIf { it.complete }?.submittedAudioMs,
            decodeWorkMs = metrics.recognitionWork?.takeIf { it.complete }?.workMs,
            finalizationMs = metrics.finalizationMs, firstPartialMs = metrics.firstPartialAfterSpeechMs,
            endpointDelayMs = metrics.endpointDetectionMs?.let { it + metrics.finalizationMs }, maxDecodeChunkMs = metrics.maxDecodeChunkMs,
            partialUpdates = metrics.partialUpdates, transcriptCharacters = text.length,
            endpointReason = metrics.endpointReason, maxBacklogMs = metrics.maxRecognitionBacklogMs))
        benchmark.metric("asr_max_recognition_work_ms", metrics.maxRecognitionWorkMs)
        benchmark.metric("asr_final_decode_ms", metrics.finalDecodeMs)
        benchmark.metric("asr_capture_audio_ms", metrics.audioMs)
        benchmark.metric("asr_capture_accept_wall_ms", metrics.decodeMs)
        metrics.recognitionWork?.let { work ->
            benchmark.configuration("asr_work_scope", work.scope)
            benchmark.configuration("asr_work_complete", work.complete.toString())
            benchmark.configuration("asr_submitted_audio_scope", "cumulative_pcm_per_api_submission_overlapping_retries_counted")
            benchmark.metric("asr_worker_feed_ms", work.feedWorkMs)
            benchmark.metric("asr_worker_partial_decode_ms", work.partialDecodeWorkMs)
            benchmark.metric("asr_worker_final_decode_ms", work.finalDecodeWorkMs)
            benchmark.metric("asr_worker_recovery_decode_ms", work.recoveryDecodeWorkMs)
            benchmark.metric("asr_worker_invocations", work.invocations)
            benchmark.metric("asr_worker_submitted_samples", work.submittedAudioSamples)
            benchmark.metric("asr_realtime_factor", work.realtimeFactor)
        }
        metrics.acoustic?.let { acoustic ->
            benchmark.metric("asr_sample_count", acoustic.sampleCount)
            benchmark.metric("asr_vad_admitted_sample_count", acoustic.speechSampleCount)
            benchmark.metric("asr_near_silent_sample_count", acoustic.nearSilentSampleCount)
            benchmark.metric("asr_clipped_sample_count", acoustic.clippedSampleCount)
            benchmark.metric("asr_rms_dbfs", acoustic.rmsDbfs)
            benchmark.metric("asr_peak_dbfs", acoustic.peakDbfs)
            benchmark.metric("asr_vad_admitted_rms_dbfs", acoustic.speechRmsDbfs)
            benchmark.metric("asr_vad_non_speech_rms_dbfs", acoustic.nonSpeechRmsDbfs)
            benchmark.metric("asr_vad_energy_contrast_db", acoustic.speechToNonSpeechEnergyDb)
            benchmark.metric("asr_noise_floor_rms", acoustic.noiseFloorRms)
        }
        comparison?.put("asr_metrics", metrics.toString())
        comparison?.put("speech_start_to_asr_first_partial_ms", metrics.firstPartialAfterSpeechMs ?: org.json.JSONObject.NULL)
        asrComparisonStore.add(asrTurnId, metrics, text, asrEngine)
        capture?.lastSpeechAtMs?.let { speechEndedAt.set(it) }
        diagnosticRecorder.recordSummary("Voice input summary: turn=$asrTurnId " +
            "reason=${metrics.endpointReason} speech=${capture?.hasSpeech} chars=${text.length} " +
            "partials=${metrics.partialUpdates} firstPartialMs=${metrics.firstPartialAfterSpeechMs} " +
            "endpointMs=${metrics.endpointDetectionMs}")
    }

    fun recordTts(metrics: TtsSessionMetrics) {
        comparison?.put("tts_metrics", metrics.toString())
        replyTtsMetrics.set(metrics)
        benchmark.tts(PipelineBenchmarkTts(
            ttsEngine.id, loadMs = metrics.loadMs, firstTextToPcmMs = metrics.firstTextToPcmMs,
            firstTextToPlaybackMs = metrics.firstTextToPlaybackMs, synthesisMs = metrics.synthesisMs,
            generatedAudioMs = metrics.audioMs, queueWaitMs = metrics.queueWaitMs,
            playbackStarvationMs = metrics.observedPlaybackStarvationMs, underruns = metrics.underruns))
        diagnosticRecorder.recordTurnEvidence(asrTurnId, "tts", "engine=${ttsEngine.id} firstTextToPcmMs=${metrics.firstTextToPcmMs} " +
            "synthesisMs=${metrics.synthesisMs} audioMs=${metrics.audioMs} underruns=${metrics.underruns}")
        ttsComparisonStore.add(ttsEngine, "voice-call", asrTurnId, metrics)
        diagnosticRecorder.recordSummary("Voice TTS turn=$asrTurnId loadMs=${metrics.loadMs} " +
            "firstTextToPcmMs=${metrics.firstTextToPcmMs} firstTextToPlaybackMs=${metrics.firstTextToPlaybackMs} " +
            "synthesisMs=${metrics.synthesisMs} audioMs=${metrics.audioMs} threads=${metrics.threads} " +
            "underruns=${metrics.underruns}")
    }

    fun recordFirstPlayback(expectedCallId: String, voiceSessionController: VoiceSessionController) {
        if (firstPlayback.compareAndSet(true, false) && finalReadyAt.get() != 0L) {
            val playbackAtNs = System.nanoTime()
            val playbackAt = playbackAtNs / 1_000_000
            val nativeTiming = nativeAudioTiming.get()?.metrics(playbackAtNs)
            publishLiveMetrics { metrics ->
                var updated = metrics.firstActualPlayback(playbackAt)
                speechEndedAt.get().takeIf { it != 0L }?.let { updated = updated.speechEnded(it) }
                if (nativeTiming != null) updated = updated.copy(nativeAudioTiming = nativeTiming)
                updated
            }
            voiceSessionController.updateReplyMetrics(expectedCallId, asrTurnId) {
                it.firstActualPlayback(playbackAt).let { metrics ->
                    if (nativeTiming == null) metrics else metrics.copy(nativeAudioTiming = nativeTiming)
                }
            }
            nativeTiming?.observations()?.forEach { (name, value) -> benchmark.metric(name, value) }
            comparison?.mark("answer_audio")
            turnTrace.mark(VoiceTurnTrace.Stage.FIRST_REPLY_AUDIO)
            benchmark.markAt("first_reply_audio", playbackAt)
            asrComparisonStore.update(asrTurnId, "final_to_playback_start_ms",
                (System.nanoTime() - finalReadyAt.get()) / 1_000_000)
            if (speechEndedAt.get() != 0L) {
                val elapsed = System.nanoTime() / 1_000_000 - speechEndedAt.get()
                speechEndToReplyMs.set(elapsed)
                asrComparisonStore.update(asrTurnId, "speech_end_to_playback_ms", elapsed)
                diagnosticRecorder.recordImportant("Voice latency: speech_end_to_playback_ms=$elapsed turn=$asrTurnId")
            }
        }
    }
}
