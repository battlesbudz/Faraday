package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import org.junit.Assert.*
import org.junit.Test

class NativeInferenceMeasurementTest {
    @Test fun fifoCallbackConsumerSubmitReturnAndDrainEdgesKeepDistinctOrigins() {
        var clock = 100_250_000L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 110_250_000L; measurement.submissionStarted(clock)
        clock = 112_250_000L; measurement.callback(false)
        clock = 116_250_000L; measurement.callback(true)
        clock = 120_250_000L; measurement.terminal()
        clock = 125_250_000L; measurement.submitted(110_250_000L)
        clock = 126_250_000L; measurement.consumed(false)
        clock = 130_250_000L; measurement.consumed(true)
        clock = 132_250_000L; measurement.visibleText()
        clock = 135_250_000L; measurement.consumerDrained()
        clock = 140_250_000L
        val s = measurement.snapshot(4)
        assertEquals(25L, s.submitReturnMs)
        assertEquals(26L, s.firstConsumerMs); assertEquals(30L, s.firstConsumerTextMs)
        assertEquals(14L, s.callbackToConsumerMs); assertEquals(14L, s.rawTextToConsumerMs)
        assertEquals(true, s.firstCallbackBeforeSubmitReturn)
        assertEquals(20L, s.nativeTerminalMs); assertEquals(35L, s.consumerDrainedMs)
        assertEquals(15L, s.terminalToConsumerDrainedMs); assertEquals(5L, s.consumerDrainedToReturnMs)
        assertEquals(116L, s.eventClocksMs["first_native_raw_text"])
        assertEquals(130L, s.eventClocksMs["first_consumer_raw_text"])
    }
    @Test fun unobservedOrReversedConsumerEdgesAndTerminalRemainUnavailable() {
        var clock = 10_000_000L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 11_000_000L; measurement.consumed(true)
        clock = 12_000_000L; measurement.callback(true)
        val s = measurement.snapshot(4)
        assertNull(s.callbackToConsumerMs); assertNull(s.rawTextToConsumerMs)
        assertNull(s.nativeTerminalMs); assertNull(s.consumerDrainedMs)
        assertNull(s.rawDecodeMs); assertNull(s.estimatedRawDecodeTokensPerSecond)
        assertNull(s.terminalToConsumerDrainedMs); assertNull(s.consumerDrainedToReturnMs)
        assertNull(s.firstCallbackBeforeSubmitReturn)
        assertFalse(s.eventClocksMs.containsKey("native_terminal"))
    }
    @Test fun callbacksAfterSdkReturnAreNotSynchronousSubmitWork() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        measurement.submissionStarted(clock)
        clock = 2_000_000L; measurement.submitted(0)
        clock = 8_000_000L; measurement.callback(true)
        clock = 10_000_000L; measurement.consumed(true)
        val s = measurement.snapshot(4)
        assertEquals(false, s.firstCallbackBeforeSubmitReturn)
        assertEquals(2L, s.rawTextToConsumerMs)
    }
    @Test fun cancelledOrFailedNativeSnapshotCannotBecomeCurrentSubmissionTelemetry() {
        for (outcome in listOf(PipelineBenchmarkOutcome.CANCELLED, PipelineBenchmarkOutcome.ERROR)) {
            var record: PipelineBenchmarkSubmission? = null
            NativeInferenceBenchmark("gemma", PipelineBenchmarkPurpose.ANSWER,
                PipelineBenchmarkWarmState.WARM, "text", sink = { record = it }).finish(
                outcome, 4, 12, 1, nativeTokens = NativeTokenTelemetry.checked(4, 2, 0.1, 20.0, 10.0, 0.1))
            val actual = requireNotNull(record)
            assertNull(actual.exactInputTokens); assertNull(actual.exactOutputTokens)
            assertNull(actual.measurements["native_terminal_ms"])
            assertNull(actual.measurements["sdk_first_prefill_decode_ttft_proxy_ms"])
            assertNull(actual.measurements["derived_native_last_prefill_executor_ms"])
            assertNull(actual.measurements["derived_native_last_decode_loop_ms"])
        }
    }

    @Test fun preparationAndSubmitCallbacksAreSeparateFromLegacyTtft() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 118_000_000L; measurement.conversationReady(0, earlyPrepared = false)
        clock = 125_000_000L; measurement.submissionStarted(clock)
        clock = 126_000_000L; measurement.callback(false)
        clock = 130_000_000L; measurement.submitted(125_000_000L)
        clock = 140_000_000L; measurement.callback(true)
        val snapshot = measurement.snapshot(4)
        assertEquals(118L, snapshot.conversationPreparationMs); assertEquals(125L, snapshot.preSubmitMs)
        assertEquals(1L, snapshot.submitToFirstCallbackMs); assertEquals(15L, snapshot.submitToFirstRawTextMs)
        assertEquals(126L, snapshot.firstCallbackMs); assertEquals(140L, snapshot.firstRawTextMs)
        assertEquals(false, snapshot.reusedEarlyConversation)
    }
    @Test fun missingPreparationOrSubmissionRemainsUnknown() {
        val snapshot = NativeInferenceMeasurement { 0L }.snapshot(0)
        assertNull(snapshot.conversationPreparationMs); assertNull(snapshot.preSubmitMs)
        assertNull(snapshot.submitToFirstCallbackMs); assertNull(snapshot.submitToFirstRawTextMs)
        assertNull(snapshot.reusedEarlyConversation)
    }
    @Test fun controlCallbackRawTextAndVisibleTextHaveSeparateClocks() {
        var clock = 1_000_000L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 11_000_000L
        measurement.callback(false)
        clock = 21_000_000L
        measurement.callback(true)
        clock = 51_000_000L
        measurement.callback(true)
        clock = 61_000_000L
        measurement.visibleText()
        clock = 111_000_000L
        measurement.callback(true)
        clock = 121_000_000L
        measurement.terminal()
        clock = 141_000_000L
        val snapshot = measurement.snapshot(40)
        assertEquals(10L, snapshot.firstCallbackMs)
        assertEquals(20L, snapshot.firstRawTextMs)
        assertEquals(60L, snapshot.firstVisibleTextMs)
        assertEquals(120L, snapshot.durationMs)
        assertEquals(140L, snapshot.returnDurationMs)
        assertEquals(100L, snapshot.rawDecodeMs)
        assertEquals(80L, snapshot.visibleDecodeMs)
        assertEquals(4, snapshot.callbackCount)
        assertEquals(10, snapshot.estimatedOutputTokens)
        assertEquals(100.0, snapshot.estimatedRawDecodeTokensPerSecond!!, 0.0001)
        assertEquals(125.0, snapshot.estimatedVisibleTokensPerSecond!!, 0.0001)
        assertEquals(30.0, snapshot.callbackIntervalP50Ms!!, 0.0)
        assertEquals(60.0, snapshot.callbackIntervalP95Ms!!, 0.0)
    }

    @Test fun callbackSamplesAreBoundedWithoutPretendingToBeAllTokenIntervals() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        repeat(5000) { clock += 1_000_000; measurement.callback(true) }
        val snapshot = measurement.snapshot(8)
        assertEquals(5000, snapshot.callbackCount)
        assertEquals(4096, snapshot.callbackIntervalsRetained)
        assertEquals(903, snapshot.callbackIntervalsDropped)
        assertEquals(1.0, snapshot.callbackIntervalMaxMs!!, 0.0)
    }

    @Test fun synchronousCompletionBeforeConsumerDoesNotProduceNegativeDurations() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 1_000_000L; measurement.callback(true)
        clock = 2_000_000L; measurement.terminal()
        clock = 5_000_000L; measurement.visibleText()
        clock = 6_000_000L
        val snapshot = measurement.snapshot(4)
        assertEquals(2L, snapshot.durationMs)
        assertEquals(6L, snapshot.returnDurationMs)
        assertEquals(1L, snapshot.rawDecodeMs)
        assertEquals(1L, snapshot.visibleDecodeMs)
        assertEquals(1000.0, snapshot.estimatedRawDecodeTokensPerSecond!!, 0.0)
    }

    @Test fun submissionDurationDoesNotIncludePreparationAndNoTextIsNotAZeroLatencyToken() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 50_000_000L
        val submittedAt = clock
        clock = 53_000_000L
        measurement.submitted(submittedAt)
        measurement.callback(false)
        clock = 55_000_000L; measurement.terminal()
        val snapshot = measurement.snapshot(0)
        assertEquals(3L, snapshot.nativeSubmitMs)
        assertEquals(53L, snapshot.firstCallbackMs)
        assertNull(snapshot.firstRawTextMs)
        assertNull(snapshot.firstVisibleTextMs)
        assertNull(snapshot.estimatedRawDecodeTokensPerSecond)
        assertEquals(0, snapshot.estimatedOutputTokens)
    }

    @Test fun terminalReceiptIsEmittedOnceAndDiagnosticsCannotBreakGeneration() {
        val submissions = mutableListOf<PipelineBenchmarkSubmission>()
        val benchmark = NativeInferenceBenchmark("gemma", PipelineBenchmarkPurpose.RETRY,
            PipelineBenchmarkWarmState.WARM, "audio_text", audioBytes = 1024,
            sink = { submissions += it; error("observer failure") })
        benchmark.measurement.callback(true)
        benchmark.measurement.visibleText()
        benchmark.measurement.terminal()
        benchmark.finish(PipelineBenchmarkOutcome.ERROR, 8, 40, 1, error = IllegalArgumentException("private error text"))
        benchmark.finish(PipelineBenchmarkOutcome.COMPLETE, 100, 40, 8)
        val record = submissions.single()
        assertEquals(PipelineBenchmarkOutcome.ERROR, record.outcome)
        assertEquals(PipelineBenchmarkPurpose.RETRY, record.purpose)
        assertNull(record.exactOutputTokens)
        assertNull(record.exactInputTokens)
        assertEquals(2, record.estimatedOutputTokens)
        assertEquals(1024.0, record.measurements["audio_bytes"]!!, 0.0)
        assertEquals("IllegalArgumentException", record.metadata["error_type"])
        assertFalse(record.metadata.values.any { it.contains("private error text") })
        assertEquals("unavailable_for_this_inference_path_or_outcome", record.tokenTelemetrySource)
    }

    @Test fun nativeCountersRemainSeparateFromCallbackTimingAndEstimates() {
        var record: PipelineBenchmarkSubmission? = null
        val benchmark = NativeInferenceBenchmark("gemma", PipelineBenchmarkPurpose.ANSWER,
            PipelineBenchmarkWarmState.WARM, "audio_text", sink = { record = it })
        benchmark.finish(PipelineBenchmarkOutcome.COMPLETE, 80, 20, 2,
            nativeTokens = NativeTokenTelemetry.checked(18, 12, 0.2, 100.0, 40.0, 0.1))
        val measured = requireNotNull(record)
        assertEquals(18, measured.exactInputTokens)
        assertEquals(12, measured.exactOutputTokens)
        assertEquals(20, measured.estimatedOutputTokens)
        assertNull(measured.measurements["native_ttft_ms"])
        assertEquals(200.0, measured.measurements["sdk_first_prefill_decode_ttft_proxy_ms"]!!, 0.0)
        assertEquals(180.0, measured.measurements["derived_native_last_prefill_executor_ms"]!!, 0.0)
        assertEquals(300.0, measured.measurements["derived_native_last_decode_loop_ms"]!!, 0.0)
        assertNull(measured.nativeFirstOutputTokenMs)
        assertEquals(40.0, measured.measurements["native_decode_tokens_per_second"]!!, 0.0)
        assertNull("SDK relative TTFT cannot be subtracted from a callback clock", measured.exactDecodeTokensPerSecond)
        assertTrue(measured.tokenTelemetrySource!!.contains("getBenchmarkInfo"))
    }
}
