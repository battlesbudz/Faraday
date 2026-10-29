package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.AudioInput
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.QuietSpeechAudioInput
import com.battlesbudz.jarvis.v2.voice.SileroSpeechDetector
import com.battlesbudz.jarvis.v2.voice.TurnStopPolicy
import com.battlesbudz.jarvis.v2.voice.VoiceModelSession
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import java.io.File
import kotlinx.coroutines.CoroutineScope

internal data class VoiceCapturePlan(
    val turnId: String,
    val asrEngine: AsrEngine,
    val asrDirectory: File?,
    val directAudioTurn: Boolean,
    val captionAsrEnabled: Boolean,
    val guardFollowupSpeech: Boolean
)

/** Freezes capture limits/recognizer policy; callers own start, stop and partial publication. */
internal class VoiceTurnCaptureFactory(
    private val context: Context,
    private val diagnosticRecorder: DiagnosticRecorder
) {
    fun create(scope: CoroutineScope, input: AudioInput, models: VoiceModelSession,
               plan: VoiceCapturePlan, comparison: LiveComparison.Trial?, reportStatus: (String) -> Unit,
               onMetrics: (AsrCaptureMetrics, String) -> Unit,
               onPartialTranscript: (String) -> Unit,
               retainedPcmObserver: com.battlesbudz.jarvis.v2.voice.RetainedPcmObserver? = null,
               onAcousticDecision: (ByteArray, com.battlesbudz.jarvis.v2.voice.SpeechDecision, com.battlesbudz.jarvis.v2.voice.SpeechDecision, Double) -> Unit = { _, _, _, _ -> },
               rejectFinalCandidate: (String, Long?) -> Boolean = { _, _ -> false },
               shadowObserver: com.battlesbudz.jarvis.v2.voice.CaptureShadowObserver? = null,
               needsFollowupTranscript: (Long?) -> Boolean = { true },
               nativePauseObserver: com.battlesbudz.jarvis.v2.voice.NativePauseObserver? = null,
               nativePauseTurnId: String = "",
               nativePauseGeneration: Long = 0): AudioTurnCapture {
        return AudioTurnCapture(
            QuietSpeechAudioInput(input, maxGain = 1.0, log = {
                diagnosticRecorder.record("Voice input: $it")
            }), scope,
            allowAudioOnlyTurns = true,
            guardFollowupSpeech = plan.guardFollowupSpeech,
            maxAudioDurationMs = if (plan.directAudioTurn) GemmaAudioInputPolicy.MAX_CAPTURE_MS else 25_000,
            rejectAtAudioLimit = plan.directAudioTurn,
            captionOnly = plan.directAudioTurn,
            // This factory builds only the conversation captures (primary and
            // follow-up). They follow the reference turn-stop pattern: the Smart
            // Turn analyzer decides every stop, backed only by the 5s timeout.
            turnStopPolicy = TurnStopPolicy.REFERENCE,
            trailingSilenceMs = if (plan.directAudioTurn && !plan.captionAsrEnabled) 650L else null,
            createDetector = { SileroSpeechDetector.create(context.assets) },
            log = {
                comparison?.log("capture $it")
                if (it.startsWith("followup_speech_evidence") || it.startsWith("followup_candidate_rejected")) {
                    diagnosticRecorder.recordTurnEvidence(plan.turnId, "followup_speech", it)
                }
                if (it.startsWith("capture_endpoint_timing") || it.startsWith("caption_finalization") || it.startsWith("native_pause_") || it.startsWith("native_endpoint_cue") || it.startsWith("smart_turn_endpoint")) {
                    diagnosticRecorder.recordTurnEvidence(plan.turnId, "capture_endpoint", it)
                }
                if (it.startsWith("asr_recovery_") || it.startsWith("empty_speech_candidate") || it.startsWith("nonverbal_candidate")) {
                    diagnosticRecorder.recordImportant("Voice input: $it")
                } else diagnosticRecorder.record("Voice input: $it")
            },
            onRecognitionRecovery = { recovering ->
                reportStatus(if (recovering) "Retrying speech recognition…" else "Voice Call is listening — speak now.")
            },
            createTranscriber = if (!plan.captionAsrEnabled) null else {
                { plan.asrEngine.create(requireNotNull(plan.asrDirectory), log = { diagnosticRecorder.recordSummary("Voice input: $it") }, modelSession = models) }
            },
            onMetrics = onMetrics,
            onPartialTranscript = onPartialTranscript,
            retainedPcmObserver = retainedPcmObserver,
            onAcousticDecision = onAcousticDecision,
            shadowObserver = shadowObserver,
            rejectFinalCandidate = rejectFinalCandidate,
            nativePauseObserver = nativePauseObserver,
            nativePauseTurnId = nativePauseTurnId,
            nativePauseGeneration = nativePauseGeneration,
            canUseSmartTurn = { onset ->
                comparison == null && !needsFollowupTranscript(onset)
            },
            // The same eligibility that closes the route gate: with captions
            // off this capture can never produce the transcript that onset's
            // echo rejection requires, so the capture rejects the candidate
            // at finalization instead of submitting it unverified.
            needsFinalTranscript = needsFollowupTranscript,
            canUseNativePause = { onset ->
                plan.directAudioTurn && comparison == null && retainedPcmObserver != null && !needsFollowupTranscript(onset)
            },
            canRetireIdleCaption = { onset ->
                plan.directAudioTurn && plan.captionAsrEnabled && plan.asrEngine == AsrEngine.WHISPER &&
                    comparison == null && retainedPcmObserver != null && !needsFollowupTranscript(onset)
            }
        )
    }
}
