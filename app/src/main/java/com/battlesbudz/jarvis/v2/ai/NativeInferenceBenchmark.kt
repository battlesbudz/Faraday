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
        // A cancellation during/after the safe read invalidates current-submission attribution too.
        val acceptedNative = nativeTokens.takeIf { outcome == PipelineBenchmarkOutcome.COMPLETE && generationAttempted }
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
            exactInputTokens = acceptedNative?.inputTokens,
            exactOutputTokens = acceptedNative?.outputTokens,
            tokenTelemetrySource = if (acceptedNative != null) "litertlm_0.16_conversation_getBenchmarkInfo_successful_idle_mixed_scopes"
                else "unavailable_for_this_inference_path_or_outcome",
            estimatedOutputTokens = measured.estimatedOutputTokens.takeIf { generationAttempted },
            estimatedDecodeTokensPerSecond = measured.estimatedRawDecodeTokensPerSecond.takeIf { generationAttempted },
            streamEvents = streamEvents,
            measurements = linkedMapOf(
                "conversation_prepare_ms" to measured.conversationPreparationMs?.toDouble(),
                "pre_submit_ms" to measured.preSubmitMs?.toDouble(),
                "submit_return_ms" to measured.submitReturnMs?.toDouble(),
                "first_consumer_callback_ms" to measured.firstConsumerMs?.toDouble(),
                "first_consumer_raw_text_ms" to measured.firstConsumerTextMs?.toDouble(),
                "callback_to_consumer_ms" to measured.callbackToConsumerMs?.toDouble(),
                "raw_text_to_consumer_ms" to measured.rawTextToConsumerMs?.toDouble(),
                "consumer_drained_ms" to measured.consumerDrainedMs?.toDouble(),
                "terminal_to_consumer_drained_ms" to measured.terminalToConsumerDrainedMs?.toDouble(),
                "consumer_drained_to_return_ms" to measured.consumerDrainedToReturnMs?.toDouble(),
                "first_callback_before_submit_return" to measured.firstCallbackBeforeSubmitReturn?.let { if (it) 1.0 else 0.0 },
                "submit_to_first_callback_ms" to measured.submitToFirstCallbackMs?.toDouble(),
                "submit_to_first_raw_text_ms" to measured.submitToFirstRawTextMs?.toDouble(),
                "early_conversation_reused" to measured.reusedEarlyConversation?.let { if (it) 1.0 else 0.0 },
                "first_visible_text_ms" to measured.firstVisibleTextMs?.toDouble(),
                "native_terminal_ms" to measured.nativeTerminalMs?.toDouble().takeIf { generationAttempted },
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
                // Old records remain readable, but new records never label the SDK proxy as measured TTFT.
                "native_ttft_ms" to null,
                "sdk_first_prefill_decode_ttft_proxy_ms" to acceptedNative?.sdkFirstPrefillDecodeTtftProxyMs,
                "derived_native_last_prefill_executor_ms" to acceptedNative?.derivedLastPrefillExecutorMs,
                "derived_native_last_decode_loop_ms" to acceptedNative?.derivedLastDecodeLoopMs,
                "native_prefill_tokens_per_second" to acceptedNative?.prefillTokensPerSecond,
                "native_decode_tokens_per_second" to acceptedNative?.decodeTokensPerSecond,
                "native_telemetry_read_ms" to acceptedNative?.readMs
            ),
            metadata = buildMap {
                put("input_mode", mode)
                put("generation_attempted", generationAttempted.toString())
                put("clock", "System.nanoTime")
                put("started_monotonic_ms", (measurement.beganAtNanos / 1_000_000).toString())
                measured.eventClocksMs.forEach { (name, at) -> put("app_event_${name}_monotonic_ms", at.toString()) }
                put("app_event_clock", "System.nanoTime")
                put("consumer_scope", "fifo_native_channel_before_assistant_filter_policy_guard_and_delivery_fence")
                put("consumer_return_scope", "benchmark_snapshot_after_checked_native_cleanup_and_optional_telemetry_read")
                put("estimated_tokens_policy", "ceil_visible_output_characters_div_4")
                put("estimated_decode_policy", "visible_char4_estimate_over_first_raw_text_to_native_terminal")
                put("pre_submit_scope", "measurement_start_to_send_includes_lazy_conversation_preparation")
                put("conversation_prepare_scope", "beginTurn_child_lookup_or_create_including_fixed_prefill_if_not_early")
                put("native_prefill_tokens_scope", "last_completed_executor_prefill_after_successful_current_submission_excludes_prior_system_prefill")
                put("native_prefill_duration_scope", "derived_count_div_rate_executor_prefill_excludes_queue_tokenization_multimodal_encoding_and_input_combine")
                put("native_decode_duration_scope", "derived_count_div_rate_whole_native_decode_loop_including_setup_and_callbacks_not_gpu_only")
                put("native_unavailable_phases", "queue_wait,tokenization,multimodal_conversion,separate_audio_prefill,first_output_token_timestamp")
                put("ttft_policy", "first_nonempty_native_text_callback_including_hidden_or_control_text")
                put("callback_intervals_policy", "native_callback_chunks_nearest_rank_prefix_cap_4096")
                put("warm_state_policy", "engine_instance_first_submission_not_disk_cache")
                put("initialization_scope", "engine_initialize_wall_including_lock_wait_excluding_engine_constructor")
                put("native_tokens_scope", if (acceptedNative != null) "completed_conversation_last_prefill_and_decode"
                    else "not_exposed_raw_session_or_incomplete_submission")
                put("native_ttft_clock", "sdk_first_recorded_prefill_plus_first_recorded_decode_average_token_duration_not_current_turn_ttft")
                put("native_ttft_legacy_field", "null_new_records_use_explicit_sdk_proxy_not_current_submission_ttft")
                error?.let { put("error_type", it.javaClass.simpleName) }
            }
        )
        // A diagnostics consumer must never break generation or cancellation cleanup.
        runCatching { sink(record) }
    }
}
