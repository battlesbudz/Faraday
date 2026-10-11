package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject

/** Exported definitions version independently from the backwards-compatible record schema. */
object PipelineBenchmarkDefinitions {
    val notes = linkedMapOf(
        "first_guard_release" to "Stage offset when the first checked voice fragment leaves the repetition/policy guard; precedes queued delivery-fence admission and Piper collection, not acoustic playback.",
        "speech_end_to_first_answer_playback_ms" to "Last detected user speech to first answer playback-head advancement. Excludes filler; acoustic onset unavailable.",
        "native_first_audio_to_speech_lower_ms" to "Lower clock-alignment bound: validated first native PCM admission to first answer playback-head event. Includes remaining capture; historical pre-roll is not backdated. Excludes filler; acoustic onset unmeasured.",
        "native_first_audio_to_speech_upper_ms" to "Upper clock-alignment bound for the same first-native-audio to answer-playback observation; not an acoustic timing accuracy bound.",
        "native_audio_first_step_from_admission_lower_ms" to "Lower elapsed bound from native PCM admission to first validated encoder graph-step completion; an append may only buffer and does not count as a step.",
        "native_audio_first_step_from_admission_upper_ms" to "Upper elapsed bound for first validated encoder-step completion, including clock alignment uncertainty.",
        "native_audio_endpoint_from_admission_lower_ms" to "Lower elapsed bound from native admission to the final accepted endpoint proposal, before recognizer finalization. Negative values mean admission was later than endpoint.",
        "native_audio_endpoint_from_admission_upper_ms" to "Upper elapsed bound for the accepted endpoint proposal relative to native admission.",
        "native_audio_steps_before_endpoint_lower_bound" to "Proven minimum validated step commits strictly before endpoint; a final snapshot spanning endpoint cannot establish an exact past count.",
        "native_audio_steps_before_endpoint_exact" to "Exact pre-endpoint count only when all observed steps precede endpoint or the first step is at/after it; otherwise unavailable.",
        "native_audio_rows_before_endpoint_exact" to "Validated post-adapter row count corresponding to an exact pre-endpoint step count; otherwise unavailable.",
        "native_audio_retained_pcm_samples" to "Complete final candidate's native-accepted 16 kHz mono PCM sample count, including retained pre-roll and trailing silence.",
        "native_audio_retained_preroll_samples" to "Actually retained onset buffer, including the confirming frame; not inferred from timestamp subtraction.",
        "native_audio_completed_steps" to "Total validated native encoder graph-step commits at final seal; includes any seal-only step.",
        "native_audio_validated_rows" to "Total validated post-adapter audio rows at final seal; excludes runtime end-of-audio marker.",
        "native_audio_clock_mapping_available" to "1 only with reviewed AOSP API30–36 same-rate source-family assumption and valid bounded calibration; installed/OEM binary not attested. 0 means mapped timing is unavailable.",
        "native_audio_alignment_uncertainty_ns" to "Ceiling of half the final Java-minus-native clock-offset interval width. Raw clocks/offsets are not exported.",
        "native_audio_checked_close_ms" to "Checked encoder close call to successful return; failed/quarantined owners cannot publish a final candidate timing record.",
        "native_audio_artifact_prepare_started" to "Stage offset before validating or reconstructing the pinned native encoder artifact; distinct from first PCM admission and inference.",
        "native_audio_artifact_prepare_finished" to "Stage offset after the verified artifact lease is registered with the turn; missing on preparation failure. The span includes cold reconstruction when required.",
        "endpoint_to_first_answer_text_ms" to "Recognition finalized to first answer text event. Includes preparation/model work after endpoint.",
        "ttft_ms" to "App inference measurement start to first nonempty text callback; includes lazy conversation preparation. Callback chunks may contain multiple tokens. Use submit_to_first_raw_text_ms for the separate send-relative phase.",
        "conversation_prepare_ms" to "Observed beginTurn child lookup/create wall time, including synchronous fixed-policy prefill when not prepared during capture; excludes earlier preparation.",
        "pre_submit_ms" to "App inference measurement start to immediately before SDK send; includes lazy conversation preparation and app setup.",
        "submit_to_first_callback_ms" to "Immediately before SDK send to first native callback, including empty/control callbacks; independent of earlier preparation.",
        "submit_to_first_raw_text_ms" to "Immediately before SDK send to first nonempty native text callback, including hidden/control text; not first visible answer.",
        "early_conversation_reused" to "1 when beginTurn consumes an unused conversation prepared earlier in this owner; 0 for lazy creation or other reuse; unavailable on raw-session paths.",
        "early_gemma_session_with_fixed_prefill_ms" to "Synchronous conversation creation plus fixed system-policy prefill and checked idle after microphone start. No history, retrieved memory, user audio or LLM audio embeddings are prefilled here.",
        "native_encoder_early_prefill_wait_ms" to "Retained encoder worker waiting for early fixed-policy preparation before native encoder creation; separate from encoding work.",
        "native_encoder_busy" to "Most recently observed retained-worker append/seal API busy state (1 or 0); final snapshot is normally idle, not utilization or peak concurrency.",
        "native_encoder_append_calls" to "Retained-worker append API calls across all candidates, including failed/buffer-only calls; not native graph steps.",
        "native_encoder_append_cumulative_ms" to "Cumulative wall time inside retained-worker native append API calls across candidates; excludes gate wait, artifact preparation and LLM multimodal prefill.",
        "native_encoder_append_max_ms" to "Maximum single retained-worker native append API wall duration; not an individual graph-step duration.",
        "native_encoder_seal_work_ms" to "Cumulative retained-worker native seal API wall time; separate from append work and LLM multimodal prefill.",
        "native_encoder_queued_pcm_ms" to "Most recent queued retained PCM bytes converted at 16 kHz mono PCM16; excludes the active dequeued chunk and is not a native feature backlog.",
        "native_encoder_peak_queued_pcm_ms" to "Peak queued retained PCM across all candidates, converted at 16 kHz mono PCM16; excludes active chunk and does not measure graph steps.",
        "native_first_output_token_ms" to "Native runtime first-output-token timing when exposed; requires telemetry source.",
        "exact_decode_tokens_per_second" to "(Native output token count minus one) / native first-token-to-completion seconds. Unavailable without both native counts and timing.",
        "estimated_decode_tokens_per_second" to "Visible character-derived token estimate over observed decode duration; not native token throughput.",
        "process_cpu_work_ms" to "Difference in Android process elapsed CPU time at turn boundaries. Process-wide, includes concurrent work; not model-only CPU or energy.",
        "process_pss_kib_before" to "Process proportional set size sampled at start; not model allocation or peak memory.",
        "process_pss_kib_after" to "Process proportional set size sampled at finish; not peak memory.",
        "resource_sampling_before_ms" to "Observed monotonic duration of bounded resource observation at turn start.",
        "resource_sampling_after_ms" to "Observed monotonic duration of bounded resource observation at turn finish.",
        "asr_wer" to "Word edit errors / user-verified reference words. Unavailable without a verified reference.",
        "asr_cer" to "Character edit errors / user-verified reference characters. Unavailable without a verified reference.",
        "tts_playback_starvation_ms" to "Observed gap while the playback queue had no supplied answer audio. Does not measure audible silence directly."
    )
    fun statuses(metrics: Map<String, Double?>) = JSONObject().also { result ->
        metrics.forEach { (name, value) ->
            val status = when { value == null -> "unavailable";
                name.startsWith("native_first_audio_to_speech_") -> "playback_proxy_clock_bound";
                name.startsWith("estimated_") -> "estimated";
                name.startsWith("exact_") || name == "native_first_output_token_ms" -> "native";
                name.contains("playback") -> "playback_proxy"; else -> "observed" }
            result.put(name, benchmarkJson("status" to status,
                "reason" to if (value == null) "not_observed_in_this_attempt_or_not_exposed_by_runtime" else null))
        }
    }
    fun json() = JSONObject().put("version", 4).put("definitions", JSONObject(notes))
        .put("missingValue", "unavailable_not_observed_or_not_supported; never zero-filled")
        .put("sampling", "resource snapshots only at turn boundaries; no periodic per-token polling")
        .put("privacy", "metrics-only by default; no prompt, transcript, reference text or PCM")
}

/** Status is derived on export; duplicating it in each disk record wastes bounded storage. */
internal fun JSONObject.withoutDerivedMetricStatus(): JSONObject {
    remove("metricStatus")
    optJSONArray("submissions")?.let { submissions ->
        for (index in 0 until submissions.length()) submissions.getJSONObject(index).remove("metricStatus")
    }
    return this
}
