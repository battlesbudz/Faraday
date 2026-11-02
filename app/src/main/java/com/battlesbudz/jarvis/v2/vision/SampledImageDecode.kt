package com.battlesbudz.jarvis.v2.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Bounds-first sampled decode for still images handed to the vision stack
 * (reliability epic item 7).
 *
 * A phone photo can be 4080x3060 (about 50 MB as an ARGB bitmap) while
 * every consumer of the decode works far below that: the ONNX models
 * letterbox to 640x640 inputs, and the screenshot blank check samples a
 * coarse pixel grid. Decoding at full resolution first was pure transient
 * memory and latency. This decodes with
 * [BitmapFactory.Options.inSampleSize] so the longest edge lands within
 * [MAX_LONGEST_EDGE_PX], following the sizing math in [sampleSizeFor] and
 * [decodedDimension] — pure functions, pinned by unit tests because
 * BitmapFactory itself is a stub under plain JVM tests.
 *
 * The sizing math models the platform decoder's output as
 * ceil(dimension / inSampleSize) per axis, which is what the standard
 * Android decoders produce for BitmapFactory-decodable formats. A decoder
 * that lands an axis a pixel off does not matter to any consumer: callers
 * that need exact source-space coordinates compute their ratios from the
 * decoded bitmap's actual size (see NativeVision).
 */
internal object SampledImageDecode {

    /** Longest-edge cap for decoded stills, in pixels. */
    const val MAX_LONGEST_EDGE_PX = 1440

    /** A decoded bitmap plus the source image's true dimensions. */
    class Decoded(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    /**
     * Smallest power-of-two sample size whose decoded longest edge is
     * within [maxLongestEdge]. Images already inside the cap get 1 (the
     * decode is unchanged). Non-positive dimensions also get 1; the
     * decode itself decides whether the bytes are an image at all.
     */
    fun sampleSizeFor(width: Int, height: Int, maxLongestEdge: Int = MAX_LONGEST_EDGE_PX): Int {
        require(maxLongestEdge > 0) { "maxLongestEdge must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxLongestEdge) return 1
        var sample = 1
        while (decodedDimension(longest, sample) > maxLongestEdge) sample *= 2
        return sample
    }

    /** One axis of a sampled decode: ceil(dimension / sampleSize), at least 1. */
    fun decodedDimension(dimension: Int, sampleSize: Int): Int {
        if (dimension <= 0) return 0
        return maxOf(1, (dimension + sampleSize - 1) / sampleSize)
    }

    /**
     * Decodes [imageBytes] sampled so the longest edge is within
     * [maxLongestEdge], or returns null when the bytes are not a
     * decodable image. When the bounds pass cannot read the source
     * dimensions, this falls back to the plain full decode the callers
     * used before, reporting the decoded size as the source size so no
     * rescaling is applied downstream.
     */
    fun decode(imageBytes: ByteArray, maxLongestEdge: Int = MAX_LONGEST_EDGE_PX): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongestEdge)
        if (sample <= 1) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            return Decoded(bitmap, bitmap.width, bitmap.height)
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options) ?: return null
        return Decoded(bitmap, bounds.outWidth, bounds.outHeight)
    }
}
