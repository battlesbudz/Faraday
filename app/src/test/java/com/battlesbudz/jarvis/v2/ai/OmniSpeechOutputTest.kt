package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OmniSpeechOutputTest {
    @Test fun fastSamplerCheckModeMatchesReferenceOnFixedRows() {
        val fast = OmniFastSampler(8)
        val cases = listOf(
            floatArrayOf(0f, 1f, -2f, 3f, 2f, -1f, 0.5f, 3f),
            floatArrayOf(-4f, -1f, -3f, -2f, -0.5f, -8f, -0.5f, -2f),
            floatArrayOf(2f, 2f, 2f, 1f, 0f, -1f, 2f, 0f),
        )
        val counts = intArrayOf(0, 2, 1, 0, 1, 0, 3, 0)
        val draws = doubleArrayOf(0.0, 0.137, 0.5, 0.999999)
        // Captured by applying SpeechGenerator.Sampler.sampleAt to these exact rows.
        val sampleChoices = listOf(
            intArrayOf(0, 3, 4, 7, 3, 3, 7, 7, 3, 3, 4, 7, 0, 3, 4, 7),
            intArrayOf(0, 4, 4, 7, 4, 4, 6, 6, 1, 4, 4, 6, 0, 4, 4, 7),
            intArrayOf(0, 0, 1, 7, 0, 0, 0, 0, 0, 0, 1, 6, 0, 0, 1, 7),
        )
        for ((rowIndex, row) in cases.withIndex()) {
            var fixtureIndex = 0
            for (topK in listOf(0, 1, 3, 7)) for (draw in draws) {
                val reference = OmniReferenceSampler.sampleAt(row, 0, row.size, 0.4f, topK, 1.2f, counts, null, draw)
                assertEquals(sampleChoices[rowIndex][fixtureIndex], reference)
                val chosen = fast.sample(row, 0, row.size, 0.4f, topK, 1.2f, counts, draw, checkReference = true)
                assertEquals(sampleChoices[rowIndex][fixtureIndex++], chosen)
            }
        }
        assertEquals(0L, fast.mismatches)
    }

    @Test fun maskgitOrderMapsFourFourFourRevealAndClampedDraws() {
        val order = intArrayOf(11, 2, 7, 0, 1, 3, 5, 4, 6, 8, 9, 10)
        val reveal = Array(3) { FloatArray(12) }
        var at = 0
        for ((pass, count) in intArrayOf(4, 4, 4).withIndex()) {
            for (j in at until at + count) reveal[pass][order[j]] = 1f
            at += count
        }
        assertArrayEquals(floatArrayOf(1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), reveal[0], 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f), reveal[1], 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 0f), reveal[2], 0f)
        val rng = OmniSpeechRng(7)
        val u = FloatArray(12)
        for (j in order.indices) u[order[j]] = minOf(rng.nextDouble().toFloat(), 1f - 1f / 2048)
        assertTrue(u.all { it >= 0f && it <= 1f - 1f / 2048 })
    }

    @Test fun interleavedStateMachineDoesSixTextThenTwelveFramesAndStopsAtTextEnd() = runBlocking {
        val lm = FakeSpeechLm(intArrayOf(10, 11, 12, 13, 14, 15, OmniSpeechTokens.TEXT_END, OmniSpeechTokens.IM_END))
        val depth = FakeDepth(IntArray(11) { 5 })
        val codec = FakeCodec()
        val generator = OmniSpeechGenerator(lm, depth, FakeFrame(), OmniCodecStream(codec, FloatArray(1280) { 1f }, OmniPcmSink { true }))
        val result = generator.generate(floatArrayOf(0f), maxSteps = 21, rng = OmniSpeechRng(42))
        assertEquals(listOf(10, 11, 12, 13, 14, 15, OmniSpeechTokens.TEXT_END, OmniSpeechTokens.IM_END), result.tokens.filter { it != OmniSpeechTokens.AUDIO_OUT })
        assertEquals(11, result.frames.size)
        assertEquals(12, depth.calls)
        assertEquals(1, codec.calls)
        assertTrue(result.finished)
        assertFalse(result.stopped)
    }

    @Test fun audioEosNormalizesAllCodesAndReturnsToTextMode() = runBlocking {
        val lm = FakeSpeechLm(intArrayOf(21))
        val depth = FakeDepth(intArrayOf(8, 4, OmniSpeechTokens.AUDIO_EOS, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val codec = FakeCodec()
        val generator = OmniSpeechGenerator(lm, depth, FakeFrame(), OmniCodecStream(codec, FloatArray(1280) { 1f }, OmniPcmSink { true }))
        val result = generator.generate(floatArrayOf(0f), initialMode = OmniSpeechGenerator.Mode.AUDIO, maxSteps = 3, rng = OmniSpeechRng(1))
        assertEquals(2, result.frames.size)
        assertEquals(2, result.tokens.count { it == OmniSpeechTokens.AUDIO_OUT })
        assertEquals(2, codec.resets)
        assertTrue(result.frames.all { it.none { code -> code == OmniSpeechTokens.AUDIO_EOS } })
    }

    @Test fun stopIsCheckedBeforeGeneratingAnySlot() = runBlocking {
        val depth = FakeDepth(intArrayOf(1))
        val result = OmniSpeechGenerator(FakeSpeechLm(intArrayOf(2)), depth, FakeFrame(),
            OmniCodecStream(FakeCodec(), FloatArray(1280) { 1f }, OmniPcmSink { true }))
            .generate(floatArrayOf(0f), maxSteps = 10, rng = OmniSpeechRng(0), stop = { true })
        assertTrue(result.stopped)
        assertTrue(result.tokens.isEmpty())
        assertEquals(0, depth.calls)
    }

    private class FakeSpeechLm(ids: IntArray) : OmniSpeechLm {
        private val tokens = ids
        private var delivered = 0
        private var logits = FloatArray(OmniSpeechTokens.AUDIO_OUT + 1).also { it[ids[0]] = 1f }
        override fun embedToken(token: Int): FloatArray { delivered++; return floatArrayOf(token.toFloat()) }
        override fun step(embedding: FloatArray, logits: Boolean): FloatArray {
            if (logits) this.logits = FloatArray(OmniSpeechTokens.AUDIO_OUT + 1).also { it[tokens.getOrElse(delivered) { OmniSpeechTokens.TEXT_END }] = 1f }
            return floatArrayOf(0f)
        }
        override fun textLogits(): FloatArray = logits
    }
    private class FakeDepth(private val frames: IntArray) : OmniDepthFrame {
        var calls = 0
        override fun sample(hidden: FloatArray, presence: FloatArray, reveal: Array<FloatArray>, uniforms: FloatArray): IntArray {
            val code = frames.getOrElse(calls++) { OmniSpeechTokens.AUDIO_EOS }
            return IntArray(12) { code }
        }
    }
    private class FakeFrame : OmniFrameEmbedder { override fun embed(codes: IntArray) = floatArrayOf(1f) }
    private class FakeCodec : OmniCodecDecoder {
        var calls = 0; var resets = 0
        // The real graph returns 6 time frames of 1,280 samples per codec frame.
        override fun decode(frames: List<IntArray>): FloatArray { calls++; return FloatArray(frames.size * 6 * 1280) { 1f } }
        override fun reset() { resets++ }
    }
}
