package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarkCaptureTest {
    private fun timedAnswer(id: String = "answer") = PipelineBenchmarkSubmission(id,
        PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE, metadata = mapOf(
            "generation_attempted" to "true", "app_event_clock" to "System.nanoTime",
            "started_monotonic_ms" to "110", "app_event_submit_enter_monotonic_ms" to "120",
            "app_event_submit_return_monotonic_ms" to "123", "app_event_first_native_callback_monotonic_ms" to "124",
            "app_event_first_native_raw_text_monotonic_ms" to "128", "app_event_first_consumer_callback_monotonic_ms" to "126",
            "app_event_first_consumer_raw_text_monotonic_ms" to "131", "app_event_native_terminal_monotonic_ms" to "200",
            "app_event_consumer_drained_monotonic_ms" to "201"))
    @Test fun singleAnswerClockAlignsCallbackConsumerGuardPcmAndPlaybackWithoutNativeClockSubtraction() {
        var now = 100L
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
        capture.markAt("first_guard_release", 140); capture.markAt("first_piper_text", 145)
        capture.markAt("first_piper_pcm", 190); capture.markAt("first_reply_audio", 205)
        capture.submission(timedAnswer())
        capture.submission(PipelineBenchmarkSubmission("caption", PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK,
            PipelineBenchmarkOutcome.COMPLETE))
        now = 220
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals("single_completed_answer_same_java_clock", result.provenance.configuration["answer_processing_alignment"])
        assertEquals(20L, result.stageOffsetsMs["answer_submit_enter"])
        assertEquals(23L, result.stageOffsetsMs["answer_submit_return"])
        val m = result.metrics()
        assertEquals(4.0, m["answer_submit_to_first_native_callback_ms"]!!, 0.0)
        assertEquals(8.0, m["answer_submit_to_first_native_raw_text_ms"]!!, 0.0)
        assertEquals(2.0, m["answer_native_callback_to_consumer_ms"]!!, 0.0)
        assertEquals(3.0, m["answer_native_raw_text_to_consumer_ms"]!!, 0.0)
        assertEquals(9.0, m["answer_consumer_raw_text_to_guard_ms"]!!, 0.0)
        assertEquals(5.0, m["guard_to_piper_first_text_ms"]!!, 0.0)
        assertEquals(45.0, m["piper_first_text_to_pcm_ms"]!!, 0.0)
        assertEquals(15.0, m["piper_first_pcm_to_answer_playback_ms"]!!, 0.0)
        assertEquals(77.0, m["answer_native_raw_text_to_playback_ms"]!!, 0.0)
        val restored = PipelineBenchmarkTurn.read(result.json())
        assertEquals(result.stageOffsetsMs, restored.stageOffsetsMs)
        assertEquals(result.metrics(), restored.metrics())
        val report = PipelineBenchmarkReport(listOf(result), 123)
        assertTrue(report.toCsv().contains("turn.answer_native_raw_text_to_playback_ms"))
        assertTrue(report.toJson().getJSONObject("metricDefinitions").getJSONObject("definitions")
            .has("piper_first_pcm_to_answer_playback_ms"))
    }
    @Test fun ambiguousFailedOrForeignClockAnswerDoesNotAcquireAnotherPassSpeechEdges() {
        for (extra in listOf(PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkPurpose.RETRY,
            PipelineBenchmarkPurpose.TOOL, PipelineBenchmarkPurpose.ANSWER)) {
            var now = 100L
            val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
            capture.submission(timedAnswer())
            capture.submission(PipelineBenchmarkSubmission("other", extra, PipelineBenchmarkOutcome.COMPLETE))
            capture.markAt("first_guard_release", 140)
            now = 220
            val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
            assertFalse(result.stageOffsetsMs.containsKey("answer_first_native_raw_text"))
            assertNull(result.metrics()["answer_consumer_raw_text_to_guard_ms"])
        }
        for (answer in listOf(timedAnswer().copy(outcome = PipelineBenchmarkOutcome.CANCELLED),
            timedAnswer().copy(metadata = timedAnswer().metadata + ("app_event_clock" to "sdk_internal")))) {
            var now = 100L
            val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
            capture.submission(answer)
            now = 220
            val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
            assertFalse(result.stageOffsetsMs.containsKey("answer_first_native_raw_text"))
            assertEquals("unavailable_incomplete_answer_or_missing_clock", result.provenance.configuration["answer_processing_alignment"])
        }
    }
    @Test fun missingOrOutOfOwnerClockEventsRemainUnavailable() {
        var now = 100L
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
        capture.submission(timedAnswer().copy(metadata = timedAnswer().metadata + mapOf(
            "app_event_submit_enter_monotonic_ms" to "99", "app_event_submit_return_monotonic_ms" to "900")))
        now = 220
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertFalse(result.stageOffsetsMs.containsKey("answer_submit_enter"))
        assertFalse(result.stageOffsetsMs.containsKey("answer_submit_return"))
        assertNull(result.metrics()["answer_submit_to_first_native_raw_text_ms"])
        assertNull(result.metrics()["piper_first_text_to_pcm_ms"])
    }

    private val provenance = PipelineBenchmarkProvenance("test", 1)
    @Test fun lateCallbacksCannotMutateFinishedTurnOrCreateAnotherReceipt() {
        var now = 100L
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
        capture.markAt("speech_ended", 90) // An interruption preceding this owner has no invented offset.
        now = 120; capture.mark("recognition_finalized")
        capture.metric("rms_dbfs", -42)
        capture.metric("invalid", Double.NaN)
        capture.configuration("capture_profile", "speech_preserving")
        now = 150
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.CANCELLED, "call"))
        capture.mark("first_reply_audio"); capture.metric("rms_dbfs", 0)
        capture.configuration("capture_profile", "late")
        assertNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(50L, result.stageOffsetsMs["turn_finished"])
        assertFalse(result.stageOffsetsMs.containsKey("speech_ended"))
        assertFalse(result.stageOffsetsMs.containsKey("first_reply_audio"))
        assertEquals(-42.0, result.observedMetrics["rms_dbfs"]!!, 0.0)
        assertNull(result.observedMetrics["invalid"])
        assertEquals("speech_preserving", result.provenance.configuration["capture_profile"])
    }
    @Test fun sharedGenerationFailureCannotBecomeACompletedVoiceSample() {
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { 100L }
        capture.noteOutcome(PipelineBenchmarkOutcome.ERROR, "native_failure")
        capture.noteOutcome(PipelineBenchmarkOutcome.COMPLETE)
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(PipelineBenchmarkOutcome.ERROR, result.outcome)
        assertEquals("native_failure", result.failureCode)
    }
    @Test fun explicitOwnerCancellationTakesPrecedenceOverChildFailure() {
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { 100L }
        capture.noteOutcome(PipelineBenchmarkOutcome.ERROR)
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, capture.finish(PipelineBenchmarkOutcome.CANCELLED)?.outcome)
    }
    @Test fun submissionIdentifiersAreDeduplicatedAndOffsetsUseSameClock() {
        val capture = PipelineBenchmarkCapture("turn", "text", 1, provenance) { 100L }
        val pass = PipelineBenchmarkSubmission("pass", PipelineBenchmarkPurpose.RETRY,
            PipelineBenchmarkOutcome.ERROR, metadata = mapOf("started_monotonic_ms" to "125"))
        capture.submission(pass); capture.submission(pass)
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.ERROR))
        assertEquals(1, result.submissions.size)
        assertEquals(25L, result.submissions.single().startedOffsetMs)
        assertNull(result.metrics()["endpoint_to_first_answer_playback_ms"])
    }
    @Test fun boundedSubmissionReceiptsRetainOmissionCount() {
        val capture = PipelineBenchmarkCapture("turn", "text", 1, provenance) { 100L }
        repeat(70) { capture.submission(PipelineBenchmarkSubmission("pass$it", PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkOutcome.COMPLETE)) }
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(64, result.submissions.size)
        assertEquals(6.0, result.observedMetrics["submissions_omitted"]!!, 0.0)
        assertEquals("unavailable_omitted_submissions", result.provenance.configuration["answer_processing_alignment"])
    }
}
