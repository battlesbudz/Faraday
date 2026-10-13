package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CallVisionControllerTest {
    private class FakeBinder : CallVisionController.VideoBinder {
        var binds = 0
        var unbinds = 0
        override var cleanupUnresolved = false
        /**
         * Scripted per-unbind outcomes for the retained-cleanup retry: each
         * unbind() consumes one entry (true = the retry confirms the detach
         * and clears the unresolved flag). Empty = unbind() leaves the
         * cleanup state untouched.
         */
        val unbindConfirmations = ArrayDeque<Boolean>()
        override fun bind(): CallVisionController.BindResult {
            binds++
            return if (cleanupUnresolved) CallVisionController.BindResult.CleanupBlocked
            else CallVisionController.BindResult.Started
        }
        override fun unbind() {
            unbinds++
            if (unbindConfirmations.removeFirstOrNull() == true) cleanupUnresolved = false
        }
    }

    @Test fun deniedPermissionNeverTouchesBinder() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.DENIED, controller.start(cameraPermissionGranted = false, callId = "call-1"))
        assertEquals(0, binder.binds)
        assertEquals(0, binder.unbinds)
    }

    @Test fun grantedPermissionBindsCamera() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true, callId = "call-1"))
        assertEquals(1, binder.binds)
        assertEquals("call-1", controller.captureCallId())
    }

    @Test fun doubleStartBindsOnlyOnce() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        assertEquals(1, binder.binds)
        assertEquals(CallVisionController.State.ACTIVE, controller.state)
    }

    @Test fun stopUnbindsAndClears() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertEquals(1, binder.unbinds)
        assertNull(hub.latest())
        assertNull(controller.captureCallId())
    }

    @Test fun stopWhenIdleIsSafe() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertEquals(0, binder.unbinds)
    }

    @Test fun deniedCanRecoverAfterGrant() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = false, callId = "call-1")
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true, callId = "call-1"))
        assertEquals(1, binder.binds)
    }

    @Test fun burstOnlyAppliesWhileActive() {
        val binder = FakeBinder()
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = { 0L })
        val controller = CallVisionController(binder, cadence = cadence)
        // No crash, no effect while idle.
        controller.requestBurst(5_000L)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.requestBurst(5_000L, fps = 10.0)
        assertTrue(cadence.shouldCapture(0L))
        cadence.markCaptured(0L)
        // 10 fps -> next capture at 100ms.
        assertTrue(cadence.shouldCapture(100L))
    }

    @Test fun restartAfterStopRebinds() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.stop()
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals(1, binder.unbinds)
    }

    // -- Call-identity gating: only the owning call controls its capture. ---

    @Test fun stopForCallWithOwningIdentityStopsCapture() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
        assertEquals(1, binder.unbinds)
        assertNull("The frame cache must be cleared with the capture", hub.latest())
        assertNull(controller.captureCallId())
    }

    @Test fun stopForCallWithStaleIdentityIsNoOp() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // A stale farewell for an older call must not touch the active capture.
        assertEquals(CallVisionController.State.ACTIVE, controller.stopForCall("call-0"))
        assertEquals(0, binder.unbinds)
        assertEquals("call-1", controller.captureCallId())
    }

    @Test fun stopForCallWhenIdleIsSafe() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
        assertEquals(0, binder.unbinds)
    }

    @Test fun newCallRebindsAfterStaleStop() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // The farewell for call-1 ends its capture...
        controller.stopForCall("call-1")
        assertEquals(1, binder.unbinds)
        // ...and the next call starts a fresh capture under its own identity.
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals("call-2", controller.captureCallId())
        // A late farewell for the old call cannot end the new capture.
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.ACTIVE, controller.state)
        assertEquals(1, binder.unbinds)
        assertEquals("call-2", controller.captureCallId())
    }

    @Test fun differentCallRebindsDefensively() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // beginCall guarantees no overlap, but a late start for another call
        // must never inherit the dead call's capture.
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals(1, binder.unbinds)
        assertEquals("call-2", controller.captureCallId())
    }

    @Test fun blankCallIdIsRejected() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        try {
            controller.start(cameraPermissionGranted = true, callId = "")
            fail("Capture without a call identity must be rejected.")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, binder.binds)
    }

    // -- Degradation: camera failure keeps the call audio-only. --------------

    @Test fun degradeReleasesCameraKeepsCallIdentity() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.DEGRADED, controller.degrade(VideoError.PROVIDER_FAILED))
        assertEquals("The failed camera must be released", 1, binder.unbinds)
        assertNull("The frame cache must be cleared on degradation", hub.latest())
        assertEquals("The owning call still ends its capture", "call-1", controller.captureCallId())
        assertEquals(VideoError.PROVIDER_FAILED, controller.videoError())
    }

    @Test fun degradeWhenIdleIsNoOp() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.IDLE, controller.degrade(VideoError.NO_BACK_CAMERA))
        assertEquals(0, binder.unbinds)
        assertNull(controller.videoError())
    }

    @Test fun stopForCallEndsDegradedCapture() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.degrade(VideoError.BIND_FAILED)
        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
        assertNull(controller.videoError())
        assertNull(controller.captureCallId())
    }

    @Test fun staleStopForCallLeavesDegradedCaptureAlone() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.degrade(VideoError.PROVIDER_FAILED)
        assertEquals(CallVisionController.State.DEGRADED, controller.stopForCall("call-0"))
        assertEquals(VideoError.PROVIDER_FAILED, controller.videoError())
    }

    @Test fun startAfterDegradeRetriesBinding() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.degrade(VideoError.PROVIDER_FAILED)
        assertEquals(CallVisionController.State.ACTIVE,
            controller.start(cameraPermissionGranted = true, callId = "call-1"))
        assertEquals("A retry must bind again", 2, binder.binds)
        assertNull(controller.videoError())
    }

    @Test fun burstDoesNothingWhileDegraded() {
        val binder = FakeBinder()
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = { 0L })
        val controller = CallVisionController(binder, cadence = cadence)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.degrade(VideoError.PROVIDER_FAILED)
        controller.requestBurst(5_000L, fps = 10.0)
        assertFalse(cadence.shouldCapture(0L))
    }

    // -- Cleanup-pending propagation: a refused bind stays visible. --------

    @Test fun stopReportsCleanupPendingWhenDetachFails() {
        // Jerry's camera-integration finding: stopLocked must not report
        // IDLE when the teardown's detach failed — the unresolved cleanup
        // stays visible and the owning call identity is retained.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        binder.cleanupUnresolved = true // the detach failed; the handle is retained
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.stopForCall("call-1"))
        assertEquals("call-1", controller.captureCallId())
        assertEquals(1, binder.unbinds)
    }

    @Test fun startWhileCleanupPendingRetriesDetachThenBinds() {
        // A fresh capture first retries the retained detach; a confirmed
        // cleanup re-arms capture under the new call's identity.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        binder.cleanupUnresolved = true // the detach failed; the handle is retained
        binder.unbindConfirmations.addAll(listOf(false, true)) // stop retry fails, start retry confirms
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.state)
        assertEquals(CallVisionController.State.ACTIVE,
            controller.start(cameraPermissionGranted = true, callId = "call-2"))
        assertEquals("call-2", controller.captureCallId())
        assertEquals(2, binder.unbinds) // the pending start retried the retained detach
        assertFalse(binder.cleanupUnresolved)
    }

    @Test fun startWhileCleanupUnresolvedStaysPendingAndNeverReportsActive() {
        // The retry also fails: the binder refuses, the controller stays
        // pending, and the waiting call owns the pending state.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        binder.cleanupUnresolved = true
        binder.unbindConfirmations.addAll(listOf(false, false)) // neither retry confirms
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.CLEANUP_PENDING,
            controller.start(cameraPermissionGranted = true, callId = "call-2"))
        assertEquals("call-2", controller.captureCallId())
        assertEquals(2, binder.binds) // the pending start attempted the bind and was refused
        // The waiting call's farewell retries the cleanup; a confirmed
        // detach returns to IDLE.
        binder.unbindConfirmations.add(true)
        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-2"))
        assertNull(controller.captureCallId())
        assertFalse(binder.cleanupUnresolved)
    }

    @Test fun staleFarewellDuringPendingIsNoOp() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        binder.cleanupUnresolved = true
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.stopForCall("call-0"))
        assertEquals("call-1", controller.captureCallId())
        assertEquals(1, binder.unbinds)
    }

    @Test fun deniedPermissionAfterFailedCleanupKeepsCleanupPending() {
        // A failed detach followed by a denied-permission next call must
        // keep the pending cleanup visible — never DENIED for cleanup the
        // binder still owns. The retained detach is retried first, before
        // the permission check.
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        binder.cleanupUnresolved = true // the detach failed; the handle is retained
        binder.unbindConfirmations.add(false) // the farewell's cleanup retry also fails
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.state)
        assertEquals(CallVisionController.State.CLEANUP_PENDING,
            controller.start(cameraPermissionGranted = false, callId = "call-2"))
        assertEquals("The retained detach must be retried before the permission check",
            2, binder.unbinds)
        assertEquals("The binder must never be asked to bind while cleanup is pending",
            1, binder.binds)
        assertEquals("The denied call takes no ownership; the failed teardown's owner is retained",
            "call-1", controller.captureCallId())
    }

    @Test fun statusTextNeverAdvertisesVideoOnForPendingCleanup() {
        assertEquals("Video on", videoStatusText(CallVisionController.State.ACTIVE))
        val pending = videoStatusText(CallVisionController.State.CLEANUP_PENDING)
        assertNotEquals("Video on", pending)
        assertTrue("the pending status must name the cleanup, got: $pending",
            pending.contains("cleanup", ignoreCase = true))
        assertEquals("Video idle — starts with your next call",
            videoStatusText(CallVisionController.State.IDLE))
    }
}
