package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FrameCadenceTest {
    private class Clock(var now: Long = 0L) {
        fun get(): Long = now
    }

    @Test fun firstFrameAlwaysCaptured() {
        val clock = Clock(10_000L)
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = clock::get)
        assertTrue(cadence.shouldCapture())
    }

    @Test fun defaultOneFpsGatesAtOneSecond() {
        val clock = Clock(0L)
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = clock::get)
        assertTrue(cadence.shouldCapture())
        cadence.markCaptured()

        clock.now = 999L
        assertFalse(cadence.shouldCapture())
        clock.now = 1000L
        assertTrue(cadence.shouldCapture())
    }

    @Test fun zeroFpsNeverCaptures() {
        val clock = Clock(0L)
        val cadence = FrameCadence(framesPerSecond = 0.0, clockMs = clock::get)
        clock.now = 60_000L
        assertFalse(cadence.shouldCapture())
    }

    @Test fun burstRaisesRateThenExpires() {
        val clock = Clock(0L)
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = clock::get)
        cadence.startBurst(durationMs = 1_000L, fps = 5.0)

        assertTrue(cadence.shouldCapture())
        cadence.markCaptured() // t=0
        clock.now = 199L
        assertFalse(cadence.shouldCapture())
        clock.now = 200L
        assertTrue(cadence.shouldCapture())
        cadence.markCaptured() // t=200

        // Burst expired at t=1000: back to 1 fps from last capture at t=200.
        clock.now = 1001L
        assertFalse(cadence.shouldCapture())
        clock.now = 1200L
        assertTrue(cadence.shouldCapture())
    }

    @Test fun burstRequiresPositiveArguments() {
        val cadence = FrameCadence()
        try {
            cadence.startBurst(0L, 5.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        try {
            cadence.startBurst(1000L, 0.0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun stopBurstRestoresBaseRateImmediately() {
        val clock = Clock(0L)
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = clock::get)
        cadence.startBurst(durationMs = 60_000L, fps = 10.0)
        cadence.stopBurst()
        assertTrue(cadence.shouldCapture())
        cadence.markCaptured()
        clock.now = 500L
        assertFalse(cadence.shouldCapture())
    }

    @Test fun resetPausesCaptureForFullInterval() {
        val clock = Clock(0L)
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = clock::get)
        assertTrue(cadence.shouldCapture())
        cadence.markCaptured()
        assertFalse(cadence.shouldCapture())
        cadence.reset()
        // After reset (e.g. camera degrade), the next capture waits a full
        // interval instead of firing immediately.
        assertFalse(cadence.shouldCapture())
        clock.now = 1000L
        assertTrue(cadence.shouldCapture())
    }
}
