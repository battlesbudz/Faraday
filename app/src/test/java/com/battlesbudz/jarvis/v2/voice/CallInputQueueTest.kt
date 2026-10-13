package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallInputQueueTest {
    @Test fun preservesCallBoundFifoAndRejectsOldOrFull() {
        val q = CallInputQueue(1)
        val a = CallFinalInput("a", "call", "thread", "one", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(a, "call"))
        assertEquals(CallInputAdmission.Full, q.offer(a.copy(id = "b"), "call"))
        assertEquals(a, q.poll("call"))
        assertEquals(CallInputAdmission.Ended, q.offer(a, "other"))
        assertTrue(q.end("call").isEmpty())
    }

    @Test fun ownershipRejectionRestoresTheSameFrontInput() {
        val q = CallInputQueue()
        val input = CallFinalInput("a", "call", "thread", "one", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(input, "call"))
        assertEquals(input, q.claim("call"))
        assertTrue(q.restore(input, "call"))
        assertEquals(input, q.poll("call"))
    }

    @Test fun endingCallReturnsEveryAdmittedUnprocessedInput() {
        val q = CallInputQueue()
        val input = CallFinalInput("kept", "call", "thread", "follow-up", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(input, "call"))
        assertEquals(listOf(input), q.end("call"))
        assertFalse(q.hasPending("call"))
    }

    @Test fun typedArrivalWakesOwnerWithoutConsumingOrReorderingIt() = runBlocking {
        val q = CallInputQueue()
        val wake = async { q.awaitAvailable("call") }
        val first = CallFinalInput("first", "call", "thread", "one", 1)
        val second = first.copy(id = "second", text = "two")
        assertEquals(CallInputAdmission.Queued, q.offer(first, "call"))
        assertEquals(CallInputAdmission.Queued, q.offer(second, "call"))
        wake.await()
        assertEquals(first, q.poll("call"))
        assertEquals(second, q.poll("call"))
    }
    @Test fun endBeforeLateOfferRejectsOldCallAndDoesNotConsumeNewCallCapacity() {
        val q = CallInputQueue(1)
        val old = CallFinalInput("old", "old-call", "thread", "late", 1)
        q.end("old-call")
        assertEquals(CallInputAdmission.Ended, q.offer(old, "old-call"))
        val next = old.copy(id = "next", callId = "next-call")
        assertEquals(CallInputAdmission.Queued, q.offer(next, "next-call"))
        assertEquals(next, q.poll("next-call"))
    }

    @Test fun dispatchableControlPassesRetainedOrdinaryWithoutReorderingOrdinaryInputs() {
        val q = CallInputQueue(4)
        val ordinary = CallFinalInput("ordinary", "call", "thread", "tell me a joke", 1)
        val control = ordinary.copy(id = "cancel", text = "cancel that action")
        assertEquals(CallInputAdmission.Queued, q.offer(ordinary, "call"))
        assertEquals(CallInputAdmission.Queued, q.offer(control, "call"))
        assertEquals(control, q.pollDispatchable("call") { it.text.startsWith("cancel") })
        assertEquals(ordinary, q.poll("call"))
    }

    @Test fun fullOrdinaryFifoStillAdmitsOneBoundedScopedControl() {
        val q = CallInputQueue(1)
        val ordinary = CallFinalInput("ordinary", "call", "thread", "question", 1)
        val control = ordinary.copy(id = "control", text = "cancel that action")
        assertEquals(CallInputAdmission.Queued, q.offer(ordinary, "call"))
        assertEquals(CallInputAdmission.Queued, q.offer(control, "call", priorityControl = true))
        assertEquals(CallInputAdmission.Full, q.offer(control.copy(id = "overflow"), "call", priorityControl = true))
        assertEquals(control, q.pollDispatchable("call") { it.id == "control" })
        assertEquals(ordinary, q.poll("call"))
    }

    @Test fun endDrainsClaimedInputAndForbidsLatePromotion() {
        val q = CallInputQueue(); val input = CallFinalInput("claimed", "call", "thread", "action", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(input, "call"))
        assertEquals(input, q.claim("call"))
        assertEquals(listOf(input), q.end("call"))
        var promoted = false
        assertFalse(q.promote(input) { promoted = true })
        assertFalse(promoted)
    }

    @Test fun restoreTransfersOwnershipAndEndReturnsOneReceipt() {
        val q = CallInputQueue(2); val a = CallFinalInput("a", "call", "thread", "A", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(a, "call")); assertEquals(a, q.claim("call"))
        assertTrue(q.restore(a, "call")); assertEquals(listOf(a), q.end("call"))
    }
    @Test fun externalHandoffCanReclaimItsExactOwnedInput() {
        val q = CallInputQueue(); val a = CallFinalInput("a", "call", "thread", "A", 1)
        assertEquals(CallInputAdmission.Queued, q.offer(a, "call")); assertEquals(a, q.claim("call"))
        assertTrue(q.claimExternal(a)); assertTrue(q.promote(a) {})
    }

    @Test fun terminalizeWinsFailureOnlyWhenEndDidNotAlreadyDrainClaim() {
        val q = CallInputQueue(); val a = CallFinalInput("a", "call", "thread", "A", 1)
        q.offer(a, "call"); q.claim("call")
        assertTrue(q.terminalize(a)); assertFalse(q.terminalize(a)); assertTrue(q.end("call").isEmpty())
        val b = a.copy(id = "b", callId = "next"); q.offer(b, "next"); q.claim("next")
        assertEquals(listOf(b), q.end("next")); assertFalse(q.terminalize(b))
    }

    @Test fun endDuringPreparationRejectsPromotionAndReturnsTheOriginalInputExactlyOnce() {
        val q = CallInputQueue()
        val input = CallFinalInput("preparing", "call-a", "thread", "open settings", 10)
        assertEquals(CallInputAdmission.Queued, q.offer(input, "call-a"))
        // This claim represents a memory/model-preparation barrier outside the queue lock.
        assertEquals(input, q.claim("call-a"))
        val cancelled = q.end("call-a")
        var admissionCount = 0
        assertFalse(q.promote(input) { admissionCount++ })
        assertEquals(listOf(input), cancelled)
        assertEquals(0, admissionCount)
        assertTrue(q.end("call-a").isEmpty())
    }

    @Test fun deferredHandoffIsRejectedWhenEndClosedItsSharedPublicationGate() {
        val q = CallInputQueue(); val input = CallFinalInput("deferred", "call", "thread", "B", 1)
        q.end("call")
        var published = false
        assertFalse(q.publishHandoff(input) { published = true })
        assertFalse(published)
    }

    @Test fun promotedInputCannotBeRestoredOrReplayedByCancellationCleanup() {
        val q = CallInputQueue(); val input = CallFinalInput("promoted", "call", "thread", "ordinary", 1)
        q.offer(input, "call"); assertEquals(input, q.claim("call"))
        assertTrue(q.promote(input) { })
        assertFalse(q.restore(input, "call"))
        assertFalse(q.hasPending("call"))
    }
    @Test fun cancellationRestoreKeepsOnePendingSuccessorAndEndDrainsItOnce() {
        val q = CallInputQueue(); val input = CallFinalInput("paused", "call", "thread", "ordinary", 1)
        q.offer(input, "call"); assertEquals(input, q.claim("call"))
        assertTrue(q.restore(input, "call"))
        assertEquals(listOf(input), q.end("call"))
        assertTrue(q.end("call").isEmpty())
    }

}
