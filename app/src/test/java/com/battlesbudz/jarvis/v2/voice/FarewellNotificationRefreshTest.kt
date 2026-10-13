package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * A spoken farewell ends the call's video capture outside the video
 * service's start/stop command path. [farewellVideoStatus] is the exact
 * composition the runtime runs on a farewell (via
 * VideoCallService.refreshVideoStatusAfterFarewell) before the resulting
 * status is pushed into the service notification: these tests drive that
 * real path and assert the notification status reflects the controller,
 * instead of only exercising [videoStatusText] as a pure helper.
 */
class FarewellNotificationRefreshTest {
    private class FakeBinder : CallVisionController.VideoBinder {
        var binds = 0
        var unbinds = 0
        var onUnbind: () -> Unit = {}
        override var cleanupUnresolved = false
        override fun bind(): CallVisionController.BindResult {
            binds++
            return CallVisionController.BindResult.Started
        }
        override fun unbind() { unbinds++; onUnbind() }
    }

    private fun withRegistry(controller: CallVisionController?, block: () -> Unit) {
        val original = CallVisionRegistry.controller
        CallVisionRegistry.controller = controller
        try {
            block()
        } finally {
            CallVisionRegistry.controller = original
        }
    }

    @Test fun spokenFarewellRefreshesNotificationStatusToIdle() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            // The call's capture is live: the notification reads "Video on".
            assertEquals(CallVisionController.State.ACTIVE,
                controller.start(cameraPermissionGranted = true, callId = "call-1"))
            assertEquals("Video on", videoStatusText(controller.state))

            // The real farewell-to-notification path: end the capture and
            // derive the status the service notification must display.
            val status = farewellVideoStatus("call-1")

            assertEquals("A farewell must unbind the camera", 1, binder.unbinds)
            assertEquals(CallVisionController.State.IDLE, controller.state)
            assertEquals(
                "The notification must reflect the IDLE controller, never the stale \"Video on\"",
                "Video idle — starts with your next call", status)
        }
    }

    @Test fun staleFarewellKeepsActiveNotificationStatus() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-2")
            // A stale farewell for an older call must not touch the newer
            // call's capture — the notification keeps reading "Video on".
            val status = farewellVideoStatus("call-1")
            assertEquals(CallVisionController.State.ACTIVE, controller.state)
            assertEquals("A stale farewell must not unbind the camera", 0, binder.unbinds)
            assertEquals("Video on", status)
        }
    }

    @Test fun farewellWithNoRegisteredControllerReportsIdle() {
        withRegistry(null) {
            assertEquals("Video idle — starts with your next call",
                farewellVideoStatus("call-1"))
        }
    }

    @Test fun teardownCallbackCannotRepostButFailedCameraCleanupStaysOwned() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        val statuses = mutableListOf<String>()
        var removed = false
        val notifications = VideoNotificationLifecycle(
            post = { statuses += it },
            removeForeground = {},
            cancelNotification = { removed = true },
        )
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        notifications.publish(videoStatusText(controller.state))
        binder.onUnbind = {
            binder.cleanupUnresolved = true
            assertFalse(notifications.publish("Camera cleanup failed"))
        }

        notifications.close { controller.stop() }

        assertEquals("Notification teardown cannot claim the camera detached",
            CallVisionController.State.CLEANUP_PENDING, controller.state)
        assertEquals("call-1", controller.captureCallId())
        assertEquals(1, binder.unbinds)
        assertEquals(listOf("Video on"), statuses)
        assertTrue(removed)
        // The binder/controller still own the retry; notification shutdown
        // neither discards their resource nor permits a fresh capture over it.
        binder.onUnbind = { binder.cleanupUnresolved = false }
        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertNull(controller.captureCallId())
        assertEquals(2, binder.unbinds)
    }

    // -- J4: generation-fenced, live-instance-only refresh routing --------

    @Test fun delayedOldRefreshAfterNewCallStartsIsDropped() {
        // A farewell refresh queued for call-1 (generation 1) must be
        // dropped when call-2's START_CAPTURE has since moved the
        // generation on — a delayed "idle" can never overwrite the newer
        // call's "Video on".
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-2")
            val derived = resolveFarewellRefresh(
                endedCallId = "call-1",
                refreshGeneration = 1L,
                currentGeneration = 2L,
                isLive = true,
            )
            assertNull("an obsolete-generation refresh must be dropped", derived)
            assertEquals(CallVisionController.State.ACTIVE, controller.state)
            assertEquals("Video on", videoStatusText(controller.state))
        }
    }

    @Test fun refreshForStoppedServiceIsDropped() {
        // A refresh queued just as the service dies must be dropped — it
        // must never resurrect the notification (or the service) on its
        // own. The capture is untouched: teardown runs only on the live
        // path.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-1")
            val derived = resolveFarewellRefresh(
                endedCallId = "call-1",
                refreshGeneration = 1L,
                currentGeneration = 1L,
                isLive = false,
            )
            assertNull("a refresh for a dead service must be dropped", derived)
            assertEquals(CallVisionController.State.ACTIVE, controller.state)
            assertEquals(0, binder.unbinds)
        }
    }

    @Test fun refreshDerivesStatusFromLiveControllerAtHandleTime() {
        // The status is derived when the refresh is handled, never from a
        // precomputed string carried by the refresh. A farewell for call-1
        // handled while call-2's capture is live derives "Video on" from
        // the live controller and leaves call-2's capture alone.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-2")
            val derived = resolveFarewellRefresh(
                endedCallId = "call-1",
                refreshGeneration = 2L,
                currentGeneration = 2L,
                isLive = true,
            )
            assertEquals("Video on", derived)
            assertEquals("a stale farewell must not stop the live call's capture",
                0, binder.unbinds)
            assertEquals(CallVisionController.State.ACTIVE, controller.state)
        }
    }

    @Test fun currentGenerationRefreshStopsEndedCallAndReportsIdle() {
        // The non-dropped path: a current-generation refresh for the ended
        // call stops its capture and derives the idle status.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-1")
            val derived = resolveFarewellRefresh(
                endedCallId = "call-1",
                refreshGeneration = 3L,
                currentGeneration = 3L,
                isLive = true,
            )
            assertEquals("Video idle — starts with your next call", derived)
            assertEquals(1, binder.unbinds)
            assertEquals(CallVisionController.State.IDLE, controller.state)
        }
    }
}
