package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniOverlapAddTest {
    /**
     * Fake codec decoder with the real graph's output geometry: each codec
     * frame decodes to 6 time frames of 1,280 samples. Time frame t is
     * filled with the constant (t + 1), except in [spike] mode where only
     * time frame 5 carries a single 1f at sample 100.
     */
    private class FakeCodec(private val spike: Boolean = false) : OmniCodecDecoder {
        override fun decode(frames: List<IntArray>): FloatArray {
            val out = FloatArray(frames.size * 6 * 1280)
            if (spike) {
                out[5 * 1280 + 100] = 1f
            } else {
                for (t in 0 until frames.size * 6) {
                    java.util.Arrays.fill(out, t * 1280, (t + 1) * 1280, (t + 1).toFloat())
                }
            }
            return out
        }
        override fun reset() = Unit
    }

    @Test
    fun fullChunkPlusFinishEmitsExactlyOneChunkOfSamples() {
        val offered = mutableListOf<FloatArray>()
        val stream = OmniCodecStream(FakeCodec(), FloatArray(1280) { 1f }, OmniPcmSink { offered += it; true })

        repeat(12) { assertTrue(stream.push(IntArray(OmniSpeechTokens.CODEBOOKS) { 1 })) }
        assertTrue(stream.finish())

        // 72 time frames at a 320 hop + the 1,280 window = 24,000 samples of
        // overlap-add; the stream holds back the 480-sample trim at each end,
        // so a full 0.96 s chunk plus finish yields exactly 23,040 samples.
        assertEquals(23_040, offered.sumOf { it.size })
    }

    @Test
    fun frameSamplesAreReadAtTheirOwnStride() {
        val offered = mutableListOf<FloatArray>()
        val stream = OmniCodecStream(FakeCodec(spike = true), FloatArray(1280) { 1f }, OmniPcmSink { offered += it; true })

        repeat(12) { assertTrue(stream.push(IntArray(OmniSpeechTokens.CODEBOOKS) { 1 })) }

        // The spike sits in time frame 5 at sample 100: the overlap-add
        // places it at 5 * 320 + 100 = 1700, and the first emission starts
        // after the 480-sample leading trim, so it lands at index 1220. Four
        // frames cover that position, so the normalized value is 1/4.
        val chunk = offered.single()
        val nonzero = chunk.indices.filter { chunk[it] != 0f }
        assertEquals(listOf(1220), nonzero)
        assertEquals(0.25f, chunk[1220], 1e-6f)
    }
}
