package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.vision.NativeVisionSnapshot
import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestServerVisionResultTest {
    private fun snapshotOf(detectionCount: Int): NativeVisionSnapshot = NativeVisionSnapshot(
        detections = (1..detectionCount).map {
            Detection(label = "car", classId = -1, confidence = 0.8f, box = Box(0f, 0f, 1f, 1f))
        },
        detectionMs = 300.0,
        segments = emptyList(),
        segmentationMs = 0.0,
        poses = emptyList(),
        poseMs = 0.0,
        classifications = emptyList(),
        classificationMs = 0.0,
    )

    @Test fun successStoresLatestWithFrameAndReceiveTimes() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(snapshotOf(2), frameTimestampMs = 111L, latencyMs = 287L, receivedAtMs = 999L)
        val latest = holder.latest()
        assertEquals(111L, latest?.frameTimestampMs)
        assertEquals(999L, latest?.receivedAtMs)
        assertEquals(2, latest?.snapshot?.detections?.size)
        val counters = holder.counters()
        assertEquals(1L, counters.detectsSucceeded)
        assertEquals(287L, counters.lastLatencyMs)
        assertEquals(2, counters.lastDetectionCount)
        assertNull(holder.lastError())
    }

    @Test fun secondSuccessReplacesFirstWholesale() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(snapshotOf(1), frameTimestampMs = 100L, latencyMs = 250L, receivedAtMs = 400L)
        holder.recordSuccess(snapshotOf(7), frameTimestampMs = 200L, latencyMs = 260L, receivedAtMs = 500L)
        val latest = holder.latest()
        assertEquals(200L, latest?.frameTimestampMs)
        assertEquals(7, latest?.snapshot?.detections?.size)
        assertEquals(2L, holder.counters().detectsSucceeded)
        assertEquals(7, holder.counters().lastDetectionCount)
    }

    @Test fun failureKeepsResultAndTruncatesError() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(snapshotOf(3), frameTimestampMs = 100L, latencyMs = 250L, receivedAtMs = 400L)
        holder.recordFailure("x".repeat(500), latencyMs = 30_000L)
        val latest = holder.latest()
        assertEquals(100L, latest?.frameTimestampMs)
        assertEquals(3, latest?.snapshot?.detections?.size)
        assertEquals(LatestServerVisionResult.MAX_ERROR_CHARS, holder.lastError()?.length)
        assertEquals(1L, holder.counters().detectsFailed)
        assertEquals(30_000L, holder.counters().lastLatencyMs)
    }

    @Test fun successAfterFailureClearsError() {
        val holder = LatestServerVisionResult()
        holder.recordFailure("server down", latencyMs = 10L)
        holder.recordSuccess(snapshotOf(1), frameTimestampMs = 1L, latencyMs = 250L, receivedAtMs = 300L)
        assertNull(holder.lastError())
        assertEquals(1L, holder.counters().detectsFailed)
        assertEquals(1L, holder.counters().detectsSucceeded)
    }

    @Test fun clearRemovesResultAndErrorButKeepsCounters() {
        val holder = LatestServerVisionResult()
        holder.recordReceived()
        holder.recordSent()
        holder.recordSuccess(snapshotOf(2), frameTimestampMs = 1L, latencyMs = 250L, receivedAtMs = 2L)
        holder.recordFailure("boom", latencyMs = 5L)
        holder.clear()
        assertNull(holder.latest())
        assertNull(holder.lastError())
        val counters = holder.counters()
        assertEquals(1L, counters.framesReceived)
        assertEquals(1L, counters.framesSent)
        assertEquals(1L, counters.detectsSucceeded)
        assertEquals(1L, counters.detectsFailed)
    }

    @Test fun resetZeroesEverything() {
        val holder = LatestServerVisionResult()
        holder.recordReceived()
        holder.recordDroppedInFlight()
        holder.recordSuccess(snapshotOf(2), frameTimestampMs = 1L, latencyMs = 250L, receivedAtMs = 2L)
        holder.reset()
        assertNull(holder.latest())
        assertNull(holder.lastError())
        val counters = holder.counters()
        assertEquals(0L, counters.framesReceived)
        assertEquals(0L, counters.framesDroppedInFlight)
        assertEquals(0L, counters.detectsSucceeded)
        assertNull(counters.lastLatencyMs)
        assertNull(counters.lastDetectionCount)
    }

    @Test fun diagnosticsTextCarriesTheProofCounters() {
        val holder = LatestServerVisionResult()
        holder.recordReceived()
        holder.recordReceived()
        holder.recordSent()
        holder.recordDroppedInFlight()
        holder.recordSuccess(snapshotOf(4), frameTimestampMs = 42L, latencyMs = 287L, receivedAtMs = 1000L)
        val text = holder.diagnosticsText()
        assertTrue(text.contains("framesReceived=2"))
        assertTrue(text.contains("framesSent=1"))
        assertTrue(text.contains("framesDroppedInFlight=1"))
        assertTrue(text.contains("detectsSucceeded=1"))
        assertTrue(text.contains("lastLatencyMs=287"))
        assertTrue(text.contains("lastDetectionCount=4"))
        assertTrue(text.contains("latestFrameTimestampMs=42"))
    }

    @Test fun diagnosticsTextCarriesTheVideoToggleState() {
        val holder = LatestServerVisionResult()
        // Step 5: a paste must show whether capture was user-enabled —
        // video=off with zero frames means nobody turned the camera on.
        assertTrue(holder.diagnosticsText().contains("video=off"))
        VoiceSessionUi.videoEnabled.value = true
        try {
            assertTrue(holder.diagnosticsText().contains("video=on"))
        } finally {
            VoiceSessionUi.videoEnabled.value = false
        }
    }

    @Test fun revisionAdvancesOnEveryMutation() {
        val holder = LatestServerVisionResult()
        val start = holder.revision.value
        holder.recordReceived()
        holder.recordSuccess(snapshotOf(1), frameTimestampMs = 1L, latencyMs = 5L, receivedAtMs = 2L)
        holder.clear()
        assertEquals(start + 3, holder.revision.value)
    }

    private fun snapshotOfDetections(vararg detections: Detection): NativeVisionSnapshot =
        NativeVisionSnapshot(
            detections = detections.toList(),
            detectionMs = 300.0,
            segments = emptyList(),
            segmentationMs = 0.0,
            poses = emptyList(),
            poseMs = 0.0,
            classifications = emptyList(),
            classificationMs = 0.0,
        )

    @Test fun successesFeedTheTrackerAcrossFrames() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(
            snapshotOfDetections(Detection("person", -1, 0.9f, Box(0f, 0f, 100f, 100f))),
            frameTimestampMs = 1_000L, latencyMs = 250L, receivedAtMs = 1_300L,
        )
        holder.recordSuccess(
            snapshotOfDetections(Detection("person", -1, 0.9f, Box(10f, 0f, 110f, 100f))),
            frameTimestampMs = 2_700L, latencyMs = 260L, receivedAtMs = 3_000L,
        )
        val tracked = holder.trackedSnapshot()
        assertEquals(1, tracked.objects.size)
        val person = tracked.objects.single()
        assertEquals(1, person.id)
        assertEquals(1_000L, person.firstSeenMs)
        assertEquals(2_700L, person.lastSeenMs)
        assertTrue(person.presentNow)
        val text = holder.diagnosticsText()
        assertTrue(text.contains("TRACKED OBJECTS"))
        assertTrue(text.contains("track#1 person present"))
        assertTrue(text.contains("presentCounts: person 1."))
    }

    @Test fun clearRetainsTheFinalTrackedSnapshotForDiagnostics() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(
            snapshotOfDetections(Detection("cup", -1, 0.9f, Box(0f, 0f, 50f, 50f))),
            frameTimestampMs = 1_000L, latencyMs = 250L, receivedAtMs = 1_300L,
        )
        holder.clear()
        assertNull(holder.latest())
        val tracked = holder.trackedSnapshot()
        assertEquals(1, tracked.objects.size)
        assertEquals("cup", tracked.objects.single().label)
        assertTrue(holder.diagnosticsText().contains("track#1 cup present"))
    }

    @Test fun resetClearsTracksAndRestartsTheirIds() {
        val holder = LatestServerVisionResult()
        holder.recordSuccess(
            snapshotOfDetections(Detection("cup", -1, 0.9f, Box(0f, 0f, 50f, 50f))),
            frameTimestampMs = 1_000L, latencyMs = 250L, receivedAtMs = 1_300L,
        )
        holder.reset()
        assertTrue(holder.trackedSnapshot().objects.isEmpty())
        assertTrue(holder.diagnosticsText().contains("trackedObjects=none"))
        holder.recordSuccess(
            snapshotOfDetections(Detection("car", -1, 0.9f, Box(0f, 0f, 50f, 50f))),
            frameTimestampMs = 2_000L, latencyMs = 250L, receivedAtMs = 2_300L,
        )
        val tracked = holder.trackedSnapshot()
        assertEquals(1, tracked.objects.size)
        assertEquals(1, tracked.objects.single().id)
    }

    @Test fun recordFrameRetainsOnlyTheNewestFrame() {
        val holder = LatestServerVisionResult()
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6, 7)
        holder.recordFrame(first, capturedAtMs = 100L, geometry = FrameGeometry(640, 480, 0))
        holder.recordFrame(second, capturedAtMs = 200L, geometry = FrameGeometry(320, 240, 90))
        val frame = holder.latestFrame()
        assertTrue(second.contentEquals(frame!!.bytes))
        assertEquals(200L, frame.capturedAtMs)
        assertEquals(320, frame.geometry?.width)
        assertEquals(90, frame.geometry?.rotationDegrees)
        assertTrue(holder.diagnosticsText().contains("retainedFrame=bytes=4 capturedAtMs=200"))
    }

    @Test fun clearAndResetDropTheRetainedFrame() {
        val holder = LatestServerVisionResult()
        holder.recordFrame(byteArrayOf(1), capturedAtMs = 1L, geometry = null)
        holder.clear()
        assertNull(holder.latestFrame())
        holder.recordFrame(byteArrayOf(2), capturedAtMs = 2L, geometry = null)
        holder.reset()
        assertNull(holder.latestFrame())
        assertTrue(holder.diagnosticsText().contains("retainedFrame=none"))
    }

    @Test fun changeListenerFiresOnMutation() {
        val holder = LatestServerVisionResult()
        var calls = 0
        holder.changeListener = { calls++ }
        holder.recordReceived()
        holder.recordSuccess(snapshotOf(1), frameTimestampMs = 1L, latencyMs = 1L, receivedAtMs = 2L)
        holder.clear()
        assertEquals(3, calls)
    }
}
