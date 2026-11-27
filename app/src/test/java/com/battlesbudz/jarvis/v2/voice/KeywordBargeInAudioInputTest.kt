package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class KeywordBargeInAudioInputTest {
    private fun decision(state: com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState, score: Float?) =
        com.battlesbudz.jarvis.v2.voice.speaker.SpeakerDecision(state, score,
            admitted = state == com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState.MATCH,
            actionEligible = false)

    private fun gatedWithSpeaker(state: com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState): Pair<KeywordBargeInAudioInput, IntArray> {
        val confirmed = intArrayOf(0)
        val gated = KeywordBargeInAudioInput(input(), createDetector = {
            object : InterruptionKeywordDetector {
                override val ready = true
                override fun accept(pcm: ByteArray): String? = if (pcm[0] == 3.toByte()) "stop" else null
                override fun close() {}
            }
        }, onConfirmed = { confirmed[0]++ })
        gated.speakerDecision = { decision(state, if (state == com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState.NO_MATCH) .321f else null) }
        return gated to confirmed
    }

    @Test fun speakerNoMatchBlocksKeywordButUnverifiableAndMatchConfirm() = runBlocking {
        val (noMatch, blocked) = gatedWithSpeaker(com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState.NO_MATCH)
        noMatch.chunks().collect {}
        assertEquals(0, blocked[0])

        val (unverifiable, allowedUnverifiable) = gatedWithSpeaker(com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState.UNVERIFIABLE)
        unverifiable.chunks().collect {}
        assertEquals(1, allowedUnverifiable[0])

        val (match, allowedMatch) = gatedWithSpeaker(com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState.MATCH)
        match.chunks().collect {}
        assertEquals(1, allowedMatch[0])
    }

    private fun input() = object : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun chunks() = flow { for (i in 1..5) emit(byteArrayOf(i.toByte(), 0)) }
    }
    @Test fun keywordStopsReplyBeforeAsrReceivesOnlyTheFollowingRequest() = runBlocking {
        var stopped = false
        var calls = 0
        var closed = 0
        var loaded = 0
        val asr = LazyStreamingTranscriber {
            assertTrue(stopped); loaded++
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray) = "request"
                override fun finish() = "request"
                override fun close() {}
            }
        }
        val gated = KeywordBargeInAudioInput(input(), createDetector = {
            object : InterruptionKeywordDetector {
                override val ready = true
                override fun accept(pcm: ByteArray): String? { calls++; return if (pcm[0] == 3.toByte()) "stop" else null }
                override fun close() { closed++ }
            }
        }, onConfirmed = { assertEquals(0, loaded); stopped = true })
        val delivered = mutableListOf<Int>()
        gated.chunks().collect { delivered += it[0].toInt(); asr.accept(it) }
        asr.close()
        assertEquals(listOf(4, 5), delivered)
        assertEquals(3, calls)
        assertEquals(1, closed)
        assertEquals(1, loaded)
    }
    @Test fun noiseWithoutKeywordNeverLoadsAsrAndDetectorClosesOnFailure() = runBlocking {
        var closed = false
        val gated = KeywordBargeInAudioInput(input(), createDetector = {
            object : InterruptionKeywordDetector {
                override val ready = true
                override fun accept(pcm: ByteArray): String? {
                    if (pcm[0] == 5.toByte()) error("capture failed")
                    return null
                }
                override fun close() { closed = true }
            }
        }, onConfirmed = { fail("No keyword") })
        try { gated.chunks().collect { fail("Background entered ASR") }; fail("Expected failure") }
        catch (expected: IllegalStateException) { assertEquals("capture failed", expected.message) }
        assertTrue(closed)
    }
    @Test fun readinessReportsActualInputWarmupAndInferenceWorkSeparately() = runBlocking {
        var clock = 100L
        var frames = 0
        val events = mutableListOf<String>()
        val gated = KeywordBargeInAudioInput(input(), createDetector = {
            clock += 20
            object : InterruptionKeywordDetector {
                override val ready get() = frames >= 3
                override fun accept(pcm: ByteArray): String? { frames++; clock += 7; return null }
                override fun close() {}
            }
        }, onConfirmed = { fail("No keyword") }, log = events::add, nowMs = { clock })
        gated.chunks().collect { fail("No confirmed request") }
        val ready = events.single { it.startsWith("barge_keyword_ready ") }
        assertTrue(ready.contains("readyMs=41"))
        assertTrue(ready.contains("loadMs=20"))
        assertTrue(ready.contains("maxWorkMs=7"))
        assertTrue(ready.contains("naturalSpeechReady=false"))
        assertTrue(events.last().contains("ready=true confirmed=false"))
    }
    @Test fun endingBeforeKeywordWarmupDoesNotClaimReady() = runBlocking {
        val events = mutableListOf<String>()
        val gated = KeywordBargeInAudioInput(input(), createDetector = {
            object : InterruptionKeywordDetector {
                override val ready = false
                override fun accept(pcm: ByteArray): String? = null
                override fun close() {}
            }
        }, onConfirmed = { fail("No keyword") }, log = events::add)
        gated.chunks().collect { fail("No confirmed request") }
        assertFalse(events.any { it.startsWith("barge_keyword_ready ") })
        assertTrue(events.last().contains("ready=false confirmed=false"))
    }
}
