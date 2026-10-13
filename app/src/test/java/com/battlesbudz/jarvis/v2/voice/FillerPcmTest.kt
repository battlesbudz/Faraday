package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class FillerPcmTest {
    @Test fun quietSpeechIsBoostedAndPaddedSilenceTrimmedWithoutClipping() {
        val pcm = ShortArray(12000) { if (it in 3000..8999) if (it % 2 == 0) 1000 else -1000 else 0 }
        val result = FillerPcm.prepare(SpeechAudio("Um, one second.", 24000, pcm, 20))
        assertTrue(result.pcm.size < pcm.size)
        assertTrue(FillerPcm.rms(result.pcm) > FillerPcm.rms(pcm))
        assertTrue(result.pcm.all { kotlin.math.abs(it.toInt()) <= 4000 })
        assertTrue(FillerPcm.firstSpeechFrame(result.pcm) <= 480)
    }
    @Test(expected = IllegalArgumentException::class) fun silentCacheIsRejected() {
        FillerPcm.prepare(SpeechAudio("Um.", 24000, ShortArray(24000), 0))
    }
}
