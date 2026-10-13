package com.battlesbudz.jarvis.v2.voice

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Exact notification owner used by VideoCallService; no Android timing or sleeps. */
class VideoNotificationLifecycleTest {
    private class NotificationSink {
        var foreground = true
        var notification: String? = "Video on"
        val events = mutableListOf<String>()

        fun owner() = VideoNotificationLifecycle(
            post = { notification = it; events += "post:$it" },
            removeForeground = {
                events += "removeForeground"
                if (foreground) notification = null
                foreground = false
            },
            cancelNotification = { events += "cancel"; notification = null },
        )

        // ActiveServices can drop the foreground association before the app's
        // onDestroy executes. Later stopForeground then has nothing to remove.
        fun systemStopsService() {
            foreground = false
            notification = null
        }
    }

    @Test fun farewellPostedAfterSystemStopIsExplicitlyRemoved() {
        val sink = NotificationSink()
        val owner = sink.owner()
        sink.systemStopsService()
        assertTrue(owner.publish("Video idle — starts with your next call"))
        assertNotNull("The racing notify is now an ordinary notification", sink.notification)

        owner.close { sink.events += "releaseCamera" }

        assertNull("Foreground removal alone cannot remove this notification", sink.notification)
        assertEquals(listOf("post:Video idle — starts with your next call",
            "releaseCamera", "removeForeground", "cancel"), sink.events)
        assertFalse(owner.publish("late farewell"))
        assertNull(sink.notification)
    }

    @Test fun synchronousCameraCleanupCallbackCannotPublish() {
        val sink = NotificationSink()
        val owner = sink.owner()
        owner.close {
            assertFalse(owner.publish("Camera cleanup failed"))
            // Reentrant teardown also cannot run cancellation twice.
            owner.close { fail("A closed owner must not repeat camera cleanup") }
        }
        assertEquals(listOf("removeForeground", "cancel"), sink.events)
        assertNull(sink.notification)
    }

    @Test fun cameraCleanupCanJoinCallbackWithoutHoldingNotificationLock() {
        val sink = NotificationSink()
        val owner = sink.owner()
        val published = AtomicReference<Boolean>()
        val finished = CountDownLatch(1)
        owner.close {
            val callback = thread {
                try { published.set(owner.publish("asynchronous detach failure")) }
                finally { finished.countDown() }
            }
            assertTrue("Cleanup must not deadlock its callback", finished.await(5, TimeUnit.SECONDS))
            callback.join(5_000)
        }
        assertEquals(false, published.get())
        assertNull(sink.notification)
    }

    @Test fun closeWaitsForInFlightPostThenCancelsIt() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val postEntered = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val owner = VideoNotificationLifecycle(
            post = {
                postEntered.countDown()
                check(releasePost.await(5, TimeUnit.SECONDS))
                events += "post"
            },
            removeForeground = { events += "removeForeground" },
            cancelNotification = { events += "cancel" },
        )
        val publisher = thread {
            try { owner.publish("idle") } catch (t: Throwable) { failure.set(t) }
        }
        assertTrue(postEntered.await(5, TimeUnit.SECONDS))
        val closer = thread {
            try { owner.close { events += "cleanup" } } catch (t: Throwable) { failure.set(t) }
        }
        try {
            // Observe the contested monitor, rather than sleeping and assuming
            // the threads happened to overlap. A broken gate completes early.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Close must serialize behind the in-flight post", Thread.State.BLOCKED, closer.state)
        } finally {
            releasePost.countDown()
            publisher.join(5_000)
            closer.join(5_000)
        }
        assertFalse(publisher.isAlive)
        assertFalse(closer.isAlive)
        assertNull(failure.get())
        assertEquals(listOf("post", "cleanup", "removeForeground", "cancel"), events)
        assertFalse(owner.publish("too late"))
    }

    @Test fun oldCallbacksAndRepeatedCloseCannotRemoveReplacementNotification() {
        val sink = NotificationSink()
        val oldOwner = sink.owner()
        val delayedCallback = { oldOwner.publish("old idle") }
        oldOwner.close {}
        val replacement = sink.owner()
        sink.foreground = true
        assertTrue(replacement.publish("new call video on"))

        assertFalse(delayedCallback())
        oldOwner.close { fail("Old teardown cannot run against the shared notification ID") }

        assertEquals("new call video on", sink.notification)
        assertEquals(1, sink.events.count { it == "cancel" })
        replacement.close {}
        assertNull(sink.notification)
    }

    @Test fun cleanupExceptionStillRemovesNotificationAndClosesPublication() {
        val sink = NotificationSink()
        val owner = sink.owner()
        val error = IllegalStateException("detach failed")
        try {
            owner.close { throw error }
            fail("Cleanup failure must remain observable")
        } catch (actual: IllegalStateException) {
            assertSame(error, actual)
        }
        assertEquals(listOf("removeForeground", "cancel"), sink.events)
        assertNull(sink.notification)
        assertFalse(owner.publish("late cleanup error"))
    }

    @Test fun foregroundRemovalExceptionStillCancelsExactNotification() {
        var cancelled = false
        val error = IllegalStateException("foreground removal failed")
        val owner = VideoNotificationLifecycle(
            post = { fail("No notification may be published after shutdown") },
            removeForeground = { throw error },
            cancelNotification = { cancelled = true },
        )
        try {
            owner.close {}
            fail("Foreground removal failure must remain observable")
        } catch (actual: IllegalStateException) {
            assertSame(error, actual)
        }
        assertTrue(cancelled)
        assertFalse(owner.publish("late idle"))
    }

    @Test fun cancellationExceptionNeverReopensPublication() {
        val error = IllegalStateException("notification service unavailable")
        val owner = VideoNotificationLifecycle(
            post = { fail("No notification may be published after shutdown") },
            removeForeground = {},
            cancelNotification = { throw error },
        )
        try {
            owner.close {}
            fail("Cancellation failure must remain observable")
        } catch (actual: IllegalStateException) {
            assertSame(error, actual)
        }
        assertFalse(owner.publish("late camera error"))
    }

    @Test fun rejectedPostClosesImmediatelyButStillCleansUpOutsidePublicationLock() {
        val cleanupFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        var cleanups = 0
        var removals = 0
        var cancellations = 0
        lateinit var owner: VideoNotificationLifecycle
        owner = VideoNotificationLifecycle(
            post = { throw SecurityException("Foreground type no longer allowed") },
            removeForeground = { removals++ },
            cancelNotification = { cancellations++ },
            onPostRejected = {
                assertFalse(owner.isOpen)
                assertFalse(owner.publish("reentrant error"))
                val cleanup = thread {
                    try {
                        owner.close {
                            cleanups++
                            assertFalse(owner.publish("detach failure"))
                        }
                    } catch (t: Throwable) { failure.set(t) }
                    finally { cleanupFinished.countDown() }
                }
                assertTrue("Rejection must release the publication lock", cleanupFinished.await(5, TimeUnit.SECONDS))
                cleanup.join(5_000)
            },
        )
        assertFalse(owner.publish("Video on"))
        owner.close { fail("Teardown cannot repeat after a failed update") }
        assertNull(failure.get())
        assertEquals(1, cleanups)
        assertEquals(1, removals)
        assertEquals(1, cancellations)
    }

    @Test fun rejectedUpdatePreservesUnresolvedCameraOwnershipWithoutFalseVideoOn() {
        val binder = object : CallVisionController.VideoBinder {
            override var cleanupUnresolved = false
            var unbinds = 0
            override fun bind() = CallVisionController.BindResult.Started
            override fun unbind() { unbinds++; cleanupUnresolved = true }
        }
        val controller = CallVisionController(binder)
        controller.start(true, "call-1")
        var removals = 0
        var cancelled = false
        lateinit var owner: VideoNotificationLifecycle
        owner = VideoNotificationLifecycle(
            post = { throw IllegalStateException("Foreground update refused") },
            removeForeground = { removals++ },
            cancelNotification = { cancelled = true },
            onPostRejected = { owner.close { controller.stop() } },
        )
        assertFalse(owner.publish("Video on"))
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.state)
        assertEquals("call-1", controller.captureCallId())
        assertTrue(binder.cleanupUnresolved)
        assertFalse(videoStatusText(controller.state).contains("Video on"))
        assertFalse(owner.publish(videoStatusText(controller.state)))
        owner.close { fail("onDestroy must not repeat the rejected update's teardown") }
        assertEquals(1, binder.unbinds)
        assertEquals(1, removals)
        assertTrue(cancelled)
    }


    @Test fun fatalPublicationErrorsAreNotSwallowedAsForegroundAdmissionRejections() {
        val fatal = AssertionError("programming failure")
        val owner = VideoNotificationLifecycle(
            post = { throw fatal },
            removeForeground = {},
            cancelNotification = {},
            onPostRejected = { fail("Fatal errors are not recoverable platform rejections") },
        )
        assertSame(fatal, assertThrows(AssertionError::class.java) { owner.publish("Video on") })
    }

}
