package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CaptureAcousticMetricsTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }
    @Test fun clippingAndNearSilenceAreCountedWithCorrectPcm16Signs() {
        val signal = Pcm16Signal.measure(pcm(-32768, 32767, -1, 0, 1, 400))
        assertEquals(2, signal.clippedSampleCount)
        assertEquals(3, signal.nearSilentSampleCount)
        assertEquals(32768, signal.peak)
    }

    @Test fun energyIsWeightedBySamplesAndContrastIsNotClaimedAsSnr() {
        val stats = CaptureAcousticAccumulator()
        stats.record(Pcm16Signal.measure(pcm(100, -100)), SpeechDecision(false, .01f), 100.0)
        stats.record(Pcm16Signal.measure(pcm(1000, -1000, 1000, -1000)), SpeechDecision(true, .99f), 100.0)
        val result = stats.snapshot()
        assertEquals(6L, result.sampleCount)
        assertEquals(4L, result.speechSampleCount)
        assertEquals(20.0, result.speechToNonSpeechEnergyDb!!, .00001)
        assertEquals(100.0, result.noiseFloorRms, .00001)
        assertEquals(-32.04825067218609, result.rmsDbfs!!, .00001)
    }

    @Test fun absentSpeechAndDigitalSilenceStayMissingInsteadOfInfinity() {
        val stats = CaptureAcousticAccumulator()
        stats.record(Pcm16Signal.measure(pcm(0, 0, 0)), SpeechDecision(false, .01f), 0.0)
        val result = stats.snapshot()
        assertEquals(3L, result.nearSilentSampleCount)
        assertNull(result.rmsDbfs)
        assertNull(result.peakDbfs)
        assertNull(result.speechRmsDbfs)
        assertNull(result.speechToNonSpeechEnergyDb)
    }
}
