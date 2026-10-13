package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class Pcm16SignalTest {
    @Test fun negativeBackgroundNoiseIsSilence() {
        val signal = Pcm16Signal.measure(pcm(-1, -100, 100, 0))
        assertEquals(100, signal.peak)
        assertTrue(signal.rms < 100)
        assertEquals(0.0, signal.activeSampleRatio, 0.0)
    }

    @Test fun fullScaleSamplesStayInPcm16Range() {
        val signal = Pcm16Signal.measure(pcm(-32768, 32767))
        assertEquals(32768, signal.peak)
        assertTrue(signal.rms <= 32768)
        assertEquals(1.0, signal.activeSampleRatio, 0.0)
    }

    @Test fun polarityDoesNotChangeMeasuredLoudness() {
        assertEquals(Pcm16Signal.measure(pcm(1, 100, 2000)),
            Pcm16Signal.measure(pcm(-1, -100, -2000)))
    }

    @Test fun wavHeaderIsExcludedFromMeasurements() {
        val wav = WavEncoder.pcm16Mono(pcm(-1, 1), 16000)
        val signal = Pcm16Signal.measure(wav, 44)
        assertEquals(2, signal.sampleCount)
        assertEquals(1, signal.peak)
    }

    private fun pcm(vararg samples: Int): ByteArray = samples.flatMap {
        listOf(it.toByte(), (it shr 8).toByte())
    }.toByteArray()
}
