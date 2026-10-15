package com.battlesbudz.jarvis.v2.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    /** Decodes, blank-checks, and runs NativeVision over one screenshot. Throws on failure. */
    fun analyze(imageBytes: ByteArray): ScreenshotVisionEvidence {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalStateException("Could not decode the screenshot.")
        try {
            val nonBlank = isNonBlank(bitmap)
            val snapshot = runBlocking {
                withTimeout(ANALYSIS_TIMEOUT_MS) { NativeVision.analyze(imageBytes, assets) }
            }
            return ScreenshotVisionEvidence(
                nonBlank = nonBlank,
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

/** A screenshot counts as visible content when a coarse sample holds at least 8 distinct colors. */
private fun isNonBlank(bitmap: Bitmap): Boolean {
    val colors = HashSet<Int>()
    val xStep = maxOf(1, bitmap.width / 32)
    val yStep = maxOf(1, bitmap.height / 32)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            colors += bitmap.getPixel(x, y)
            if (colors.size >= 8) return true
            x += xStep
        }
        y += yStep
    }
    return colors.size >= 8
}
