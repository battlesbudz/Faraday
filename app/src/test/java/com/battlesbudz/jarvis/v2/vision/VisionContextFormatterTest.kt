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

    @Test fun serverDetectionsFormattedWithoutPeopleCount() {
        val text = VisionContextFormatter.formatServerDetections(
            listOf(detection("person"), detection("bus")), 83.5
        )
        assertTrue("expected 'Phone Inference server' in:\n$text", text.contains("Phone Inference server"))
        assertTrue("expected 'Objects detected: 2' in:\n$text", text.contains("Objects detected: 2"))
        assertTrue("expected person line in:\n$text", text.contains("person 0.85"))
        assertFalse("must not print a people count for a detections-only snapshot:\n$text", text.contains("People count"))
    }

    @Test fun serverDetectionsEmptyIsExplicit() {
        val text = VisionContextFormatter.formatServerDetections(emptyList(), 10.0)
        assertTrue("expected 'Objects detected: 0' in:\n$text", text.contains("Objects detected: 0"))
    }
}
