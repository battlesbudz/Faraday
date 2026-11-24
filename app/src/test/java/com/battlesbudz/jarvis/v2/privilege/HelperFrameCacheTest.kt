package com.battlesbudz.jarvis.v2.privilege

import org.junit.Assert.*
import org.junit.Test

class HelperFrameCacheTest {
    private fun packed(mark: Int) = RgbaFramePacker.Packed(1, 1, byteArrayOf(mark.toByte(), 0, 0, 0), mark.toLong())
    private fun assertPacked(mark: Int, actual: RgbaFramePacker.Packed?) {
        assertNotNull(actual)
        assertEquals(1, actual!!.width)
        assertEquals(1, actual.height)
        assertArrayEquals(byteArrayOf(mark.toByte(), 0, 0, 0), actual.rgba)
        assertEquals(mark.toLong(), actual.capturedAtMs)
    }

    @Test fun throttlesRefreshAndRetainsFramesWhenAcquireIsEmpty() {
        var now = 0L
        var calls = 0
        var next: RgbaFramePacker.Packed? = packed(1)
        val cache = HelperFrameCache { now }
        fun acquire(): RgbaFramePacker.Packed? { calls++; return next }
        assertPacked(1, cache.frame(4, ::acquire))
        now = 99
        next = packed(2)
        assertPacked(1, cache.frame(4, ::acquire))
        assertEquals(1, calls)
        now = 100
        assertPacked(2, cache.frame(4, ::acquire))
        assertEquals(2, calls)
        now = 200
        next = null
        assertPacked(2, cache.frame(4, ::acquire))
        assertEquals(3, calls)
    }

    @Test fun emptyCacheReturnsNullAndInvalidationForcesRefresh() {
        var now = 0L
        var calls = 0
        val cache = HelperFrameCache { now }
        assertNull(cache.frame(4) { calls++; null })
        assertEquals(1, calls)
        now = 100
        assertPacked(3, cache.frame(4) { calls++; packed(3) })
        assertEquals(2, calls)
        cache.invalidate(4)
        assertPacked(4, cache.frame(4) { calls++; packed(4) })
        assertEquals(3, calls)
        cache.clear()
        now = 0
        assertNull(cache.frame(4) { null })
    }

    @Test fun displaysHaveIndependentSlotsAndRefreshTimes() {
        var now = 0L
        val cache = HelperFrameCache { now }
        assertPacked(1, cache.frame(1) { packed(1) })
        now = 50
        assertPacked(2, cache.frame(2) { packed(2) })
        now = 99
        assertPacked(1, cache.frame(1) { error("must stay throttled") })
        now = 100
        assertPacked(3, cache.frame(1) { packed(3) })
        assertPacked(2, cache.frame(2) { error("display 2 has its own refresh age") })
    }
}
