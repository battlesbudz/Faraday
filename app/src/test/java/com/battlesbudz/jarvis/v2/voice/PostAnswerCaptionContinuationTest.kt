package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PostAnswerCaptionContinuationTest {
    @Test fun oldCaptionContinuesAfterPlaybackWhenNoNextInputExists() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finishCaption = CompletableDeferred<String>()
        val events = mutableListOf<String>()
        val caption = async {
            PostAnswerCaptionContinuation.run(
                isCurrent = { true }, hasNextInput = { false }, awaitNextInput = { awaitCancellation() },
                generate = { started.complete(Unit); finishCaption.await() },
                checkedReset = { events += "reset" }, observe = events::add)
        }
        withTimeout(2000) { started.await() }
        yield()
        assertFalse(caption.isCompleted)
        assertFalse(events.contains("caption_cancel_requested"))
        assertTrue(events.isEmpty())
        finishCaption.complete("the user's exact old words")
        assertEquals(PostAnswerCaptionContinuation.Result(PostAnswerCaptionContinuation.End.COMPLETED,
            "the user's exact old words"), withTimeout(2000) { caption.await() })
        assertEquals(listOf("caption_child_joined", "reset", "caption_native_reset_complete"), events)
    }

    @Test fun acceptedInputCancelsExactCaptionJoinsNativeCloseThenResets() = runBlocking {
        val accepted = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>(); val releaseClose = CompletableDeferred<Unit>()
        val unrelated = launch { awaitCancellation() }
        val events = mutableListOf<String>()
        var exactCaptionJob: Job? = null
        try {
            val task = async {
                PostAnswerCaptionContinuation.run(
                    isCurrent = { true }, hasNextInput = { accepted.isCompleted }, awaitNextInput = { accepted.await() },
                    generate = {
                        exactCaptionJob = currentCoroutineContext()[Job]; started.complete(Unit)
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            events += "native-close-started"; closing.complete(Unit); releaseClose.await()
                            events += "native-close-finished"
                        } }
                    }, checkedReset = {
                        assertTrue(requireNotNull(exactCaptionJob).isCompleted)
                        assertTrue(unrelated.isActive)
                        events += "reset"
                    }, observe = events::add)
            }
            withTimeout(2000) { started.await() }; accepted.complete(Unit)
            withTimeout(2000) { closing.await() }
            assertFalse(task.isCompleted)
            assertFalse(events.contains("reset"))
            assertTrue(unrelated.isActive)
            releaseClose.complete(Unit)
            assertEquals(PostAnswerCaptionContinuation.End.NEXT_INPUT, withTimeout(2000) { task.await() }.end)
            assertTrue(events.indexOf("native-close-finished") < events.indexOf("caption_child_joined"))
            assertTrue(events.indexOf("caption_child_joined") < events.indexOf("reset"))
            assertTrue(events.indexOf("reset") < events.indexOf("caption_native_reset_complete"))
        } finally { releaseClose.complete(Unit); unrelated.cancelAndJoin() }
    }

    @Test fun preexistingNextInputDoesNotStartCaptionOrResetSomeoneElsesOwner() = runBlocking {
        val result = PostAnswerCaptionContinuation.run(
            isCurrent = { true }, hasNextInput = { true },
            awaitNextInput = { error("Already accepted") }, generate = { error("Must not begin a stale caption") },
            checkedReset = { error("No native owner was started") })
        assertEquals(PostAnswerCaptionContinuation.End.NEXT_INPUT, result.end)
    }

    @Test fun nextInputArrivingDuringListenerRegistrationPreventsCaptionStart() = runBlocking {
        var accepted = false; var listenerClosed = false
        val result = PostAnswerCaptionContinuation.run(
            isCurrent = { true }, hasNextInput = { accepted },
            awaitNextInput = { accepted = true; try { awaitCancellation() } finally { listenerClosed = true } },
            generate = { error("Registration race must not launch caption") }, checkedReset = { error("No caption owner") })
        assertEquals(PostAnswerCaptionContinuation.End.NEXT_INPUT, result.end)
        assertTrue(listenerClosed)
    }

    @Test fun replacedOwnerNeverStartsNewCaption() = runBlocking {
        val result = PostAnswerCaptionContinuation.run(
            isCurrent = { false }, hasNextInput = { false }, awaitNextInput = { error("Not current") },
            generate = { error("Not current") }, checkedReset = { error("Must not reset replacement") })
        assertEquals(PostAnswerCaptionContinuation.End.OWNER_REPLACED, result.end)
    }

    @Test fun replacedOwnerDiscardsLateCaptionValueAfterJoiningAndResettingItsOwnWork() = runBlocking {
        var current = true; var resets = 0
        val result = PostAnswerCaptionContinuation.run(
            isCurrent = { current }, hasNextInput = { false }, awaitNextInput = { awaitCancellation() },
            generate = { current = false; "stale text" }, checkedReset = { resets++ })
        assertEquals(PostAnswerCaptionContinuation.End.OWNER_REPLACED, result.end)
        assertNull(result.value)
        assertEquals(1, resets)
    }

    @Test fun failedCheckedResetEscapesInsteadOfReportingSuccessfulHandoff() = runBlocking {
        val nativeFailure = IllegalStateException("native owner quarantined")
        val events = mutableListOf<String>()
        var exactCaptionJob: Job? = null
        var resets = 0
        supervisorScope {
            val task = async {
                PostAnswerCaptionContinuation.run(
                    isCurrent = { true }, hasNextInput = { false },
                    awaitNextInput = { try { awaitCancellation() } finally { events += "listener_closed" } },
                    generate = { exactCaptionJob = currentCoroutineContext()[Job]; "caption" },
                    checkedReset = {
                        assertTrue(requireNotNull(exactCaptionJob).isCompleted)
                        resets++
                        events += "reset"
                        throw nativeFailure
                    }, observe = events::add)
            }
            try { task.await(); fail("Reset failure was swallowed") }
            catch (error: PostAnswerCaptionContinuation.NativeReleaseFailure) {
                assertEquals("Caption native release/reset failed", error.message)
                // Coroutine stacktrace recovery may add a same-type wrapper at await.
                assertSame("The exact native failure must remain the root cause for quarantine",
                    nativeFailure, generateSequence<Throwable>(error) { it.cause }.last())
            }
        }
        assertEquals(1, resets)
        assertEquals(listOf("caption_child_joined", "listener_closed", "reset"), events)
    }

    @Test fun captionGenerationFailureStillResetsAndPropagates() = runBlocking {
        var resets = 0
        supervisorScope {
            val task = async {
                PostAnswerCaptionContinuation.run(
                    isCurrent = { true }, hasNextInput = { false }, awaitNextInput = { awaitCancellation() },
                    generate = { error("caption generation failed") }, checkedReset = { resets++ })
            }
            try { task.await(); fail("Native caption failure was swallowed") }
            catch (error: IllegalStateException) {
                assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "caption generation failed" })
            }
        }
        assertEquals(1, resets)
    }

    @Test fun captionNativeCloseFailureOnAcceptedInputMustPropagateForQuarantine() = runBlocking {
        val started = CompletableDeferred<Unit>(); val accepted = CompletableDeferred<Unit>()
        supervisorScope {
            val task = async {
                PostAnswerCaptionContinuation.run(
                    isCurrent = { true }, hasNextInput = { accepted.isCompleted }, awaitNextInput = { accepted.await() },
                    generate = {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { error("native caption close failed") }
                    }, checkedReset = {})
            }
            withTimeout(2000) { started.await() }; accepted.complete(Unit)
            try { task.await(); fail("Native close failure was swallowed; caller cannot quarantine old owner") }
            catch (error: IllegalStateException) {
                assertTrue("Native close cause must remain inspectable",
                    generateSequence<Throwable>(error) { it.cause }.any { it.message == "native caption close failed" })
            }
        }
    }

    @Test fun boundedCaptionTimeoutJoinsAndResetsBeforeReportingTimeout() = runBlocking {
        val events = mutableListOf<String>()
        val result = withTimeout(2000) {
            PostAnswerCaptionContinuation.run(
                isCurrent = { true }, hasNextInput = { false }, awaitNextInput = { awaitCancellation() },
                generate = { try { awaitCancellation() } finally { events += "closed" } },
                checkedReset = { events += "reset" }, timeoutMs = 25)
        }
        assertEquals(PostAnswerCaptionContinuation.End.TIMEOUT, result.end)
        assertEquals(listOf("closed", "reset"), events)
    }

    @Test fun parentCancellationIsNotMistakenForCaptionTimeoutAndStillResets() = runBlocking {
        val started = CompletableDeferred<Unit>(); val events = mutableListOf<String>()
        var returnedNormally = false
        val task = launch {
            PostAnswerCaptionContinuation.run(
                isCurrent = { true }, hasNextInput = { false }, awaitNextInput = { awaitCancellation() },
                generate = { started.complete(Unit); try { awaitCancellation() } finally { events += "closed" } },
                checkedReset = { currentCoroutineContext().ensureActive(); events += "reset" })
            returnedNormally = true
        }
        withTimeout(2000) { started.await() }; task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(listOf("closed", "reset"), events)
    }
}
