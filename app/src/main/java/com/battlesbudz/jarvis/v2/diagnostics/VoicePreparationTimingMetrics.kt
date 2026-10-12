package com.battlesbudz.jarvis.v2.diagnostics

/** Same-clock wall intervals only. A speech span includes pauses, not just voiced frames. */
internal object VoicePreparationTimingMetrics {
    fun from(stages: Map<String, Long>): Map<String, Double?> {
        fun interval(start: String, end: String): LongRange? {
            val a = stages[start] ?: return null
            val b = stages[end] ?: return null
            return (a..b).takeIf { a >= 0 && b >= a }
        }
        fun elapsed(start: String, end: String): Double? = interval(start, end)?.let { (it.last - it.first).toDouble() }
        fun overlap(a: LongRange?, b: LongRange?): Double? = if (a == null || b == null) null else
            (minOf(a.last, b.last) - maxOf(a.first, b.first)).coerceAtLeast(0).toDouble()
        val preparation = interval("early_gemma_session_prepare_started", "early_gemma_fixed_prefill_completed")
        val speech = interval("speech_started", "speech_ended")
        val afterSpeech = interval("speech_ended", "turn_finished")
        return linkedMapOf(
            "early_gemma_preparation_capture_overlap_ms" to overlap(preparation, interval("microphone_ready", "recognition_finalized")),
            "early_gemma_preparation_speech_span_overlap_ms" to overlap(preparation, speech),
            "early_gemma_preparation_after_speech_ms" to overlap(preparation, afterSpeech),
            "early_gemma_preparation_completed_before_speech" to if (preparation == null || speech == null) null else
                if (preparation.last <= speech.first) 1.0 else 0.0,
            "speech_end_to_endpoint_decision_ms" to elapsed("speech_ended", "endpoint_decided"),
            "endpoint_decision_to_recognition_finalized_ms" to elapsed("endpoint_decided", "recognition_finalized"),
            "speech_end_to_answer_submit_ms" to elapsed("speech_ended", "answer_submit_enter"),
        ).also { result ->
            // The fixed request budget bounds export size and never attributes a
            // late completion to another turn. Missing backend edges stay null.
            repeat(8) { index ->
                val prefix = "smart_turn_${index}_"
                val backend = interval(prefix + "backend_called", prefix + "backend_returned")
                result[prefix + "backend_call_ms"] = elapsed(prefix + "backend_called", prefix + "backend_returned")
                result[prefix + "backend_speech_span_overlap_ms"] = overlap(backend, speech)
                result[prefix + "backend_after_speech_ms"] = overlap(backend, afterSpeech)
            }
        }
    }
}
