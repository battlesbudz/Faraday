package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class ExternalSpeechGateTest {
    private fun pcm(marker: Int, milliseconds: Int) = ByteArray(milliseconds * 32) { marker.toByte() }

    @Test fun silenceDoesNotReachNativeDecoderAndOnsetIsRetained() {
        val gate = ExternalSpeechGate()
        gate.observe(false)
        repeat(20) { assertEquals(0, gate.accept(ByteArray(3200)).size) }
        gate.observe(true)
        assertEquals(7680 + 3200, gate.accept(ByteArray(3200)).size)
        gate.observe(false)
        repeat(3) { assertEquals(3200, gate.accept(ByteArray(3200)).size) }
        assertEquals(640, gate.accept(ByteArray(3200)).size)
        repeat(30) { assertEquals(0, gate.accept(ByteArray(3200)).size) }
        gate.observe(true)
        assertEquals(7680 + 3200, gate.accept(ByteArray(3200)).size)
        assertEquals(3200, gate.accept(ByteArray(3200)).size)
    }

    @Test fun boundedRecordingAndQualifiedProbeDoNotNeedASecondVadGate() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, ExternalSpeechGate().accept(pcm))
        val probe = ExternalSpeechGate(); probe.observe(true)
        assertArrayEquals(pcm, probe.accept(pcm))
    }

    @Test fun shortInternalPausesAndTrailingConsonantsRemainByteExact() {
        val gate = ExternalSpeechGate()
        gate.observe(true)
        val speech = ByteArray(3200) { 1 }
        assertArrayEquals(speech, gate.accept(speech))
        gate.observe(false)
        val pause = ByteArray(6400) { 2 }
        assertArrayEquals(pause, gate.accept(pause))
        gate.observe(true)
        assertArrayEquals(speech, gate.accept(speech))
        assertEquals(12800L, gate.acceptedBytes)
    }

    @Test fun boundedUtteranceKeepsOpeningAndClosingAudioWithoutIdleBackground() {
        val gate = ExternalSpeechGate()
        gate.observe(false)
        repeat(30) { assertTrue(gate.accept(pcm(1, 100)).isEmpty()) }
        gate.observe(true)
        val opening = gate.accept(pcm(2, 100))
        assertArrayEquals(pcm(1, 240) + pcm(2, 100), opening)
        gate.observe(false)
        assertArrayEquals(pcm(3, 100), gate.accept(pcm(3, 100)))
        assertArrayEquals(pcm(3, 220), gate.accept(pcm(3, 500)))
        assertTrue(gate.accept(pcm(4, 100)).isEmpty())
        assertEquals(660L * 32, gate.acceptedBytes)
    }

    @Test fun finalOnlyProbeWithoutVadObservationKeepsOriginalSamples() {
        val gate = ExternalSpeechGate()
        val probe = pcm(17, 2000)
        assertArrayEquals(probe, gate.accept(probe))
        assertEquals(probe.size.toLong(), gate.acceptedBytes)
    }

    @Test fun whisperFinalKeepsFullOpeningAndEndingWithoutLongIdleNoise() {
        var decoded = byteArrayOf()
        val whisper = AsyncWhisperSession({ decoded = it; "Where was the first restaurant?" }, {})
        try {
            whisper.observeSpeech(false)
            repeat(30) { whisper.accept(pcm(1, 100), false) }
            whisper.observeSpeech(true)
            whisper.accept(pcm(2, 500), false)
            whisper.observeSpeech(false)
            repeat(20) { whisper.accept(pcm(3, 100), false) }
            assertEquals("Where was the first restaurant?", whisper.finish())
            assertArrayEquals(pcm(1, 1200) + pcm(2, 500) + pcm(3, 2000), decoded)
        } finally { whisper.close() }
    }

    @Test fun sharedCallPhrasePreservesInternalPauseAndQuietBoundarySoundsInOrder() {
        val gate = ExternalSpeechGate.completePhrase()
        gate.observe(false)
        assertTrue(gate.accept(pcm(1, 3000)).isEmpty())
        gate.observe(true)
        val opening = gate.accept(pcm(2, 500))
        gate.observe(false)
        val pause = gate.accept(pcm(3, 700))
        gate.observe(true)
        val ending = gate.accept(pcm(4, 500))
        gate.observe(false)
        val quietEnding = gate.accept(pcm(5, 900))
        assertArrayEquals(pcm(1, 1200) + pcm(2, 500) + pcm(3, 700) + pcm(4, 500) + pcm(5, 900),
            opening + pause + ending + quietEnding)
        gate.clear()
        gate.observe(false)
        assertTrue(gate.accept(pcm(6, 500)).isEmpty())
    }

    @Test fun qualifyingAndClearingCannotMutateOriginalPcm() {
        val gate = ExternalSpeechGate()
        val original = pcm(19, 500)
        val expected = original.copyOf()
        gate.observe(false); gate.accept(original)
        gate.observe(true); val result = gate.accept(original)
        result.fill(0)
        gate.clear()
        assertArrayEquals(expected, original)
        assertArrayEquals(expected, gate.accept(original))
    }
}
