package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CaptureFirstReplyHandoffTest {
    private fun receipt(turn: String = "old") = SpeechDelivery(turn, SpeechDeliveryState.COMPLETED,
        playedFrames = 320, spans = listOf(DeliveredSpeechSpan(0, "Done.", 0, 320, 16000, sealed = true)))
    private fun playback() = requireNotNull(NormalReplyPlayback.from("old", receipt(), 100))
    private fun turn(text: String) = CapturedVoiceTurn(text, byteArrayOf(1, 2))
    private fun unusedInput() = object : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override fun chunks() = emptyFlow<ByteArray>()
    }

    @Test fun playbackRequiresCompletedSameTurnPositiveSealedDeliveredFrames() {
        assertEquals(NormalReplyPlayback("old", 123), NormalReplyPlayback.from("old", receipt(), 123))
        assertNull(NormalReplyPlayback.from("old", null, 123))
        assertNull(NormalReplyPlayback.from("other", receipt(), 123))
        SpeechDeliveryState.entries.filter { it != SpeechDeliveryState.COMPLETED }.forEach { state ->
            assertNull("$state must not rearm capture", NormalReplyPlayback.from("old", receipt().copy(state = state), 123))
        }
        val full = receipt(); val span = full.spans.single()
        listOf(
            full.copy(playedFrames = 0),
            full.copy(spans = emptyList()),
            full.copy(spans = listOf(span.copy(sealed = false))),
            full.copy(spans = listOf(span.copy(endFrame = span.startFrame))),
            full.copy(playedFrames = span.endFrame - 1),
            full.copy(spans = full.spans + span.copy(index = 1, startFrame = 320, endFrame = 640)),
        ).forEach { assertNull(NormalReplyPlayback.from("old", it, 123)) }
    }

    @Test fun completedReplyJobAloneNeverStartsFollowupCapture() = runBlocking {
        val noPlayback = CompletableDeferred<NormalReplyPlayback>()
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = noPlayback, reply = { "silent answer" }, listen = { awaitCancellation() },
            stopReply = { fail("No interruption") },
            beginCapture = { error("Job completion is not successful playback") },
            capture = { _, _ -> error("No audio delivered") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
        assertEquals(CaptureFirstReplyHandoff.Result.Finished("silent answer"), result)
        assertFalse(noPlayback.isCompleted)
    }



    @Test fun acceptedTypedInputCancelsCaptureAndWaitsForItsExactClose() = runBlocking {
        val typed = CompletableDeferred<Unit>(); val capturing = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>(); val releaseClose = CompletableDeferred<Unit>()
        var replyReleased = false
        try {
            val answer = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(playback()),
                    reply = { typed.await(); replyReleased = true; "done" }, listen = { awaitCancellation() },
                    stopReply = { fail("Typed followup is not interruption") }, beginCapture = { unusedInput() },
                    capture = { _, _ ->
                        capturing.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) { closeStarted.complete(Unit); releaseClose.await() } }
                    }, awaitTypedInput = { typed.await() }, hasTypedInput = { typed.isCompleted })
            }
            withTimeout(2000) { capturing.await() }; typed.complete(Unit)
            withTimeout(2000) { closeStarted.await() }
            assertTrue(replyReleased)
            assertFalse(answer.isCompleted)
            releaseClose.complete(Unit)
            assertEquals(CaptureFirstReplyHandoff.Result.Finished("done"), withTimeout(2000) { answer.await() })
        } finally { releaseClose.complete(Unit) }
    }

    @Test fun confirmedInterruptionWinsAlreadyCompletedPlaybackWithoutRearming() = runBlocking {
        val confirmed = CompletableDeferred<Unit>()
        val normal = CompletableDeferred<NormalReplyPlayback>()
        val expected = turn("stop and change it")
        var stopCount = 0
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = normal, reply = { confirmed.await(); awaitCancellation() },
            listen = { callback -> callback(); confirmed.complete(Unit); normal.complete(playback()); expected },
            stopReply = { stopCount++ }, beginCapture = { error("Confirmed interruption must win") },
            capture = { _, _ -> error("No ordinary handoff") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
        assertEquals(CaptureFirstReplyHandoff.Result.Interrupted(expected), result)
        assertEquals(1, stopCount)
    }

    @Test fun failedOutputPropagatesWithoutCaptureOrSuccessfulCompletion() = runBlocking {
        supervisorScope {
            val failure = IllegalStateException("output route failed")
            val result = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(), reply = { throw failure }, listen = { awaitCancellation() },
                    stopReply = {}, beginCapture = { error("Failed output must not arm") },
                    capture = { _, _ -> error("Failed output must not capture") }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            try { result.await(); fail("Failure became Finished") }
            catch (actual: IllegalStateException) { assertEquals(failure.message, actual.message) }
        }
    }

    @Test fun nativeResetFailurePropagatesInsteadOfReleasingNextTurn() = runBlocking {
        supervisorScope {
            val captured = CompletableDeferred<Unit>()
            var nativeReady: (() -> Boolean)? = null
            val result = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(playback()),
                    reply = { captured.await(); error("old native owner quarantined") }, listen = { awaitCancellation() },
                    stopReply = {}, beginCapture = { unusedInput() },
                    capture = { _, ready -> nativeReady = ready; assertFalse(ready()); captured.complete(Unit); turn("must not run") },
                    awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            try { withTimeout(2000) { result.await() }; fail("Quarantined owner released a next turn") }
            catch (actual: IllegalStateException) { assertEquals("old native owner quarantined", actual.message) }
            assertFalse(requireNotNull(nativeReady).invoke())
        }
    }

    @Test fun cancellationJoinsBothOldListenerAndNewCaptureCleanup() = runBlocking {
        val listenerStarted = CompletableDeferred<Unit>(); val listenerClosing = CompletableDeferred<Unit>()
        val releaseListener = CompletableDeferred<Unit>(); val rawReady = CompletableDeferred<Unit>()
        val normal = CompletableDeferred<NormalReplyPlayback>()
        var replyClosed = false; var listenerClosed = false; var captureCalls = 0
        try {
            val task = launch {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = normal,
                    reply = { try { awaitCancellation() } finally { replyClosed = true } },
                    listen = {
                        listenerStarted.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            listenerClosing.complete(Unit); releaseListener.await(); listenerClosed = true
                        } }
                    }, stopReply = {}, beginCapture = { unusedInput().also { rawReady.complete(Unit) } },
                    capture = { _, _ -> captureCalls++; awaitCancellation() },
                    awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            withTimeout(2000) { listenerStarted.await() }; normal.complete(playback())
            withTimeout(2000) { rawReady.await(); listenerClosing.await() }
            task.cancel(); yield()
            assertFalse(task.isCompleted)
            assertFalse(listenerClosed)
            releaseListener.complete(Unit); withTimeout(2000) { task.join() }
            assertTrue(replyClosed); assertTrue(listenerClosed); assertEquals(0, captureCalls)
        } finally { releaseListener.complete(Unit) }
    }
    @Test fun typedInputAlreadyAcceptedDoesNotConstructOrdinaryRecognizer() = runBlocking {
        var captures = 0; var begins = 0
        val result = CaptureFirstReplyHandoff.run(
            normalPlayback = CompletableDeferred(playback()), reply = { "done" }, listen = { awaitCancellation() },
            stopReply = { fail("Not an interruption") }, beginCapture = { begins++; unusedInput() },
            capture = { _, _ -> captures++; error("Typed input already owns next turn") },
            awaitTypedInput = {}, hasTypedInput = { true })
        assertEquals(CaptureFirstReplyHandoff.Result.Finished("done"), result)
        assertEquals(1, begins)
        assertEquals(0, captures)
    }

    @Test fun cancelledPlaybackReceiptCannotBecomeNormalSuccess() = runBlocking {
        val normal = CompletableDeferred<NormalReplyPlayback>()
        normal.cancel(CancellationException("output cancelled"))
        try {
            CaptureFirstReplyHandoff.run(
                normalPlayback = normal, reply = { awaitCancellation() }, listen = { awaitCancellation() },
                stopReply = {}, beginCapture = { error("Cancelled output must not arm") },
                capture = { _, _ -> error("Cancelled output must not capture") },
                awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            fail("Cancelled playback returned success")
        } catch (error: CancellationException) { assertEquals("output cancelled", error.message) }
    }



}
