package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VisionFrameHubTest {
    private class Recording : VisionObserver {
        val frames = mutableListOf<Pair<ByteArray, Long>>()
        override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
            frames += jpegBytes to timestampMs
        }
    }

    @Test fun framesAreDroppedWhenNobodyIsRegistered() {
        val hub = VisionFrameHub()
        hub.dispatch(byteArrayOf(1, 2, 3), 1000L)
        assertEquals(1L, hub.droppedCount())
        assertNull(hub.latest())
        assertEquals(0, hub.observerCount())
    }

    @Test fun registeredObserverReceivesFrames() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.dispatch(byteArrayOf(7), 2000L)
        assertEquals(1, rec.frames.size)
        assertArrayEquals(byteArrayOf(7), rec.frames[0].first)
        assertEquals(2000L, rec.frames[0].second)
        assertEquals(0L, hub.droppedCount())
    }

    @Test fun unregisteredObserverStopsReceiving() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.unregister(rec)
        hub.dispatch(byteArrayOf(7), 2000L)
        assertTrue(rec.frames.isEmpty())
        assertEquals(1L, hub.droppedCount())
    }

    @Test fun latestCacheHoldsOnlyTheNewestFrame() {
        val hub = VisionFrameHub()
        hub.register(Recording())
        hub.dispatch(byteArrayOf(1), 1000L)
        hub.dispatch(byteArrayOf(2), 2000L)
        val latest = hub.latest()
        assertNotNull(latest)
        assertArrayEquals(byteArrayOf(2), latest!!.jpegBytes)
        assertEquals(2000L, latest.timestampMs)
    }

    @Test fun clearEmptiesTheCache() {
        val hub = VisionFrameHub()
        hub.register(Recording())
        hub.dispatch(byteArrayOf(1), 1000L)
        hub.clear()
        assertNull(hub.latest())
    }

    @Test fun throwingObserverDoesNotBreakOthers() {
        val hub = VisionFrameHub()
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
                throw RuntimeException("boom")
            }
        })
        val rec = Recording()
        hub.register(rec)
        hub.dispatch(byteArrayOf(9), 3000L)
        assertEquals(1, rec.frames.size)
    }

    @Test fun duplicateRegistrationDeliversOnce() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        hub.register(rec)
        hub.dispatch(byteArrayOf(1), 1000L)
        assertEquals(1, rec.frames.size)
        assertEquals(1, hub.observerCount())
    }

    @Test fun clearRevokesPreparedDeliveryWithoutRepopulatingTheCache() {
        val hub = VisionFrameHub()
        val rec = Recording()
        hub.register(rec)
        val delivery = hub.prepareDispatch(byteArrayOf(1), 1000L)
        assertNotNull(hub.latest())
        hub.clear()
        hub.deliver(delivery)
        assertTrue(rec.frames.isEmpty())
        assertNull(hub.latest())
        hub.dispatch(byteArrayOf(2), 2000L)
        assertEquals(1, rec.frames.size)
        assertArrayEquals(byteArrayOf(2), hub.latest()!!.jpegBytes)
    }

    @Test fun firstObserverClearingHubRevokesRemainingSnapshotTargets() {
        val hub = VisionFrameHub()
        var firstDeliveries = 0
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
                firstDeliveries++
                hub.clear()
            }
        })
        val rec = Recording()
        hub.register(rec)
        hub.dispatch(byteArrayOf(1), 1000L)
        assertEquals(1, firstDeliveries)
        assertTrue(rec.frames.isEmpty())
        assertNull(hub.latest())
    }

    @Test fun clearingDuringAdmittedCallbackDoesNotWaitForItOrDeliverToNextObserver() {
        val hub = VisionFrameHub()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        })
        val rec = Recording()
        hub.register(rec)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val dispatch = workers.submit { hub.dispatch(byteArrayOf(1), 1000L) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A lock held across callbacks would block clear until release.
            workers.submit { hub.clear() }.get(2, TimeUnit.SECONDS)
            assertNull(hub.latest())
            release.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            assertTrue(rec.frames.isEmpty())
            assertNull(hub.latest())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun preparedDeliveryKeepsCopiedTargetsAndOriginalFramePayload() {
        val hub = VisionFrameHub()
        val original = Recording()
        val later = Recording()
        val payload = byteArrayOf(1, 2, 3)
        hub.register(original)
        val delivery = hub.prepareDispatch(payload, 1000L)
        hub.unregister(original)
        hub.register(later)
        hub.deliver(delivery)
        // Preserve the existing target-snapshot and ByteArray identity semantics.
        assertSame(payload, original.frames.single().first)
        assertSame(payload, hub.latest()!!.jpegBytes)
        assertEquals(1000L, original.frames.single().second)
        assertTrue(later.frames.isEmpty())
    }
}
