package com.battlesbudz.jarvis.v2.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Result of analyzing one launch-verification screenshot with the existing
 * NativeVision stack. [nonBlank] is the pixel evidence that the capture
 * holds real screen content (a black/empty capture is not evidence of an
 * opening); the remaining fields are the vision summary recorded in
 * diagnostics.
 */
data class ScreenshotVisionEvidence(
    val nonBlank: Boolean,
    val detections: Int,
    val classification: String?,
    val totalMs: Double
)

/**
 * Screenshot analysis for launch verification, using the EXISTING vision
 * components (NativeVision over the :phoneinference module) — no new vision
 * stack, no OCR engine. The repo has no text-recognition dependency, so
 * this analyzer does not read text out of the screenshot; the identity
 * leg of verification is the foreground-package observation in
 * ScreenshotLaunchVerifier, and this analyzer proves the captured pixels
 * are a real, non-blank screen that the vision models actually ran over.
 */
class NativeVisionScreenshotAnalyzer(private val assets: AssetManager) {

    /**
     * Decodes, blank-checks, and runs the detection + classification subset
     * of NativeVision over one screenshot. Throws on failure.
     *
     * Scoped models: launch verification records detections + the first
     * classification and discards segmentation/pose, so this does NOT run
     * those two extra 640-input YOLO models. The full four-model analysis
     * still exists on NativeVision for callers that consume all four
     * outputs; the evidence this analyzer records is identical either way.
     *
     * Bounded execution: the analysis is joined with a real wall-clock
     * bound on NativeVision's dedicated vision dispatcher — never
     * Dispatchers.Default, and never an independent runBlocking root on
     * the caller's (queue-worker) thread. The old runBlocking +
     * cooperative withTimeout occupied both Default threads on a 2-core
     * emulator and left an un-cancellable orphan starving the next test's
     * queue worker after the caller's timeout fired.
     */
    fun analyze(imageBytes: ByteArray): ScreenshotVisionEvidence {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalStateException("Could not decode the screenshot.")
        try {
            val nonBlank = isNonBlank(bitmap)
            if (!nonBlank) {
                // A blank capture carries no evidence, and running the full
                // vision stack over it wastes 15-25s of inference per
                // attempt. Launch verification retries blank captures, so
                // return cheaply: decode plus a pixel sample, no inference.
                return ScreenshotVisionEvidence(
                    nonBlank = false,
                    detections = 0,
                    classification = null,
                    totalMs = 0.0
                )
            }
            val snapshot = NativeVision.analyzeDetectionAndClassificationBlocking(
                imageBytes, assets, ANALYSIS_TIMEOUT_MS
            )
            return ScreenshotVisionEvidence(
                nonBlank = true,
                detections = snapshot.detections.size,
                classification = snapshot.classifications.firstOrNull()?.label,
                totalMs = snapshot.totalMs
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        const val ANALYSIS_TIMEOUT_MS = 90_000L
    }
}

/**
 * A screenshot counts as visible content when a coarse sample spans a real
 * brightness range. The previous rule — at least 8 distinct exact colors on
 * a 32x32 grid — misclassified real, fully rendered flat UIs as blank: the
 * Android Settings Internet page samples only 7 distinct colors (light
 * background, dividers, dark text, one blue toggle), so launch verification
 * reported an opened Settings page as an unconfirmed launch. Brightness
 * range is the property that actually separates an empty capture (solid
 * black, solid white, or one flat color: range ~0) from any real screen
 * (dark text or icons on a light background: range in the hundreds).
 */
internal fun hasVisibleContent(sampledPixels: IntArray): Boolean {
    var minLuminance = 255
    var maxLuminance = 0
    for (pixel in sampledPixels) {
        // Rec. 601 luma from the opaque RGB channels, via plain bit shifts
        // (not android.graphics.Color, whose methods are stubs that throw
        // under plain JVM unit tests); alpha carries no visible content.
        val luminance = (((pixel shr 16) and 0xFF) * 299 +
            ((pixel shr 8) and 0xFF) * 587 +
            (pixel and 0xFF) * 114) / 1000
        if (luminance < minLuminance) minLuminance = luminance
        if (luminance > maxLuminance) maxLuminance = luminance
        if (maxLuminance - minLuminance > MIN_VISIBLE_LUMINANCE_RANGE) return true
    }
    return maxLuminance - minLuminance > MIN_VISIBLE_LUMINANCE_RANGE
}

private const val MIN_VISIBLE_LUMINANCE_RANGE = 32

private fun isNonBlank(bitmap: Bitmap): Boolean {
    val xStep = maxOf(1, bitmap.width / 32)
    val yStep = maxOf(1, bitmap.height / 32)
    val samples = ArrayList<Int>()
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            samples += bitmap.getPixel(x, y)
            x += xStep
        }
        y += yStep
    }
    return hasVisibleContent(samples.toIntArray())
}
