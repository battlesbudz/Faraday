package com.battlesbudz.jarvis.v2.display

import org.junit.Assert.*
import org.junit.Test

class OperatedDisplayTest {
    private class FakeHelper(var createdId: Int = 12, var frame: DisplayFrameMailbox.Frame? = null) : OperatedDisplayHelper {
        val released = mutableListOf<Int>()
        val moved = mutableListOf<Pair<Int, Int>>()
        val frameReads = mutableListOf<Int>()
        override fun create(name: String, width: Int, height: Int, densityDpi: Int) = createdId
        override fun release(displayId: Int) { released += displayId }
        override fun moveTask(taskId: Int, displayId: Int): Boolean { moved += taskId to displayId; return true }
        override fun readFrame(displayId: Int): DisplayFrameMailbox.Frame? {
            frameReads += displayId
            return frame?.takeIf { it.displayId == displayId }
        }
    }

    @Test fun creationFailureDoesNotPublishIdentityOrTryAnotherDisplay() {
        val helper = FakeHelper(-1)
        val display = HelperOperatedDisplay({ helper })
        assertThrows(IllegalStateException::class.java) { display.create("task-1", "YouTube", 1920, 1080, 420) }
        assertNull(display.current())
        assertTrue(helper.released.isEmpty())
    }

    @Test fun focusLossIsReportedAndTeardownReleasesOnlyOwnedDisplay() {
        val helper = FakeHelper()
        val events = mutableListOf<OperatedDisplayEvent>()
        val display = HelperOperatedDisplay({ helper }, DisplayEventListener(events::add))
        val identity = display.create("task-1", "YouTube", 1920, 1080, 420)
        assertTrue(display.moveTask(77))
        display.focusChanged(false)
        display.close()
        display.close()
        assertEquals(listOf(77 to 12), helper.moved)
        assertEquals(listOf(12), helper.released)
        assertEquals(listOf(OperatedDisplayEvent.Kind.CREATED, OperatedDisplayEvent.Kind.FOCUS_LOST,
            OperatedDisplayEvent.Kind.RELEASED), events.map { it.kind })
        assertEquals(identity, events.first().identity)
    }

    @Test fun mailboxCapsAtTenFpsAndKeepsOnlyNewestBoundedThumbnail() {
        var now = 0L
        val mailbox = DisplayFrameMailbox { now }
        val data = ByteArray(800 * 400 * 4) { 1 }
        val first = DisplayFrameMailbox.Frame(12, 800, 400, data, 0)
        assertTrue(mailbox.offer(first))
        val thumb = mailbox.takeLatest()!!
        assertEquals(360, thumb.width)
        assertEquals(180, thumb.height)
        now = 99
        assertFalse(mailbox.offer(first))
        now = 100
        val second = first.copy(capturedAtMs = 100)
        assertTrue(mailbox.offer(second))
        assertEquals(100, mailbox.takeLatest()!!.capturedAtMs)
        assertNull(mailbox.takeLatest())
    }

    @Test fun mainDisplayAssertionFailsIfMainDisplayWasUsed() {
        MainDisplayUntouchedAssertion(12, false).requireSatisfied()
        assertThrows(IllegalStateException::class.java) {
            MainDisplayUntouchedAssertion(12, true).requireSatisfied()
        }
    }

    @Test fun helperDisplayReadsOnlyItsOwnedFrameAndStopsReadingAfterClose() {
        val frame = DisplayFrameMailbox.Frame(12, 1, 1, byteArrayOf(1, 2, 3, 4), 8)
        val helper = FakeHelper(frame = frame)
        val display = HelperOperatedDisplay({ helper })
        assertNull(display.readFrame())
        display.create("task-frame", "YouTube", 800, 400, 420)
        assertEquals(frame, display.readFrame())
        display.close()
        assertNull(display.readFrame())
    }

    @Test fun frameReadNeverTargetsDisplayZero() {
        val helper = FakeHelper(0, DisplayFrameMailbox.Frame(0, 1, 1, byteArrayOf(1, 2, 3, 4), 1))
        val display = HelperOperatedDisplay({ helper })
        display.create("task-main-guard", "YouTube", 800, 400, 420)
        assertNull(display.readFrame())
        assertTrue(helper.frameReads.isEmpty())
    }

    @Test fun operatedFrameFeedPollsIntoMailboxAndHonorsDisplayAndFrameRateGates() {
        var now = 0L
        val frame = DisplayFrameMailbox.Frame(12, 1, 1, byteArrayOf(4, 3, 2, 1), 10)
        val helper = FakeHelper(frame = frame)
        val display = HelperOperatedDisplay({ helper })
        val mailbox = DisplayFrameMailbox { now }
        val feed = OperatedFrameFeed(display, mailbox)
        assertFalse(feed.pollOnce())
        display.create("task-frame", "YouTube", 800, 400, 420)
        assertTrue(feed.pollOnce())
        val first = mailbox.takeLatest()!!
        assertEquals(frame.displayId, first.displayId)
        assertEquals(frame.width, first.width)
        assertEquals(frame.height, first.height)
        assertEquals(frame.capturedAtMs, first.capturedAtMs)
        assertArrayEquals(frame.rgba, first.rgba)
        assertFalse(feed.pollOnce())
        now = 100
        assertTrue(feed.pollOnce())
        assertArrayEquals(frame.rgba, mailbox.takeLatest()!!.rgba)
    }
}
