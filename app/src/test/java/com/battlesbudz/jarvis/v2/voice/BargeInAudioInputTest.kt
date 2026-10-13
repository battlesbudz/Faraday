package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class BargeInAudioInputTest {
    @Test fun lazyInterruptionRecognizerKeepsWhispersShortSilenceTarget() {
        var loaded = false
        var observed = false
        val lazy = LazyStreamingTranscriber {
            loaded = true
            object : StreamingTranscriber {
                override val noTextSilenceMs = 900L
                override fun observeSpeech(speech: Boolean) { observed = speech }
                override fun accept(pcm: ByteArray) = ""
                override fun finish() = ""
                override fun close() = Unit
            }
        }
        assertEquals(900L, lazy.noTextSilenceMs)
        assertFalse(loaded)
        lazy.observeSpeech(true)
        assertTrue(loaded && observed)
        assertEquals(900L, lazy.noTextSilenceMs)
        lazy.close()
    }

    @Test fun echoIsDiscardedAndRecognizerReleasesBeforeFinalTurnOwnerStarts() = runBlocking {
        var clock = 0L
        var openModels = 0
        var maxModels = 0
        var confirmed = 0
        var detectorClosed = false
        fun recognizer(probe: Boolean) = object : StreamingTranscriber {
            init { openModels++; maxModels = maxOf(maxModels, openModels) }
            override fun accept(pcm: ByteArray) = if (probe && clock < 300) "The sky is blue" else "open youtube"
            override fun finish() = "open youtube"
            override fun close() { openModels-- }
        }
        val finalAsr = LazyStreamingTranscriber { recognizer(false) }
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                for (at in listOf(0L, 100L, 300L, 600L, 700L)) {
                    clock = at; emit(byteArrayOf((at / 100).toByte(), 0))
                }
            }
        }
        val gated = BargeInAudioInput(input,
            createDetector = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(true, 0.9f)
                override fun close() { detectorClosed = true }
            } }, playing = { true }, createTranscriber = { recognizer(true) },
            spokenText = { "The sky is blue" }, onConfirmed = {
                assertEquals(0, openModels); confirmed++
            }, nowMs = { clock })
        val delivered = mutableListOf<ByteArray>()
        gated.chunks().collect { delivered += it; finalAsr.accept(it) }
        finalAsr.close()
        assertEquals(1, confirmed)
        assertEquals(1, maxModels)
        assertEquals(0, openModels)
        assertTrue(detectorClosed)
        assertEquals(2, delivered.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 3, 0, 6, 0), delivered[0])
        assertArrayEquals(byteArrayOf(7, 0), delivered[1])
    }
    @Test fun noiseDuringPreparationIsRecognizedButCannotCancelSynthesis() = runBlocking {
        var clock = 0L
        var accepted = 0
        var closed = 0
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                repeat(30) { clock = it * 100L; emit(byteArrayOf(1, 0)) }
            }
        }
        val gated = BargeInAudioInput(input,
            createDetector = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(true, 0.99f)
                override fun close() {}
            } }, playing = { false }, createTranscriber = { object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { accepted++; return "" }
                override fun finish() = ""
                override fun close() { closed++ }
            } }, spokenText = { "Battery is at 58 percent" },
            onConfirmed = { fail("Noise cancelled unfinished reply") }, nowMs = { clock })
        gated.chunks().collect { fail("Noise was forwarded as a new turn") }
        assertEquals(30, accepted)
        assertEquals(1, closed)
    }

}
