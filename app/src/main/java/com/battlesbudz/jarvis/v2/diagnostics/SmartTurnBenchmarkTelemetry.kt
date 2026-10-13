package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnTelemetry
import com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnWorkCompletion

/** Fixed, bounded metadata only; the existing benchmark export contains no PCM from this lane. */
internal class SmartTurnBenchmarkTelemetry(
    private val capture: PipelineBenchmarkCapture,
    private val prefix: String = "",
    private val record: (String, String) -> Unit,
) : SmartTurnTelemetry {
    override fun metric(name: String, value: Number?) { capture.metric(prefix + name, value) }
    override fun configuration(name: String, value: String) { capture.configuration(prefix + name, value) }
    override fun event(message: String) { record("smart_turn_shadow_status", message) }
    override fun markAt(name: String, monotonicMs: Long) { capture.markAt(prefix + name, monotonicMs) }
    override fun completion(index: Int, completion: SmartTurnWorkCompletion, postPriorityWorkerMs: Double?) {
        require(index in 0..7) // Endpoint mode admits eight observations; shadow mode admits three.
        markAt("smart_turn_${index}_request_started", completion.startedAtNanos / 1_000_000)
        markAt("smart_turn_${index}_request_completed", completion.finishedAtNanos / 1_000_000)
        completion.backendCalledAtNanos?.let { markAt("smart_turn_${index}_backend_called", it / 1_000_000) }
        completion.backendReturnedAtNanos?.let { markAt("smart_turn_${index}_backend_returned", it / 1_000_000) }
        // Bounded exact old-turn record survives benchmark.finish. No waveform/text.
        // The frozen row keeps its pending/unavailable marker if already finalized.
        record("smart_turn_shadow_${index}_work", "observation=$index turn=${completion.generation.turnId} " +
            "captureGeneration=${completion.generation.captureGeneration} revision=${completion.generation.revision} " +
            "sampleBoundary=${completion.captureSampleBoundary} cancelled=${completion.cancelled} " +
            "initializationMs=${completion.timing.initializationNanos?.div(1e6)} " +
            "frontendMs=${completion.timing.frontendNanos?.div(1e6)} inferenceMs=${completion.timing.inferenceNanos?.div(1e6)} " +
            "workerWallMs=${completion.timing.totalNanos / 1e6} postPriorityWorkerWallMs=$postPriorityWorkerMs " +
            "startedAtNanos=${completion.startedAtNanos} finishedAtNanos=${completion.finishedAtNanos} " +
            "backendCalledAtNanos=${completion.backendCalledAtNanos} backendReturnedAtNanos=${completion.backendReturnedAtNanos} " +
            "scope=old_turn_metadata_only phases_null_when_unavailable=true post_priority_wall_is_not_cpu_overlap=true")
    }
}
