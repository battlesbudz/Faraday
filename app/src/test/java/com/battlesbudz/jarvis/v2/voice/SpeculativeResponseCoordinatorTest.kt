package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.ToolCall
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpeculativeResponseCoordinatorTest {
    private fun identity(prompt: String = "Exact prompt", pcm: ByteArray = byteArrayOf(1, 2, 3, 4),
                         call: String = "call", turn: String = "turn", generation: Long = 1) =
        SpeculativeInputIdentity.audio(call, turn, generation, prompt, pcm)
    private fun result(text: String) = GenerationResult(text, 17, 20.0)
    private class Fixture(val scope: CoroutineScope,
                          val budget: SpeculativeResponseCoordinator.Budget = SpeculativeResponseCoordinator.Budget(),
                          val generate: suspend (String, (String) -> Unit) -> GenerationResult) {
        var rollbacks = 0
        var safe = true
        var current = true
        var failRollback = false
        val events = mutableListOf<String>()
        val lane = SpeculativeResponseCoordinator(scope, generate, rollback = {
            rollbacks++
            if (failRollback) error("checked drain failed")
        }, nativeSafeToRelease = { safe }, ownerIsCurrent = { current }, budget = budget,
            observe = { synchronized(events) { events.add(it) } })
    }

    @Test fun candidateCannotPublishUntilExactFinalEndpointAndPromotesOnlyOnce() = runBlocking<Unit> {
        val f = Fixture(this) { _, token -> token("Answer."); result("Answer.") }
        val spoken = StringBuilder()
        assertTrue(f.lane.propose(identity(), "input"))
        f.lane.awaitIdle()
        assertEquals("", spoken.toString())
        assertEquals(1, f.rollbacks)
        val actual = f.lane.promote(identity(), true, true, spoken::append)
        assertEquals("Answer.", spoken.toString())
        assertEquals(17L, actual!!.timeToFirstTokenMs) // Original launch clock, never promotion clock.
        try { f.lane.promote(identity(), true, true) {}; fail("double promotion") }
        catch (_: IllegalStateException) { }
        assertTrue(f.lane.closeAndDrain())
    }

    @Test fun everyIdentityFieldAndEvenEqualLengthPcmOrPromptMismatchFailsClosed() = runBlocking<Unit> {
        val variants = listOf(identity(prompt = "Other prompt"), identity(pcm = byteArrayOf(1, 2, 3, 5)),
            identity(pcm = byteArrayOf(1, 2)), identity(call = "new call"), identity(turn = "new turn"), identity(generation = 2))
        for (different in variants) {
            val f = Fixture(this) { _, token -> token("Secret"); result("Secret") }
            f.lane.propose(identity(), "input"); f.lane.awaitIdle()
            assertNull(f.lane.promote(different, true, true) { fail("mismatched publication") })
            assertTrue(f.lane.closeAndDrain())
        }
    }

    @Test fun endpointAndOrdinaryRoutingAreIndependentlyRequired() = runBlocking<Unit> {
        for ((endpoint, route) in listOf(false to true, true to false, false to false)) {
            val f = Fixture(this) { _, token -> token("Held"); result("Held") }
            f.lane.propose(identity(), "input"); f.lane.awaitIdle()
            assertNull(f.lane.promote(identity(), endpoint, route) { fail("unauthorized publication") })
            f.lane.closeAndDrain()
        }
    }

    @Test fun resumeAtEveryStageDrainsBeforeReplacementAndNeverPublishesOldTokens() = runBlocking<Unit> {
        for (stage in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val terminal = CompletableDeferred<Unit>()
            var calls = 0
            var oldReturned = false
            val f = Fixture(this) { _, token ->
                calls++
                if (calls == 1) {
                    try {
                        if (stage >= 1) token("Old")
                        entered.complete(Unit)
                        if (stage == 2) terminal.complete(Unit) else awaitCancellation()
                        result("Old")
                    } finally { oldReturned = true }
                } else { assertTrue(oldReturned); token("Fresh"); result("Fresh") }
            }
            f.lane.propose(identity(), "old")
            entered.await()
            if (stage == 2) f.lane.awaitIdle()
            f.lane.invalidate(SpeculativeResponseCoordinator.Invalidation.RESUMED_SPEECH)
            f.lane.awaitIdle()
            assertTrue(oldReturned)
            assertTrue(f.lane.propose(identity(generation = 2), "fresh"))
            f.lane.awaitIdle()
            val visible = StringBuilder()
            assertEquals("Fresh", f.lane.promote(identity(generation = 2), true, true, visible::append)!!.text)
            assertEquals("Fresh", visible.toString())
            assertEquals(2, f.rollbacks)
            f.lane.closeAndDrain()
        }
    }

    @Test fun cancellationWhilePrefillOrDrainIsBlockedRetainsExclusiveOwnership() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, _ ->
            entered.complete(Unit); awaitCancellation()
        }, rollback = { drainEntered.complete(Unit); releaseDrain.await() },
            nativeSafeToRelease = { true }, ownerIsCurrent = { true })
        lane.propose(identity(), "old"); entered.await()
        lane.invalidate(SpeculativeResponseCoordinator.Invalidation.RESUMED_SPEECH)
        drainEntered.await()
        assertFalse(lane.propose(identity(generation = 2), "replacement"))
        val closing = async { lane.closeAndDrain() }
        yield(); assertFalse(closing.isCompleted)
        releaseDrain.complete(Unit)
        assertTrue(closing.await())
    }

    @Test fun lostOwnerAfterPromotionBeforeFirstTokenCannotAuthorizeFallback() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current = true
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, token ->
            started.complete(Unit); release.await(); token("Stale"); result("Stale")
        }, rollback = {}, nativeSafeToRelease = { true }, ownerIsCurrent = { current })
        lane.propose(identity(), "input"); started.await()
        val promoting = async { lane.promote(identity(), true, true) { fail("stale publication") } }
        yield(); current = false; release.complete(Unit)
        try { promoting.await(); fail("lost owner authorized fallback") }
        catch (_: CancellationException) { }
        assertTrue(lane.closeAndDrain())
    }

    @Test fun failedDraftCannotPublishWhileItsCheckedDrainIsStillRunning() = runBlocking<Unit> {
        val drainEntered = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, token ->
            token("Failed draft"); error("native rejected")
        }, rollback = { drainEntered.complete(Unit); releaseDrain.await() },
            nativeSafeToRelease = { true }, ownerIsCurrent = { true })
        lane.propose(identity(), "input")
        drainEntered.await()
        val promoting = async { lane.promote(identity(), true, true) { fail("known failed draft leaked during drain") } }
        yield(); assertFalse(promoting.isCompleted)
        releaseDrain.complete(Unit)
        assertNull(promoting.await())
        assertTrue(lane.closeAndDrain())
    }

    @Test fun lateCallbacksAfterTerminalOrCancellationAreNeverPublished() = runBlocking<Unit> {
        lateinit var callback: (String) -> Unit
        val f = Fixture(this) { _, token -> callback = token; token("Good"); result("Good") }
        f.lane.propose(identity(), "input"); f.lane.awaitIdle()
        callback("late")
        val seen = StringBuilder()
        f.lane.promote(identity(), true, true, seen::append)
        assertEquals("Good", seen.toString())
        f.lane.closeAndDrain(); callback("later")
        assertEquals("Good", seen.toString())
    }

    @Test fun replacementTypedStopAndLostCallRevokeReadyCandidates() = runBlocking<Unit> {
        for (reason in SpeculativeResponseCoordinator.Invalidation.entries) {
            val f = Fixture(this) { _, token -> token("Held"); result("Held") }
            f.lane.propose(identity(), "input"); f.lane.awaitIdle()
            f.lane.invalidate(reason)
            assertNull(f.lane.promote(identity(), true, true) { fail(reason.name) })
            f.lane.closeAndDrain()
        }
    }

    @Test fun changedOwnerWithoutExplicitInvalidationStillCannotPublish() = runBlocking<Unit> {
        val f = Fixture(this) { _, token -> token("Held"); result("Held") }
        f.lane.propose(identity(), "input"); f.lane.awaitIdle(); f.current = false
        assertNull(f.lane.promote(identity(), true, true) { fail("stale call") })
        assertTrue(f.lane.closeAndDrain())
    }

    @Test fun checkedDrainFailureQuarantinesAndForbidsFallbackOrReplacement() = runBlocking<Unit> {
        for (closeThrows in listOf(false, true)) {
            val f = Fixture(this) { _, token -> token("Held"); result("Held") }
            if (closeThrows) f.failRollback = true else f.safe = false
            f.lane.propose(identity(), "input")
            try { f.lane.awaitIdle(); fail("must quarantine") }
            catch (_: SpeculativeResponseCoordinator.Quarantined) { }
            assertFalse(f.lane.propose(identity(generation = 2), "replacement"))
            assertFalse(f.lane.closeAndDrain())
        }
    }

    @Test fun safeNativeRejectionFallsBackWithoutPublishingBufferedDraft() = runBlocking<Unit> {
        val f = Fixture(this) { _, token -> token("Partial"); error("native rejected") }
        f.lane.propose(identity(), "input"); f.lane.awaitIdle()
        assertNull(f.lane.promote(identity(), true, true) { fail("failed draft leaked") })
        assertEquals(1, f.rollbacks); assertTrue(f.lane.closeAndDrain())
    }

    @Test fun allBudgetsBoundBacklogAndWorkAndRejectBeforePublication() = runBlocking<Unit> {
        val budgets = listOf(
            SpeculativeResponseCoordinator.Budget(maxHeldChars = 3),
            SpeculativeResponseCoordinator.Budget(maxHeldUtf8Bytes = 3),
            SpeculativeResponseCoordinator.Budget(maxEstimatedTokens = 1),
            SpeculativeResponseCoordinator.Budget(maxQueuedCallbacks = 1))
        for (budget in budgets) {
            val f = Fixture(this, budget) { _, token -> token("AAAA"); token("BBBB"); result("AAAABBBB") }
            f.lane.propose(identity(), "input"); f.lane.awaitIdle()
            assertNull(f.lane.promote(identity(), true, true) { fail("over-budget draft") })
            assertTrue(f.lane.closeAndDrain())
        }
    }

    @Test fun generationTimeoutCancelsAndDrainsBeforeSafeFallback() = runBlocking<Unit> {
        val returned = AtomicBoolean()
        val f = Fixture(this, SpeculativeResponseCoordinator.Budget(maxGenerationMs = 20)) { _, _ ->
            try { awaitCancellation() } finally { returned.set(true) }
        }
        f.lane.propose(identity(), "input"); f.lane.awaitIdle()
        assertTrue(returned.get()); assertEquals(1, f.rollbacks)
        assertNull(f.lane.promote(identity(), true, true) { fail("timeout published") })
        f.lane.closeAndDrain()
    }

    @Test fun noCandidateQueueAndAtMostTwoAttempts() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val f = Fixture(this) { _, _ -> entered.complete(Unit); awaitCancellation() }
        assertTrue(f.lane.propose(identity(), "first")); entered.await()
        repeat(1000) { assertFalse(f.lane.propose(identity(generation = 2), "queued")) }
        f.lane.invalidate(SpeculativeResponseCoordinator.Invalidation.RESUMED_SPEECH); f.lane.awaitIdle()
        assertTrue(f.lane.propose(identity(generation = 2), "second"))
        f.lane.invalidate(SpeculativeResponseCoordinator.Invalidation.STOP); f.lane.awaitIdle()
        assertFalse(f.lane.propose(identity(generation = 3), "third"))
        f.lane.closeAndDrain()
    }

    @Test fun toolOrUnstreamedTerminalDataIsRejected() = runBlocking<Unit> {
        val generators: List<suspend (String, (String) -> Unit) -> GenerationResult> = listOf(
            { _, token -> token("Held"); result("Held").copy(toolCalls = listOf(ToolCall("action", "{}"))) },
            { _, _ -> result("Not streamed") },
            { _, token -> token("AAAA"); result("BBBB") })
        for (generate in generators) {
            val f = Fixture(this, generate = generate)
            f.lane.propose(identity(), "input"); f.lane.awaitIdle()
            assertNull(f.lane.promote(identity(), true, true) { fail("invalid terminal") })
            f.lane.closeAndDrain()
        }
    }
    @Test fun confirmedStreamingContinuesBeyondSpeculativeDeadlineWithoutWaitingForTerminal() = runBlocking<Unit> {
        val firstReady = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, token ->
            token("Opening"); firstReady.complete(Unit); finish.await(); token(" answer"); result("Opening answer")
        }, rollback = {}, nativeSafeToRelease = { true }, ownerIsCurrent = { true },
            budget = SpeculativeResponseCoordinator.Budget(maxGenerationMs = 100))
        lane.propose(identity(), "input"); firstReady.await()
        val heard = CompletableDeferred<Unit>(); val text = StringBuilder()
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            lane.promote(identity(), true, true) { text.append(it); heard.complete(Unit) }
        }
        heard.await(); assertFalse(answer.isCompleted)
        delay(150); finish.complete(Unit)
        assertEquals("Opening answer", answer.await()!!.text)
        assertEquals("Opening answer", text.toString())
        assertTrue(lane.closeAndDrain())
    }

    @Test fun failureAfterConfirmedOutputNeverFallsBackAndReplays() = runBlocking<Unit> {
        val failNow = CompletableDeferred<Unit>()
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, token ->
            token("Opening"); failNow.await(); error("late native failure")
        }, rollback = {}, nativeSafeToRelease = { true }, ownerIsCurrent = { true })
        lane.propose(identity(), "input")
        var tokens = 0
        try {
            lane.promote(identity(), true, true) { tokens++; failNow.complete(Unit) }
            fail("must propagate after output")
        } catch (expected: IllegalStateException) { assertEquals("late native failure", expected.message) }
        assertEquals(1, tokens); assertTrue(lane.closeAndDrain())
    }

    @Test fun livePromotionKeepsCallbackBacklogBoundedAndDoesNotReplayAfterOverflow() = runBlocking<Unit> {
        val continueNative = CompletableDeferred<Unit>()
        val lane = SpeculativeResponseCoordinator<String>(this, generate = { _, token ->
            token("first"); continueNative.await(); token("a"); token("b"); result("firstab")
        }, rollback = {}, nativeSafeToRelease = { true }, ownerIsCurrent = { true },
            budget = SpeculativeResponseCoordinator.Budget(maxQueuedCallbacks = 1), dispatcher = Dispatchers.Unconfined)
        lane.propose(identity(), "input")
        val text = StringBuilder()
        try {
            lane.promote(identity(), true, true) { text.append(it); continueNative.complete(Unit) }
            fail("bounded live queue must reject overflow")
        } catch (expected: IllegalStateException) { assertEquals("Speculative response budget exceeded", expected.message) }
        assertTrue(text.startsWith("first")); assertFalse(text.toString().contains("b"))
        assertTrue(lane.closeAndDrain())
    }

}
