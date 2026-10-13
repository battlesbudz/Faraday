package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingAudioBufferTest {
    @Test
    fun wavEncoderWritesPcm16MonoHeader() {
        val wav = WavEncoder.pcm16Mono(byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(44 + 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test
    fun retainsOnlyConfiguredRollingWindow() {
        val buffer = RollingAudioBuffer(AudioFormat(sampleRateHz = 1, channelCount = 1), maxDurationMs = 2_000)
        buffer.append(byteArrayOf(1, 2, 3))
        buffer.append(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), buffer.snapshot())
        assertEquals(4L, buffer.sizeBytes())
    }

    @Test
    fun clearDiscardsTemporaryAudio() {
        val buffer = RollingAudioBuffer()
        buffer.append(byteArrayOf(1, 2))
        buffer.clear()
        assertEquals(0L, buffer.sizeBytes())
        assertArrayEquals(byteArrayOf(), buffer.snapshot())
    }
}
