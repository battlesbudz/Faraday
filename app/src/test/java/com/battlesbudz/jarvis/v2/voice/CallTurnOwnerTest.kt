package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CallTurnOwnerTest {
    @Test fun exactChildIsCancelledAndJoinedBeforeCompletionCallbackRestarts() = runBlocking {
        val owner = CallTurnOwner()
        assertTrue(owner.begin("a"))
        var childFinally = false
        val child = launch { try { awaitCancellation() } finally { childFinally = true } }
        yield()
        var released = false
        assertTrue(owner.finish("a", child) { released = true })
        var restarted = 0
        owner.restartAfterOwnerCompletion("a") { restarted++ }
        assertTrue(childFinally)
        assertTrue(released)
        assertEquals(1, restarted)
        owner.restartAfterOwnerCompletion("a") { restarted++ }
        assertEquals(1, restarted)
    }
    @Test fun endBeforePromotionLeavesTheOriginalLeaseToReleaseOnce() {
        val queue = CallInputQueue(); val input = CallFinalInput("end", "call", "thread", "open settings", 1)
        queue.offer(input, "call"); assertEquals(input, queue.claim("call"))
        val promotedLease = CallPromotionLease(); var originalModelHeld = true; var originalReleases = 0
        assertEquals(listOf(input), queue.end("call"))
        assertFalse(queue.promote(input) { promotedLease.transfer { error("End must win before lease transfer") } })
        // This mirrors the common finally: no accepted lease was acquired, so it releases the
        // original model operation exactly once and the next input is not blocked.
        if (originalModelHeld) { originalModelHeld = false; originalReleases++ }
        assertFalse(promotedLease.isOwned()); assertFalse(originalModelHeld); assertEquals(1, originalReleases)
        val next = input.copy(id = "next", callId = "next-call")
        assertEquals(CallInputAdmission.Queued, queue.offer(next, "next-call"))
    }

    @Test fun throwingPromotionReleasesAcceptedLeaseExactlyOnceBeforeAnyWorkerStarts() {
        val queue = CallInputQueue(); val input = CallFinalInput("throw", "call", "thread", "open app", 1)
        queue.offer(input, "call"); assertEquals(input, queue.claim("call"))
        val promotion = CallPromotionLease()
        val accepted = com.battlesbudz.jarvis.v2.actions.AcceptedActionLease()
        var modelHeld = false; var releases = 0
        try {
            queue.promote(input) {
                assertTrue(promotion.transfer {
                    accepted.transfer(); modelHeld = true; true
                })
                throw CancellationException("promotion admission cancelled before enqueue")
            }
            fail("expected cancellation from admission")
        } catch (_: CancellationException) {
            // Runtime's NonCancellable common finally performs this exact release.
            assertTrue(promotion.releaseIfUnadmitted {
                assertTrue(accepted.releaseIfCurrent(accepted.generation(), idle = true))
                modelHeld = false; releases++
            })
        }
        assertFalse(promotion.isOwned()); assertFalse(accepted.active()); assertFalse(modelHeld)
        assertEquals(1, releases)
        assertFalse(promotion.releaseIfUnadmitted { releases++ })
        assertEquals(1, releases)
        assertTrue(promotion.transfer { modelHeld = true; true }) // later input can acquire
        assertTrue(promotion.releaseIfUnadmitted { modelHeld = false; releases++ })
        assertFalse(modelHeld); assertEquals(2, releases)
    }
    @Test fun promotedAcceptedLeaseIsNotReleasedByTheFormerInputFinally() {
        val lease = CallPromotionLease(); var releases = 0
        assertTrue(lease.transfer { true }); lease.markAdmitted()
        assertFalse(lease.releaseIfUnadmitted { releases++ })
        assertEquals(0, releases)
    }

}
