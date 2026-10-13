package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.voice.smartturn.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual raw bridge, capture, handoff and shadow owner; only model outputs are fake. */
class CaptureFirstSmartTurnTest {
    private val speech = ByteArray(3072) { if (it % 2 == 0) 64 else 8 }
    private val silence = ByteArray(3072)
    private class Telemetry : SmartTurnTelemetry {
        val config = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val metrics = java.util.Collections.synchronizedMap(mutableMapOf<String, Number?>())
        override fun metric(name: String, value: Number?) { metrics[name] = value }
        override fun configuration(name: String, value: String) { config[name] = value }
        override fun event(message: String) {}
    }
    private suspend fun eventually(check: () -> Boolean) = withTimeout(2000) { while (!check()) yield() }

    private suspend fun scenario(releaseBeforeEndpoint: Boolean, cancelDuringCleanup: Boolean = false) = coroutineScope {
        val rig = CaptureFirstTestRig(this)
        val now = AtomicLong(1000)
        val calls = AtomicInteger(); val loads = AtomicInteger()
        val nativeBusy = AtomicBoolean(true)
        val telemetry = Telemetry()
        val shadow = SmartTurnCallOwner(clock = { now.get() * 1_000_000 }) {
            loads.incrementAndGet()
            SmartTurnShadow({ object : SmartTurnBackend {
                override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
                    check(!nativeBusy.get()) { "Shadow overlapped old native cleanup" }
                    calls.incrementAndGet()
                    return SmartTurnInference(.75f, 1, 2)
                }
                override fun cancel(requestId: Long) {}
                override fun close() {}
            } }, enabled = true, clock = { now.get() * 1_000_000 })
        }
        val accepted = CompletableDeferred<Unit>()
        val captionReady = CompletableDeferred<Unit>(); val nativeClose = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>(); val resetReady = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>(); val rawReady = CompletableDeferred<Unit>()
        val captureReady = CompletableDeferred<AudioTurnCapture>(); val sealed = CompletableDeferred<CapturedVoiceTurn>()
        val gateReady = CompletableDeferred<() -> Boolean>()
        var capture: AudioTurnCapture? = null
        try {
            rig.start()
            val task = async {
                CaptureFirstReplyHandoff.run(
                    normalPlayback = CompletableDeferred(NormalReplyPlayback("old", 1000)),
                    reply = {
                        PostAnswerCaptionContinuation.run(isCurrent = { true }, hasNextInput = { accepted.isCompleted },
                            awaitNextInput = { accepted.await() }, generate = {
                                captionReady.complete(Unit)
                                try { awaitCancellation() } finally { withContext(NonCancellable) {
                                    nativeClose.complete(Unit); releaseNative.await()
                                } }
                            }, checkedReset = { resetReady.complete(Unit); releaseReset.await(); nativeBusy.set(false) })
                        "old answer"
                    }, listen = { awaitCancellation() }, stopReply = { fail("No interruption") },
                    beginCapture = { rig.bridge.beginFollowup(1000, retainOverlap = false).also { rawReady.complete(Unit) } },
                    capture = { input, previousReplyCompleted ->
                        gateReady.complete(previousReplyCompleted)
                        val observer = shadow.beginCapture("call", "next", 0, true, File("fake"), telemetry,
                            { coroutineContext[Job]?.isActive == true },
                            { if (previousReplyCompleted()) null else "previous_reply_native_cleanup" })!!
                        val active = AudioTurnCapture(input, this,
                            createDetector = { FrameSpeechDetector({ frame -> if (frame.any { it != 0f }) .99f else .01f }) },
                            nowMs = now::get, nowNs = { now.get() * 1_000_000 },
                            captureDispatcher = Dispatchers.Unconfined,
                            createTranscriber = null, captionOnly = true, allowAudioOnlyTurns = true,
                            guardFollowupSpeech = true, trailingSilenceMs = 1200,
                            shadowObserver = observer,
                            retainedPcmObserver = object : RetainedPcmObserver {
                                override fun onPcm(retainedPcm16: ByteArray) { accepted.complete(Unit) }
                                override fun onCandidateDiscarded() { fail("Retained speech was discarded") }
                                override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) {}
                            })
                        capture = active
                        try {
                            active.start(initialSilenceTimeoutMs = null); captureReady.complete(active)
                            active.awaitTurnCompletion()
                            val wav = active.stop()
                            CapturedVoiceTurn(active.finalTranscript, wav, active.audioIsComplete, active.recognitionIssue)
                                .also { sealed.complete(it) }
                        } finally { active.stop() }
                    }, awaitTypedInput = { awaitCancellation() }, hasTypedInput = { false })
            }
            withTimeout(2000) { rawReady.await(); captionReady.await(); captureReady.await() }
            val gate = gateReady.await()
            suspend fun emit(pcm: ByteArray) {
                now.addAndGet(96); rig.push(pcm, now.get())
                val through = rig.admitted
                eventually { rig.acknowledged == through }
            }
            repeat(4) { emit(speech) }
            withTimeout(2000) { nativeClose.await() }
            repeat(3) { emit(silence) }
            assertEquals(0, calls.get()); assertFalse(gate())
            assertEquals("previous_reply_native_cleanup", telemetry.config["smart_turn_last_admission_blocker"])
            assertEquals(1, rig.hardware.starts); assertEquals(0, rig.hardware.stops)
            if (cancelDuringCleanup) {
                task.cancel(); yield()
                assertFalse(gate()); assertEquals(0, calls.get())
                releaseNative.complete(Unit); withTimeout(2000) { resetReady.await() }; releaseReset.complete(Unit)
                withTimeout(2000) { task.join() }
                assertFalse(gate()) // A cancelled reply never qualifies after its cleanup returns.
                assertEquals(0, calls.get())
                return@coroutineScope
            }
            if (releaseBeforeEndpoint) {
                // New raw speech is consumed even while native reset remains parked.
                releaseNative.complete(Unit); withTimeout(2000) { resetReady.await() }
                repeat(4) { emit(speech) }
                assertFalse(gate()); assertEquals(0, calls.get())
                releaseReset.complete(Unit); eventually { gate() }
                repeat(6) { emit(speech) }
                repeat(3) { emit(silence) }
                eventually { calls.get() == 1 }
                assertEquals(1, loads.get())
            }
            val active = captureReady.await(); active.finishNow(); emit(silence)
            val result = withTimeout(2000) { sealed.await() }
            assertTrue(result.audioIsComplete); assertNull(result.recognitionIssue)
            assertTrue(result.wav.size > 44); assertEquals("", result.transcript) // ASR-free native acceptance remains possible.
            assertEquals("explicit_stop", telemetry.config["smart_turn_revoked_for_priority"])
            if (!releaseBeforeEndpoint) {
                assertEquals(0, calls.get()); assertFalse(task.isCompleted)
                releaseNative.complete(Unit); withTimeout(2000) { resetReady.await() }; releaseReset.complete(Unit)
            }
            val finished = withTimeout(2000) { task.await() } as CaptureFirstReplyHandoff.Result.Finished
            assertSame(result, finished.followup)
            assertEquals(if (releaseBeforeEndpoint) 1 else 0, calls.get())
        } finally {
            releaseNative.complete(Unit); releaseReset.complete(Unit)
            capture?.stop(); shadow.close(); rig.close()
        }
    }

    @Test fun rawSpeechCanContinueDuringCleanupThenShadowUsesSameCallWorker() = runBlocking { scenario(true) }
    @Test fun endpointBeforeCleanupProducesNoObservationAndNeverDelaysRawCapture() = runBlocking { scenario(false) }
    @Test fun cancelledCaptureNeverReopensWhenOldCleanupReturns() = runBlocking { scenario(false, cancelDuringCleanup = true) }
}
