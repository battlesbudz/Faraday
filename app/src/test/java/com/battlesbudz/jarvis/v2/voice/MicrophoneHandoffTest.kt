package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class MicrophoneHandoffTest {
    @After fun release() { MicrophoneHandoff.finishDictation(); MicrophoneHandoff.installMonitor(null) }
    @Test fun dictationHasExclusivePriorityUntilCleanupFinishes() {
        assertTrue(MicrophoneHandoff.requestDictation())
        assertTrue(MicrophoneHandoff.dictationRequested)
        assertFalse(MicrophoneHandoff.requestDictation())
        MicrophoneHandoff.finishDictation()
        assertFalse(MicrophoneHandoff.dictationRequested)
        assertTrue(MicrophoneHandoff.requestDictation())
    }
    @Test fun cancellationBeforeRecognitionStartsReleasesPriority() = runBlocking {
        assertTrue(MicrophoneHandoff.requestDictation())
        val owner = Job().apply { cancel() }
        val recognition = CoroutineScope(owner).launch { error("Must never run") }
        val cleanupFinished = CompletableDeferred<Unit>()
        recognition.invokeOnCompletion {
            MicrophoneHandoff.finishDictation()
            cleanupFinished.complete(Unit)
        }
        recognition.join()
        // A completed Job can be observed before another thread's completion
        // handler returns. Assert the cleanup contract after that handler, too.
        withTimeout(1000) { cleanupFinished.await() }
        assertFalse(MicrophoneHandoff.dictationRequested)
    }
    @Test fun externalRecordingHoldsUntilMonitorConfirmsRelease() {
        MicrophoneHandoff.interrupted.value = true
        assertTrue(MicrophoneHandoff.shouldYield)
        assertTrue(MicrophoneHandoff.requestDictation())
        MicrophoneHandoff.finishDictation()
        assertTrue(MicrophoneHandoff.shouldYield)
        MicrophoneHandoff.interrupted.value = false
        assertFalse(MicrophoneHandoff.shouldYield)
    }
    @Test fun priorityRequestStopsCaptureBeforeNotifyingRuntime() {
        val recorder = Any()
        var recording = true
        var stops = 0
        try {
            MicrophoneHandoff.registerRecorder(recorder, { recording }, { recording = false; stops++ })
            MicrophoneHandoff.installMonitor {
                assertFalse("Hardware capture must stop before runtime cleanup", recording)
                assertTrue(MicrophoneHandoff.shouldYield)
            }
            MicrophoneHandoff.requestInterruption("keyboard_microphone_button")
            assertEquals(1, stops)
            assertEquals(0, MicrophoneHandoff.recordingCount())
            assertEquals(1, MicrophoneHandoff.backgroundRecorders.get()) // release still pending
            assertTrue(MicrophoneHandoff.consumeRequest())
            assertFalse(MicrophoneHandoff.consumeRequest())
            try {
                MicrophoneHandoff.registerRecorder(Any(), { true }, {})
                fail("Must not reacquire during handoff")
            } catch (_: IllegalStateException) { }
        } finally {
            MicrophoneHandoff.installMonitor(null)
            MicrophoneHandoff.unregisterRecorder(recorder)
        }
    }
    @Test fun stopAndReleaseCannotExposeAnInconsistentOwnershipSnapshot() {
        val recorder = Any()
        val started = java.util.concurrent.CountDownLatch(1)
        val finishStart = java.util.concurrent.CountDownLatch(1)
        val sampled = java.util.concurrent.CountDownLatch(1)
        var result = -1
        val owner = Thread {
            MicrophoneHandoff.withRecorderLock {
                started.countDown()
                check(finishStart.await(2, java.util.concurrent.TimeUnit.SECONDS))
                MicrophoneHandoff.registerRecorder(recorder, { true }, {})
            }
        }
        val observer = Thread {
            result = MicrophoneHandoff.recordingCount()
            sampled.countDown()
        }
        try {
            owner.start()
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            observer.start()
            assertFalse(sampled.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
            finishStart.countDown()
            owner.join(2000)
            assertTrue(sampled.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, result)
        } finally {
            finishStart.countDown()
            owner.join(2000); observer.join(2000)
            MicrophoneHandoff.unregisterRecorder(recorder)
        }
    }
    @Test fun standaloneCaptureDoesNotLeaveAnUnownedInterruptionLatch() {
        val recorder = Any()
        var stopped = false
        MicrophoneHandoff.installMonitor(null)
        try {
            MicrophoneHandoff.registerRecorder(recorder, { !stopped }, { stopped = true })
            assertTrue(MicrophoneHandoff.requestDictation())
            assertTrue(stopped)
            assertTrue(MicrophoneHandoff.shouldYield)
            MicrophoneHandoff.finishDictation()
            assertFalse(MicrophoneHandoff.shouldYield)
            assertFalse(MicrophoneHandoff.consumeRequest())
        } finally { MicrophoneHandoff.unregisterRecorder(recorder) }
    }
}
