package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class CaptureSpeechQueueTest {
    @Test fun queuedWeakWhisperEvidenceMustBeConsumedBeforeSealingEvenAfterNewSilence() = runBlocking {
        val input = Input()
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = when (pcm[0].toInt()) {
                1 -> SpeechDecision(true, .95f)
                2 -> SpeechDecision(false, .25f)
                else -> SpeechDecision(false, .01f)
            }
            override fun close() {}
        }
        val queue = CaptureSpeechQueue(input, detector, { 0 }, {}, dispatcher = Dispatchers.Unconfined)
        input.send(100, 1); input.send(200, 2); input.send(400, 0); input.stop()
        queue.frames().collect { /* Simulate VAD prefetch during a blocked final decoder. */ }
        assertTrue(queue.requiresEndpointDrain(100))
        assertFalse(queue.requiresEndpointDrain(400))
    }

    @Test fun classifiedSilenceDuringFinalizationDoesNotDelayACompleteTurn() = runBlocking {
        val input = Input()
        val clock = AtomicLong(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val silenceClassified = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Where was the first restaurant?"
            override fun finish(): String {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return "Where was the first restaurant?"
            }
            override fun close() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) =
                SpeechDecision(pcm[0] != 0.toByte(), if (pcm[0] != 0.toByte()) .95f else .01f)
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { asr }, nowMs = clock::get,
            trailingSilenceMs = 1200, log = events::add,
            onAcousticDecision = { _, _, _, _ -> if (input.lastChunkCaptureTimeMs == 1500L) silenceClassified.countDown() })
        try {
            capture.start()
            clock.set(100); input.send(100, 1)
            clock.set(1400); input.send(1400, 0)
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            clock.set(1500); input.send(1500, 0)
            assertTrue("VAD must classify new silence while finalization is blocked",
                withContext(Dispatchers.IO) { silenceClassified.await(3, TimeUnit.SECONDS) })
            release.countDown()
            // No further input is sent. Old logic waited indefinitely for another
            // microphone frame after seeing this already-classified silence.
            assertTrue(withTimeout(3000) { capture.awaitTurnCompletion() })
            assertFalse(events.any { it.startsWith("turn_endpoint_deferred") })
        } finally { release.countDown(); capture.stop() }
    }

    @Test fun confirmedSpeechDuringFinalizationStillReopensTheUtterance() = runBlocking {
        val input = Input()
        val clock = AtomicLong(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val continuationClassified = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var loads = 0
        val first = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Open Settings"
            override fun finish(): String {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return "Open Settings"
            }
            override fun close() {}
        }
        val continuation = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "actually battery"
            override fun finish() = "actually battery"
            override fun close() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) =
                SpeechDecision(pcm[0] != 0.toByte(), if (pcm[0] != 0.toByte()) .95f else .01f)
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { if (loads++ == 0) first else continuation },
            nowMs = clock::get, trailingSilenceMs = 1200, log = events::add,
            onAcousticDecision = { _, _, _, _ -> if (input.lastChunkCaptureTimeMs == 1500L) continuationClassified.countDown() })
        try {
            capture.start(); clock.set(100); input.send(100, 1)
            clock.set(1400); input.send(1400, 0)
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            clock.set(1500); input.send(1500, 2)
            assertTrue(withContext(Dispatchers.IO) { continuationClassified.await(3, TimeUnit.SECONDS) })
            release.countDown()
            clock.set(2800); input.send(2800, 0)
            assertTrue(withTimeout(3000) { capture.awaitTurnCompletion() })
            assertEquals("Open Settings actually battery", capture.finalTranscript)
            assertTrue(events.any { it.startsWith("turn_endpoint_invalidated") })
        } finally { release.countDown(); capture.stop() }
    }

    @Test fun cancellationWaitsForNativeWorkAndDoesNotPublishItsLateWords() = runBlocking {
        val input = Input()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray): String {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return "Late words must not become a question"
            }
            override fun finish(): String = error("Cancelled capture must not finalize")
            override fun close() { closed.set(true) }
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(true, .95f)
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { asr },
            onPartialTranscript = { text -> partials.add(text) })
        try {
            capture.start(); input.send(100, 1)
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val stop = async(start = CoroutineStart.UNDISPATCHED) { capture.stop() }
            assertFalse(stop.isCompleted)
            assertFalse(closed.get())
            release.countDown()
            withTimeout(3000) { stop.await() }
            assertTrue(closed.get())
            assertTrue(partials.isEmpty())
            assertEquals("", capture.finalTranscript)
        } finally { release.countDown(); capture.stop() }
    }

    @Test fun slowRecognitionCannotBlockVadOrLoseResumedSpeech() = runBlocking {
        val clock = AtomicLong(0)
        val input = Input()
        val enteredDecode = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val detected = CountDownLatch(4)
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val policies = Collections.synchronizedList(mutableListOf<Boolean>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        var finishes = 0
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = accept(pcm, true)
            override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
                received.add(pcm[0].toInt()); policies.add(allowPartial)
                if (received.size == 1) {
                    enteredDecode.countDown()
                    check(releaseDecode.await(5, TimeUnit.SECONDS))
                }
                return "What is two plus two?"
            }
            override fun finish(): String { finishes++; return "What is two plus two, plus three?" }
            override fun close() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray): SpeechDecision {
                detected.countDown()
                return SpeechDecision(pcm[0] != 0.toByte(), if (pcm[0] != 0.toByte()) .95f else .01f)
            }
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { asr }, nowMs = clock::get,
            trailingSilenceMs = 1200, log = events::add)
        try {
            capture.start()
            clock.set(100); input.send(100, 1)
            assertTrue(withContext(Dispatchers.IO) { enteredDecode.await(3, TimeUnit.SECONDS) })
            clock.set(2800)
            input.send(1400, 0); input.send(1500, 2); input.send(2800, 0)
            assertTrue("VAD must continue during a blocked native decode",
                withContext(Dispatchers.IO) { detected.await(3, TimeUnit.SECONDS) })
            assertEquals(0, finishes)
            releaseDecode.countDown()
            assertTrue(withTimeout(3000) { capture.awaitTurnCompletion() })
            assertEquals(listOf(1, 0, 2, 0), received)
            assertTrue(policies.any { !it })
            assertEquals("What is two plus two, plus three?", capture.finalTranscript)
            assertEquals(1500L, capture.lastSpeechAtMs)
            assertEquals(1, finishes)
            assertTrue(events.any { it.startsWith("capture_silence_detected") })
            assertTrue(events.any { it.startsWith("capture_endpoint_timing") })
        } finally { releaseDecode.countDown(); capture.stop() }
    }

    @Test fun queueOverflowFailsInsteadOfDroppingWords() = runBlocking {
        val input = Input()
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(true, .95f)
            override fun close() {}
        }
        // Deliberately tiny bound exercises failure without allocating 25 seconds.
        val queue = CaptureSpeechQueue(input, detector, { 0 }, {}, maxBytes = 3200,
            dispatcher = Dispatchers.Unconfined)
        input.send(100, 1); input.send(200, 2)
        input.stop()
        val error = runCatching { queue.frames().collect { /* intentionally not consumed */ } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("incomplete command"))
    }


    @Test fun nativeDrainUsesPcmWatermarkWhenQueuedFramesShareCaptureTimestamp() = runBlocking {
        val input = Input()
        val profiled = object : AudioInput by input {
            override val captureNoiseProfile = CaptureNoiseProfile().also { it.floorRms = 1000.0 }
        }
        var probability = .01f
        val framing = FrameSpeechDetector({ probability })
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray): SpeechDecision {
                probability = if (pcm[0] == 1.toByte()) .25f else .01f
                return framing.accept(pcm)
            }
            override fun close() { framing.close() }
        }
        val queue = CaptureSpeechQueue(profiled, detector, { 100 }, {}, dispatcher = Dispatchers.Unconfined)
        input.send(100, 2); input.send(100, 1); input.stop()
        val frames = mutableListOf<CaptureSpeechQueue.Frame>()
        queue.frames().collect { frames += it }
        assertEquals(.25f, frames.last().rawDecision.probability)
        assertEquals(0f, frames.last().decision.probability) // Room-floor gate hides the weak raw frame.
        assertFalse(queue.requiresEndpointDrain(100)) // Existing timestamp policy remains unchanged.
        assertTrue(queue.requiresNativeEndpointDrain(3200))
        assertFalse(queue.requiresNativeEndpointDrain(6400))
    }

    @Test fun genuinelyCompletedQuietCoverageResolvesQueuedPartialFramesWithoutReopening() = runBlocking {
        val input = Input()
        val queue = CaptureSpeechQueue(input, FrameSpeechDetector({ .01f }), { 100 }, {}, dispatcher = Dispatchers.Unconfined)
        repeat(8) { input.send(100, 0) }; input.stop()
        queue.frames().collect { /* Eight1600-sample chunks align with actual512-sample VAD frames. */ }
        assertFalse(queue.requiresNativeEndpointDrain(3200))
    }

    @Test fun unclassifiedQueuedTailStillDrainsWithIdenticalTimestamps() = runBlocking {
        val input = Input()
        val queue = CaptureSpeechQueue(input, FrameSpeechDetector({ .01f }), { 100 }, {}, dispatcher = Dispatchers.Unconfined)
        input.send(100, 0); input.send(100, 0); input.stop()
        queue.frames().collect { }
        assertTrue(queue.requiresNativeEndpointDrain(3200))
        assertFalse(queue.requiresNativeEndpointDrain(6400))
    }


    @Test fun pendingBytesCannotBecomeVisibleBeforeUnclassifiedDrainFlag() = runBlocking {
        val input = Input(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val parkedTimestamp = object : AudioInput by input {
            override val lastChunkCaptureTimeMs: Long get() {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return 100
            }
        }
        val queue = CaptureSpeechQueue(parkedTimestamp, FrameSpeechDetector({ .01f }), { 100 }, {})
        val collecting = async(Dispatchers.Default) { queue.frames().collect { } }
        try {
            input.send(100, 0); input.stop()
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            assertEquals(100L, queue.bufferedAudioMs)
            assertTrue(queue.requiresNativeEndpointDrain(0))
            release.countDown(); withTimeout(3000) { collecting.await() }
        } finally { release.countDown(); collecting.cancelAndJoin() }
    }

    private class Input : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        private val channel = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
        override var lastChunkCaptureTimeMs: Long? = null
        override fun chunks() = flow {
            for ((at, pcm) in channel) { lastChunkCaptureTimeMs = at; emit(pcm) }
        }
        suspend fun send(at: Long, marker: Int) { channel.send(at to ByteArray(3200) { marker.toByte() }) }
        override suspend fun start() {}
        override suspend fun stop() { channel.close() }
    }
}
