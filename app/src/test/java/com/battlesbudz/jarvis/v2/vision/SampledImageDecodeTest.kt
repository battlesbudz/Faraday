package com.battlesbudz.jarvis.v2.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sampled-decode sizing math of [SampledImageDecode]
 * (reliability epic item 7, V2). The math is pure on purpose:
 * BitmapFactory is a stub under plain JVM unit tests, so the decode
 * itself is exercised on device, while the contract it follows —
 * smallest power-of-two sample whose decoded longest edge is within
 * the cap — is pinned here.
 */
class SampledImageDecodeTest {

    private val cap = SampledImageDecode.MAX_LONGEST_EDGE_PX

    @Test fun imageAlreadyWithinCapDecodesUnsampled() {
        assertEquals(1, SampledImageDecode.sampleSizeFor(800, 600))
        assertEquals(1, SampledImageDecode.sampleSizeFor(1440, 1080))
        assertEquals(1, SampledImageDecode.sampleSizeFor(1080, 1440))
    }

    @Test fun typicalPhonePhotoSamplesByFour() {
        // 4080x3060 (a 12 MP phone camera): /2 leaves 2040, still over
        // the cap; /4 lands at 1020x765.
        assertEquals(4, SampledImageDecode.sampleSizeFor(4080, 3060))
        assertEquals(1020, SampledImageDecode.decodedDimension(4080, 4))
        assertEquals(765, SampledImageDecode.decodedDimension(3060, 4))
    }

    @Test fun portraitPhotoUsesHeightAsTheLongestEdge() {
        assertEquals(4, SampledImageDecode.sampleSizeFor(3060, 4080))
        assertEquals(1020, SampledImageDecode.decodedDimension(4080, 4))
        assertEquals(765, SampledImageDecode.decodedDimension(3060, 4))
    }

    @Test fun justOverTheCapSamplesByTwo() {
        assertEquals(2, SampledImageDecode.sampleSizeFor(2000, 1200))
        // Exact boundary: 2880 / 2 lands on the cap itself.
        assertEquals(2, SampledImageDecode.sampleSizeFor(2880, 1920))
        assertEquals(1440, SampledImageDecode.decodedDimension(2880, 2))
    }

    @Test fun veryLargeImagesKeepDoubling() {
        assertEquals(8, SampledImageDecode.sampleSizeFor(8000, 6000))
        // A wide panorama: only the long edge drives the sample size.
        assertEquals(16, SampledImageDecode.sampleSizeFor(12000, 1000))
    }

    @Test fun decodedLongestEdgeIsWithinTheCapForEveryShape() {
        val shapes = listOf(
            800 to 600, 1440 to 1440, 1441 to 900, 2000 to 1200,
            2880 to 1920, 2881 to 1000, 4080 to 3060, 3060 to 4080,
            8000 to 6000, 6000 to 8000, 12000 to 1000,
        )
        for ((width, height) in shapes) {
            val sample = SampledImageDecode.sampleSizeFor(width, height)
            val decodedLongest = maxOf(
                SampledImageDecode.decodedDimension(width, sample),
                SampledImageDecode.decodedDimension(height, sample),
            )
            assertTrue(
                "${width}x$height at sample $sample decodes to longest edge $decodedLongest, over cap $cap",
                decodedLongest <= cap,
            )
        }
    }

    @Test fun sampleSizeIsAlwaysAPowerOfTwo() {
        for (longest in listOf(1, 100, 1439, 1440, 1441, 3000, 5000, 12000)) {
            val sample = SampledImageDecode.sampleSizeFor(longest, 10)
            assertTrue(
                "sample $sample for longest edge $longest is not a power of two",
                sample >= 1 && (sample and (sample - 1)) == 0,
            )
        }
    }

    @Test fun decodedDimensionRoundsUpAndNeverBelowOne() {
        assertEquals(721, SampledImageDecode.decodedDimension(1441, 2))
        assertEquals(1, SampledImageDecode.decodedDimension(1, 16))
        assertEquals(0, SampledImageDecode.decodedDimension(0, 4))
    }
}
