package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.ClassPrediction
import com.battlesbudz.phoneinference.Detection
import com.battlesbudz.phoneinference.Keypoint
import com.battlesbudz.phoneinference.PoseResult
import org.junit.Assert.*
import org.junit.Test

class VisionContextFormatterTest {
    private fun pose(keypoints: Int = 17) = PoseResult(
        confidence = 0.9f,
        box = Box(0f, 0f, 100f, 200f),
        keypoints = List(keypoints) { Keypoint("kp$it", it, 10f, 20f, 0.8f) }
    )

    private fun detection(label: String = "person") = Detection(
        label = label, classId = 0, confidence = 0.85f,
        box = Box(10f, 10f, 50f, 90f)
    )

    private fun snapshot(
        detections: List<Detection> = emptyList(),
        poses: List<PoseResult> = emptyList(),
    ) = NativeVisionSnapshot(
        detections = detections, detectionMs = 10.0,
        segments = emptyList(), segmentationMs = 5.0,
        poses = poses, poseMs = 8.0,
        classifications = listOf(ClassPrediction("outdoor", 1, 0.7f)),
        classificationMs = 3.0,
    )

    @Test fun peopleCountIsExplicit() {
        val text = VisionContextFormatter.format(snapshot(poses = listOf(pose(), pose(), pose())))
        assertTrue("expected 'People count: 3' in:\n$text", text.contains("People count: 3"))
    }

    @Test fun zeroPeopleIsExplicit() {
        val text = VisionContextFormatter.format(snapshot())
        assertTrue("expected 'People count: 0' in:\n$text", text.contains("People count: 0"))
    }

    @Test fun objectCountIsExplicit() {
        val text = VisionContextFormatter.format(
            snapshot(detections = listOf(detection("car"), detection("dog")))
        )
        assertTrue("expected 'Objects detected: 2' in:\n$text", text.contains("Objects detected: 2"))
    }

    @Test fun zeroObjectsIsExplicit() {
        val text = VisionContextFormatter.format(snapshot())
        assertTrue("expected 'Objects detected: 0' in:\n$text", text.contains("Objects detected: 0"))
    }

    @Test fun detailLinesPreserved() {
        val text = VisionContextFormatter.format(
            snapshot(detections = listOf(detection("car")), poses = listOf(pose(15)))
        )
        assertTrue(text.contains("car 0.85"))
        assertTrue(text.contains("person with 15 tracked keypoints"))
    }

    @Test fun failureNoteMentionsUnavailability() {
        val note = VisionContextFormatter.failureNote(IllegalStateException("Could not decode the attached image."))
        assertTrue(note.contains("unavailable"))
        assertTrue(note.contains("Could not decode"))
    }

    @Test fun failureNoteHandlesNullMessage() {
        val note = VisionContextFormatter.failureNote(RuntimeException())
        assertTrue(note.contains("unavailable"))
        assertTrue(note.contains("unknown error"))
    }

    @Test fun serverDetectionsIncludeCodeMadeCounts() {
        val text = VisionContextFormatter.formatServerDetections(
            listOf(detection("person"), detection("bus")), 83.5
        )
        assertTrue("expected 'Phone Inference server' in:\n$text", text.contains("Phone Inference server"))
        assertTrue("expected 'Objects detected: 2' in:\n$text", text.contains("Objects detected: 2"))
        assertTrue("expected person line in:\n$text", text.contains("person 0.85"))
        // Counts are computed in code from the detections (the server has
        // no pose results), so the model reads the numbers instead of
        // counting boxes itself.
        assertTrue("expected per-label counts in:\n$text", text.contains("Counts: bus 1, person 1."))
        assertTrue("expected 'People count: 1' in:\n$text", text.contains("People count: 1"))
    }

    @Test fun serverCountsMatchCleanedDetectionList() {
        // Fixture resembling the on-device street photo: several cars and
        // people, plus one weaker person box fully inside a stronger
        // person box. The formatted counts must describe the CLEANED
        // list (8 objects), not the raw one (9).
        fun det(label: String, confidence: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
            Detection(label = label, classId = -1, confidence = confidence, box = Box(x1, y1, x2, y2))
        val raw = listOf(
            det("bus", 0.93f, 1106f, 35f, 1535f, 968f),
            det("car", 0.90f, 335f, 493f, 507f, 633f),
            det("person", 0.88f, 539f, 462f, 713f, 930f),
            det("car", 0.87f, 0f, 497f, 208f, 795f),
            det("person", 0.86f, 684f, 488f, 797f, 836f),
            det("car", 0.85f, 157f, 508f, 377f, 689f),
            det("person", 0.84f, 775f, 503f, 951f, 978f),
            det("person", 0.48f, 810f, 540f, 870f, 700f),
            det("car", 0.52f, 738f, 461f, 797f, 508f),
        )
        val text = VisionContextFormatter.formatServerDetections(raw, 254.0)
        assertTrue("expected cleaned total in:\n$text", text.contains("Objects detected: 8"))
        assertTrue("expected cleaned counts in:\n$text", text.contains("Counts: car 4, person 3, bus 1."))
        assertTrue("expected cleaned people count in:\n$text", text.contains("People count: 3"))
        assertFalse("duplicate box must not be listed in:\n$text", text.contains("person 0.48"))
    }

    @Test fun serverDetectionsEmptyIsExplicit() {
        val text = VisionContextFormatter.formatServerDetections(emptyList(), 10.0)
        assertTrue("expected 'Objects detected: 0' in:\n$text", text.contains("Objects detected: 0"))
    }
}
