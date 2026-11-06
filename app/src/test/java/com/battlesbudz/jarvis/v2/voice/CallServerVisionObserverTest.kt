package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.vision.NativeVisionSnapshot
import com.battlesbudz.jarvis.v2.vision.VisionServerSettings
import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallServerVisionObserverTest {
    private fun snapshotOf(detectionCount: Int): NativeVisionSnapshot = NativeVisionSnapshot(
        detections = (1..detectionCount).map {
            Detection(label = "person", classId = -1, confidence = 0.9f, box = Box(0f, 0f, 1f, 1f))
        },
        detectionMs = 250.0,
        segments = emptyList(),
        segmentationMs = 0.0,
        poses = emptyList(),
        poseMs = 0.0,
        classifications = emptyList(),
        classificationMs = 0.0,
    )

    /** A detect stand-in: records calls, can gate (stay in flight) or fail. */
    private class FakeDetect(var snapshot: NativeVisionSnapshot) {
        val calls = mutableListOf<Pair<Int, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        private val returns = java.util.concurrent.atomic.AtomicInteger(0)

        val fn: suspend (ByteArray, String) -> NativeVisionSnapshot = { bytes, token ->
            synchronized(calls) { calls += bytes.size to token }
            try {
                gate?.await()
                failure?.let { throw it }
                snapshot
            } finally {
                returns.incrementAndGet()
            }
        }

        fun callCount(): Int = synchronized(calls) { calls.size }
        fun returnCount(): Int = returns.get()
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $description")
            }
            Thread.sleep(10)
        }
    }

    private fun observer(
        holder: LatestServerVisionResult,
        detect: FakeDetect,
        config: VisionServerSettings.Config,
        scope: CoroutineScope,
    ) = CallServerVisionObserver(
        holder = holder,
        settings = { config },
        detect = detect.fn,
        scope = scope,
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun gatingDisabledMakesZeroCalls() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = false, token = "tok"), scope)
        observer.onFrame(byteArrayOf(1), 100L)
        observer.onFrame(byteArrayOf(2), 200L)
        assertEquals(0, detect.callCount())
        val counters = holder.counters()
        assertEquals(2L, counters.framesReceived)
        assertEquals(0L, counters.framesSent)
        scope.cancel()
    }

    @Test fun gatingBlankTokenMakesZeroCalls() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "  "), scope)
        observer.onFrame(byteArrayOf(1), 100L)
        assertEquals(0, detect.callCount())
        assertEquals(1L, holder.counters().framesReceived)
        assertEquals(0L, holder.counters().framesSent)
        scope.cancel()
    }

    @Test fun successReplacesHolderWholesale() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(2))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)

        observer.onFrame(byteArrayOf(1, 2, 3), 1_000L)
        awaitCondition("first detect succeeds") { holder.counters().detectsSucceeded == 1L }
        var latest = holder.latest()
        assertNotNull(latest)
        assertEquals(1_000L, latest!!.frameTimestampMs)
        assertEquals(2, latest.snapshot.detections.size)
        assertEquals("tok", synchronized(detect.calls) { detect.calls[0].second })

        detect.snapshot = snapshotOf(5)
        observer.onFrame(byteArrayOf(4), 2_000L)
        awaitCondition("second detect succeeds") { holder.counters().detectsSucceeded == 2L }
        latest = holder.latest()
        assertNotNull(latest)
        assertEquals(2_000L, latest!!.frameTimestampMs)
        assertEquals(5, latest.snapshot.detections.size)
        val counters = holder.counters()
        assertEquals(2L, counters.framesSent)
        assertEquals(5, counters.lastDetectionCount)
        scope.cancel()
    }

    @Test fun singleFlightDropsFramesWhileDetectIsBusy() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        detect.gate = CompletableDeferred()
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)

        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("detect started") { detect.callCount() == 1 }
        observer.onFrame(byteArrayOf(2), 200L)
        observer.onFrame(byteArrayOf(3), 300L)
        assertEquals(1, detect.callCount())
        var counters = holder.counters()
        assertEquals(3L, counters.framesReceived)
        assertEquals(1L, counters.framesSent)
        assertEquals(2L, counters.framesDroppedInFlight)

        detect.gate?.complete(Unit)
        awaitCondition("detect completes") { holder.counters().detectsSucceeded == 1L }
        observer.onFrame(byteArrayOf(4), 400L)
        awaitCondition("second detect succeeds") { holder.counters().detectsSucceeded == 2L }
        counters = holder.counters()
        assertEquals(2L, counters.framesSent)
        assertEquals(2, detect.callCount())
        scope.cancel()
    }

    @Test fun failureKeepsPreviousResultAndCounts() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(3))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)

        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("first detect succeeds") { holder.counters().detectsSucceeded == 1L }

        detect.failure = RuntimeException("server down")
        observer.onFrame(byteArrayOf(2), 200L)
        awaitCondition("second detect fails") { holder.counters().detectsFailed == 1L }

        val latest = holder.latest()
        assertNotNull(latest)
        assertEquals(100L, latest!!.frameTimestampMs)
        assertEquals(3, latest.snapshot.detections.size)
        assertEquals("server down", holder.lastError())
        scope.cancel()
    }

    @Test fun closeStopsAllSending() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)

        observer.close()
        observer.onFrame(byteArrayOf(1), 100L)
        assertEquals(0, detect.callCount())
        assertEquals(0L, holder.counters().framesReceived)
        scope.cancel()
    }

    @Test fun framesAreRetainedForTheSnapshotEvenWhenTheServerIsDisabled() {
        // Snapshot synthesis: the retained frame serves Gemma's per-turn
        // snapshot, not the server, so the server gating must not starve
        // it — while still making zero server calls.
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val scope = testScope()
        val observer = CallServerVisionObserver(
            holder = holder,
            settings = { VisionServerSettings.Config(enabled = false, token = "") },
            detect = detect.fn,
            scope = scope,
            frameGeometry = { FrameGeometry(640, 480, 0) },
        )
        val jpeg = byteArrayOf(9, 9, 9)
        observer.onFrame(jpeg, 123L)
        assertEquals(0, detect.callCount())
        val frame = holder.latestFrame()
        assertNotNull(frame)
        assertTrue(jpeg.contentEquals(frame!!.bytes))
        assertEquals(123L, frame.capturedAtMs)
        assertEquals(640, frame.geometry?.width)
        observer.onCaptureEnded()
        assertNull(holder.latestFrame())
        scope.cancel()
    }

    @Test fun successFromAnEndedCaptureIsDiscarded() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(2))
        val gate = CompletableDeferred<Unit>()
        detect.gate = gate
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)
        observer.onCaptureStarted()
        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("detect started") { detect.callCount() == 1 }
        // The capture ends and a new one begins while the detect is
        // still in flight; its result belongs to the dead capture.
        observer.onCaptureEnded()
        observer.onCaptureStarted()
        gate.complete(Unit)
        awaitCondition("stale detect returned") { detect.returnCount() == 1 }
        Thread.sleep(300)
        assertNull(holder.latest())
        assertNull(holder.entryFrame())
        assertEquals(0L, holder.counters().detectsSucceeded)
        assertTrue(holder.trackedSnapshot().objects.isEmpty())
        scope.cancel()
    }

    @Test fun failureFromAnEndedCaptureIsDiscarded() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val gate = CompletableDeferred<Unit>()
        detect.gate = gate
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)
        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("detect started") { detect.callCount() == 1 }
        observer.onCaptureEnded()
        detect.failure = RuntimeException("server down")
        gate.complete(Unit)
        awaitCondition("stale detect returned") { detect.returnCount() == 1 }
        Thread.sleep(300)
        assertEquals(0L, holder.counters().detectsFailed)
        assertNull(holder.lastError())
        scope.cancel()
    }

    @Test fun staleInFlightDetectNeitherBlocksNorClearsTheNewCapture() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val firstGate = CompletableDeferred<Unit>()
        detect.gate = firstGate
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)
        observer.onCaptureStarted()
        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("first detect started") { detect.callCount() == 1 }

        // Restart the capture around the still-running request: the new
        // capture's frame must be sent, not dropped behind the stale one.
        observer.onCaptureEnded()
        observer.onCaptureStarted()
        detect.gate = null
        observer.onFrame(byteArrayOf(2, 2), 200L)
        awaitCondition("new capture's detect succeeds") { holder.counters().detectsSucceeded == 1L }
        assertEquals(200L, holder.latest()!!.frameTimestampMs)
        assertEquals(0L, holder.counters().framesDroppedInFlight)

        // A third request goes in flight for the new capture; only then
        // does the stale request complete. Its completion is discarded
        // and must not release the third request's single-flight flag.
        val thirdGate = CompletableDeferred<Unit>()
        detect.gate = thirdGate
        observer.onFrame(byteArrayOf(3, 3, 3), 300L)
        awaitCondition("third detect started") { detect.callCount() == 3 }
        firstGate.complete(Unit)
        awaitCondition("stale detect returned") { detect.returnCount() == 2 }
        Thread.sleep(300)
        observer.onFrame(byteArrayOf(4), 400L)
        assertEquals(3, detect.callCount())
        assertEquals(1L, holder.counters().framesDroppedInFlight)
        assertEquals(1L, holder.counters().detectsSucceeded)
        assertEquals(200L, holder.latest()!!.frameTimestampMs)

        thirdGate.complete(Unit)
        awaitCondition("third detect succeeds") { holder.counters().detectsSucceeded == 2L }
        assertEquals(300L, holder.latest()!!.frameTimestampMs)
        scope.cancel()
    }

    @Test fun successPairsTheDetectFrameWithTheEntry() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(1))
        val scope = testScope()
        val observer = CallServerVisionObserver(
            holder = holder,
            settings = { VisionServerSettings.Config(enabled = true, token = "tok") },
            detect = detect.fn,
            scope = scope,
            frameGeometry = { FrameGeometry(640, 480, 0) },
        )
        val sent = byteArrayOf(1, 2, 3)
        observer.onFrame(sent, 1_000L)
        awaitCondition("detect succeeds") { holder.counters().detectsSucceeded == 1L }
        val paired = holder.entryFrame()
        assertNotNull(paired)
        assertTrue(sent.contentEquals(paired!!.bytes))
        assertEquals(1_000L, paired.capturedAtMs)
        assertEquals(640, paired.geometry?.width)
        scope.cancel()
    }

    @Test fun captureEndClearsResultButKeepsCounters() {
        val holder = LatestServerVisionResult()
        val detect = FakeDetect(snapshotOf(4))
        val scope = testScope()
        val observer = observer(holder, detect, VisionServerSettings.Config(enabled = true, token = "tok"), scope)

        observer.onFrame(byteArrayOf(1), 100L)
        awaitCondition("detect succeeds") { holder.counters().detectsSucceeded == 1L }
        assertNotNull(holder.latest())

        observer.onCaptureEnded()
        assertNull(holder.latest())
        assertNull(holder.lastError())
        assertEquals(1L, holder.counters().detectsSucceeded)

        observer.onCaptureStarted()
        assertEquals(0L, holder.counters().framesReceived)
        assertTrue(holder.diagnosticsText().contains("framesReceived=0"))
        scope.cancel()
    }
}
