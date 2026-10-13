package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SynthesizedSpeechPcmTest {
    @Test fun conversionRetainsSignedPcmAndClipsBeyondFullScale() {
        val result = SynthesizedSpeechPcm.fromModel(floatArrayOf(-2f, -1f, -.5f, 0f, .5f, 1f, 2f), 16000)
        assertArrayEquals(shortArrayOf(-32767, -32767, -16383, 0, 16383, 32767, 32767), result.samples)
        assertEquals(4, result.clippedSamples)
        // Levels describe original model output, so clipping remains observable.
        assertEquals(2f, result.peak, 0f)
        assertTrue(result.rms > 1.0)
    }

    @Test fun silenceUsesTheSameAudibleThresholdAsPlaybackConfirmation() {
        val result = SynthesizedSpeechPcm.fromModel(floatArrayOf(0f, 63f / 32767f, 64f / 32767f, -64f / 32767f, 0f), 16000)
        assertEquals(2, result.leadingSilenceFrames)
        assertEquals(1, result.trailingSilenceFrames)
    }

    @Test fun emptyAndSilentOutputKeepFiniteLevelsAndFullSilenceBounds() {
        val empty = SynthesizedSpeechPcm.fromModel(floatArrayOf(), 16000)
        assertEquals(0, empty.samples.size)
        assertEquals(0.0, empty.rms, 0.0)
        assertEquals(0f, empty.peak, 0f)
        assertEquals(0, empty.leadingSilenceFrames)
        assertEquals(0, empty.trailingSilenceFrames)
        val silent = SynthesizedSpeechPcm.fromModel(FloatArray(320), 16000)
        assertEquals(320, silent.leadingSilenceFrames)
        assertEquals(320, silent.trailingSilenceFrames)
    }

    @Test fun naturalSentencePauseAndPassageEdgesKeepEveryFrame() {
        for (rate in listOf(16000, 22050, 24000)) {
            val leading = rate * 40 / 1000
            val speech = rate / 5
            val sentencePause = rate * 278 / 1000
            val trailing = rate * 80 / 1000
            val model = FloatArray(leading) + FloatArray(speech) { .25f } +
                FloatArray(sentencePause) + FloatArray(speech) { -.25f } + FloatArray(trailing)
            val result = SynthesizedSpeechPcm.fromModel(model, rate)
            val expected = ShortArray(leading) + ShortArray(speech) { 8191 } +
                ShortArray(sentencePause) + ShortArray(speech) { -8191 } + ShortArray(trailing)
            assertArrayEquals("No trim, inserted filler, or compressed sentence pause at $rate Hz", expected, result.samples)
            assertEquals(model.size, result.samples.size)
            assertEquals(leading, result.leadingSilenceFrames)
            assertEquals(trailing, result.trailingSilenceFrames)
        }
    }

    @Test fun invalidModelOutputFailsBeforeAudioCanBeQueued() {
        for (rate in listOf(0, -16000)) {
            val failure = assertThrows(IllegalStateException::class.java) {
                SynthesizedSpeechPcm.fromModel(floatArrayOf(0f), rate)
            }
            assertEquals("Voice model returned an invalid sample rate.", failure.message)
        }
        for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val failure = assertThrows(IllegalStateException::class.java) {
                SynthesizedSpeechPcm.fromModel(floatArrayOf(0f, sample), 16000)
            }
            assertEquals("Voice model returned non-finite PCM.", failure.message)
        }
    }
}
