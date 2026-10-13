package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

/** Exercises the exact admission owner used before the Android START_CAPTURE path. */
class VideoCaptureStartFenceTest {
    private class Fixture {
        var currentCallId: String? = "call-1"
        val fence = VideoCaptureStartFence()
        var generation = 0L
        fun request(callId: String = "call-1") = fence.request(callId) { currentCallId }
        fun deliver(callId: String = "call-1") = fence.admit(callId) { generation++ }
    }

    @Test fun farewellBeforeServiceCreationRejectsDelayedCaptureStart() {
        val f = Fixture()
        assertTrue(f.request())
        // finishVoiceCall refreshes video before clearing the voice identity.
        // No service instance is needed to revoke its queued start.
        f.fence.end("call-1")
        assertEquals("call-1", f.currentCallId)
        assertFalse(f.deliver())
        assertEquals(0L, f.generation)
        f.currentCallId = null
        assertFalse(f.deliver())
    }

    @Test fun farewellBeforeOnCallBeganCallbackRejectsItsLateRequest() {
        val f = Fixture()
        f.fence.end("call-1")
        assertFalse(f.request())
        assertFalse(f.deliver())
        assertEquals(0L, f.generation)
    }

    @Test fun newerCallAndItsGrantRetryRemainAdmissible() {
        val f = Fixture()
        assertTrue(f.request())
        assertTrue(f.deliver())
        f.fence.end("call-1")
        f.currentCallId = "call-2"
        assertTrue(f.request("call-2"))
        assertTrue(f.deliver("call-2"))
        // Admission must not consume the identity: permission may be denied
        // on the first delivery and granted for a retry during the same call.
        assertTrue(f.request("call-2"))
        assertTrue(f.deliver("call-2"))
        assertFalse(f.deliver("call-1"))
        assertEquals(3L, f.generation)
    }

    @Test fun oldBeginAndFarewellCannotReplaceOrEndNewCallRequest() {
        val f = Fixture()
        assertTrue(f.request())
        f.currentCallId = "call-2"
        assertTrue(f.request("call-2"))
        assertFalse(f.request("call-1"))
        f.fence.end("call-1")
        assertFalse(f.deliver("call-1"))
        assertTrue(f.deliver("call-2"))
        assertEquals(1L, f.generation)
    }

    @Test fun staleFarewellCannotRemoveCurrentEndingCallsFence() {
        val f = Fixture()
        assertTrue(f.request())
        f.currentCallId = "call-2"
        assertTrue(f.request("call-2"))
        f.fence.end("call-2")
        f.fence.end("call-1")
        assertFalse(f.request("call-2"))
        assertFalse(f.deliver("call-2"))
    }

    @Test fun lostOrChangedVoiceOwnershipRejectsEvenPreviouslyRequestedStart() {
        val f = Fixture()
        assertTrue(f.request())
        f.currentCallId = null
        assertFalse(f.deliver())
        f.currentCallId = "call-2"
        assertFalse(f.deliver())
        assertFalse(f.deliver("call-2")) // no start was requested for this call
        assertEquals(0L, f.generation)
    }

    @Test fun arbitraryOrBlankStartNeverAdvancesCaptureGeneration() {
        val f = Fixture()
        assertFalse(f.deliver())
        assertFalse(f.request("other-call"))
        assertFalse(f.request(""))
        assertFalse(f.deliver(""))
        assertEquals(0L, f.generation)
    }

    @Test fun admittedStartAdvancesGenerationBeforeFarewellCanSnapshotIt() {
        val f = Fixture()
        assertTrue(f.request())
        assertTrue(f.deliver())
        f.fence.end("call-1")
        val farewellGeneration = f.generation
        assertEquals(1L, farewellGeneration)
        assertFalse(f.deliver())
        assertEquals(farewellGeneration, f.generation)
    }
}
