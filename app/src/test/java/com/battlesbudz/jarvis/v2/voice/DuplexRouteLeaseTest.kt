package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class DuplexRouteLeaseTest {
    private class Port : DuplexRouteLease.Port {
        val events = mutableListOf<String>()
        private var actualMode = 0
        override var mode: Int
            get() = actualMode
            set(value) { events += "mode:$value"; actualMode = value }
        override var device: Int? = 7
        var selectGranted = true
        var focusGranted = true
        var clearFailure = false
        override fun selectDevice(id: Int): Boolean {
            events += "select:$id"
            if (selectGranted) device = id
            return selectGranted
        }
        override fun clearDevice() {
            events += "clear"
            if (clearFailure) error("clear failed")
            device = null
        }
        override fun requestFocus(): Boolean { events += "focus"; return focusGranted }
        override fun abandonFocus() { events += "abandon" }
    }

    @Test fun normalCompletionWithdrawsOwnRequestsAndCloseIsIdempotent() {
        val port = Port(); val lease = DuplexRouteLease(port, 9)
        lease.start()
        assertTrue(lease.valid)
        assertTrue(lease.ownsCommunication)
        lease.close(); lease.close()
        assertFalse(lease.ownsCommunication)
        assertEquals(listOf("mode:3", "select:9", "focus", "clear", "mode:0", "abandon"), port.events)
        // Do not force an old observed route over the system's next routing decision.
        assertNull(port.device)
    }
    @Test fun existingCallOrCommunicationOwnerIsNeverTakenOver() {
        for (mode in listOf(1, 2, 3)) {
            val port = Port().apply { this.mode = mode; events.clear() }
            val lease = DuplexRouteLease(port, 9)
            assertThrows(IllegalStateException::class.java) { lease.start() }
            lease.close()
            assertTrue(port.events.isEmpty())
            assertEquals(mode, port.mode)
        }
    }
    @Test fun rejectedDeviceSelectionRestoresModeWithoutRequestingFocus() {
        val port = Port().apply { selectGranted = false }; val lease = DuplexRouteLease(port, 9)
        assertThrows(IllegalStateException::class.java) { lease.start() }
        assertEquals(listOf("mode:3", "select:9", "clear", "mode:0"), port.events)
        assertFalse(lease.valid)
    }
    @Test fun rejectedFocusWithdrawsAllRequests() {
        val port = Port().apply { focusGranted = false }; val lease = DuplexRouteLease(port, 9)
        assertThrows(IllegalStateException::class.java) { lease.start() }
        assertEquals(listOf("clear", "mode:0", "abandon"), port.events.takeLast(3))
        assertTrue(lease.closed)
    }
    @Test fun routeLossInvalidatesTheLeaseWithoutSilentlySelectingAnotherDevice() {
        val port = Port(); val lease = DuplexRouteLease(port, 9)
        lease.start(); port.device = 10
        assertFalse(lease.valid)
        assertEquals(1, port.events.count { it.startsWith("select:") })
        lease.close()
    }
    @Test fun externalCallInvalidatesLeaseAndOurRequestsAreStillWithdrawn() {
        val port = Port(); val lease = DuplexRouteLease(port, 9)
        lease.start(); port.mode = 2
        assertFalse(lease.valid)
        lease.close()
        assertEquals(listOf("clear", "mode:0", "abandon"), port.events.takeLast(3))
    }
    @Test fun cleanupFailureStillReleasesModeAndFocus() {
        val port = Port(); val lease = DuplexRouteLease(port, 9)
        lease.start(); port.clearFailure = true
        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertEquals(listOf("clear", "mode:0", "abandon"), port.events.takeLast(3))
        assertFalse(lease.ownsCommunication)
    }
    @Test fun failedStartPreservesOriginalErrorAndAttachesCleanupFailure() {
        val port = Port().apply { focusGranted = false; clearFailure = true }
        val lease = DuplexRouteLease(port, 9)
        val failure = assertThrows(IllegalStateException::class.java) { lease.start() }
        assertEquals("Communication audio focus was not granted.", failure.message)
        assertEquals("clear failed", failure.suppressed.single().message)
    }
}
