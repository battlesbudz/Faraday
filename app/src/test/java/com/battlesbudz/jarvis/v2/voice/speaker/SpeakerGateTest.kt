package com.battlesbudz.jarvis.v2.voice.speaker

import com.battlesbudz.jarvis.v2.voice.WavEncoder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SpeakerGateTest {
    @Test fun decoderAcceptsCanonicalWavAndRawPcm16LeAndRejectsMalformedInput() {
        val pcm = byteArrayOf(0, 64, 0, -64)
        val wav = WavEncoder.pcm16Mono(pcm)
        val fromWav = requireNotNull(WavPcm.decode(wav))
        val fromRaw = requireNotNull(WavPcm.decode(pcm))
        assertEquals(16_000, fromWav.second)
        assertEquals(16_000, fromRaw.second)
        assertArrayEquals(fromWav.first, fromRaw.first, 0f)
        assertNull(WavPcm.decode(byteArrayOf(1)))
        assertNull(WavPcm.decode(byteArrayOf()))
    }
    @Test fun ownerAndStrangerAndMissingEmbedder() {
        val dir = Files.createTempDirectory("speaker-gate").toFile(); val store = SpeakerProfileStore(dir)
        store.save(listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 1)
        val audio = WavEncoder.pcm16Mono(ByteArray(16_000 * 2) { if (it % 2 == 0) 20 else 0 })
        val owner = SpeakerGate(store, SpeakerEmbedder { _, _ -> floatArrayOf(1f, 0f) }).evaluate(audio)
        assertEquals(SpeakerState.MATCH, owner.state); assertTrue(owner.actionEligible)
        val stranger = SpeakerGate(store, SpeakerEmbedder { _, _ -> floatArrayOf(0f, 1f) }).evaluate(audio)
        assertEquals(SpeakerState.NO_MATCH, stranger.state)
        assertEquals(SpeakerState.UNVERIFIABLE, SpeakerGate(store, null).evaluate(audio).state)
        dir.deleteRecursively()
    }
}
