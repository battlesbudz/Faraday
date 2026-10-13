package com.battlesbudz.jarvis.v2.voice.smartturn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Fresh lifecycle coverage after recovery. These are not the lost original worker tests. */
class SmartTurnShadowTest {
    private val generation = SmartTurnGeneration("turn", 3, 0)
    private val now = AtomicLong(1_000_000_000)
    private fun snapshot(at: Long = now.get(), g: SmartTurnGeneration = generation) =
        SmartTurnAudioSnapshot.fromPcm16(g, 1600, at, ShortArray(1600) { 12 })
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Condition did not complete within bounded test wait", condition())
    }
    private class Backend(block: Boolean = false) : SmartTurnBackend {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (block) 1 else 0)
        val calls = AtomicInteger(); val cancels = AtomicInteger(); val closes = AtomicInteger()
        val owner = AtomicReference<Thread>()
        var answer = SmartTurnInference(.75f, 20, 30)
        var fail = false
        override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
            val previous = owner.getAndSet(Thread.currentThread())
            check(previous == null || previous === Thread.currentThread())
            calls.incrementAndGet(); entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            if (fail) error("injected inference failure")
            return answer
        }
        override fun cancel(requestId: Long) { cancels.incrementAndGet() }
        override fun close() {
            check(owner.get() == null || owner.get() === Thread.currentThread())
            closes.incrementAndGet()
        }
    }
    private fun worker(backend: Backend) = SmartTurnShadow({ backend }, true, 2_000_000_000, now::get)
        .also { it.updateGeneration(generation) }
    private fun stop(shadow: SmartTurnShadow, backend: Backend? = null) {
        backend?.release?.countDown(); shadow.close(); assertTrue(shadow.awaitClosed(3000))
    }
    private fun completed(shadow: SmartTurnShadow): SmartTurnShadowResult {
        waitFor { !shadow.isBusy() }
        return requireNotNull(shadow.takeResult(generation))
    }

    @Test fun backendCallEdgesDistinguishInitializationSkippedAndThrowingInference() {
        for (mode in listOf("success", "init_failure", "inference_failure")) {
            now.set(1_000_000_000L)
            val completed = CountDownLatch(1)
            val receipt = AtomicReference<SmartTurnWorkCompletion>()
            val shadow = SmartTurnShadow({
                now.addAndGet(20_000_000L)
                if (mode == "init_failure") error("injected initialization failure")
                object : SmartTurnBackend {
                    override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
                        now.addAndGet(10_000_000L)
                        if (mode == "inference_failure") error("injected inference failure")
                        return SmartTurnInference(.75f, 2_000_000L, 8_000_000L)
                    }
                    override fun cancel(requestId: Long) {}
                    override fun close() {}
                }
            }, true, 2_000_000_000L, now::get)
            try {
                shadow.updateGeneration(generation)
                assertEquals(SmartTurnOffer.ACCEPTED, shadow.offer(snapshot()) { receipt.set(it); completed.countDown() })
                assertTrue(completed.await(1, TimeUnit.SECONDS))
                val work = receipt.get()
                assertEquals(1_000_000_000L, work.startedAtNanos)
                if (mode == "init_failure") {
                    assertNull(work.backendCalledAtNanos); assertNull(work.backendReturnedAtNanos)
                } else {
                    assertEquals(1_020_000_000L, work.backendCalledAtNanos)
                    assertEquals(1_030_000_000L, work.backendReturnedAtNanos)
                }
            } finally { stop(shadow) }
        }
    }
    @Test fun defaultOffDoesNotConstructBackendOrAcceptAudio() {
        val calls = AtomicInteger()
        val shadow = SmartTurnShadow({ calls.incrementAndGet(); Backend() })
        shadow.updateGeneration(generation)
        assertEquals(SmartTurnOffer.DISABLED, shadow.offer(snapshot()))
        assertFalse(shadow.isBusy()); assertEquals(0, calls.get())
        shadow.close(); assertTrue(shadow.awaitClosed(0))
    }
    @Test fun snapshotsOwnBoundedCopiesOfOnlyRecentAudio() {
        val pcm = ShortArray(128002) { it.toShort() }
        val expectedFirst = pcm[2] / 32768f
        val captured = SmartTurnAudioSnapshot.fromPcm16(generation, pcm.size.toLong(), now.get(), pcm)
        pcm.fill(0)
        val copy = captured.copySamples(); assertEquals(128000, copy.size)
        assertEquals(expectedFirst, copy.first(), 0f)
        copy.fill(1f); assertEquals(expectedFirst, captured.copySamples().first(), 0f)
    }
    @Test fun invalidSnapshotRateBoundaryAndIdentityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SmartTurnGeneration("", 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SmartTurnGeneration("turn", -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SmartTurnAudioSnapshot.fromPcm16(generation, 0, 0, shortArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { SmartTurnAudioSnapshot.fromPcm16(generation, 1, 0, shortArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { SmartTurnAudioSnapshot.fromPcm16(generation, 1, 0, shortArrayOf(1), 8000) }
    }
    @Test fun staleWrongGenerationAndFutureRequestsNeverInitialize() {
        val calls = AtomicInteger()
        val shadow = SmartTurnShadow({ calls.incrementAndGet(); Backend() }, true, 2_000_000_000, now::get)
        try {
            shadow.updateGeneration(generation)
            assertEquals(SmartTurnOffer.STALE, shadow.offer(snapshot(g = generation.copy(revision = 1))))
            assertEquals(SmartTurnOffer.STALE, shadow.offer(snapshot(now.get() + 1)))
            assertEquals(SmartTurnOffer.STALE, shadow.offer(snapshot(now.get() - 2_000_000_000)))
            assertEquals(0, calls.get())
        } finally { stop(shadow) }
    }
    @Test fun busyWorkerRejectsEveryAdditionalOfferWithoutBacklog() {
        val backend = Backend(true); val shadow = worker(backend)
        try {
            assertEquals(SmartTurnOffer.ACCEPTED, shadow.offer(snapshot()))
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
            repeat(50) { assertEquals(SmartTurnOffer.BUSY, shadow.offer(snapshot())) }
            assertEquals(1, backend.calls.get())
            backend.release.countDown(); assertEquals(.75f, completed(shadow).probability)
            assertEquals(1, backend.calls.get())
        } finally { stop(shadow, backend) }
    }
    @Test fun generationRevocationFencesResultsButRetainsActualBorrow() {
        val backend = Backend(true); val shadow = worker(backend)
        try {
            shadow.offer(snapshot()); assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
            val next = generation.copy(revision = 1); shadow.updateGeneration(next)
            assertTrue(backend.cancels.get() > 0); assertTrue(shadow.isBusy())
            assertEquals(SmartTurnOffer.BUSY, shadow.offer(snapshot(g = next)))
            backend.release.countDown(); waitFor { !shadow.isBusy() }
            assertNull(shadow.takeResult(generation)); assertNull(shadow.takeResult(next))
        } finally { stop(shadow, backend) }
    }
    @Test fun closeRevokesWithoutDrainAndNeverClosesBorrowedBackend() {
        val backend = Backend(true); val shadow = worker(backend)
        try {
            shadow.offer(snapshot()); assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
            shadow.close(); shadow.close()
            assertEquals(0, backend.closes.get()); assertFalse(shadow.awaitClosed(0))
            assertEquals(SmartTurnOffer.CLOSED, shadow.offer(snapshot()))
            assertNull(shadow.takeResult(generation)); assertTrue(backend.cancels.get() > 0)
        } finally { stop(shadow, backend) }
        assertEquals(1, backend.closes.get())
    }
    @Test fun independentDeadlineReportsUnknownWhileNativeBorrowStillRuns() {
        val backend = Backend(true)
        val shadow = SmartTurnShadow({ backend }, true, 200_000_000, now::get)
        try {
            shadow.updateGeneration(generation); shadow.offer(snapshot())
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
            waitFor { backend.cancels.get() > 0 }
            val result = requireNotNull(shadow.takeResult(generation))
            assertEquals(SmartTurnUnknown.DEADLINE, result.unknown); assertNull(result.probability)
            assertNull(result.timing.initializationNanos); assertNull(result.timing.frontendNanos)
            assertTrue(shadow.isBusy()); assertEquals(0, backend.closes.get())
        } finally { stop(shadow, backend) }
    }
    @Test fun completedProbabilityExpiresBeforeConsumption() {
        val backend = Backend(); val shadow = worker(backend)
        try {
            shadow.offer(snapshot()); waitFor { !shadow.isBusy() }
            now.addAndGet(2_000_000_000)
            assertNull(shadow.takeResult(generation))
        } finally { stop(shadow, backend) }
    }
    @Test fun failedInitializationRetainsMeasuredAttemptButUnknownInferenceCosts() {
        val shadow = SmartTurnShadow({ now.addAndGet(7); error("injected init failure") }, true, 2_000_000_000, now::get)
        try {
            shadow.updateGeneration(generation); shadow.offer(snapshot()); val result = completed(shadow)
            assertEquals(SmartTurnUnknown.INITIALIZATION_FAILED, result.unknown)
            assertEquals(7L, result.timing.initializationNanos); assertNull(result.timing.frontendNanos)
            assertNull(result.timing.inferenceNanos); assertNull(result.probability)
        } finally { stop(shadow) }
    }
    @Test fun deadlineBeforeInitializationHasNullCostAndNeverConstructsBackend() {
        val initialized = AtomicInteger()
        val shadow = SmartTurnShadow({ initialized.incrementAndGet(); Backend() }, true, 2_000_000_000,
            { if (Thread.currentThread().name == "jarvis-smart-turn-shadow") now.get() + 3_000_000_000 else now.get() })
        try {
            shadow.updateGeneration(generation); shadow.offer(snapshot()); val result = completed(shadow)
            assertEquals(0, initialized.get()); assertEquals(SmartTurnUnknown.DEADLINE, result.unknown)
            assertNull(result.timing.initializationNanos); assertNull(result.timing.frontendNanos)
        } finally { stop(shadow) }
    }
    @Test fun inferenceFailureCannotManufactureProbabilityOrPhaseTimings() {
        val backend = Backend().also { it.fail = true }; val shadow = worker(backend)
        try {
            shadow.offer(snapshot()); val result = completed(shadow)
            assertEquals(SmartTurnUnknown.INFERENCE_FAILED, result.unknown)
            assertNull(result.probability); assertNull(result.timing.frontendNanos); assertNull(result.timing.inferenceNanos)
        } finally { stop(shadow, backend) }
    }
    @Test fun invalidProbabilityAndNegativeCostAreUnknown() {
        for (answer in listOf(SmartTurnInference(Float.NaN, 0, 0), SmartTurnInference(1.1f, 0, 0), SmartTurnInference(.5f, -1, 0))) {
            val backend = Backend().also { it.answer = answer }; val shadow = worker(backend)
            try {
                shadow.offer(snapshot()); val result = completed(shadow)
                assertEquals(SmartTurnUnknown.INVALID_RESULT, result.unknown); assertNull(result.probability)
            } finally { stop(shadow, backend) }
        }
    }
    @Test fun completionRunsOutsideOwnerLockAndReportsRevokedOldIdentity() {
        val backend = Backend(true); val callback = CountDownLatch(1); val outside = AtomicBoolean()
        val completion = AtomicReference<SmartTurnWorkCompletion>(); val shadow = worker(backend)
        try {
            shadow.offer(snapshot()) { value ->
                completion.set(value)
                val closed = CountDownLatch(1)
                Thread { shadow.close(); closed.countDown() }.start()
                outside.set(closed.await(1, TimeUnit.SECONDS)); callback.countDown()
            }
            assertTrue(backend.entered.await(2, TimeUnit.SECONDS)); shadow.updateGeneration(generation.copy(revision = 1))
            backend.release.countDown(); assertTrue(callback.await(2, TimeUnit.SECONDS))
            assertTrue(outside.get()); assertEquals(generation, completion.get().generation)
            assertTrue(completion.get().cancelled)
        } finally { stop(shadow, backend) }
    }
    @Test fun throwingCompletionDoesNotPoisonTheNextRequest() {
        val backend = Backend(); val shadow = worker(backend); val seen = CountDownLatch(1)
        try {
            shadow.offer(snapshot()) { seen.countDown(); error("injected optional telemetry failure") }
            assertTrue(seen.await(2, TimeUnit.SECONDS)); assertNotNull(shadow.takeResult(generation))
            assertEquals(SmartTurnOffer.ACCEPTED, shadow.offer(snapshot()))
            assertEquals(.75f, completed(shadow).probability); assertEquals(2, backend.calls.get())
        } finally { stop(shadow, backend) }
    }
    @Test fun backendAndWorkerAreReusedWithMeasuredZeroSecondInitialization() {
        val backend = Backend(); val factoryCalls = AtomicInteger()
        val shadow = SmartTurnShadow({ factoryCalls.incrementAndGet(); now.addAndGet(5); backend }, true, 2_000_000_000, now::get)
        try {
            shadow.updateGeneration(generation); shadow.offer(snapshot())
            assertEquals(5L, completed(shadow).timing.initializationNanos)
            shadow.offer(snapshot()); assertEquals(0L, completed(shadow).timing.initializationNanos)
            assertEquals(1, factoryCalls.get()); assertEquals(2, backend.calls.get())
        } finally { stop(shadow, backend) }
        assertEquals(1, backend.closes.get())
    }
    @Test fun resultIsSingleConsumptionAndWrongGenerationCannotConsumeIt() {
        val backend = Backend(); val shadow = worker(backend)
        try {
            shadow.offer(snapshot()); waitFor { !shadow.isBusy() }
            assertNull(shadow.takeResult(generation.copy(revision = 1)))
            assertNotNull(shadow.takeResult(generation)); assertNull(shadow.takeResult(generation))
        } finally { stop(shadow, backend) }
    }
}
