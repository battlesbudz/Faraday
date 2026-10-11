package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the owner at submission creation, even if a later turn replaces the engine's observer. */
internal class NativeInferenceBenchmark(
    private val modelId: String?,
    private val purpose: PipelineBenchmarkPurpose,
    private val warmState: PipelineBenchmarkWarmState,
    private val mode: String,
    private val audioBytes: Int = 0,
    private val imageBytes: Int = 0,
    private val initializationMs: Long? = null,
    private val sink: (PipelineBenchmarkSubmission) -> Unit = {},
    val measurement: NativeInferenceMeasurement = NativeInferenceMeasurement()
) {
    private val reported = AtomicBoolean(false)

    fun finish(
        outcome: PipelineBenchmarkOutcome,
        outputChars: Int,
        promptChars: Int,
        streamEvents: Int,
        prefillMs: Long? = null,
        prefillChunks: Int? = null,
        error: Throwable? = null,
        generationAttempted: Boolean = true,
        nativeTokens: NativeTokenTelemetry? = null
    ) {
        if (!reported.compareAndSet(false, true)) return
        val measured = measurement.snapshot(outputChars)
        val record = PipelineBenchmarkSubmission(
            submissionId = UUID.randomUUID().toString(),
            purpose = purpose,
            outcome = outcome,
            warmState = warmState,
            modelId = modelId,
            firstTokenMs = measured.firstRawTextMs.takeIf { generationAttempted },
            firstCallbackMs = measured.firstCallbackMs.takeIf { generationAttempted },
            totalGenerationMs = measured.durationMs.takeIf { generationAttempted },
            prefillMs = prefillMs,
            nativeSubmitMs = measured.nativeSubmitMs,
            promptCharacters = promptChars,
            exactInputTokens = nativeTokens?.inputTokens,
            exactOutputTokens = nativeTokens?.outputTokens,
            tokenTelemetrySource = if (nativeTokens != null) "litertlm_0.16_conversation_getBenchmarkInfo_last_decode"
                else "unavailable_for_this_inference_path_or_outcome",
            estimatedOutputTokens = measured.estimatedOutputTokens.takeIf { generationAttempted },
            estimatedDecodeTokensPerSecond = measured.estimatedRawDecodeTokensPerSecond.takeIf { generationAttempted },
            streamEvents = streamEvents,
            measurements = linkedMapOf(
                "conversation_prepare_ms" to measured.conversationPreparationMs?.toDouble(),
                "pre_submit_ms" to measured.preSubmitMs?.toDouble(),
                "submit_to_first_callback_ms" to measured.submitToFirstCallbackMs?.toDouble(),
                "submit_to_first_raw_text_ms" to measured.submitToFirstRawTextMs?.toDouble(),
                "early_conversation_reused" to measured.reusedEarlyConversation?.let { if (it) 1.0 else 0.0 },
                "first_visible_text_ms" to measured.firstVisibleTextMs?.toDouble(),
                "native_terminal_ms" to measured.durationMs.toDouble().takeIf { generationAttempted },
                "consumer_return_ms" to measured.returnDurationMs.toDouble().takeIf { generationAttempted },
                "callback_count" to measured.callbackCount.toDouble(),
                "callback_interval_samples" to measured.callbackIntervalsRetained.toDouble(),
                "callback_interval_samples_dropped" to measured.callbackIntervalsDropped.toDouble(),
                "callback_interval_p50_ms" to measured.callbackIntervalP50Ms,
                "callback_interval_p95_ms" to measured.callbackIntervalP95Ms,
                "callback_interval_max_ms" to measured.callbackIntervalMaxMs,
                "visible_output_characters" to outputChars.toDouble(),
                "audio_bytes" to audioBytes.toDouble(),
                "image_bytes" to imageBytes.toDouble(),
                "engine_initialization_ms" to initializationMs?.toDouble(),
                "prefill_chunks" to prefillChunks?.toDouble(),
                "native_ttft_ms" to nativeTokens?.timeToFirstTokenMs,
                "native_prefill_tokens_per_second" to nativeTokens?.prefillTokensPerSecond,
                "native_decode_tokens_per_second" to nativeTokens?.decodeTokensPerSecond,
                "native_telemetry_read_ms" to nativeTokens?.readMs
            ),
            metadata = buildMap {
                put("input_mode", mode)
                put("generation_attempted", generationAttempted.toString())
                put("clock", "System.nanoTime")
                put("started_monotonic_ms", (measurement.beganAtNanos / 1_000_000).toString())
                put("estimated_tokens_policy", "ceil_visible_output_characters_div_4")
                put("estimated_decode_policy", "visible_char4_estimate_over_first_raw_text_to_native_terminal")
                put("pre_submit_scope", "measurement_start_to_send_includes_lazy_conversation_preparation")
                put("conversation_prepare_scope", "beginTurn_child_lookup_or_create_including_fixed_prefill_if_not_early")
                put("native_prefill_tokens_scope", "last_user_submission_excludes_separately_prefilled_system_policy")
                put("ttft_policy", "first_nonempty_native_text_callback_including_hidden_or_control_text")
                put("callback_intervals_policy", "native_callback_chunks_nearest_rank_prefix_cap_4096")
                put("warm_state_policy", "engine_instance_first_submission_not_disk_cache")
                put("initialization_scope", "engine_initialize_wall_including_lock_wait_excluding_engine_constructor")
                put("native_tokens_scope", if (nativeTokens != null) "completed_conversation_last_prefill_and_decode"
                    else "not_exposed_raw_session_or_incomplete_submission")
                put("native_ttft_clock", "sdk_internal_relative_duration_not_callback_clock")
                error?.let { put("error_type", it.javaClass.simpleName) }
            }
        )
        // A diagnostics consumer must never break generation or cancellation cleanup.
        runCatching { sink(record) }
    }
}
