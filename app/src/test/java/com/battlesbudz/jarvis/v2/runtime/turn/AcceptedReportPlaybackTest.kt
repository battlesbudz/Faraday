package com.battlesbudz.jarvis.v2.runtime.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AcceptedReportPlaybackTest {
    @Test fun cancelledPumpWaitsForItsExactWriterCleanupBeforeReleasingOrDetaching() = runBlocking {
        val events = mutableListOf<String>()
        val writerFinalizing = CompletableDeferred<Unit>()
        val allowWriterCleanup = CompletableDeferred<Unit>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    writerFinalizing.complete(Unit)
                    allowWriterCleanup.await()
                    events += "writer cleanup complete"
                }
            }
        }
        val playback = AcceptedReportPlayback(
            stop = { events += "playback stopped" },
            release = { assertTrue(writer.isCompleted); events += "output released" },
            detach = { events += "output detached" }
        )
        playback.job = writer
        val pump = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                playback.close()
            }
        }

        pump.cancel()
        writerFinalizing.await()
        assertEquals(listOf("playback stopped"), events)
        assertFalse(pump.isCompleted)
        allowWriterCleanup.complete(Unit)
        pump.join()
        playback.close()

        assertEquals(listOf("playback stopped", "writer cleanup complete", "output released", "output detached"), events)
        assertTrue(writer.isCancelled)
    }

    @Test fun stopFailureStillJoinsTheWriterAndReleasesExactlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    yield()
                    events += "writer cleanup complete"
                }
            }
        }
        val playback = AcceptedReportPlayback(
            stop = { events += "stop failed"; error("AudioTrack stop failed") },
            release = { assertTrue(writer.isCompleted); events += "output released" },
            detach = { events += "output detached" }
        )
        playback.job = writer

        playback.close()
        playback.close()

        assertEquals(listOf("stop failed", "writer cleanup complete", "output released", "output detached"), events)
    }

    @Test fun releaseFailurePropagatesAfterJoiningButCannotSkipDetachOrRepeatCleanup() = runBlocking {
        val failure = IllegalStateException("output release failed")
        val events = mutableListOf<String>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                events += "writer cleanup complete"
            }
        }
        val playback = AcceptedReportPlayback(
            stop = { events += "playback stopped" },
            release = { assertTrue(writer.isCompleted); events += "release failed"; throw failure },
            detach = { events += "output detached" }
        )
        playback.job = writer

        try {
            playback.close()
            fail("expected release failure")
        } catch (caught: IllegalStateException) {
            // Coroutine stacktrace recovery may copy the exception across withContext.
            // Require the original failure in its cause chain, not an unrelated lookalike.
            assertEquals(failure.message, caught.message)
            assertTrue(generateSequence(caught as Throwable?) { it.cause }.any { it === failure })
        }
        playback.close()

        assertEquals(listOf("playback stopped", "writer cleanup complete", "release failed", "output detached"), events)
    }

    @Test fun failureBeforeWriterCreationReleasesTheAlreadyRegisteredOutputWithoutCancellingProcessWork() = runBlocking {
        val processWork = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        val events = mutableListOf<String>()
        val pumpLifetime = AcceptedFollowupLifetime()
        pumpLifetime.report = AcceptedReportPlayback(
            stop = { events += "playback stopped" },
            release = { events += "output released" },
            detach = { events += "output detached" }
        )

        // The stage registers the resource before creating its report coroutine. A setup
        // failure in that interval must release output while leaving accepted work alive.
        pumpLifetime.close()
        pumpLifetime.close()

        assertNull(pumpLifetime.report)
        assertEquals(listOf("playback stopped", "output released", "output detached"), events)
        assertTrue(processWork.isActive)
        processWork.cancel()
        processWork.join()
    }
}
