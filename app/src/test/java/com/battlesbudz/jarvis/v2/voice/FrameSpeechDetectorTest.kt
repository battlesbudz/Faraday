package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FrameSpeechDetectorTest {
    @Test fun retainedWindowCountsOnlyConfirmedSpeechNotItsSilence() {
        var frame = 0
        val detector = FrameSpeechDetector({ if (frame++ < 3) .9f else .01f })
        val result = detector.accept(pcm(1200, 16000))
        assertTrue(result.isSpeech)
        assertEquals(1536, result.speechSamples)
        assertEquals(1536, result.strongSpeechSamples)
        val evidence = CandidateSpeechEvidence()
        evidence.observe(32000, result)
        assertEquals(96L, evidence.snapshot().strongMs)
        assertFalse(CandidateConfirmation().observe("Thank you", 1000, 32000, evidence.snapshot()))
    }
    @Test fun onsetFramesAreCreditedOnlyOnceAcrossChunks() {
        val detector = FrameSpeechDetector({ .9f })
        var samples = 0
        repeat(5) { samples += detector.accept(pcm(1200, 512)).speechSamples!! }
        assertEquals(5 * 512, samples)
    }
    @Test fun requiresThreeSpeechFramesAndRejectsLoudNonSpeech() {
        var probability = 0.99f
        val detector = FrameSpeechDetector({ probability })
        val loud = pcm(4000, 512)
        assertFalse(detector.accept(loud).isSpeech)
        assertFalse(detector.accept(loud).isSpeech)
        assertTrue(detector.accept(loud).isSpeech)
        probability = 0.01f
        repeat(40) { assertFalse(detector.accept(loud).isSpeech) }
        probability = 0.99f
        assertFalse(detector.accept(loud).isSpeech)
    }

    @Test fun handlesOddChunkBoundariesAndNormalizesSignedPcm() {
        val received = mutableListOf<FloatArray>()
        val detector = FrameSpeechDetector({ received.add(it.copyOf()); 0.9f })
        val bytes = pcm(-32768, 1536)
        var speech = false
        bytes.asList().chunked(101).forEach {
            speech = detector.accept(it.toByteArray()).isSpeech || speech
        }
        assertTrue(speech)
        assertEquals(3, received.size)
        assertTrue(received.all { it.size == 512 && it.all { sample -> sample == -1f } })
    }

    @Test fun isolatedSpikesDoNotConfirmSpeech() {
        var index = 0
        val detector = FrameSpeechDetector({ if (index++ % 2 == 0) 0.99f else 0.01f })
        repeat(100) { assertFalse(detector.accept(pcm(2000, 512)).isSpeech) }
    }

    @Test fun releaseIsIdempotentAndCannotInferAfterRelease() {
        var releases = 0
        val detector = FrameSpeechDetector({ 0f }, { releases++ })
        detector.close()
        detector.close()
        assertEquals(1, releases)
        assertTrue(runCatching { detector.accept(pcm(0, 512)) }.exceptionOrNull() is IllegalStateException)
    }

    private fun pcm(sample: Int, count: Int) = ByteArray(count * 2) {
        if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte()
    }
}
