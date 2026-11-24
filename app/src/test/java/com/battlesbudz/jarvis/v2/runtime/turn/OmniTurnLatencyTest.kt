package com.battlesbudz.jarvis.v2.runtime.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmniTurnLatencyTest {
    @Test fun mapsOmniGraphTimingsToStandardLatency() {
        val latency = OmniTurnLatency.build(
            endpointAtMs = 1234L,
            endpointToFirstSoundMs = 567L,
            graphTimingsMs = mapOf(
                "audio_encode_ms" to 100L,
                "text_prefill_ms" to 200L,
                "speech_generate_ms" to 4000L,
            ),
            totalMs = 5000L,
            answer = OmniCallAnswer("spoken", speechFrames = 8),
        )

        assertEquals("omni-1234", latency.id)
        assertEquals(5000L, latency.totalMs)
        assertNull(latency.firstVisibleTextMs)
        assertEquals(0L, latency.modelLoadMs)
        assertEquals(0L, latency.lookupMs)
        assertEquals(OmniTurnLatency.ENGINE_LABEL, latency.voice)
        assertEquals(567L, latency.speechEndToReplyMs)
        assertNull(latency.textToPcmMs)
        assertNull(latency.textToPlaybackMs)
        assertNull(latency.supplyGapMs)
        val pass = latency.passes.single()
        assertEquals("omni_speech", pass.stage)
        assertEquals(300L, pass.firstTokenMs)
        assertEquals(4000L, pass.totalMs)
        assertEquals(8, pass.estimatedTokens)
        assertEquals(2.0, pass.estimatedTokensPerSecond!!, 0.0)
        assertEquals(false, pass.prepared)
    }

    @Test fun missingTimingsRemainUnknownAndNonPositiveGenerationHasNoSpeed() {
        val missing = OmniTurnLatency.build(
            endpointAtMs = 1L,
            endpointToFirstSoundMs = null,
            graphTimingsMs = emptyMap(),
            totalMs = -9L,
            answer = OmniCallAnswer("spoken", speechFrames = 3),
        )
        assertEquals(0L, missing.totalMs)
        assertNull(missing.speechEndToReplyMs)
        assertNull(missing.passes.single().firstTokenMs)
        assertNull(missing.passes.single().totalMs)
        assertNull(missing.passes.single().estimatedTokensPerSecond)

        val zeroGeneration = OmniTurnLatency.build(
            endpointAtMs = 2L,
            endpointToFirstSoundMs = 0L,
            graphTimingsMs = mapOf("speech_generate_ms" to 0L),
            totalMs = 0L,
            answer = OmniCallAnswer("spoken", speechFrames = 3),
        )
        assertEquals(0L, zeroGeneration.passes.single().totalMs)
        assertNull(zeroGeneration.passes.single().estimatedTokensPerSecond)
    }

    @Test fun firstTokenRequiresBothPrefillStages() {
        val latency = OmniTurnLatency.build(
            endpointAtMs = 3L,
            endpointToFirstSoundMs = null,
            graphTimingsMs = mapOf("audio_encode_ms" to 20L),
            totalMs = 20L,
            answer = OmniCallAnswer("spoken"),
        )
        assertNull(latency.passes.single().firstTokenMs)
    }
}
