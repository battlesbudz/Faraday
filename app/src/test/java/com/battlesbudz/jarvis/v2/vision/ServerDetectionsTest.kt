package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection
import org.junit.Assert.*
import org.junit.Test

class ServerDetectionsTest {

    private fun det(label: String, confidence: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
        Detection(label = label, classId = -1, confidence = confidence, box = Box(x1, y1, x2, y2))

    @Test fun containedSameLabelDuplicateIsDroppedAndStrongerBoxKept() {
        val big = det("person", 0.9f, 0f, 0f, 200f, 400f)
        val small = det("person", 0.5f, 50f, 100f, 150f, 300f)
        // Fully contained, weaker, same label: dropped regardless of order.
        assertEquals(listOf(big), ServerDetections.dropContainedDuplicates(listOf(big, small)))
        assertEquals(listOf(big), ServerDetections.dropContainedDuplicates(listOf(small, big)))
    }

    @Test fun mostlyContainedSameLabelDuplicateIsDropped() {
        // 90% of the small box sits inside the big box: over the 0.8 threshold.
        val big = det("person", 0.9f, 0f, 0f, 200f, 400f)
        val mostlyInside = det("person", 0.5f, 20f, 100f, 220f, 300f)
        assertEquals(listOf(big), ServerDetections.dropContainedDuplicates(listOf(big, mostlyInside)))
    }

    @Test fun partiallyOverlappingSameLabelBoxesAreBothKept() {
        // Two people side by side: only a quarter of each box overlaps.
        val a = det("person", 0.9f, 0f, 0f, 200f, 200f)
        val b = det("person", 0.8f, 150f, 0f, 350f, 200f)
        assertEquals(listOf(a, b), ServerDetections.dropContainedDuplicates(listOf(a, b)))
        // Half-contained is still a distinct object, not a duplicate.
        val c = det("person", 0.85f, 0f, 0f, 200f, 200f)
        val d = det("person", 0.7f, 100f, 0f, 300f, 200f)
        assertEquals(listOf(c, d), ServerDetections.dropContainedDuplicates(listOf(c, d)))
    }

    @Test fun containedBoxesOfDifferentLabelsAreBothKept() {
        // A handbag fully inside a person's box is not a duplicate person.
        val person = det("person", 0.9f, 0f, 0f, 200f, 400f)
        val handbag = det("handbag", 0.6f, 50f, 100f, 150f, 300f)
        assertEquals(
            listOf(person, handbag),
            ServerDetections.dropContainedDuplicates(listOf(person, handbag))
        )
    }

    @Test fun weakerBoxIsNotDroppedByAWeakerContainer() {
        // The container must be the stronger detection: a weak big box
        // never swallows a strong small one.
        val weakBig = det("person", 0.5f, 0f, 0f, 200f, 400f)
        val strongSmall = det("person", 0.9f, 50f, 100f, 150f, 300f)
        assertEquals(
            listOf(weakBig, strongSmall),
            ServerDetections.dropContainedDuplicates(listOf(weakBig, strongSmall))
        )
    }

    @Test fun cleanupIsIdempotentAndHandlesTrivialInputs() {
        assertEquals(emptyList<Detection>(), ServerDetections.dropContainedDuplicates(emptyList()))
        val single = det("car", 0.9f, 0f, 0f, 10f, 10f)
        assertEquals(listOf(single), ServerDetections.dropContainedDuplicates(listOf(single)))
        val big = det("person", 0.9f, 0f, 0f, 200f, 400f)
        val small = det("person", 0.5f, 50f, 100f, 150f, 300f)
        val once = ServerDetections.dropContainedDuplicates(listOf(big, small))
        assertEquals(once, ServerDetections.dropContainedDuplicates(once))
    }
}
