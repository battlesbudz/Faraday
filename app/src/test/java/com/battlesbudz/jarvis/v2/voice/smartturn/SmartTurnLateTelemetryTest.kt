package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.SmartTurnBenchmarkTelemetry
import com.battlesbudz.jarvis.v2.voice.CaptureShadowFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class SmartTurnLateTelemetryTest {
    @Test fun completionAfterCallEndRetainsOldTurnRecordAndPendingFrozenMetrics() {
        val now = AtomicLong(1_000_000_000L)
        val capture = PipelineBenchmarkCapture("old-turn", "voice", 1, PipelineBenchmarkProvenance("test", 1))
        val newer = PipelineBenchmarkCapture("new-turn", "voice", 2, PipelineBenchmarkProvenance("test", 1))
        val records = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val retained = CountDownLatch(1); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val telemetry = SmartTurnBenchmarkTelemetry(capture) { key, data -> records[key] = data; if (key.endsWith("_work")) retained.countDown() }
        val backend = object : SmartTurnBackend {
            override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
                entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
                return SmartTurnInference(0.75f, 20_000, 40_000)
            }
            override fun cancel(requestId: Long) {}
            override fun close() {}
        }
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "old-turn", 7, telemetry, { true }, { null }, now::get)
        try {
            observer.onPcm(ByteArray(3200), 1600)
            observer.onFrame(CaptureShadowFrame(1600, now.get(), true, false, 250, 0))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            now.addAndGet(10_000_000); observer.onPriority("gemma_speculation"); observer.close()
            val frozen = capture.finish(PipelineBenchmarkOutcome.CANCELLED, "old-call")!!
            assertEquals("pending;late_completion_retained_in_diagnostics", frozen.provenance.configuration["smart_turn_0_worker_completion"])
            assertNull(frozen.observedMetrics["smart_turn_0_actual_worker_wall_ms"])
            now.addAndGet(30_000_000); release.countDown()
            assertTrue(retained.await(1, TimeUnit.SECONDS))
            val record = records.getValue("smart_turn_shadow_0_work")
            assertTrue(record.contains("turn=old-turn")); assertTrue(record.contains("captureGeneration=7"))
            assertTrue(record.contains("postPriorityWorkerWallMs=30.0")); assertTrue(record.contains("frontendMs=0.02"))
            assertTrue(record.contains("cancelled=true"))
            assertNull(frozen.observedMetrics["smart_turn_0_actual_worker_wall_ms"])
            val fresh = newer.finish(PipelineBenchmarkOutcome.COMPLETE, "new-call")!!
            assertFalse(fresh.observedMetrics.keys.any { it.startsWith("smart_turn_") })
        } finally { release.countDown(); observer.close(); shadow.close(); assertTrue(shadow.awaitClosed(1000)) }
    }
    @Test fun distinctObservationKeysDoNotOverwriteEarlierCompletions() {
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, PipelineBenchmarkProvenance("test", 1))
        val records = mutableMapOf<String, String>()
        val telemetry = SmartTurnBenchmarkTelemetry(capture) { key, data -> records[key] = data }
        val completion = SmartTurnWorkCompletion(SmartTurnGeneration("turn", 1, 0), 100,
            10, 20, true, SmartTurnTiming(0, null, null, null, 10))
        repeat(8) { telemetry.completion(it, completion, null) }
        assertEquals(8, records.size)
        assertTrue(records.getValue("smart_turn_shadow_0_work").contains("initializationMs=null"))
        assertThrows(IllegalArgumentException::class.java) { telemetry.completion(8, completion, null) }
    }
    @Test fun actualBackendEdgesExcludeInitializationAndRemainTurnRelative() {
        var now = 1000L
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, PipelineBenchmarkProvenance("test", 1)) { now }
        val records = mutableMapOf<String, String>()
        val telemetry = SmartTurnBenchmarkTelemetry(capture) { key, data -> records[key] = data }
        telemetry.completion(7, SmartTurnWorkCompletion(SmartTurnGeneration("turn", 1, 0), 1600,
            1_010_000_000, 1_050_000_000, false, SmartTurnTiming(0, 20_000_000, 1_000_000, 9_000_000, 40_000_000),
            backendCalledAtNanos = 1_040_000_000, backendReturnedAtNanos = 1_050_000_000), null)
        now = 1100L
        val row = capture.finish(PipelineBenchmarkOutcome.COMPLETE)!!
        assertEquals(10L, row.stageOffsetsMs["smart_turn_7_request_started"])
        assertEquals(40L, row.stageOffsetsMs["smart_turn_7_backend_called"])
        assertEquals(50L, row.stageOffsetsMs["smart_turn_7_backend_returned"])
        assertEquals(10.0, row.metrics()["smart_turn_7_backend_call_ms"])
        assertTrue(records.getValue("smart_turn_shadow_7_work").contains("backendCalledAtNanos=1040000000"))
        assertNull(row.metrics()["smart_turn_7_backend_speech_span_overlap_ms"])
    }
    @Test fun followupMeasurementsCannotOverwriteInitialCaptureAndDiagnosticsKeepNextIdentity() {
        val capture = PipelineBenchmarkCapture("old", "voice", 1, PipelineBenchmarkProvenance("test", 1))
        val records = mutableMapOf<String, String>()
        val first = SmartTurnBenchmarkTelemetry(capture) { _, _ -> }
        val next = SmartTurnBenchmarkTelemetry(capture, prefix = "followup_") { key, data -> records[key] = data }
        first.metric("smart_turn_accepted_observations", 2)
        first.configuration("smart_turn_mode", "shadow_only_v1")
        next.metric("smart_turn_accepted_observations", 1)
        next.configuration("smart_turn_mode", "shadow_only_v1")
        next.completion(0, SmartTurnWorkCompletion(SmartTurnGeneration("next", 0, 0), 1600,
            10, 20, false, SmartTurnTiming(0, null, 1, 2, 10)), null)
        val row = capture.finish(PipelineBenchmarkOutcome.COMPLETE, "call")!!
        assertEquals(2.0, row.observedMetrics["smart_turn_accepted_observations"])
        assertEquals(1.0, row.observedMetrics["followup_smart_turn_accepted_observations"])
        assertEquals("shadow_only_v1", row.provenance.configuration["followup_smart_turn_mode"])
        assertTrue(records.getValue("smart_turn_shadow_0_work").contains("turn=next "))
    }

}
