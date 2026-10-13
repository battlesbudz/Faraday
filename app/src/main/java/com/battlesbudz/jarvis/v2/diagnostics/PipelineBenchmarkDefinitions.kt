package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject

/** Exported definitions version independently from the backwards-compatible record schema. */
object PipelineBenchmarkDefinitions {
    val notes = linkedMapOf(
        "speech_started" to "First retained speech-confirming frame capture timestamp; not microphone-measured acoustic onset. Same Java monotonic clock as preparation.",
        "endpoint_decided" to "Final accepted endpoint proposal observed by the capture owner, before recognizer finalization; not last speech.",
        "early_gemma_preparation_capture_overlap_ms" to "Intersection of synchronous session/fixed-policy preparation with microphone-ready through recognition-finalized wall interval. Capture overlap alone does not prove speech overlap.",
        "early_gemma_preparation_speech_span_overlap_ms" to "Intersection of session/fixed-policy preparation with first-to-last detected speech wall span, including internal pauses. Not voiced-frame-only overlap, GPU time or audio-LLM prefill.",
        "early_gemma_preparation_after_speech_ms" to "Part of observed session/fixed-policy preparation after last detected speech and before turn finish. Zero only when both intervals exist and do not overlap.",
        "early_gemma_preparation_completed_before_speech" to "1 when observed fixed-policy preparation finished by first detected speech, 0 when later. Unavailable without complete preparation and speech boundaries.",
        "speech_end_to_endpoint_decision_ms" to "Last detected speech capture time to accepted endpoint proposal. Includes silence threshold, backlog, model advice and scheduling; not isolated endpoint-inference compute.",
        "endpoint_decision_to_recognition_finalized_ms" to "Accepted endpoint proposal to recognition stage completion. Includes finalization, capture drain and coroutine handoff.",
        "speech_end_to_answer_submit_ms" to "Last detected speech to unique completed answer's SDK send entry; unavailable for earlier speculative submission or ambiguous attempts.",
        "smart_turn_first_frame_observed" to "First live-capture frame received by the Smart Turn observer on the Java clock. Observation does not mean an inference ran.",
        "smart_turn_first_possible_speech_frame_captured" to "Capture timestamp of first hasSpeech-and-possibleSpeech frame seen by Smart Turn; conservative classification can include uncertain raw frames.",
        "smart_turn_first_possible_speech_frame_observed" to "Wall timestamp when that first possible-speech frame reached Smart Turn; compare capture timestamp for delivery delay, not inference timing.",
        "native_audio_last_step_from_admission_lower_ms" to "Lower clock-alignment bound from native PCM admission to last validated encoder graph-step commit. Includes input/capture gaps; not cumulative encoder compute.",
        "native_audio_last_step_from_admission_upper_ms" to "Upper clock-alignment bound from native PCM admission to last validated encoder graph-step commit. Includes input/capture gaps; not cumulative encoder compute.",
        "native_audio_first_to_last_step_elapsed_lower_ms" to "Lower clock-alignment bound from first to last validated encoder graph-step commit. Includes time between calls/capture; not summed or per-step compute.",
        "native_audio_first_to_last_step_elapsed_upper_ms" to "Upper clock-alignment bound from first to last validated encoder graph-step commit. Includes time between calls/capture; not summed or per-step compute.",
        "native_audio_endpoint_to_last_step_lower_ms" to "Lower clock-alignment bound from accepted endpoint decision to last validated encoder graph-step commit. Signed: negative means the final step preceded endpoint; not encoder compute.",
        "native_audio_endpoint_to_last_step_upper_ms" to "Upper clock-alignment bound from accepted endpoint decision to last validated encoder graph-step commit. Signed: negative means the final step preceded endpoint; not encoder compute.",
        "native_audio_endpoint_to_seal_commit_lower_ms" to "Lower clock-alignment bound from accepted endpoint decision to native seal-commit boundary. Signed elapsed time includes queued/input/finalization work; not isolated seal compute.",
        "native_audio_endpoint_to_seal_commit_upper_ms" to "Upper clock-alignment bound from accepted endpoint decision to native seal-commit boundary. Signed elapsed time includes queued/input/finalization work; not isolated seal compute.",
        "submit_return_ms" to "App inference measurement start to SDK send return/throw; a callback may precede return. Not native prefill or queue time.",
        "first_consumer_callback_ms" to "App inference measurement start to first FIFO native-channel receive, including empty/control callbacks; before policy guard and delivery fence.",
        "first_consumer_raw_text_ms" to "App inference measurement start to first nonempty native text received by the FIFO consumer, including hidden/control text; before policy guard.",
        "callback_to_consumer_ms" to "First any native callback to its FIFO consumer receive on the same Java monotonic clock.",
        "raw_text_to_consumer_ms" to "First nonempty native callback to its corresponding FIFO consumer receive; differs from any-callback queue delay.",
        "first_callback_before_submit_return" to "1 when first callback occurs before or at SDK send return; 0 when later; unavailable if either edge is missing.",
        "native_terminal_ms" to "App inference measurement start to observed terminal callback or synchronous submission exception; unavailable when neither is observed.",
        "consumer_drained_ms" to "App inference measurement start to successful native-channel drain and raw-session text flush, before checked native cleanup.",
        "terminal_to_consumer_drained_ms" to "Observed terminal edge to successful consumer drain; absent/reversed edges remain unavailable.",
        "consumer_drained_to_return_ms" to "Successful consumer drain to benchmark snapshot after checked native cleanup and optional telemetry read; not isolated native-drain time.",
        "first_visible_text_ms" to "Legacy adapter-consumer text edge; Conversation still applies assistant filtering, repetition/policy guard and delivery fence afterwards.",
        "first_piper_text" to "Turn-stage offset when Piper collects first nonblank answer text after the queued delivery/memory fences; before text batching and synthesis.",
        "first_piper_pcm" to "Turn-stage offset after answer synthesis first returns nonempty validated PCM; before PCM queue admission and playback. No acoustic onset measurement.",
        "answer_processing_alignment" to "Answer-native turn stages require exactly one completed ANSWER submission, same Java clock and no omitted submissions. Draft/retry/tool/multiple-answer attribution is unavailable; transcription-only passes remain independent.",
        "answer_submit_to_first_native_callback_ms" to "Same-clock SDK send entry to first any native callback for the uniquely attributable completed answer.",
        "answer_submit_to_first_native_raw_text_ms" to "Same-clock SDK send entry to first nonempty native text callback for the uniquely attributable completed answer.",
        "answer_native_callback_to_consumer_ms" to "First any native callback to its FIFO consumer event for the uniquely attributable completed answer.",
        "answer_native_raw_text_to_consumer_ms" to "First nonempty native text callback to its FIFO consumer event for the uniquely attributable completed answer.",
        "answer_consumer_raw_text_to_guard_ms" to "First consumed raw answer text to first repetition/policy guard release; includes filtering and clause accumulation, before queued delivery fence.",
        "guard_to_piper_first_text_ms" to "First guard release to Piper first nonblank text collection; includes queued delivery, memory-fence admission and coroutine scheduling.",
        "piper_first_text_to_pcm_ms" to "Piper first text collection to first validated answer PCM; includes batching, waiting for the TTS owner and synthesis.",
        "piper_first_pcm_to_answer_playback_ms" to "First validated answer PCM to first answer playback-head advancement; queue/buffering/scheduling proxy, not acoustic latency.",
        "answer_native_raw_text_to_piper_pcm_ms" to "First native raw-text callback to first answer PCM only with unambiguous completed-answer attribution; no SDK-clock subtraction.",
        "answer_native_raw_text_to_playback_ms" to "First native raw-text callback to first answer playback-head advancement only with unambiguous completed-answer attribution; not acoustic onset.",
        "native_ttft_ms" to "Legacy SDK value was a first-recorded-prefill plus average first-recorded-decode proxy, not measured current-turn TTFT. New records leave this field unavailable.",
        "sdk_first_prefill_decode_ttft_proxy_ms" to "SDK first recorded prefill duration plus average token duration of first recorded decode. Fixed system prefill can supply its origin. Not current-turn or measured first-token latency.",
        "derived_native_last_prefill_executor_ms" to "Derived 1000 * last completed prefill token count / its SDK rate after successful checked idle. Executor.Prefill only; excludes task queue, tokenization, multimodal encoding and input combining.",
        "derived_native_last_decode_loop_ms" to "Derived 1000 * last completed decode token count / its SDK rate after successful checked idle. Whole native decode loop/setup/callbacks; not post-first-token or GPU-only work.",
        "native_prefill_tokens_per_second" to "SDK last completed executor-prefill count/duration after successful checked idle; excludes separate earlier system-policy prefill.",
        "native_decode_tokens_per_second" to "SDK last completed whole-decode count/duration after successful checked idle; includes first token and native setup/callback work.",
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
    ).apply {
        repeat(8) { index ->
            val prefix = "smart_turn_${index}_"
            put(prefix + "request_accepted", "Turn-stage offset after the bounded Smart Turn offer confirms admission; model admission occurs only after a pause, not every voiced frame.")
            put(prefix + "source_captured", "Turn-stage offset of the retained PCM snapshot boundary for this observation.")
            put(prefix + "request_started", "Turn-stage offset at worker-request admission; includes queue/init before backend call.")
            put(prefix + "request_completed", "Turn-stage offset after request worker completion, including cancelled/stale work; not current endpoint authority.")
            put(prefix + "backend_called", "Turn-stage offset immediately before actual backend infer call, after initialization; unavailable if never called.")
            put(prefix + "backend_returned", "Turn-stage offset when backend infer returned or threw; includes frontend and ORT inference, not isolated CPU busy time.")
            put(prefix + "backend_call_ms", "Actual backend infer-call wall duration including frontend, inference and failure/cancellation return; excludes request queue/initialization.")
            put(prefix + "backend_speech_span_overlap_ms", "Backend-call wall intersection with first-to-last detected speech span, including internal pauses. Does not claim inference during continuous voiced audio or endpoint knowledge before final silence.")
            put(prefix + "backend_after_speech_ms", "Actual backend-call wall interval after last detected speech; includes cancelled/stale work and is not proof its endpoint verdict was accepted.")
        }
    }
    fun statuses(metrics: Map<String, Double?>) = JSONObject().also { result ->
        metrics.forEach { (name, value) ->
            val status = when { value == null -> "unavailable";
                name.startsWith("native_first_audio_to_speech_") -> "playback_proxy_clock_bound";
                name == "sdk_first_prefill_decode_ttft_proxy_ms" || name == "native_ttft_ms" -> "sdk_proxy";
                name.startsWith("derived_native_") -> "derived_native";
                name.startsWith("native_audio_") && (name.endsWith("_lower_ms") || name.endsWith("_upper_ms")) -> "observed_clock_bound";
                name.startsWith("estimated_") -> "estimated";
                name.startsWith("exact_") || name == "native_first_output_token_ms" -> "native";
                name.contains("playback") -> "playback_proxy"; else -> "observed" }
            result.put(name, benchmarkJson("status" to status,
                "reason" to if (value == null) "not_observed_in_this_attempt_or_not_exposed_by_runtime" else null))
        }
    }
    fun json() = JSONObject().put("version", 6).put("definitions", JSONObject(notes))
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
