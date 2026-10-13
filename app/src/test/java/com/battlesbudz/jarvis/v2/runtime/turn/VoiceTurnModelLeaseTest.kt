package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.actions.AcceptedActionLease
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import com.battlesbudz.jarvis.v2.voice.CallInputAdmission
import com.battlesbudz.jarvis.v2.voice.CallInputQueue
import com.battlesbudz.jarvis.v2.voice.CallPromotionLease
import com.battlesbudz.jarvis.v2.voice.CallTurnOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production turn lease together with its actual call/admission owners. */
class VoiceTurnModelLeaseTest {
    @Test fun activeConversationDoesNotBorrowOrReleaseItsExistingModelOperation() {
        var acquisitions = 0
        var releases = 0
        val lease = VoiceTurnModelLease({ acquisitions++; true }, { releases++ })

        assertFalse(lease.acquireWhenIdle(noActiveConversation = false))
        lease.close()

        assertFalse(lease.owned)
        assertEquals(0, acquisitions)
        assertEquals(0, releases)
    }

    @Test fun failedAcquisitionLeavesNoLeaseAndALaterTurnCanAcquireAndReleaseOnce() {
        var available = false
        var acquisitions = 0
        var releases = 0
        val lease = VoiceTurnModelLease({ acquisitions++; available }, { releases++ })

        assertFalse(lease.acquireWhenIdle(noActiveConversation = true))
        lease.close()
        assertEquals(0, releases)

        available = true
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))
        assertTrue(lease.owned)
        lease.close()
        lease.close()

        assertFalse(lease.owned)
        assertEquals(2, acquisitions)
        assertEquals(1, releases)
    }

    @Test fun acquisitionExceptionCannotMakeCleanupReleaseAnotherOwnersLease() {
        val failure = IllegalStateException("model preparation failed before lease acquisition")
        var releases = 0
        val lease = VoiceTurnModelLease({ throw failure }, { releases++ })

        try {
            lease.acquireWhenIdle(noActiveConversation = true)
            fail("expected acquisition failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        lease.close()

        assertFalse(lease.owned)
        assertEquals(0, releases)
    }

    @Test fun unownedTransferDoesNotAdmitWorkOrInventAProcessLease() {
        var admissions = 0
        val accepted = AcceptedActionLease()
        val lease = VoiceTurnModelLease({ false }, { fail("no model operation was acquired") })

        assertFalse(lease.transfer { accepted.transfer(); admissions++ })

        assertFalse(accepted.active())
        assertEquals(0, admissions)
    }

    @Test fun successfulPromotionKeepsTheExistingLeaseUntilTheAcceptedWorkerIsIdle() {
        val input = CallFinalInput("accepted", "call", "thread", "open settings", 1)
        val queue = CallInputQueue()
        val promotion = CallPromotionLease()
        val accepted = AcceptedActionLease()
        var acquisitions = 0
        var releases = 0
        val lease = VoiceTurnModelLease({ acquisitions++; true }, { releases++ })
        assertEquals(CallInputAdmission.Queued, queue.offer(input, "call"))
        assertEquals(input, queue.claim("call"))
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))

        assertTrue(queue.promote(input) {
            assertTrue(promotion.transfer { lease.transfer { accepted.transfer() } })
            promotion.markAdmitted()
        })
        val generation = accepted.generation()
        lease.close()
        assertFalse(promotion.releaseIfUnadmitted { releases++ })

        assertEquals(1, acquisitions)
        assertEquals(0, releases)
        assertFalse(lease.owned)
        assertTrue(accepted.active())
        assertFalse(accepted.releaseIfCurrent(generation, idle = false))
        assertTrue(accepted.releaseIfCurrent(generation, idle = true))
        releases++ // The accepted worker releases the physical model operation it now owns.
        assertFalse(accepted.releaseIfCurrent(generation, idle = true))
        assertEquals(1, releases)
        assertTrue(queue.end("call").isEmpty())
    }

    @Test fun endBeforePromotionLeavesCleanupWithTheOriginalTurnAndNoAcceptedWorker() {
        val input = CallFinalInput("ended", "call", "thread", "open app", 1)
        val queue = CallInputQueue()
        val promotion = CallPromotionLease()
        var releases = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        queue.offer(input, "call")
        assertEquals(input, queue.claim("call"))
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))

        assertEquals(listOf(input), queue.end("call"))
        assertFalse(queue.promote(input) {
            promotion.transfer { lease.transfer { fail("End must reject before process admission") } }
        })
        assertFalse(promotion.releaseIfUnadmitted { releases++ })
        lease.close()
        lease.close()

        assertFalse(lease.owned)
        assertEquals(1, releases)
        assertTrue(queue.end("call").isEmpty())
    }

    @Test fun rejectedOwnershipTransferPropagatesFailureAndRetainsTheOriginalLease() {
        val failure = IllegalStateException("accepted lease already held by another batch")
        var releases = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))

        try {
            lease.transfer { throw failure }
            fail("expected transfer rejection")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }

        assertTrue(lease.owned)
        lease.close()
        lease.close()
        assertEquals(1, releases)
    }

    @Test fun cancellationAfterTransferButBeforeAdmissionReleasesThroughPromotionOnly() {
        val input = CallFinalInput("cancelled", "call", "thread", "open app", 1)
        val queue = CallInputQueue()
        val promotion = CallPromotionLease()
        val accepted = AcceptedActionLease()
        val cancellation = CancellationException("cancelled before enqueue")
        var releases = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        queue.offer(input, "call")
        assertEquals(input, queue.claim("call"))
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))

        try {
            queue.promote(input) {
                assertTrue(promotion.transfer { lease.transfer { accepted.transfer() } })
                throw cancellation
            }
            fail("expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }

        lease.close()
        assertEquals(0, releases)
        assertTrue(promotion.releaseIfUnadmitted {
            assertTrue(accepted.releaseIfCurrent(accepted.generation(), idle = true))
            releases++
        })
        assertFalse(promotion.releaseIfUnadmitted { releases++ })
        assertFalse(accepted.active())
        assertFalse(lease.owned)
        assertEquals(1, releases)
        assertTrue(queue.terminalize(input))
        assertTrue(queue.end("call").isEmpty())
    }

    @Test fun cancelledCallOwnerJoinsItsExactChildBeforeReleasingAndRestarting() = runBlocking {
        val owner = CallTurnOwner()
        val events = mutableListOf<String>()
        val lease = VoiceTurnModelLease({ true }, { events += "model released" })
        assertTrue(owner.begin("typed"))
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))
        val child = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    yield()
                    events += "native child joined"
                }
            }
        }
        val turn = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                assertTrue(owner.finish("typed", child, lease::close))
            }
        }

        turn.cancelAndJoin()
        owner.restartAfterOwnerCompletion("typed") { events += "next turn restarted" }
        lease.close()
        owner.restartAfterOwnerCompletion("typed") { fail("completion may restart only once") }

        assertEquals(listOf("native child joined", "model released", "next turn restarted"), events)
        assertFalse(owner.isActive())
        assertFalse(lease.owned)
    }

    @Test fun releaseExceptionCannotCauseCompletionCleanupToReleaseTwice() {
        val failure = IllegalStateException("model close failed")
        var releases = 0
        val lease = VoiceTurnModelLease({ true }, { releases++; throw failure })
        assertTrue(lease.acquireWhenIdle(noActiveConversation = true))

        try {
            lease.close()
            fail("expected release failure")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        lease.close()

        assertFalse(lease.owned)
        assertEquals(1, releases)
    }
    @org.junit.Test fun failedSpeculativeOrEncoderDrainRetainsOwnedLeaseEvenWithIdleEngine() {
        var releases = 0; var quarantines = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        org.junit.Assert.assertTrue(lease.acquireWhenIdle(true))
        lease.finishNativeDrain(false, true) { quarantines++ }
        org.junit.Assert.assertTrue(lease.owned)
        org.junit.Assert.assertEquals(0, releases)
        org.junit.Assert.assertEquals(1, quarantines)
    }

    @org.junit.Test fun successfulSpeculationCannotReleaseAnUndrainedNativeEngine() {
        var releases = 0; var quarantines = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        lease.acquireWhenIdle(true)
        lease.finishNativeDrain(true, false) { quarantines++ }
        org.junit.Assert.assertTrue(lease.owned)
        org.junit.Assert.assertEquals(0, releases)
        org.junit.Assert.assertEquals(1, quarantines)
    }

    @org.junit.Test fun onlyBothSuccessfulDrainsReleaseTheLeaseExactlyOnce() {
        var releases = 0
        val lease = VoiceTurnModelLease({ true }, { releases++ })
        lease.acquireWhenIdle(true)
        repeat(2) { lease.finishNativeDrain(true, true) { error("must not quarantine") } }
        org.junit.Assert.assertFalse(lease.owned)
        org.junit.Assert.assertEquals(1, releases)
    }

}
