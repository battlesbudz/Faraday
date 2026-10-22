package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.voice.CaptureShadowFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SmartTurnCaptureObserverTest {
    private class Telemetry : SmartTurnTelemetry {
        val numbers = java.util.Collections.synchronizedMap(mutableMapOf<String, Number?>())
        val strings = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val stages = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
        override fun markAt(name: String, monotonicMs: Long) { stages.putIfAbsent(name, monotonicMs) }
        override fun metric(name: String, value: Number?) { numbers[name] = value }
        override fun configuration(name: String, value: String) { strings[name] = value }
        override fun event(message: String) { assertFalse(message.contains("transcript")) }
    }
    private class Backend(blocked: Boolean = false) : SmartTurnBackend {
        val entered = CountDownLatch(1); val release = CountDownLatch(if (blocked) 1 else 0)
        val closed = CountDownLatch(1); val calls = AtomicInteger(); val cancelled = AtomicInteger()
        var samples = floatArrayOf()
        override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
            this.samples = samples.copyOf(); calls.incrementAndGet(); entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            return SmartTurnInference(0.75f, 20_000, 40_000)
        }
        override fun cancel(requestId: Long) { cancelled.incrementAndGet() }
        override fun close() { closed.countDown() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue(condition())
    }
    private fun frame(now: Long, boundary: Long = 1600, speech: Boolean = false, backlog: Long = 0) =
        CaptureShadowFrame(boundary, now, true, speech, if (speech) 0 else 250, backlog)

    @Test fun observingSpeechFramesDoesNotClaimInferenceUntilPauseAdmission() {
        val now = AtomicLong(1_000_000_000L); val backend = Backend(); val telemetry = Telemetry()
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null }, now::get,
            endpointEnabled = true)
        try {
            observer.onPcm(ByteArray(3200), 1600)
            observer.onFrame(frame(now.get(), speech = true))
            assertEquals(1000L, telemetry.stages["smart_turn_first_frame_observed"])
            assertEquals(1000L, telemetry.stages["smart_turn_first_possible_speech_frame_captured"])
            assertEquals(1000L, telemetry.stages["smart_turn_first_possible_speech_frame_observed"])
            assertEquals(0, backend.calls.get())
            now.addAndGet(350_000_000L)
            observer.onFrame(frame(now.get()).copy(silenceMs = 349))
            assertFalse(telemetry.stages.containsKey("smart_turn_0_request_accepted"))
            observer.onFrame(frame(now.get()).copy(silenceMs = 350))
            waitFor { backend.calls.get() == 1 && !shadow.isBusy() }
            assertEquals(1350L, telemetry.stages["smart_turn_0_request_accepted"])
            assertEquals(1350L, telemetry.stages["smart_turn_0_source_captured"])
        } finally { observer.close(); shadow.close(); assertTrue(shadow.awaitClosed(1000)) }
    }
    @Test fun realPcmSnapshotAndPredictionEnterMetadataOnly() {
        val now = AtomicLong(1_000_000_000); val backend = Backend(); val telemetry = Telemetry()
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null }, now::get)
        try {
            val pcm = ByteArray(3200) { if (it % 2 == 0) 1 else 0 }
            observer.onPcm(pcm, 1600); pcm.fill(0)
            observer.onFrame(frame(now.get()))
            waitFor { backend.calls.get() == 1 && !shadow.isBusy() }
            observer.onFrame(frame(now.get()))
            assertEquals(0.75f, telemetry.numbers["smart_turn_0_probability"])
            assertEquals(1f / 32768f, backend.samples.first(), 0f)
            assertEquals("observed_only", telemetry.strings["smart_turn_0_outcome"])
        } finally { observer.close(); shadow.close(); assertTrue(shadow.awaitClosed(1000)) }
    }
    @Test fun candidateFrequencyAndCopiesAreBoundedPerTurn() {
        val now = AtomicLong(1_000_000_000); val backend = Backend(); val telemetry = Telemetry()
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null }, now::get)
        try {
            observer.onPcm(ByteArray(3200), 1600)
            repeat(10) { iteration ->
                observer.onFrame(frame(now.get()))
                if (iteration < 3) waitFor { backend.calls.get() == iteration + 1 && !shadow.isBusy() }
                observer.onFrame(frame(now.get()))
                now.addAndGet(600_000_000)
            }
            assertEquals(3, backend.calls.get())
            assertEquals(3, telemetry.numbers["smart_turn_observation_attempts"])
        } finally { observer.close(); shadow.close(); assertTrue(shadow.awaitClosed(1000)) }
    }
    @Test fun resumptionRevokesInflightProbabilityAndLabelsEarlierObservation() {
        val now = AtomicLong(1_000_000_000); val backend = Backend(true); val telemetry = Telemetry()
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null }, now::get)
        try {
            observer.onPcm(ByteArray(3200), 1600); observer.onFrame(frame(now.get()))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            now.addAndGet(10_000_000); observer.onFrame(frame(now.get(), speech = true))
            backend.release.countDown(); waitFor { !shadow.isBusy() }
            observer.onFrame(frame(now.get(), speech = true))
            assertNull(telemetry.numbers["smart_turn_0_probability"])
            assertEquals(1, telemetry.numbers["smart_turn_0_speech_resumed_after_observation"])
            assertTrue(backend.cancelled.get() > 0)
        } finally { backend.release.countDown(); observer.close(); shadow.close(); shadow.awaitClosed(1000) }
    }
    @Test fun priorityCancelsWithoutJoiningAndMeasuresActualPostCancelWork() {
        val now = AtomicLong(1_000_000_000); val backend = Backend(true); val telemetry = Telemetry()
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null }, now::get)
        try {
            observer.onPcm(ByteArray(3200), 1600); observer.onFrame(frame(now.get()))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            now.addAndGet(10_000_000); observer.onPriority("gemma_speculation")
            assertTrue(shadow.isBusy()); assertEquals(1L, backend.release.count)
            now.addAndGet(30_000_000); backend.release.countDown()
            waitFor { telemetry.numbers["smart_turn_0_post_priority_worker_wall_ms"] == 30.0 }
            assertEquals(30.0, telemetry.numbers["smart_turn_0_post_priority_worker_wall_ms"])
            now.addAndGet(600_000_000); observer.onFrame(frame(now.get()))
            assertEquals(1, backend.calls.get())
        } finally { backend.release.countDown(); observer.close(); shadow.close(); shadow.awaitClosed(1000) }
    }
    @Test fun backlogBlockerAndMissingBoundarySkipBeforeAnyWorker() {
        val now = AtomicLong(1_000_000_000); val backend = Backend(); val telemetry = Telemetry()
        var blocker: String? = "admission_blocked"
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2), now::get)
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { blocker }, now::get)
        try {
            observer.onPcm(ByteArray(3200), 1600); observer.onFrame(frame(now.get()))
            blocker = null; now.addAndGet(600_000_000); observer.onFrame(frame(now.get(), backlog = 41))
            now.addAndGet(600_000_000); observer.onFrame(frame(now.get(), boundary = 1700))
            assertEquals(0, backend.calls.get()); assertEquals(2, telemetry.numbers["smart_turn_priority_or_backlog_skips"])
            assertEquals("retained_pcm_boundary_mismatch", telemetry.strings["smart_turn_last_admission_blocker"])
        } finally { observer.close(); shadow.close(); shadow.awaitClosed(1000) }
    }
    @Test fun ownerRetainsUndrainedBudgetAcrossCallAndEnableChanges() {
        val first = Backend(true); val second = Backend(); val created = AtomicInteger(); val telemetry = Telemetry()
        val owner = SmartTurnCallOwner {
            SmartTurnShadow({ if (created.incrementAndGet() == 1) first else second }, true, TimeUnit.SECONDS.toNanos(2))
        }
        fun begin(call: String, enabled: Boolean = true) = owner.beginCapture(call, "turn-$call", 1,
            enabled, File("synthetic-model"), telemetry, { true }, { null })
        try {
            val observer = requireNotNull(begin("first"))
            observer.onPcm(ByteArray(3200), 1600); observer.onFrame(frame(System.nanoTime()))
            assertTrue(first.entered.await(1, TimeUnit.SECONDS))
            owner.closeCall("first")
            assertNull(begin("second")); assertNull(begin("second", false)); assertNull(begin("second"))
            assertEquals(1, created.get())
            first.release.countDown(); assertTrue(first.closed.await(1, TimeUnit.SECONDS))
            var replacement: com.battlesbudz.jarvis.v2.voice.CaptureShadowObserver? = null
            waitFor { replacement = begin("second"); replacement != null }
            owner.closeCall("first") // Stale close must not affect second call.
            replacement!!.onPcm(ByteArray(3200), 1600); replacement!!.onFrame(frame(System.nanoTime()))
            assertTrue(second.entered.await(1, TimeUnit.SECONDS))
            assertEquals(2, created.get())
        } finally { first.release.countDown(); owner.close() }
    }
    @Test fun disabledMissingModelAndDisposedRuntimeNeverCreateWorker() {
        val telemetry = Telemetry(); val owner = SmartTurnCallOwner { error("must not create worker") }
        assertNull(owner.beginCapture("call", "turn", 1, false, File("fixture"), telemetry, { true }, { null }))
        assertNull(owner.beginCapture("call", "turn", 1, true, null, telemetry, { true }, { null }))
        owner.close()
        assertNull(owner.beginCapture("call", "turn", 1, true, File("fixture"), telemetry, { true }, { null }))
        assertEquals("runtime_disposed", telemetry.strings["smart_turn_mode"])
    }
    @Test fun oldCaptureCloseCannotInvalidateReplacementWithinSameCall() {
        val backend = Backend(); val telemetry = Telemetry()
        val owner = SmartTurnCallOwner { SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2)) }
        try {
            val old = owner.beginCapture("call", "old", 1, true, File("fixture"), telemetry, { true }, { null })!!
            val fresh = owner.beginCapture("call", "fresh", 2, true, File("fixture"), telemetry, { true }, { null })!!
            old.close(); old.onInvalidated("late_finalizer")
            fresh.onPcm(ByteArray(3200), 1600); fresh.onFrame(frame(System.nanoTime()))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
        } finally { owner.close() }
    }
    @Test fun staleBeginCannotRevokeNewerCall() {
        val backend = Backend(); val telemetry = Telemetry()
        val owner = SmartTurnCallOwner { SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2)) }
        try {
            val current = owner.beginCapture("new", "new-turn", 2, true, File("fixture"), telemetry, { true }, { null })!!
            assertNull(owner.beginCapture("old", "old-turn", 1, true, File("fixture"), telemetry, { false }, { null }))
            current.onPcm(ByteArray(3200), 1600); current.onFrame(frame(System.nanoTime()))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
        } finally { owner.close() }
    }
    @Test fun duplicateAndGappedPcmRevokeInsteadOfProducingMislabelledSnapshot() {
        for (nextBoundary in listOf(1600L, 3300L)) {
            val backend = Backend(); val telemetry = Telemetry()
            val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2))
            val observer = SmartTurnCaptureObserver(shadow, "turn", 1, telemetry, { true }, { null })
            try {
                observer.onPcm(ByteArray(3200), 1600)
                observer.onPcm(ByteArray(3200), nextBoundary)
                observer.onFrame(frame(System.nanoTime(), boundary = nextBoundary))
                assertEquals(0, backend.calls.get())
                assertEquals("pcm_discontinuity", telemetry.strings["smart_turn_revoked_for_priority"])
            } finally { observer.close(); shadow.close(); shadow.awaitClosed(1000) }
        }
    }
    @Test fun telemetryFailureCannotBlockRevocationOrClose() {
        val backend = Backend(true)
        val broken = object : SmartTurnTelemetry {
            override fun metric(name: String, value: Number?) { error("diagnostics failed") }
            override fun configuration(name: String, value: String) { error("diagnostics failed") }
            override fun event(message: String) { error("diagnostics failed") }
            override fun markAt(name: String, monotonicMs: Long) { error("diagnostics failed") }
        }
        val shadow = SmartTurnShadow({ backend }, true, TimeUnit.SECONDS.toNanos(2))
        val observer = SmartTurnCaptureObserver(shadow, "turn", 1, broken, { true }, { null })
        try {
            observer.onPcm(ByteArray(3200), 1600); observer.onFrame(frame(System.nanoTime()))
            assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
            observer.onPriority("gemma_speculation")
            assertTrue(backend.cancelled.get() > 0)
            observer.close(); backend.release.countDown()
        } finally { backend.release.countDown(); shadow.close(); assertTrue(shadow.awaitClosed(1000)) }
    }
    @Test fun optionalWorkerConstructionFailureLeavesVoiceAvailable() {
        val telemetry = Telemetry()
        val owner = SmartTurnCallOwner { throw IllegalStateException("fixture") }
        assertNull(owner.beginCapture("call", "turn", 1, true, File("fixture"), telemetry, { true }, { null }))
        assertEquals("unavailable_optional_setup", telemetry.strings["smart_turn_mode"])
        owner.close()
    }

    @Test fun sealedFollowupRetainsBackendButDisableMissingModelAndChangedCallStillRevoke() {
        for (reason in listOf("disabled", "model_missing", "new_call")) {
            val backend = Backend(); val created = AtomicInteger(); val telemetry = Telemetry()
            val owner = SmartTurnCallOwner {
                created.incrementAndGet()
                SmartTurnShadow({ backend }, true)
            }
            try {
                val first = owner.beginCapture("call", "first", 1, true, File("fixture"), telemetry, { true }, { null })!!
                first.onPcm(ByteArray(3200), 1600); first.onFrame(frame(System.nanoTime()))
                assertTrue(backend.entered.await(1, TimeUnit.SECONDS))
                waitFor { telemetry.numbers["smart_turn_0_actual_worker_wall_ms"] != null }
                assertNull(owner.beginCapture("call", "sealed", 0, true, File("fixture"), telemetry,
                    { true }, { null }, observeCapture = false))
                assertEquals("ineligible_sealed_capture", telemetry.strings["smart_turn_mode"])
                assertEquals(1L, backend.closed.count)
                val next = owner.beginCapture("call", "followup", 0, true, File("fixture"), telemetry, { true }, { null })!!
                waitFor { backend.calls.get() == 1 }
                next.onPcm(ByteArray(3200), 1600); next.onFrame(frame(System.nanoTime()))
                waitFor { backend.calls.get() == 2 }
                assertEquals(1, created.get())
                owner.beginCapture(if (reason == "new_call") "other" else "call", "sealed-2", 0,
                    reason != "disabled", if (reason == "model_missing") null else File("fixture"), telemetry,
                    { true }, { null }, observeCapture = false)
                assertTrue("$reason must close the retained worker", backend.closed.await(1, TimeUnit.SECONDS))
                assertEquals(1, created.get())
            } finally { owner.close() }
        }
    }

}
