package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class QuietSpeechGainTest {
    private fun pcm(vararg values: Int) = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { i, v -> bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
    }
    private fun value(bytes: ByteArray, index: Int) =
        ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort().toInt()
    @Test fun quietSpeechIsBoostedWithoutChangingSignOrInput() {
        val gain = QuietSpeechGain()
        repeat(10) { gain.apply(pcm(40, -40)) }
        val source = pcm(120, -120, 200, -200)
        val original = source.copyOf()
        val boosted = gain.apply(source)
        assertTrue(value(boosted, 0) > 120)
        assertTrue(value(boosted, 1) < -120)
        assertArrayEquals(original, source)
        assertTrue(gain.currentGain <= 8)
    }
    @Test fun digitalSilenceStaysSilent() {
        val gain = QuietSpeechGain()
        repeat(30) { assertArrayEquals(ByteArray(3200), gain.apply(ByteArray(3200))) }
        assertEquals(1.0, gain.currentGain, 0.001)
    }
    @Test fun suddenLoudSpeechImmediatelyDropsGainAndCannotOverflow() {
        val gain = QuietSpeechGain()
        repeat(10) { gain.apply(pcm(30, -30)) }
        repeat(5) { gain.apply(pcm(100, -100)) }
        val output = gain.apply(pcm(30000, -32768))
        assertEquals(30000, value(output, 0))
        assertEquals(-32768, value(output, 1))
        assertEquals(1.0, gain.currentGain, 0.001)
    }
    @Test fun interruptionGainIsCappedIndependentlyFromNormalListening() {
        val gain = QuietSpeechGain(maxGain = 3.0)
        repeat(20) { gain.apply(pcm(20, -20)) }
        repeat(8) { gain.apply(pcm(80, -80)) }
        assertTrue(gain.currentGain <= 3.0)
        assertEquals(3.0, gain.currentGain, 0.01)
        assertArrayEquals(pcm(30000, -32768), gain.apply(pcm(30000, -32768)))
    }

    @Test fun steadyBackgroundIsNotMagnified() {
        val gain = QuietSpeechGain()
        val background = pcm(80, -80)
        repeat(100) { assertArrayEquals(background, gain.apply(background)) }
        assertEquals(1.0, gain.currentGain, 0.001)
    }

}
