package com.battlesbudz.jarvis.v2.ai.storage

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {

    @Test fun labelShowsPercentAndByteCounts() {
        val label = downloadProgressLabel(512L * 1024 * 1024, 1024L * 1024 * 1024, 0.0)
        assertTrue(label, label.contains("50%"))
        assertTrue(label, label.contains("512.0 MB of 1.0 GB"))
        assertFalse(label, label.contains("/s"))
        assertFalse(label, label.contains("left"))
    }

    @Test fun labelShowsSpeedAndEta() {
        // 1.0 GB done of 2.0 GB at 10 MB/s -> 50%, ~102 sec left -> minutes.
        val label = downloadProgressLabel(1024L * 1024 * 1024, 2L * 1024 * 1024 * 1024, 10.0 * 1024 * 1024)
        assertTrue(label, label.contains("50%"))
        assertTrue(label, label.contains("10.0 MB/s"))
        assertTrue(label, label.contains("min left"))
    }

    @Test fun labelFallsBackWhenTotalUnknown() {
        val label = downloadProgressLabel(300L * 1024 * 1024, -1L, 0.0)
        assertTrue(label, label.contains("so far"))
        assertTrue(label, label.contains("300.0 MB"))
        assertFalse(label, label.contains("%"))
    }

    @Test fun labelFallsBackWhenTotalUnknownWithSpeed() {
        val label = downloadProgressLabel(300L * 1024 * 1024, 0L, 5.0 * 1024 * 1024)
        assertTrue(label, label.contains("5.0 MB/s"))
        assertFalse(label, label.contains("%"))
    }

    @Test fun percentIsClamped() {
        val over = downloadProgressLabel(3L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024, 0.0)
        assertTrue(over, over.startsWith("100%"))
        val under = downloadProgressLabel(-50L, 1024L * 1024 * 1024, 0.0)
        assertTrue(under, under.startsWith("0%"))
    }

    @Test fun formatBytesUsesBinaryUnits() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.9 GB", formatBytes((2.9 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun speedTrackerEstimatesRateFromSamples() {
        var now = 0L
        val tracker = DownloadSpeedTracker(clockMs = { now })
        assertEquals(0.0, tracker.addSample(0L), 0.001)
        now = 2_000L
        assertEquals(2048.0, tracker.addSample(4096L), 0.001)
        now = 4_000L
        assertEquals(2048.0, tracker.addSample(8192L), 0.001)
    }

    @Test fun speedTrackerResetsOnNewTransfer() {
        var now = 0L
        val tracker = DownloadSpeedTracker(clockMs = { now })
        tracker.addSample(0L)
        now = 2_000L
        tracker.addSample(8192L)
        // Byte count drops: a new transfer started, old samples are discarded.
        now = 3_000L
        assertEquals(0.0, tracker.addSample(100L), 0.001)
        now = 5_000L
        // (5000, 5100) vs (3000, 100): 5000 bytes / 2 sec = 2500 B/s.
        assertEquals(2500.0, tracker.addSample(5100L), 0.001)
    }

    @Test fun speedTrackerIgnoresStaleSamples() {
        var now = 0L
        val tracker = DownloadSpeedTracker(clockMs = { now }, windowMs = 1_000L)
        tracker.addSample(0L)
        now = 500L
        tracker.addSample(10_000L) // 20_000 B/s over the live window
        assertEquals(20_000.0, tracker.speedBytesPerSec, 0.001)
        now = 5_000L // first sample is now stale; only one sample left -> 0
        tracker.addSample(10_000L)
        assertEquals(0.0, tracker.speedBytesPerSec, 0.001)
    }
}
