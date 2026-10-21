package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.phoneinference.Box
import com.battlesbudz.phoneinference.Detection

/**
 * Cleanup for Phone Inference server detections.
 *
 * The server returns every box its model proposes at the requested
 * confidence cutoff, including near-duplicate boxes for one physical
 * object: a small, weaker box sitting almost entirely inside a bigger,
 * stronger box of the same label (seen on device as extra "person"
 * boxes inside one person's box, inflating every count). Those
 * duplicates are dropped here, in code, before any consumer counts or
 * formats the list, so the formatted output and every count describe
 * the same cleaned list.
 *
 * A detection is dropped only when ALL of these hold:
 *  - another detection has the SAME label (different labels never merge),
 *  - the other detection has strictly higher confidence,
 *  - the other box is at least as large,
 *  - at least [CONTAINMENT_THRESHOLD] of this box's area lies inside
 *    the other box (containment = intersection area / this box's area;
 *    this box is the smaller one whenever the size condition holds).
 *
 * Partially overlapping distinct objects (two people standing side by
 * side, a person in front of a car) stay well under the threshold and
 * survive. The function is pure, preserves the input order of the
 * survivors, and is idempotent: cleaning an already-clean list changes
 * nothing.
 */
object ServerDetections {
    /**
     * Fraction of a box that must sit inside a bigger same-label box for it to count as a duplicate.
     * The compiled-in default; the live value is editable harness content
     * (FormatterConfig.containmentThreshold) passed in by VisionContextFormatter.
     */
    const val CONTAINMENT_THRESHOLD = 0.8f

    fun dropContainedDuplicates(detections: List<Detection>,
                                threshold: Float = CONTAINMENT_THRESHOLD): List<Detection> {
        if (detections.size < 2) return detections
        return detections.filterIndexed { index, candidate ->
            val candidateArea = area(candidate.box)
            if (candidateArea <= 0f) {
                true
            } else {
                detections.indices.none { otherIndex ->
                    if (otherIndex == index) {
                        false
                    } else {
                        val other = detections[otherIndex]
                        other.label == candidate.label &&
                            other.confidence > candidate.confidence &&
                            area(other.box) >= candidateArea &&
                            intersection(candidate.box, other.box) / candidateArea >=
                            threshold
                    }
                }
            }
        }
    }

    private fun area(box: Box): Float {
        val width = box.x2 - box.x1
        val height = box.y2 - box.y1
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    private fun intersection(a: Box, b: Box): Float {
        val width = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)
        val height = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }
}
