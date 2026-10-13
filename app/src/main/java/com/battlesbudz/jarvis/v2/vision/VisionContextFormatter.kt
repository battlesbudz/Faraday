package com.battlesbudz.jarvis.v2.vision

/**
 * Formats a [NativeVisionSnapshot] as compact context text for Gemma's
 * prompt, so a single message fuses the native model's structured
 * detections with Gemma's own image understanding.
 */
object VisionContextFormatter {
    fun format(snapshot: NativeVisionSnapshot): String {
        val parts = mutableListOf(
            "On-device vision analysis (native ONNX models, ${snapshot.totalMs.toLong()} ms total). " +
                "Treat the following as authoritative for what is visible in the attached image."
        )
        if (snapshot.detections.isEmpty()) {
            parts += "Objects detected: 0. Detected objects: none recognizable."
        } else {
            parts += "Objects detected: ${snapshot.detections.size}. Detected objects: " + snapshot.detections.joinToString("; ") {
                "${it.label} ${"%.2f".format(it.confidence)} at " +
                    "[${it.box.x1.toInt()},${it.box.y1.toInt()},${it.box.x2.toInt()},${it.box.y2.toInt()}]"
            }
        }
        if (snapshot.segments.isNotEmpty()) {
            parts += "Segmented outlines: " + snapshot.segments.joinToString("; ") {
                "${it.label} (${it.points.size}-point outline)"
            }
        }
        if (snapshot.poses.isEmpty()) {
            parts += "People count: 0."
        } else {
            parts += "People count: ${snapshot.poses.size}. People: " + snapshot.poses.joinToString("; ") {
                "person with ${it.keypoints.size} tracked keypoints"
            }
        }
        snapshot.classifications.firstOrNull()?.let {
            parts += "Scene looks like: ${it.label} (${"%.2f".format(it.confidence)})"
        }
        return parts.joinToString("\n")
    }

    /**
     * User-visible note when native analysis throws. Returned as the vision
     * context (instead of null) so the failure is diagnosable instead of
     * silently swallowed; the raw image remains attached as fallback.
     */
    fun failureNote(error: Throwable): String =
        "On-device vision analysis was unavailable for this image " +
            "(${error.message?.take(120) ?: "unknown error"}). " +
            "Answer from the attached image alone and mention that on-device detection was unavailable."

    /** One-line summary for the visible magnifier reply. */
    fun summaryLine(snapshot: NativeVisionSnapshot): String =
        if (snapshot.detections.isEmpty()) {
            "I didn't spot anything recognizable in that image (${snapshot.totalMs.toLong()} ms on-device)."
        } else {
            "I see: " + snapshot.detections.joinToString(", ") {
                "${it.label} (${"%.2f".format(it.confidence)})"
            } + " \u2014 ${snapshot.totalMs.toLong()} ms on-device."
        }
}
