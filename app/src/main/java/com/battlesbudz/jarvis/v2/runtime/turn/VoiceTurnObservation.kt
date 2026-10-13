package com.battlesbudz.jarvis.v2.runtime.turn

import android.content.Context
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarks
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency
import com.battlesbudz.jarvis.v2.runtime.VoiceTurnTelemetry
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
import com.battlesbudz.jarvis.v2.voice.CoalescingVoiceCallStore
import com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState
import com.battlesbudz.jarvis.v2.voice.TtsComparisonStore
import com.battlesbudz.jarvis.v2.voice.VoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Per-turn evidence, independent of microphone/model resource ownership and release. */
internal class VoiceTurnObservation(
    private val context: Context,
    val request: VoiceTurnRequest,
    conversationId: String,
    val store: AndroidPipelineBenchmarkStore,
    private val benchmarks: PipelineBenchmarks,
    private val recorder: DiagnosticRecorder,
    private val asrStore: AsrComparisonStore,
    ttsStore: TtsComparisonStore,
    private val callStore: VoiceCallStore
) {
    val turnTrace = VoiceTurnTrace(request.asrTurnId)
    val benchmark = benchmarks.create(request.asrTurnId, "voice",
        if (request.captionAsrEnabled) request.asrEngine else null, request.ttsEngine)
    val hypothesisEpoch = store.hypothesisEpoch()
    var outcome = PipelineBenchmarkOutcome.UNKNOWN
    var failure: String? = null
    var engine: LiteRtLmEngine? = null
    val replyLatency = AtomicReference<TurnLatency?>(null)
    val telemetry = VoiceTurnTelemetry(request.asrTurnId, conversationId, request.asrEngine,
        request.ttsEngine, request.captionAsrEnabled, benchmark, turnTrace, request.comparison,
        recorder, asrStore, ttsStore)

    init {
        benchmark.configuration("voice_input_mode", if (request.directAudioTurn) "gemma_audio" else "transcribed_text")
        benchmark.configuration("caption_engine", if (request.directAudioTurn && request.captionAsrEnabled) "whisper_base_en"
            else if (request.directAudioTurn) "off" else request.asrEngine.id)
        benchmark.configuration("caption_authorizes_request", "false")
        benchmark.configuration("gemma_audio_long_request_policy", "reject_entire_request_at_28_seconds_no_tail_submission")
        benchmark.configuration("reply_id", request.asrTurnId)
        benchmark.configuration("conversation_id", request.queuedTypedInput?.conversationId ?: conversationId)
        benchmark.configuration("input_origin", if (request.queuedTypedInput != null) "TYPED" else "SPOKEN")
    }

    /** Called after joined resource cleanup, including failed/cancelled turns. */
    fun finish(lifetime: VoiceTurnLifetime, cancelled: Boolean, acceptedWorkUnfinished: () -> Boolean) {
        turnTrace.mark(VoiceTurnTrace.Stage.TURN_FINISHED)
        request.comparison?.let {
            it.mark("finished")
            it.put("final_status", lifetime.finalMessage)
            it.put("turn_completed", !cancelled && lifetime.finalMessage.startsWith("Voice Call turn complete."))
            it.put("pipeline", JSONObject(turnTrace.snapshot()))
            it.put("thermal_after", context.getSystemService(PowerManager::class.java).currentThermalStatus)
            it.put("speech_delivery", lifetime.finalSpeechDelivery.get()?.toString() ?: "unavailable")
            LiveComparison.finish(it)
        }
        if (cancelled) outcome = PipelineBenchmarkOutcome.CANCELLED
        benchmarks.finishResources(benchmark)
        if (lifetime.finalSpeechDelivery.get()?.state == SpeechDeliveryState.FAILED)
            benchmark.noteOutcome(PipelineBenchmarkOutcome.ERROR, "speech_delivery_failed")
        benchmark.metric("speech_end_to_first_answer_playback_ms", telemetry.speechEndToReplyMs.get().takeIf { it >= 0 })
        benchmark.finish(outcome, lifetime.expectedResourceCall, failure)?.let {
            store.append(it, telemetry.hypothesis, expectedHypothesisEpoch = hypothesisEpoch)
        }
        if (!acceptedWorkUnfinished()) engine?.onBenchmarkSubmission = {}
        val stages = JSONObject(turnTrace.snapshot())
        asrStore.update(request.asrTurnId, "pipeline_stage_ms", stages)
        recorder.recordSummary("Voice pipeline turn=${request.asrTurnId} stageOffsetsMs=$stages clock=monotonic fillerExcluded=true")
        recorder.recordTurnEvidence(request.asrTurnId, "pipeline", "stageOffsetsMs=$stages")
        (callStore as? CoalescingVoiceCallStore)?.metrics()?.let {
            recorder.recordSummary("Voice checkpoints scope=runtime_cumulative progressUpdates=${it.progressUpdates} " +
                "coalescedUpdates=${it.coalescedUpdates} writes=${it.writes} writeMs=${it.writeMs} failures=${it.failures}")
        }
    }
}
