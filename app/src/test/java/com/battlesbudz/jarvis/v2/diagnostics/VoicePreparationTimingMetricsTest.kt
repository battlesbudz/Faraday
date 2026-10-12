package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class VoicePreparationTimingMetricsTest {
    private val stages = mapOf(
        "microphone_ready" to 10L,
        "speech_started" to 50L,
        "speech_ended" to 250L,
        "endpoint_decided" to 600L,
        "recognition_finalized" to 630L,
        "turn_finished" to 1000L,
        "early_gemma_session_prepare_started" to 20L,
        "early_gemma_fixed_prefill_completed" to 300L,
        "answer_submit_enter" to 700L,
        "smart_turn_0_backend_called" to 190L,
        "smart_turn_0_backend_returned" to 280L,
    )
    @Test fun presentationKeysPreserveEveryMeasuredAndUnavailableTimingField() {
        val metrics = VoicePreparationTimingMetrics.from(stages)
        assertEquals(31, VoicePreparationTimingMetrics.keys.size)
        assertEquals(metrics.keys, VoicePreparationTimingMetrics.keys)
        assertEquals(VoicePreparationTimingMetrics.from(emptyMap()).keys, VoicePreparationTimingMetrics.keys)
        assertFalse("Existing pipeline measurements are not collapsed", "tts_load_ms" in VoicePreparationTimingMetrics.keys)
        val turn = PipelineBenchmarkTurn("turn", channel = "voice", capturedAtEpochMs = 1L,
            provenance = PipelineBenchmarkProvenance("test", 1), outcome = PipelineBenchmarkOutcome.COMPLETE,
            stageOffsetsMs = stages)
        val all = turn.metrics()
        val details = all.filterKeys { it in VoicePreparationTimingMetrics.keys }
        val ordinary = all.filterKeys { it !in VoicePreparationTimingMetrics.keys }
        assertEquals(metrics, details)
        assertTrue(ordinary.containsKey("tts_load_ms"))
        assertTrue(details.keys.intersect(ordinary.keys).isEmpty())
        assertEquals(all, ordinary + details)
    }
    @Test fun separatesCaptureSpeechSpanAndPostSpeechRemainder() {
        val metrics = VoicePreparationTimingMetrics.from(stages)
        assertEquals(280.0, metrics["early_gemma_preparation_capture_overlap_ms"])
        assertEquals(200.0, metrics["early_gemma_preparation_speech_span_overlap_ms"])
        assertEquals(50.0, metrics["early_gemma_preparation_after_speech_ms"])
        assertEquals(0.0, metrics["early_gemma_preparation_completed_before_speech"])
        assertEquals(350.0, metrics["speech_end_to_endpoint_decision_ms"])
        assertEquals(30.0, metrics["endpoint_decision_to_recognition_finalized_ms"])
        assertEquals(450.0, metrics["speech_end_to_answer_submit_ms"])
        assertEquals(90.0, metrics["smart_turn_0_backend_call_ms"])
        assertEquals(60.0, metrics["smart_turn_0_backend_speech_span_overlap_ms"])
        assertEquals(30.0, metrics["smart_turn_0_backend_after_speech_ms"])
        assertNull(metrics["smart_turn_1_backend_call_ms"])
    }
    @Test fun preparationBeforeSpeechIsUsefulButDoesNotBecomeSpeechOverlap() {
        val metrics = VoicePreparationTimingMetrics.from(stages + ("early_gemma_fixed_prefill_completed" to 40L))
        assertEquals(20.0, metrics["early_gemma_preparation_capture_overlap_ms"])
        assertEquals(0.0, metrics["early_gemma_preparation_speech_span_overlap_ms"])
        assertEquals(0.0, metrics["early_gemma_preparation_after_speech_ms"])
        assertEquals(1.0, metrics["early_gemma_preparation_completed_before_speech"])
    }
    @Test fun missingFailedReversedAndSpeculativeEdgesStayUnavailable() {
        val missing = VoicePreparationTimingMetrics.from(stages - "early_gemma_fixed_prefill_completed" - "speech_started")
        assertNull(missing["early_gemma_preparation_capture_overlap_ms"])
        assertNull(missing["early_gemma_preparation_speech_span_overlap_ms"])
        assertNull(missing["early_gemma_preparation_completed_before_speech"])
        val reversed = VoicePreparationTimingMetrics.from(stages + mapOf(
            "early_gemma_fixed_prefill_completed" to 19L,
            "smart_turn_0_backend_returned" to 100L,
            "endpoint_decided" to 200L,
            "answer_submit_enter" to 240L,
        ))
        assertNull(reversed["early_gemma_preparation_speech_span_overlap_ms"])
        assertNull(reversed["smart_turn_0_backend_call_ms"])
        assertNull(reversed["smart_turn_0_backend_speech_span_overlap_ms"])
        assertNull(reversed["speech_end_to_endpoint_decision_ms"])
        assertNull(reversed["speech_end_to_answer_submit_ms"])
        assertTrue(VoicePreparationTimingMetrics.from(emptyMap()).values.all { it == null })
    }
    @Test fun onlyFinalPauseInferenceHasZeroSpeechSpanOverlapAndCompletePostSpeechWork() {
        val metrics = VoicePreparationTimingMetrics.from(stages + mapOf(
            "smart_turn_0_backend_called" to 610L, "smart_turn_0_backend_returned" to 620L))
        assertEquals(0.0, metrics["smart_turn_0_backend_speech_span_overlap_ms"])
        assertEquals(10.0, metrics["smart_turn_0_backend_after_speech_ms"])
    }
    @Test fun jsonCsvAndCopiedTurnMetricsRetainRelativeEvidence() {
        val turn = PipelineBenchmarkTurn("turn", channel = "voice", capturedAtEpochMs = 1L,
            provenance = PipelineBenchmarkProvenance("test", 1), outcome = PipelineBenchmarkOutcome.COMPLETE,
            stageOffsetsMs = stages)
        val restored = PipelineBenchmarkTurn.read(turn.json())
        assertEquals(turn.metrics(), restored.metrics())
        val report = PipelineBenchmarkReport(listOf(turn), 1)
        assertTrue(report.toCsv().contains("early_gemma_preparation_speech_span_overlap_ms"))
        assertTrue(report.toJson().toString().contains("backend_speech_span_overlap_ms"))
        assertTrue(PipelineBenchmarkDefinitions.notes.getValue("early_gemma_preparation_speech_span_overlap_ms").contains("pauses"))
    }
}
