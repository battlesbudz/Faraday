package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AsrRecognitionWorkMetricsTest {
    @Test fun asynchronousWhisperPartialWorkSurvivesFinalResultReuse() {
        val clock = AtomicLong(0)
        val decoded = CountDownLatch(1)
        val whisper = AsyncWhisperSession({ clock.addAndGet(80_000_000); "Hello" }, {},
            log = { if (it.startsWith("whisper_partial")) decoded.countDown() }, workClockNs = clock::get)
        try {
            whisper.observeSpeech(true); whisper.accept(ByteArray(48_000))
            assertTrue(decoded.await(3, TimeUnit.SECONDS))
            assertEquals("Hello", whisper.finish())
            val measured = whisper.recognitionWorkMetrics
            assertEquals(80L, measured.workMs)
            assertEquals(80L, measured.partialDecodeWorkMs)
            assertEquals(0L, measured.finalDecodeWorkMs)
            assertEquals(1, measured.invocations)
            assertEquals(24_000L, measured.submittedAudioSamples)
            assertEquals(1_500L, measured.submittedAudioMs)
            assertEquals(80.0 / 1500, measured.realtimeFactor!!, .000001)
        } finally { whisper.close() }
    }

    @Test fun finalQueueWaitDoesNotEnterDecoderWorkAndOverlappingInputsHaveAnExactDenominator() = runBlocking {
        val clock = AtomicLong(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val whisper = AsyncWhisperSession({
            val call = calls.incrementAndGet()
            if (call == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            clock.addAndGet(if (call == 1) 100_000_000 else 200_000_000)
            "Hello there"
        }, {}, log = {
            // This time is outside decode, before the queued final worker begins.
            if (it.startsWith("whisper_partial")) clock.addAndGet(5_000_000_000)
        }, workClockNs = clock::get)
        try {
            whisper.observeSpeech(true); whisper.accept(ByteArray(48_000))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            whisper.accept(ByteArray(3_200), false)
            val finishing = async(Dispatchers.Default) { whisper.finish() }
            release.countDown()
            assertEquals("Hello there", withTimeout(3000) { finishing.await() })
            val measured = whisper.recognitionWorkMetrics
            assertEquals(300L, measured.workMs)
            assertEquals(100L, measured.partialDecodeWorkMs)
            assertEquals(200L, measured.finalDecodeWorkMs)
            assertEquals(2, measured.invocations)
            assertEquals((48_000L + 51_200L) / 2, measured.submittedAudioSamples)
            assertEquals(3_100L, measured.submittedAudioMs)
            assertEquals(300.0 / 3100, measured.realtimeFactor!!, .000001)
        } finally { release.countDown(); whisper.close() }
    }

    @Test fun finalAndRecoveryMeasureTheirOwnInputWithoutIdleCaptureAudio() {
        val clock = AtomicLong(0)
        val whisper = AsyncWhisperSession({ clock.addAndGet(20_000_000); "Hello" }, {}, workClockNs = clock::get)
        try {
            whisper.observeSpeech(false)
            repeat(30) { whisper.accept(ByteArray(3200), false) }
            whisper.observeSpeech(true); whisper.accept(ByteArray(3200), false)
            whisper.observeSpeech(false)
            repeat(20) { whisper.accept(ByteArray(3200), false) }
            whisper.finish(); whisper.recover(ByteArray(6400))
            val measured = whisper.recognitionWorkMetrics
            assertEquals(40L, measured.workMs)
            assertEquals(20L, measured.finalDecodeWorkMs)
            assertEquals(20L, measured.recoveryDecodeWorkMs)
            // Final decode includes the bounded onset context and complete quiet
            // ending; recovery is a separate 200 ms submission. Idle time beyond
            // the 1200 ms pre-roll still never enters the decode denominator.
            assertEquals((1200L + 100 + 2000 + 200) * 16, measured.submittedAudioSamples)
            assertEquals(3500L, measured.submittedAudioMs)
        } finally { whisper.close() }
    }

    @Test fun segmentedWorkIncludesSealedStreamsOnceAndSurvivesClose() {
        var created = 0
        val segmented = SegmentedTranscriber(create = {
            val index = ++created
            object : StreamingTranscriber {
                override val recognitionWorkMetrics = AsrRecognitionWorkMetrics("fixture", finalDecodeWorkNs = index * 50_000_000L,
                    invocations = 1, submittedAudioSamples = 16_000)
                override fun accept(pcm: ByteArray) = if (index == 1) "First" else "Second"
                override fun finish() = if (index == 1) "First" else "Second"
                override fun close() {}
            }
        })
        segmented.observeSpeech(true); segmented.accept(ByteArray(3200))
        segmented.finish(); segmented.resumeAfterEndpoint()
        segmented.observeSpeech(true); segmented.accept(ByteArray(3200)); segmented.finish()
        assertEquals(150L, segmented.recognitionWorkMetrics!!.workMs)
        assertEquals(32_000L, segmented.recognitionWorkMetrics!!.submittedAudioSamples)
        segmented.close()
        assertEquals(150L, segmented.recognitionWorkMetrics!!.workMs)
        assertEquals(2, segmented.recognitionWorkMetrics!!.invocations)
    }

    @Test fun missingInstrumentationIsUnknownAndPreventsAnRtfClaim() {
        val ledger = AsrRecognitionWorkLedger()
        assertNull(ledger.withActive(null, hasActive = true))
        ledger.retain(AsrRecognitionWorkMetrics("fixture", finalDecodeWorkNs = 50_000_000, submittedAudioSamples = 16_000))
        ledger.retain(null)
        val incomplete = ledger.withActive(null, hasActive = false)!!
        assertFalse(incomplete.complete)
        assertEquals(50L, incomplete.workMs)
        assertNull(incomplete.realtimeFactor)
        assertNull(AsrRecognitionWorkMetrics("fixture").realtimeFactor)
    }
}
