package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniAudioEncoderTest {
    @Test
    fun pcm16ConversionUsesSignedLittleEndianNormalization() {
        val pcm = byteArrayOf(0x00, 0x80.toByte(), 0xff.toByte(), 0x7f)

        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768f), OmniAudioEncoder.pcm16ToFloat(pcm), 0f)
    }

    @Test
    fun fixedWindowsPadOnlyTheLastWindow() {
        val windows = mutableListOf<Pair<FloatArray, Int>>()

        OmniAudioWindowing.forEach(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 4) { window, valid ->
            windows += window to valid
        }

        assertEquals(2, windows.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), windows[0].first, 0f)
        assertEquals(4, windows[0].second)
        assertArrayEquals(floatArrayOf(5f, 6f, 0f, 0f), windows[1].first, 0f)
        assertEquals(2, windows[1].second)
    }

    @Test
    fun emptyInputDoesNotRunAnArtificialZeroWindow() {
        var calls = 0

        OmniAudioWindowing.forEach(floatArrayOf(), 4) { _, _ -> calls++ }

        assertEquals(0, calls)
    }

    @Test
    fun onlyMaskValidFeatureRowsAreReturnedAsIndependentRows() {
        val features = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        val rows = selectValidOmniAudioRows(features, booleanArrayOf(true, false, true), 2)

        assertEquals(2, rows.size)
        assertArrayEquals(floatArrayOf(1f, 2f), rows[0], 0f)
        assertArrayEquals(floatArrayOf(5f, 6f), rows[1], 0f)
        features.fill(0f)
        assertArrayEquals(floatArrayOf(1f, 2f), rows[0], 0f)
    }
}
