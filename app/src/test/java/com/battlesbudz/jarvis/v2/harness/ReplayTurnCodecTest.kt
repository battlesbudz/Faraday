package com.battlesbudz.jarvis.v2.harness

import org.junit.Assert.*
import org.junit.Test

class ReplayTurnCodecTest {
    private fun fullRecord(): ReplayTurnRecord = ReplayTurnRecord(
        id = "turn-1",
        capturedAtMs = 1_792_619_000_000L,
        harnessVersion = 1,
        modelId = "gemma-4-e2b",
        kind = ReplayTurnRecord.Kind.IMAGE_TEXT,
        replayable = true,
        userPrompt = "How many cars are in this photo?",
        historyOffered = listOf(
            ReplayTurnRecord.HistoryEntry("You", "Look at this street."),
            ReplayTurnRecord.HistoryEntry("Jarvis", "I see a street with cars.")
        ),
        seedContextRequested = true,
        seeded = true,
        memorySection = "[Approved MemoryOS context] - Name: Kiko",
        continuitySection = "Current conversation evidence",
        captureSection = "Memory capture receipt: none.",
        contextBlock = "On-device vision analysis (Phone Inference server, 254 ms).",
        vision = ReplayTurnRecord.VisionCapture(
            source = "server",
            totalMs = 254.0,
            detections = listOf(
                ReplayTurnRecord.DetectionCapture("car", 1, 0.92f,
                    ReplayTurnRecord.BoxCapture(0f, 0f, 200f, 100f)),
                ReplayTurnRecord.DetectionCapture("person", 0, 0.88f,
                    ReplayTurnRecord.BoxCapture(300f, 0f, 360f, 200f))
            ),
            segments = listOf(
                ReplayTurnRecord.SegmentCapture("car", 1, 0.7f,
                    ReplayTurnRecord.BoxCapture(0f, 0f, 200f, 100f),
                    listOf(ReplayTurnRecord.PointCapture(1.5f, 2.5f)))
            ),
            poses = listOf(
                ReplayTurnRecord.PoseCapture(0.9f,
                    ReplayTurnRecord.BoxCapture(300f, 0f, 360f, 200f),
                    listOf(ReplayTurnRecord.KeypointCapture("nose", 0, 330f, 20f, 0.95f)))
            ),
            classifications = listOf(ReplayTurnRecord.ClassificationCapture("outdoor", 1, 0.7f))
        ),
        referenceSection = null,
        activeSubject = "the photo",
        resolvedQuestion = null,
        toolPlan = listOf(
            ReplayTurnRecord.ToolStep("open_app", "{\"app\":\"Facebook\"}", "Open Facebook")
        ),
        toolReceipts = listOf(
            ReplayTurnRecord.ToolReceipt("open_app", "Opened Facebook.", true, "SUCCEEDED")
        ),
        finalPrompt = "You are Jarvis...\n\nCurrent user message:\nHow many cars are in this photo?"
    )

    @Test fun fullRecordRoundTrips() {
        val record = fullRecord()
        val restored = ReplayTurnCodec.fromJson(ReplayTurnCodec.toJson(record))
        assertEquals(record, restored)
    }

    @Test fun minimalRecordRoundTrips() {
        val record = ReplayTurnRecord(
            id = "turn-2", capturedAtMs = 5L, harnessVersion = 1, modelId = "gemma-4-e4b",
            kind = ReplayTurnRecord.Kind.DIRECT_AUDIO, replayable = false,
            userPrompt = "", historyOffered = emptyList(),
            seedContextRequested = false, seeded = false,
            memorySection = null, continuitySection = "", captureSection = "",
            contextBlock = null, vision = null, referenceSection = null,
            activeSubject = null, resolvedQuestion = null,
            toolPlan = emptyList(), toolReceipts = emptyList(), finalPrompt = "prompt"
        )
        assertEquals(record, ReplayTurnCodec.fromJson(ReplayTurnCodec.toJson(record)))
    }

    @Test fun malformedAndForeignJsonAreRejected() {
        assertNull(ReplayTurnCodec.fromJson("{broken"))
        assertNull(ReplayTurnCodec.fromJson("""{"schema": 99}"""))
        assertNull(ReplayTurnCodec.fromJson("""{"schema": 1}"""))
        assertNull(ReplayTurnCodec.fromJson("[]"))
    }
}
