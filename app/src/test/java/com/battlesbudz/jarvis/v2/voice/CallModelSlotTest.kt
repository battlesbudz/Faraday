package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CallModelSlotTest {
    @Test fun warmProbeIsExclusiveAndCannotLoadOrChangeModels() {
        var releases = 0
        val slot = CallModelSlot<Any>({ releases++ })
        assertThrows(IllegalStateException::class.java) { slot.acquireWarm("whisper") }
        val command = slot.acquire("whisper") { Any() }
        assertThrows(IllegalStateException::class.java) { slot.acquireWarm("whisper") }
        command.finish()
        assertThrows(IllegalStateException::class.java) { slot.acquireWarm("other") }
        val probe = slot.acquireWarm("whisper")
        assertSame(command.value, probe.value)
        assertThrows(IllegalStateException::class.java) { slot.acquireWarm("whisper") }
        slot.close()
        assertEquals(0, releases)
        probe.finish()
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { slot.acquireWarm("whisper") }
    }
    @Test fun freshCommandDiscardsPriorProbeStateBeforeLoadingAndKeepsNextProbeWarm() {
        val released = mutableListOf<Any>()
        val slot = CallModelSlot<Any>({ released += it })
        val old = slot.acquire("moonshine") { Any() }; old.finish()
        val command = slot.acquire("moonshine", fresh = true) {
            assertEquals(listOf(old.value), released)
            Any()
        }
        assertFalse(command.reused); command.finish()
        val probe = slot.acquire("moonshine") { error("Probe should use new command weights") }
        assertSame(command.value, probe.value); probe.finish(); slot.close()
        assertEquals(2, released.size)
    }
    @Test fun normalUtterancesReuseWeightsAndEndReleasesExactlyOnce() {
        var loads = 0; var releases = 0
        val slot = CallModelSlot<Any>({ releases++ })
        val first = slot.acquire("paul") { loads++; Any() }; first.finish()
        val second = slot.acquire("paul") { loads++; Any() }
        assertSame(first.value, second.value); assertTrue(second.reused)
        second.finish(); second.finish(); slot.close(); slot.close()
        assertEquals(1, loads); assertEquals(1, releases)
    }
    @Test fun closeDuringBorrowDefersReleaseAndBlocksNewUse() {
        var releases = 0; val slot = CallModelSlot<Any>({ releases++ })
        val lease = slot.acquire("model") { Any() }; slot.close()
        assertEquals(0, releases)
        assertThrows(IllegalStateException::class.java) { slot.acquire("model") { Any() } }
        lease.finish(); assertEquals(1, releases)
    }
    @Test fun simultaneousBorrowIsRejectedAndFailedInferenceInvalidatesWeights() {
        var releases = 0; val slot = CallModelSlot<Any>({ releases++ })
        val first = slot.acquire("model") { Any() }
        assertThrows(IllegalStateException::class.java) { slot.acquire("model") { Any() } }
        first.finish(false)
        val second = slot.acquire("model") { Any() }
        assertNotSame(first.value, second.value); assertFalse(second.reused)
        second.finish(); slot.close(); assertEquals(2, releases)
    }
    @Test fun boundedSdkCacheAndConfigurationChangeRotateModels() {
        val released = mutableListOf<Any>(); val events = mutableListOf<String>()
        val slot = CallModelSlot<Any>({ released += it }, events::add)
        val first = slot.acquire("moonshine", 2) { Any() }; first.finish()
        slot.acquire("moonshine", 2) { Any() }.finish()
        val rotated = slot.acquire("moonshine", 2) { Any() }; rotated.finish()
        assertNotSame(first.value, rotated.value)
        val changed = slot.acquire("whisper") { Any() }; changed.finish(); slot.close()
        assertEquals(3, released.size)
        assertTrue(events.any { it.contains("bounded_sdk_cache") })
        assertTrue(events.any { it.contains("configuration_changed") })
    }
    @Test fun failedLoadDoesNotLeaveSlotBorrowed() {
        val slot = CallModelSlot<Any>({})
        assertThrows(IllegalArgumentException::class.java) { slot.acquire("model") { throw IllegalArgumentException() } }
        slot.acquire("model") { Any() }.finish(); slot.close()
    }
    @Test fun stopDuringBlockingLoadNeverFreesBeforeBorrowerReturns() {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var releases = 0; val slot = CallModelSlot<Any>({ releases++ })
        try {
            val loading = executor.submit<CallModelSlot<Any>.Lease> {
                slot.acquire("model") { entered.countDown(); check(unblock.await(2, TimeUnit.SECONDS)); Any() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stop = executor.submit { slot.close() }
            unblock.countDown()
            val lease = loading.get(2, TimeUnit.SECONDS); stop.get(2, TimeUnit.SECONDS)
            assertEquals(0, releases); lease.finish(); assertEquals(1, releases)
        } finally { unblock.countDown(); executor.shutdownNow() }
    }
    @Test fun optionalProbeCannotForceColdLoadRotationOrCompetingBorrow() {
        val slot = CallModelSlot<Any>({})
        assertFalse(slot.canReuse("moonshine", 2))
        val first = slot.acquire("moonshine", 2) { Any() }
        assertFalse(slot.canReuse("moonshine", 2))
        first.finish()
        assertTrue(slot.canReuse("moonshine", 2))
        assertFalse(slot.canReuse("other", 2))
        slot.acquire("moonshine", 2) { error("Must reuse") }.finish()
        assertFalse(slot.canReuse("moonshine", 2))
        slot.close()
        assertFalse(slot.canReuse("moonshine", 8))
    }
    @Test fun commandReservesAllReplyProbesWithoutExceedingSdkBound() {
        var loads = 0
        val slot = CallModelSlot<Any>({})
        repeat(7) { slot.acquire("moonshine", 8) { loads++; Any() }.finish() }
        val command = slot.acquire("moonshine", 8, requiredUses = 5) { loads++; Any() }
        assertFalse(command.reused); command.finish()
        repeat(4) {
            assertTrue(slot.canReuse("moonshine", 8))
            slot.acquire("moonshine", 8) { error("Probe must remain warm") }.finish()
        }
        assertEquals(2, loads)
        repeat(3) { slot.acquire("moonshine", 8) { error("Still within bound") }.finish() }
        assertFalse(slot.canReuse("moonshine", 8))
        slot.close()
    }
}
