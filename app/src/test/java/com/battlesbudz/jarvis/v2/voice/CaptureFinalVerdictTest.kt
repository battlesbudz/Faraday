package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real capture/session/bridge, injected model probabilities and recognizer results only. */
class CaptureFinalVerdictTest {
    private fun speech(seed: Int) = ByteArray(3072) { i ->
        val sample = 2000 + seed * 23 + (i / 2) % 37
        if (i % 2 == 0) sample.toByte() else (sample shr 8).toByte()
    }
    private class Fixture(scope: CoroutineScope, val useAsr: Boolean = true, val finalText: String = "old answer echo",
        val shadowObserver: CaptureShadowObserver? = null) {
        val fence = CaptionPublicationFence()
        val rig = CaptureFirstTestRig(scope, fence::rawOffered, fence::transferToCapture)
        val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = AtomicLong(1000)
        val enteredFinish = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val finalizations = AtomicInteger()
        val generations = AtomicInteger()
        val closes = AtomicInteger()
        val activeStreams = AtomicInteger()
        val maxActiveStreams = AtomicInteger()
        val rejections = AtomicInteger()
        val retainedAfterReset = CompletableDeferred<Unit>()
        val secondAsr = ByteArrayOutputStream()
        var parkFirstFinish = false
        var parkFirstClose = false
        var switchAfterFirstFinal = false
        lateinit var capture: AudioTurnCapture
        val admission = CaptionInputAdmission({ useAsr }, onAccepted = { fence.revoke { } },
            onPending = fence::candidatePending, onRejected = fence::candidateRejected)
        suspend fun start() {
            rig.start()
            val input = rig.bridge.beginFollowup(1000, retainOverlap = false)
            val tracked = object : AudioInput by input {
                override fun acknowledgeConsumed(sequence: Long) {
                    fence.rawConsumed(sequence); input.acknowledgeConsumed(sequence)
                }
            }
            capture = AudioTurnCapture(tracked, captureScope,
                createDetector = { FrameSpeechDetector(computeProbability = { frame -> if (frame.any { it != 0f }) .99f else 0f }) },
                nowMs = now::get, nowNs = { now.get() * 1_000_000 },
                createTranscriber = if (!useAsr) null else { {
                    val generation = generations.incrementAndGet()
                    val active = activeStreams.incrementAndGet()
                    maxActiveStreams.updateAndGet { maxOf(it, active) }
                    object : StreamingTranscriber {
                        override fun accept(pcm: ByteArray): String {
                            if (generation > 1) synchronized(secondAsr) { secondAsr.write(pcm) }
                            return if (generation == 1) finalText else "new request"
                        }
                        override fun finish(): String {
                            val finalization = finalizations.incrementAndGet()
                            if (generation == 1 && parkFirstFinish) {
                                enteredFinish.countDown(); check(releaseFinish.await(3, TimeUnit.SECONDS))
                            }
                            return if (generation == 1 && !(switchAfterFirstFinal && finalization > 1)) finalText else "new request"
                        }
                        override fun close() {
                            closes.incrementAndGet()
                            activeStreams.decrementAndGet()
                            if (generation == 1 && parkFirstClose) {
                                enteredClose.countDown(); check(releaseClose.await(3, TimeUnit.SECONDS))
                            }
                        }
                    }
                } },
                captionOnly = true, allowAudioOnlyTurns = true, guardFollowupSpeech = true, trailingSilenceMs = 96,
                onAcousticDecision = { _, raw, admitted, _ -> fence.rawClassified(input.lastChunkSequence, raw, admitted) },
                retainedPcmObserver = object : RetainedPcmObserver {
                    override fun onPcm(retainedPcm16: ByteArray) {
                        admission.onPcm(retainedPcm16)
                        if (rejections.get() > 0) retainedAfterReset.complete(Unit)
                    }
                    override fun onCandidateDiscarded() { admission.onCandidateDiscarded() }
                    override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) { admission.onCaptureInvalidated(reason) }
                },
                shadowObserver = shadowObserver,
                rejectFinalCandidate = { text, _ ->
                    (text == "old answer echo").also { if (it) rejections.incrementAndGet() }
                })
            capture.start(initialSilenceTimeoutMs = null)
        }
        suspend fun push(pcm: ByteArray) { now.addAndGet(96); rig.push(pcm, now.get()) }
        suspend fun consumed(sequence: Long) = withTimeout(2000) { while (rig.acknowledged < sequence) yield() }
        suspend fun close() = withContext(NonCancellable) {
            releaseFinish.countDown(); releaseClose.countDown()
            if (::capture.isInitialized) capture.stop()
            captureScope.cancel(); rig.close()
        }
    }

    @Test fun finalizationThenLateRawThenEchoRejectionKeepsSameReaderAndExactLateBytes() = runBlocking {
        val f = Fixture(this); f.parkFirstFinish = true; f.parkFirstClose = true
        try {
            f.start(); repeat(3) { f.push(speech(1)) }; f.push(ByteArray(3072)); f.consumed(4)
            assertTrue(f.enteredFinish.await(2, TimeUnit.SECONDS))
            f.push(ByteArray(3072)) // A late raw frame arrives during finalization.
            f.releaseFinish.countDown()
            assertTrue(f.enteredClose.await(2, TimeUnit.SECONDS))
            val late = speech(83)
            f.push(late) // New speech arrives while the rejected old ASR stream closes.
            assertEquals(1, f.generations.get())
            val oldFarewell = async(start = CoroutineStart.UNDISPATCHED) {
                f.fence.publishWhenResolved({ true }) { claim -> f.fence.commit(claim, farewell = true) }
            }
            assertFalse(oldFarewell.isCompleted)
            f.releaseClose.countDown()
            withTimeout(2000) { f.retainedAfterReset.await() }
            assertEquals(1, f.rejections.get())
            assertEquals(2, f.generations.get())
            assertEquals(1, f.closes.get())
            assertEquals(1, f.rig.hardware.starts); assertEquals(0, f.rig.hardware.stops)
            withTimeout(2000) {
                while (synchronized(f.secondAsr) { f.secondAsr.size() < late.size * 6 }) yield()
            }
            val accepted = synchronized(f.secondAsr) { f.secondAsr.toByteArray() }
            assertArrayEquals(late, accepted.takeLast(late.size).toByteArray())
            assertArrayEquals(speech(1) + speech(1) + speech(1) + ByteArray(6144) + late, accepted)
            assertFalse(oldFarewell.isCompleted)
            f.push(speech(84)); f.push(speech(85)); f.push(ByteArray(3072));
            assertTrue(withTimeout(2000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            f.admission.onFinalCandidate(true, f.capture.finalTranscript, true, false,
                nativeAudioAccepted = GemmaAudioInputPolicy.retainedAudioIssue(f.capture.recognitionIssue, true, f.capture.audioIsComplete, wav.size) == null)
            assertFalse(withTimeout(2000) { oldFarewell.await() })
        } finally { f.close() }
    }

    @Test fun echoRejectedWithNoFurtherInputKeepsBackstopAndRecorderAliveUntilCancellation() = runBlocking {
        val f = Fixture(this)
        try {
            f.start(); repeat(3) { f.push(speech(2)) }; f.push(ByteArray(3072)); f.consumed(4)
            withTimeout(2000) { while (f.rejections.get() == 0) yield() }
            val completed = async { f.capture.awaitTurnCompletion() }
            yield(); assertFalse(completed.isCompleted)
            assertTrue(withTimeout(2000) { f.fence.publishWhenResolved({ true }) { claim -> f.fence.commit(claim, true) } })
            assertEquals(0, f.rig.hardware.stops)
            assertEquals("", f.capture.finalTranscript)
            assertEquals("pending", f.capture.finalAsrStatus)
            completed.cancelAndJoin()
        } finally { f.close() }
        assertEquals(1, f.rig.hardware.stops)
    }

    @Test fun missingFinalTextAndAsrDisabledStillAcceptCompleteNativeAudio() = runBlocking {
        for (withAsr in listOf(false, true)) {
            val f = Fixture(this, useAsr = withAsr, finalText = "")
            try {
                f.start(); repeat(3) { f.push(speech(3)) }; f.push(ByteArray(3072))
                assertTrue(withTimeout(2000) { f.capture.awaitTurnCompletion() })
                val wav = f.capture.stop()
                assertTrue(wav.size > 44); assertTrue(f.capture.audioIsComplete)
                assertEquals(0, f.rejections.get())
                assertEquals(if (withAsr) 1 else 0, f.generations.get())
                f.admission.onFinalCandidate(true, "", false, false, nativeAudioAccepted = true)
                assertFalse(f.fence.publishWhenResolved({ true }) { claim -> f.fence.commit(claim, true) })
            } finally { f.close() }
        }
    }

    @Test fun cancellationWhileFinalizationIsParkedJoinsExactDecoderWithoutStartingReplacement() = runBlocking {
        val f = Fixture(this); f.parkFirstFinish = true
        try {
            f.start(); repeat(3) { f.push(speech(4)) }; f.push(ByteArray(3072)); f.consumed(4)
            assertTrue(f.enteredFinish.await(2, TimeUnit.SECONDS))
            f.captureScope.cancel()
            val stopping = async(Dispatchers.Default) { f.capture.stop() }
            yield(); assertFalse(stopping.isCompleted)
            assertEquals(1, f.generations.get())
            f.releaseFinish.countDown()
            withTimeout(2000) { stopping.await() }
            assertEquals(1, f.generations.get())
            assertEquals(1, f.closes.get())
            assertEquals(0, f.rejections.get())
        } finally { f.close() }
        assertEquals(1, f.rig.hardware.stops)
    }
    @Test fun newSpeechDuringFinalDecodeDefersOldEchoVerdictAndKeepsEntireSameRequest() = runBlocking {
        val f = Fixture(this); f.parkFirstFinish = true; f.switchAfterFirstFinal = true
        try {
            f.start()
            val first = speech(6)
            repeat(3) { f.push(first) }; f.push(ByteArray(3072)); f.consumed(4)
            assertTrue(f.enteredFinish.await(2, TimeUnit.SECONDS))
            val later = speech(97)
            repeat(3) { f.push(later) }; f.push(ByteArray(3072))
            f.releaseFinish.countDown()
            assertTrue(withTimeout(2000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertEquals(0, f.rejections.get())
            // Existing segmentation replaces the sealed stream sequentially, under one model owner.
            assertEquals(2, f.generations.get()); assertEquals(1, f.maxActiveStreams.get())
            assertArrayEquals(WavEncoder.pcm16Mono(first + first + first + ByteArray(3072) +
                later + later + later + ByteArray(3072), 16000), wav)
            f.admission.onFinalCandidate(true, f.capture.finalTranscript, true, false, nativeAudioAccepted = true)
            assertFalse(f.fence.publishWhenResolved({ true }) { claim -> f.fence.commit(claim, true) })
        } finally { f.close() }
    }

    @Test fun callerTimeoutKeepsNativeJoinUntilReleaseAndCannotPublishRejectedEvidence() = runBlocking {
        val f = Fixture(this); f.parkFirstFinish = true
        try {
            f.start(); repeat(3) { f.push(speech(8)) }; f.push(ByteArray(3072)); f.consumed(4)
            assertTrue(f.enteredFinish.await(2, TimeUnit.SECONDS))
            val timedOut = CompletableDeferred<Unit>()
            val owner = async(Dispatchers.Default) {
                try { withTimeout(25) { f.capture.awaitTurnCompletion() }; false }
                catch (_: TimeoutCancellationException) { timedOut.complete(Unit); true }
                finally { f.captureScope.cancel(); f.capture.stop() }
            }
            withTimeout(2000) { timedOut.await() }
            withTimeout(2000) {
                while (f.captureScope.coroutineContext[Job]?.children?.none { it.isCancelled } == true) yield()
            }
            assertFalse(owner.isCompleted)
            f.releaseFinish.countDown()
            assertTrue(withTimeout(2000) { owner.await() })
            assertEquals(1, f.generations.get()); assertEquals(1, f.closes.get())
            assertEquals(0, f.rejections.get())
        } finally { f.close() }
    }

    @Test fun echoResetKeepsRealShadowRevokedWhileSameReaderAcceptsLaterNativeAudio() = runBlocking {
        val calls = AtomicInteger()
        val config = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val shadow = com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnCallOwner {
            com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnShadow({
                object : com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnBackend {
                    override fun infer(samples: FloatArray, requestId: Long, cancelled: java.util.concurrent.atomic.AtomicBoolean): com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnInference {
                        calls.incrementAndGet(); return com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnInference(.5f, 1, 2)
                    }
                    override fun cancel(requestId: Long) {}
                    override fun close() {}
                }
            }, true)
        }
        val observer = shadow.beginCapture("call", "next", 0, true, java.io.File("fake"),
            object : com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnTelemetry {
                override fun metric(name: String, value: Number?) {}
                override fun configuration(name: String, value: String) { config[name] = value }
                override fun event(message: String) {}
            }, { true }, { null })!!
        val f = Fixture(this, shadowObserver = observer)
        try {
            f.start(); repeat(3) { f.push(speech(2)) }; f.push(ByteArray(3072)); f.consumed(4)
            withTimeout(2000) { while (f.rejections.get() == 0 || f.generations.get() < 2) yield() }
            assertEquals("endpoint_finalization", config["smart_turn_revoked_for_priority"])
            assertEquals("candidate_discarded", config["smart_turn_capture_invalidation"])
            repeat(6) { f.push(speech(9)) }; f.push(ByteArray(3072))
            assertTrue(withTimeout(2000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertTrue(wav.size > 44); assertTrue(f.capture.audioIsComplete)
            assertEquals("new request", f.capture.finalTranscript)
            assertEquals(0, calls.get())
            assertEquals(1, f.rig.hardware.starts); assertEquals(1, f.maxActiveStreams.get())
        } finally { f.close(); shadow.close() }
    }

}
