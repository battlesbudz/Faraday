package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The GPU→CPU creation ladder (reliability epic M5, item 1), driven with
 * scripted attempts: no native engine is constructed. Pins the attempt
 * order, the first-error rule, the per-model process latch, and that
 * cancellation is never treated as a backend verdict.
 */
class EngineBackendLadderTest {

    private class Attempts {
        val backends = mutableListOf<Boolean>()
        var gpuFailure: Throwable? = null
        var cpuFailure: Throwable? = null

        suspend fun run(useGpu: Boolean): String {
            backends += useGpu
            (if (useGpu) gpuFailure else cpuFailure)?.let { throw it }
            return if (useGpu) "gpu" else "cpu"
        }
    }

    @Test fun gpuSuccessNeedsNoFallback() = runBlocking {
        val attempts = Attempts()
        val result = EngineBackendLadder().run("model-a", gpuRequested = true, attempts::run)
        assertEquals("gpu", result)
        assertEquals(listOf(true), attempts.backends)
    }

    @Test fun gpuFailureFallsBackToCpuOnce() = runBlocking {
        val attempts = Attempts()
        attempts.gpuFailure = IllegalStateException("gpu boom")
        val result = EngineBackendLadder().run("model-a", gpuRequested = true, attempts::run)
        assertEquals("cpu", result)
        assertEquals(listOf(true, false), attempts.backends)
    }

    @Test fun bothAttemptsFailingSurfaceTheFirstError() = runBlocking {
        val attempts = Attempts()
        val gpuError = IllegalStateException("gpu boom")
        val cpuError = IllegalStateException("cpu boom")
        attempts.gpuFailure = gpuError
        attempts.cpuFailure = cpuError
        try {
            EngineBackendLadder().run("model-a", gpuRequested = true, attempts::run)
            fail("the GPU failure must surface")
        } catch (error: IllegalStateException) {
            assertSame(gpuError, error)
            assertTrue(error.suppressed.contains(cpuError))
        }
        assertEquals(listOf(true, false), attempts.backends)
    }

    @Test fun gpuFailureIsLatchedPerModelForTheProcess() = runBlocking {
        val ladder = EngineBackendLadder()
        val attempts = Attempts()
        attempts.gpuFailure = IllegalStateException("gpu boom")
        assertEquals("cpu", ladder.run("model-a", gpuRequested = true, attempts::run))
        // Same model: the failed GPU attempt is not repaid on the next rebuild.
        attempts.backends.clear()
        attempts.gpuFailure = null
        assertEquals("cpu", ladder.run("model-a", gpuRequested = true, attempts::run))
        assertEquals(listOf(false), attempts.backends)
        // A different model still gets its own GPU attempt.
        attempts.backends.clear()
        assertEquals("gpu", ladder.run("model-b", gpuRequested = true, attempts::run))
        assertEquals(listOf(true), attempts.backends)
    }

    @Test fun cpuOnlyRequestNeverAttemptsGpu() = runBlocking {
        val attempts = Attempts()
        val result = EngineBackendLadder().run("model-a", gpuRequested = false, attempts::run)
        assertEquals("cpu", result)
        assertEquals(listOf(false), attempts.backends)
        // And its failure propagates untouched, with no retry.
        val cpuError = IllegalStateException("cpu boom")
        attempts.cpuFailure = cpuError
        try {
            EngineBackendLadder().run("model-a", gpuRequested = false, attempts::run)
            fail("the CPU failure must surface")
        } catch (error: IllegalStateException) {
            assertSame(cpuError, error)
        }
        assertEquals(listOf(false, false), attempts.backends)
    }

    @Test fun cancellationIsNeverRetriedOrLatched() = runBlocking {
        val ladder = EngineBackendLadder()
        val attempts = Attempts()
        attempts.gpuFailure = CancellationException("caller cancelled")
        try {
            ladder.run("model-a", gpuRequested = true, attempts::run)
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
        }
        assertEquals(listOf(true), attempts.backends)
        // Not latched: the next creation still tries the requested backend.
        attempts.gpuFailure = null
        attempts.backends.clear()
        assertEquals("gpu", ladder.run("model-a", gpuRequested = true, attempts::run))
        assertEquals(listOf(true), attempts.backends)
    }

    @Test fun cancellationDuringTheCpuFallbackStillPropagates() = runBlocking {
        val attempts = Attempts()
        val gpuError = IllegalStateException("gpu boom")
        attempts.gpuFailure = gpuError
        attempts.cpuFailure = CancellationException("caller cancelled")
        try {
            EngineBackendLadder().run("model-a", gpuRequested = true, attempts::run)
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(expected.suppressed.contains(gpuError))
        }
        assertEquals(listOf(true, false), attempts.backends)
    }
}
