package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests production retention with recorded phonemes; ASR itself runs in the host gate. */
class RecordedPhraseCaptureTest {
    @Test fun whisperRetainsRecordedOpeningPauseAndEnding() = retainRecordedPhrase(900)
    @Test fun longSilencePolicyRetainsRecordedOpeningPauseAndEnding() = retainRecordedPhrase(3000)

    private fun retainRecordedPhrase(recognizerNoTextSilenceMs: Long) = runBlocking<Unit> {
        val recording = RecordedSpeechFixture.pcm
        val chunks = MutableSharedFlow<ByteArray>()
        var clock = 0L
        var speech = false
        val detector = object : SpeechDetector {
            // Unconfirmed speech must not train the room-noise floor from spoken phonemes.
            override fun accept(pcm: ByteArray) = SpeechDecision(speech, if (speech) .95f else .2f)
            override fun close() = Unit
        }
        val nativeInput = ByteArrayOutputStream()
        // Use the same phrase gate that the real Whisper adapters own.
        val gate = ExternalSpeechGate.completePhrase()
        var finishes = 0
        val recognizer = object : StreamingTranscriber {
            override val noTextSilenceMs = recognizerNoTextSilenceMs
            override fun observeSpeech(speech: Boolean) = gate.observe(speech)
            override fun accept(pcm: ByteArray): String {
                nativeInput.write(gate.accept(pcm))
                return ""
            }
            override fun finish(): String { finishes++; return RecordedSpeechFixture.TEXT }
            override fun close() = Unit
        }
        val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override val lastChunkCaptureTimeMs get() = clock
            override fun chunks() = chunks
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }
        val capture = AudioTurnCapture(input, this, createDetector = { detector },
            createTranscriber = { recognizer }, nowMs = { clock }, trailingSilenceMs = 1200,
            captureDispatcher = Dispatchers.Unconfined)
        val delivered = ByteArrayOutputStream()
        capture.start()
        try {
            // Confirmation starts late, then misses a 500 ms internal pause and the
            // final 735 ms. Those frames contain real first/last-word phonemes.
            for (offset in recording.indices step 3200) {
                val frame = recording.copyOfRange(offset, minOf(offset + 3200, recording.size))
                clock = offset / 32L
                speech = clock in 900..9700 && clock !in 4300..4700
                delivered.write(frame)
                chunks.emit(frame)
                yield()
            }
            // Explicit finalization includes the quiet ending; it must not require
            // another speech frame to flush either native adapter's phrase gate.
            capture.finishNow()
            val tail = ByteArray(3200)
            clock += 100
            speech = false
            delivered.write(tail)
            chunks.emit(tail)
            yield()
            assertTrue(withTimeout(1000) { capture.awaitTurnCompletion() })
            val expected = delivered.toByteArray()
            assertArrayEquals("decoder dropped recorded phonemes or a pause", expected, nativeInput.toByteArray())
            assertArrayEquals("direct audio must contain the same full recording", expected, capture.stop().copyOfRange(44, 44 + expected.size))
            assertArrayEquals(recording.copyOfRange(0, 16000), nativeInput.toByteArray().copyOfRange(0, 16000))
            assertArrayEquals(recording.takeLast(16000).toByteArray(), nativeInput.toByteArray().copyOfRange(recording.size - 16000, recording.size))
            assertEquals(1, finishes)
            assertEquals(RecordedSpeechFixture.TEXT, capture.finalTranscript)
            assertTrue(capture.audioIsComplete)
        } finally { capture.stop() }
    }
}
