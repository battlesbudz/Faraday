package com.battlesbudz.jarvis.v2.vision

import com.battlesbudz.jarvis.v2.harness.HarnessConfig
import com.battlesbudz.jarvis.v2.harness.HarnessTemplate
import com.battlesbudz.phoneinference.Detection

/**
 * Formats a [NativeVisionSnapshot] as compact context text for Gemma's
 * prompt, so a single message fuses the native model's structured
 * detections with Gemma's own image understanding.
 *
 * The wording and the numeric knobs are editable harness content
 * (self-improving harness project, Phase 1) supplied through
 * [configProvider]; the composition logic stays here. With the shipped v1
 * content the output is byte-identical to the previously hardcoded text.
 */
object VisionContextFormatter {
    @Volatile
    var configProvider: () -> HarnessConfig = { HarnessConfig.DEFAULT }

    /** The formatter component of the current harness config, for vision collaborators. */
    fun currentConfig(): HarnessConfig.FormatterConfig = configProvider().formatter

    fun format(snapshot: NativeVisionSnapshot): String {
        val config = currentConfig()
        val parts = mutableListOf(
            HarnessTemplate.render(config.nativeHeader, mapOf(
                HarnessConfig.TOKEN_MS to snapshot.totalMs.toLong().toString()))
        )
        parts += objectsLine(config, snapshot.detections.size) {
            snapshot.detections.joinToString("; ") {
                "${it.label} ${"%.2f".format(it.confidence)} at " +
                    "[${it.box.x1.toInt()},${it.box.y1.toInt()},${it.box.x2.toInt()},${it.box.y2.toInt()}]"
            }
        }
        if (config.emitSegmentsLine && snapshot.segments.isNotEmpty()) {
            parts += config.segmentsLinePrefix + snapshot.segments.joinToString("; ") {
                "${it.label} (${it.points.size}-point outline)"
            }
        }
        if (config.emitPeopleCountLine) {
            if (snapshot.poses.isEmpty()) {
                parts += config.peopleCountPrefix + "0."
            } else {
                parts += config.peopleCountPrefix + "${snapshot.poses.size}. " + config.peopleLinePrefix +
                    snapshot.poses.joinToString("; ") {
                        "person with ${it.keypoints.size} tracked keypoints"
                    }
            }
        }
        if (config.emitSceneLine) {
            snapshot.classifications.firstOrNull()?.let {
                parts += config.sceneLinePrefix + "${it.label} (${"%.2f".format(it.confidence)})"
            }
        }
        return parts.joinToString("\n")
    }

    /**
     * Formats a detections-only snapshot from the Phone Inference server.
     * The server returns detections only — no segmentation, pose, or
     * classification — so unlike [format] there are no outline or scene
     * lines. The counts ARE here, computed in code from the cleaned
     * detections (people are counted from the person detections, since
     * the server computes no poses): the model is handed the numbers
     * instead of being asked to count boxes itself, which on device led
     * it to repeat a stale count from earlier in the conversation.
     */
    fun formatServerDetections(detections: List<Detection>, inferenceMs: Double): String {
        val config = currentConfig()
        // Clean here as well as in PhoneVisionClient, so the counts below
        // describe the same list the model is shown whoever built the
        // input. The cleanup is idempotent, so an already-clean list
        // passes through unchanged.
        val cleaned = ServerDetections.dropContainedDuplicates(detections, config.containmentThreshold)
        val parts = mutableListOf(
            HarnessTemplate.render(config.serverHeader, mapOf(
                HarnessConfig.TOKEN_MS to inferenceMs.toLong().toString()))
        )
        parts += objectsLine(config, cleaned.size) {
            cleaned.joinToString("; ") {
                "${it.label} ${"%.2f".format(it.confidence)} at " +
                    "[${it.box.x1.toInt()},${it.box.y1.toInt()},${it.box.x2.toInt()},${it.box.y2.toInt()}]"
            }
        }
        if (cleaned.isNotEmpty()) {
            if (config.emitCountsLine) {
                val counts = cleaned.groupingBy { it.label }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                parts += config.countsLinePrefix + counts.joinToString(", ") { "${it.key} ${it.value}" } + "."
            }
            if (config.emitPeopleCountLine) {
                parts += config.peopleCountPrefix + "${cleaned.count { it.label == "person" }}."
            }
        }
        return parts.joinToString("\n")
    }

    /**
     * User-visible note when native analysis throws. Returned as the vision
     * context (instead of null) so the failure is diagnosable instead of
     * silently swallowed; the raw image remains attached as fallback.
     */
    fun failureNote(error: Throwable): String =
        HarnessTemplate.render(currentConfig().failureNote, mapOf(
            HarnessConfig.TOKEN_ERROR to (error.message?.take(120) ?: "unknown error")))

    /** One-line summary for the visible magnifier reply. */
    fun summaryLine(snapshot: NativeVisionSnapshot): String {
        val config = currentConfig()
        return if (snapshot.detections.isEmpty()) {
            HarnessTemplate.render(config.summaryEmpty, mapOf(
                HarnessConfig.TOKEN_MS to snapshot.totalMs.toLong().toString()))
        } else {
            config.summaryPrefix + snapshot.detections.joinToString(", ") {
                "${it.label} (${"%.2f".format(it.confidence)})"
            } + HarnessTemplate.render(config.summarySuffix, mapOf(
                HarnessConfig.TOKEN_MS to snapshot.totalMs.toLong().toString()))
        }
    }

    private fun objectsLine(config: HarnessConfig.FormatterConfig, count: Int,
                            items: () -> String): String =
        if (count == 0) {
            config.objectsLinePrefix + "0" + config.objectsLineJoiner + config.noObjectsText
        } else {
            config.objectsLinePrefix + count.toString() + config.objectsLineJoiner + items()
        }
}
