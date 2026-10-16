package com.battlesbudz.jarvis.v2.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the launch-verification blank check: the Android
 * Settings Internet page samples only 7 distinct colors on the verifier's
 * grid, so the old "at least 8 distinct colors" rule misclassified a real,
 * fully rendered Settings screen as blank and reported the opened page as
 * an unconfirmed launch.
 */
class ScreenshotVisionAnalysisTest {

    // The exact 7 colors sampled from the failing run's Settings screenshot:
    // light background, dividers, dark text, and the blue Wi-Fi toggle.
    private val settingsScreenshotSample = intArrayOf(
        0xFF1A1B21.toInt(), 0xFF45464F.toInt(), 0xFF495D92.toInt(), 0xFFB6B6BD.toInt(),
        0xFFC3C8D6.toInt(), 0xFFEEEDF4.toInt(), 0xFFFFFFFF.toInt()
    )

    @Test fun flatSettingsUiCountsAsVisibleContent() {
        assertTrue(hasVisibleContent(settingsScreenshotSample))
    }

    @Test fun solidBlackCountsAsBlank() {
        assertFalse(hasVisibleContent(IntArray(64) { 0xFF000000.toInt() }))
    }

    @Test fun solidWhiteCountsAsBlank() {
        assertFalse(hasVisibleContent(IntArray(64) { 0xFFFFFFFF.toInt() }))
    }

    @Test fun singleFlatColorCountsAsBlank() {
        assertFalse(hasVisibleContent(IntArray(64) { 0xFF808080.toInt() }))
    }

    @Test fun darkTextOnLightBackgroundCountsAsVisible() {
        val pixels = IntArray(64) { 0xFFFFFFFF.toInt() }
        pixels[0] = 0xFF000000.toInt()
        pixels[63] = 0xFF000000.toInt()
        assertTrue(hasVisibleContent(pixels))
    }

    @Test fun nearFlatNoiseBelowThresholdCountsAsBlank() {
        // Luminance range 32 or less is dithering/anti-aliasing, not content.
        val pixels = IntArray(64) { 0xFF808080.toInt() }
        pixels[0] = 0xFF909090.toInt() // range 16
        assertFalse(hasVisibleContent(pixels))
    }

    @Test fun emptySampleCountsAsBlank() {
        assertFalse(hasVisibleContent(intArrayOf()))
    }
}
