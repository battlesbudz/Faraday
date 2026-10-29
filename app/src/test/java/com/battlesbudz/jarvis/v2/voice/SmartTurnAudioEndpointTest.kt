package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.voice.smartturn.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test

/** Real capture/worker/PCM owner with deterministic model latency and synthetic acoustic/model outputs. */
class SmartTurnAudioEndpointTest {
    private class Backend : SmartTurnBackend {
        val calls = AtomicInteger(); val cancellations = AtomicInteger(); val releases = Semaphore(0)
        val closed = AtomicBoolean(); @Volatile var probability = .9f
        override fun infer(samples: FloatArray, requestId: Long, cancelled: AtomicBoolean): SmartTurnInference {
            calls.incrementAndGet()
            check(releases.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS))
            return SmartTurnInference(probability, 1, 2)
        }
        override fun cancel(requestId: Long) { cancellations.incrementAndGet() }
        override fun close() { closed.set(true) }
    }
    private class Telemetry : SmartTurnTelemetry {
        val config = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        val metrics = java.util.Collections.synchronizedMap(mutableMapOf<String, Number?>())
        override fun metric(name: String, value: Number?) { metrics[name] = value }
        override fun configuration(name: String, value: String) { config[name] = value }
        override fun event(message: String) {}
    }
    private class Rig(scope: CoroutineScope, captions: Boolean = false, val native: Boolean = true,
                      available: Boolean = true, val fixed: Long? = null, speculate: Boolean = false,
                      segmentSoftMs: Long = 15_000, segmentHardMs: Long = 22_000,
                      reference: Boolean = false,
                      guardFollowup: Boolean = false, rejectAtLimit: Boolean = false,
                      maxDurationMs: Int = 25_000) {
        val wall = AtomicLong(); var samples = 0L; var acoustic = .99f; var captureTimeGapMs = 0L
        var riskyTailSamples = 0; var current = true; var routeAllowed = true; var blocker: String? = null
        var needsTranscript = false
        var caption = "what is the"; var finalCalls = 0; var captionClosed = false; var finalizeLogHook: (() -> Unit)? = null
        var finalCaption: String? = null; var finishHook: (() -> Unit)? = null
        var recoveryText = ""; var recoveredPcm: ByteArray? = null; var recoverHook: (() -> Unit)? = null
        var hardwareBacklog = 0L; var collectorBytes = 0; var acknowledgedSamples = 0L
        val proposals = mutableListOf<NativePauseProposal>()
        val logs = mutableListOf<String>(); val original = ByteArrayOutputStream(); val retained = ByteArrayOutputStream()
        val inputFrames = Channel<ByteArray>(Channel.UNLIMITED)
        val backend = Backend(); val telemetry = Telemetry()
        val worker = SmartTurnShadow({ backend }, enabled = true, clock = { wall.get() * 1_000_000 })
        val observer = SmartTurnCaptureObserver(worker, "turn-1", 7, telemetry, { current }, { blocker },
            { wall.get() * 1_000_000 }, endpointEnabled = true)
        val capture = AudioTurnCapture(object : AudioInput {
            override val sampleRateHz = 16000; override val channelCount = 1
            override val lastChunkCaptureTimeMs get() = samples / 16 + captureTimeGapMs
            override val bufferedAudioMs get() = hardwareBacklog
            override val stoppedUnconsumedPcmBytes get() = hardwareBacklog * 32 + (samples - acknowledgedSamples) * 2
            override val lastChunkSequence get() = samples
            override fun acknowledgeConsumed(sequence: Long) { acknowledgedSamples = sequence }
            override fun chunks() = inputFrames.receiveAsFlow()
            override suspend fun start() {}
            override suspend fun stop() {}
        }, scope, createDetector = { FrameSpeechDetector({ acoustic }) },
            nowMs = wall::get, nowNs = { wall.get() * 1_000_000 }, captureDispatcher = Dispatchers.Unconfined,
            captionOnly = native, allowAudioOnlyTurns = native, trailingSilenceMs = fixed,
            guardFollowupSpeech = guardFollowup, rejectAtAudioLimit = rejectAtLimit,
            maxAudioDurationMs = maxDurationMs,
            turnStopPolicy = if (reference) TurnStopPolicy.REFERENCE else TurnStopPolicy.LEGACY,
            createTranscriber = if (!captions) null else { { object : StreamingTranscriber {
                override val segmentSoftLimitMs = segmentSoftMs
                override val segmentHardLimitMs = segmentHardMs
                override fun accept(pcm: ByteArray) = caption
                override fun finish(): String { finalCalls++; finishHook?.invoke(); return finalCaption ?: caption }
                override fun recover(pcm: ByteArray): String { recoveredPcm = pcm.copyOf(); recoverHook?.invoke(); return recoveryText }
                override fun close() { captionClosed = true }
            } } },
            canUseSmartTurn = { routeAllowed }, needsFinalTranscript = { needsTranscript },
            shadowObserver = observer.takeIf { available },
            nativePauseObserver = if (!speculate) null else object : NativePauseObserver {
                override fun onProposal(proposal: NativePauseProposal): Boolean { proposals += proposal; return true }
                override fun onInvalidated(reason: NativePauseInvalidation) {}
            }, nativePauseTurnId = "turn-1", nativePauseGeneration = 7, canUseNativePause = { routeAllowed },
            retainedPcmObserver = object : RetainedPcmObserver {
                override fun onPcm(retainedPcm16: ByteArray) { retained.write(retainedPcm16); collectorBytes = retained.size() }
                override fun onCandidateDiscarded() {}
                override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) {}
            }, log = {
                logs.add(it)
                if (it.startsWith("caption_finalization")) finalizeLogHook?.invoke()
            })
        suspend fun emit(p: Float, count: Int = 1600) {
            acoustic = p
            val amplitude = if (p >= .5f) 5000 else if (p >= .15f) 900 else 0
            val pcm = ByteArray(count * 2) {
                val sampleAmplitude = if (it / 2 >= count - riskyTailSamples) 5000 else amplitude
                if (it % 2 == 0) sampleAmplitude.toByte() else (sampleAmplitude shr 8).toByte()
            }
            samples += count; wall.set(maxOf(wall.get(), samples / 16 + captureTimeGapMs)); original.write(pcm)
            inputFrames.send(pcm)
            eventually { acknowledgedSamples == samples || capture.endpointDecisionAtNs != null }
            repeat(8) { yield() }
        }
        suspend fun startPause() {
            capture.start(); repeat(3) { emit(.99f) }; repeat(4) { emit(.01f) }
            eventually { backend.calls.get() == 1 }
            assertEquals(700L, wall.get()); assertEquals(11200L, samples)
        }
        suspend fun result(probability: Float, wallMs: Long) {
            backend.probability = probability; wall.set(wallMs); backend.releases.release()
            eventually { !worker.isBusy() }
            val index = backend.calls.get() - 1
            eventually { telemetry.config.containsKey("smart_turn_${index}_outcome") || !current || backend.cancellations.get() > 0 }
            repeat(16) { yield() }
        }
        suspend fun completeWav(): ByteArray {
            assertTrue(withTimeout(1000) { capture.awaitTurnCompletion() })
            val wav = capture.stop()
            assertArrayEquals(original.toByteArray(), wav.copyOfRange(44, wav.size))
            return wav
        }
        suspend fun close() { backend.releases.release(8); capture.stop(); observer.close(); worker.close(); assertTrue(worker.awaitClosed(1000)); inputFrames.close() }
    }
    companion object {
        private suspend fun eventually(test: () -> Boolean) = withTimeout(2000) { while (!test()) yield() }
    }

    @Test fun completeAt65msWakesBetween100msReadsAndKeepsEverySampleWithoutCaptions() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.9f, 765)
            r.completeWav()
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(300L, r.capture.lastSpeechAtMs)
            assertEquals(0, r.finalCalls)
            assertTrue(r.logs.any { it.contains("modelWake=true") && it.contains("modelThroughSample=11200 retainedThroughSample=11200") })
            println("TRACE complete65ms: speech_end=300 admission=700 snapshot=11200 decision=765 retained=11200 final_asr=0")
        } finally { r.close() }
    }

    @Test fun slowValidResultKeepsClassifiedAndUnclassifiedLaterPcmInSameTurn() = runBlocking<Unit> {
        for (latency in listOf(120L, 220L)) {
            val r = Rig(this)
            try {
                r.startPause(); r.emit(.01f) // 800ms: full raw coverage after original snapshot.
                if (latency > 200) r.emit(.01f) // 900ms: post-snapshot suffix is unclassified.
                r.result(.9f, 700 + latency)
                if (latency == 120L) {
                    r.completeWav(); assertEquals(820_000_000L, r.capture.endpointDecisionAtNs)
                } else {
                    assertNull(r.capture.endpointDecisionAtNs)
                    while (r.samples < 25600) r.emit(.01f)
                    r.completeWav(); assertEquals(1_600_000_000L, r.capture.endpointDecisionAtNs)
                }
                println("TRACE complete${latency}ms: snapshot=11200 endpoint_ns=${r.capture.endpointDecisionAtNs} retained=${r.samples}")
            } finally { r.close() }
        }
    }

    @Test fun incompleteDefeats650msEndpointThenResumptionGetsFreshCompleteWithoutLosingWords() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765); repeat(4) { r.emit(.01f) }
            assertNull(r.capture.endpointDecisionAtNs)
            repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 2 }
            r.result(.9f, r.wall.get() + 65); r.completeWav()
            assertEquals(2, r.backend.calls.get())
            assertEquals(1, r.telemetry.metrics["smart_turn_resumption_invalidations"])
        } finally { r.close() }
    }

    @Test fun repeatedIncompleteHas3500msBoundAndOnlyOneRefresh() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.5f, 765) // Upstream strict >0.5: equal remains incomplete.
            while (r.samples / 16 < 1200) r.emit(.01f)
            eventually { r.backend.calls.get() == 2 }; r.result(.1f, 1265)
            while (r.samples / 16 < 3900) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs); assertEquals(2, r.backend.calls.get())
            r.emit(.01f); r.completeWav(); assertEquals(4_000_000_000L, r.capture.endpointDecisionAtNs)
        } finally { r.close() }
    }

    @Test fun oneFreshReassessmentCorrectsEarlyIncompleteAt965msSpeechEndDelay() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765)
            while (r.samples / 16 < 1100) r.emit(.01f)
            assertEquals(1, r.backend.calls.get()); assertNull(r.capture.endpointDecisionAtNs)
            r.emit(.01f); eventually { r.backend.calls.get() == 2 }; r.result(.9f, 1265)
            r.completeWav(); assertEquals(1_265_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(2, r.backend.calls.get())
            println("TRACE continue->complete: speech_end=300 snapshots=700,1200 decision=1265 delay=965 requests=2; single_assessment_fallback=4000 delay=3700 requests=1")
        } finally { r.close() }
    }

    @Test fun unknownOrBlockedRefreshKeepsPriorContinueAndResumptionRevokesRefresh() = runBlocking<Unit> {
        for (kind in listOf("deadline", "blocked", "resumed")) {
            val r = Rig(this, fixed = 650)
            try {
                r.startPause(); r.result(.1f, 765)
                if (kind == "blocked") r.blocker = "thermal_severe"
                while (r.samples / 16 < 1200) r.emit(.01f)
                assertNull(r.capture.endpointDecisionAtNs)
                if (kind != "blocked") {
                    eventually { r.backend.calls.get() == 2 }
                    if (kind == "resumed") {
                        r.emit(.99f); r.result(.9f, 1320)
                        assertNull(r.capture.endpointDecisionAtNs)
                        assertTrue(r.backend.cancellations.get() > 0)
                        continue
                    }
                    r.result(.9f, 1451)
                }
                while (r.samples / 16 < 3900) r.emit(.01f)
                assertNull(r.capture.endpointDecisionAtNs)
                r.emit(.01f); r.completeWav()
                assertEquals(if (kind == "blocked") 1 else 2, r.backend.calls.get())
            } finally { r.close() }
        }
    }

    @Test fun staleSpeechAndCallResultsCannotCloseCapture() = runBlocking<Unit> {
        for (changedCall in listOf(false, true)) {
            val r = Rig(this)
            try {
                r.startPause()
                if (changedCall) r.current = false else r.emit(.25f)
                r.result(.9f, 820)
                assertNull(r.capture.endpointDecisionAtNs)
                if (!changedCall) assertTrue(r.backend.cancellations.get() > 0)
                val wav = r.capture.stop()
                assertArrayEquals(r.original.toByteArray(), wav.copyOfRange(44, wav.size))
            } finally { r.close() }
        }
    }

    @Test fun deadlineAndThermalBusyOrMissingModelUseUnextendedOrdinaryEndpoint() = runBlocking<Unit> {
        for (kind in listOf("deadline", "blocked", "missing")) {
            val r = Rig(this, fixed = 650, available = kind != "missing")
            try {
                if (kind == "blocked") r.blocker = "thermal_severe"
                r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
                if (kind == "deadline") {
                    eventually { r.backend.calls.get() == 1 }
                    r.result(.9f, 951) // The expired result cannot gain authority.
                }
                repeat(3) { r.emit(.01f) }
                r.completeWav()
                assertEquals(1_000_000_000L, r.capture.endpointDecisionAtNs)
                assertTrue(r.logs.any { it.contains("endpointCue=fixed") })
            } finally { r.close() }
        }
    }

    @Test fun completeNativeAudioDoesNotDecodeOrLetCaptionTextVetoButClosesSameOwner() = runBlocking<Unit> {
        val r = Rig(this, captions = true)
        try {
            r.startPause(); r.result(.9f, 765); r.completeWav()
            assertEquals(0, r.finalCalls); assertTrue(r.captionClosed)
            assertEquals("skipped_smart_turn_caption", r.capture.finalAsrStatus)
            assertEquals("", r.capture.finalTranscript)
        } finally { r.close() }
    }

    @Test fun playbackRiskKeepsOrdinaryEndpointAndRequiredFinalRecognitionOnBothPaths() = runBlocking<Unit> {
        for (native in listOf(false, true)) {
            val r = Rig(this, captions = true, native = native, fixed = 1200)
            try {
                r.routeAllowed = false
                r.startPause(); r.result(.9f, 765); assertNull(r.capture.endpointDecisionAtNs)
                while (r.samples / 16 < 1500) r.emit(.01f)
                r.completeWav(); assertEquals(1, r.finalCalls); assertEquals("what is the", r.capture.finalTranscript)
            } finally { r.close() }
        }
    }

    @Test fun whisperCompleteWakesEarlyButFinalTranscriptRemainsAuthoritative() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "provisional words"
            r.finalCaption = "What is the weather today?"
            r.startPause(); r.result(.9f, 765); r.completeWav()
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(1, r.finalCalls)
            assertEquals("What is the weather today?", r.capture.finalTranscript)
            assertEquals("finalized", r.capture.finalAsrStatus)
            assertEquals("authoritative_asr", r.capture.captionFinalizationReason)
            assertTrue(r.captionClosed)
            assertTrue(r.logs.any { it.contains("endpointCue=smart_turn_complete") })
        } finally { r.close() }
    }

    @Test fun whisperSegmentedPcmCanCompleteWithoutLosingCommittedOrFinalWords() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800, segmentSoftMs = 500)
        try {
            r.caption = "Please tell me"
            r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(2) { r.emit(.01f) }
            assertEquals(1, r.finalCalls)
            assertTrue(r.logs.any { it.contains("asr_segment_committed index=1") })
            r.caption = "the weather today."
            repeat(2) { r.emit(.01f) }; eventually { r.backend.calls.get() == 1 }
            r.result(.9f, 765); r.completeWav()
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(2, r.finalCalls)
            assertEquals("Please tell me the weather today.", r.capture.finalTranscript)
            assertEquals("finalized", r.capture.finalAsrStatus)
            assertNull(r.capture.recognitionIssue)
        } finally { r.close() }
    }

    @Test fun whisperContinueKeepsListeningAndFreshCompleteFinalizesContinuedSpeech() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765); repeat(4) { r.emit(.01f) }
            assertNull(r.capture.endpointDecisionAtNs); assertEquals(0, r.finalCalls)
            r.caption = "What is the weather tomorrow?"
            repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 2 }
            r.result(.9f, r.wall.get() + 65); r.completeWav()
            assertEquals(1, r.finalCalls)
            assertEquals("What is the weather tomorrow?", r.capture.finalTranscript)
            assertEquals(1, r.telemetry.metrics["smart_turn_resumption_invalidations"])
        } finally { r.close() }
    }

    @Test fun whisperHardSegmentSeamRetainsOverlapValidationAndCompleteAudioRecovery() = runBlocking<Unit> {
        for (seamMatches in listOf(false, true)) {
            val r = Rig(this, captions = true, native = false, fixed = 1800, segmentHardMs = 500)
            try {
                r.caption = "Please tell me the weather"
                r.capture.start(); repeat(5) { r.emit(.99f) }
                assertEquals(1, r.finalCalls)
                assertTrue(r.logs.any { it.contains("asr_segment_committed index=1") && it.contains("overlapMs=500") })
                r.caption = if (seamMatches) "the weather tomorrow." else "A different hypothesis."
                r.recoveryText = "Please tell me the weather tomorrow."
                repeat(4) { r.emit(.01f) }; eventually { r.backend.calls.get() == 1 }
                r.result(.9f, 965)
                if (!seamMatches) {
                    // The conflicting seam still exposes only the committed unfinished
                    // request, but the final-transcript guard no longer re-opens a
                    // Smart-Turn-decided endpoint; recovery supplies its text below.
                    assertFalse(r.logs.any { it.contains("native_endpoint_deferred") })
                }
                r.completeWav()
                assertEquals(965_000_000L, r.capture.endpointDecisionAtNs)
                assertEquals(2, r.finalCalls)
                assertEquals("Please tell me the weather tomorrow.", r.capture.finalTranscript)
                assertNull(r.capture.recognitionIssue)
                if (seamMatches) assertNull(r.recoveredPcm)
                else {
                    assertArrayEquals(r.original.toByteArray(), r.recoveredPcm)
                    assertTrue(r.logs.any { it.contains("asr_recovery_started reason=segment_boundary_uncertain") })
                }
            } finally { r.close() }
        }
    }

    @Test fun whisperDeadlineMissingAndBlockedModelFallBackWithFinalText() = runBlocking<Unit> {
        for (kind in listOf("deadline", "blocked", "missing")) {
            val r = Rig(this, captions = true, native = false, fixed = 1200, available = kind != "missing")
            try {
                r.caption = "What is the weather today?"
                if (kind == "blocked") r.blocker = "thermal_severe"
                r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
                if (kind == "deadline") {
                    eventually { r.backend.calls.get() == 1 }; r.result(.9f, 951)
                }
                assertNull(r.capture.endpointDecisionAtNs); assertEquals(0, r.finalCalls)
                while (r.samples / 16 < 1500) r.emit(.01f)
                r.completeWav()
                assertEquals(1_500_000_000L, r.capture.endpointDecisionAtNs)
                assertEquals(1, r.finalCalls); assertEquals(r.caption, r.capture.finalTranscript)
                assertEquals("finalized", r.capture.finalAsrStatus)
                assertTrue(r.logs.any { it.contains("endpointCue=fixed") })
            } finally { r.close() }
        }
    }

    @Test fun whisperSmartCompleteSealsAfterAudioArrivingDuringSeamRecoveryDrains() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800, segmentHardMs = 500)
        try {
            r.caption = "Please tell me the weather"
            r.capture.start(); repeat(5) { r.emit(.99f) }
            r.caption = "A conflicting seam."
            r.recoveryText = "Please tell me the weather."
            r.recoverHook = { r.hardwareBacklog = 100; r.recoverHook = null }
            repeat(4) { r.emit(.01f) }; eventually { r.backend.calls.get() == 1 }; r.result(.9f, 965)
            eventually { r.recoveredPcm != null }
            // Seam recovery still runs and its text stays authoritative. Audio that
            // arrived during it is queued and unclassified, so the seal waits until
            // that frontier resolves; the verdict itself is not re-judged meanwhile.
            eventually { r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_recovery") } }
            assertNull(r.capture.endpointDecisionAtNs)
            r.hardwareBacklog = 0; r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(1_000_000_000L, r.capture.endpointDecisionAtNs)
            r.completeWav()
            assertEquals(r.recoveryText, r.capture.finalTranscript)
            assertArrayEquals(r.original.toByteArray(), r.recoveredPcm)
            assertNull(r.capture.recognitionIssue)
        } finally { r.close() }
    }

    @Test fun whisperStaleCallAndRawSpeechRevisionCannotAuthorizeEarlyFinalization() = runBlocking<Unit> {
        for (changedCall in listOf(false, true)) {
            val r = Rig(this, captions = true, native = false, fixed = 1800)
            try {
                r.startPause()
                if (changedCall) r.current = false else r.emit(.25f)
                r.result(.9f, 820)
                assertNull(r.capture.endpointDecisionAtNs); assertEquals(0, r.finalCalls)
                if (!changedCall) assertTrue(r.backend.cancellations.get() > 0)
                val wav = r.capture.stop()
                assertArrayEquals(r.original.toByteArray(), wav.copyOfRange(44, wav.size))
            } finally { r.close() }
        }
    }

    @Test fun whisperSmartCompleteSealsAfterAudioArrivingDuringBlankFinalRetryDrains() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "What is the weather today?"
            r.finalCaption = ""
            r.recoveryText = r.caption
            r.recoverHook = { r.hardwareBacklog = 100; r.recoverHook = null }
            r.startPause(); r.result(.9f, 765)
            // The blank-final recovery still supplies the request text. Audio that
            // arrived during it is queued and unclassified, so the seal waits until
            // that frontier resolves; the verdict itself is not re-judged meanwhile.
            eventually { r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_recovery") } }
            assertNull(r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("reason=empty_stream") })
            r.hardwareBacklog = 0; r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(800_000_000L, r.capture.endpointDecisionAtNs)
            r.completeWav()
            assertEquals(r.recoveryText, r.capture.finalTranscript)
            assertArrayEquals(r.original.toByteArray(), r.recoveredPcm)
            assertNull(r.capture.recognitionIssue)
        } finally { r.close() }
    }

    @Test fun whisperSmartCompleteSealsAfterAudioArrivingDuringFinalizationDrains() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "Please tell me the weather."
            r.finishHook = { r.hardwareBacklog = 100; r.finishHook = null }
            r.startPause(); r.result(.9f, 765)
            // Audio that arrives during finalization is queued and unclassified: it
            // may hold a continuation of this turn that finish() has not seen, so the
            // seal waits until the frontier resolves instead of dropping those words
            // or splitting them into a separate turn. The verdict is not re-judged.
            eventually { r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_finalization") } }
            assertNull(r.capture.endpointDecisionAtNs)
            r.hardwareBacklog = 0; r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(800_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(1, r.finalCalls)
            assertEquals("Please tell me the weather.", r.capture.finalTranscript)
            assertEquals("finalized", r.capture.finalAsrStatus)
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun whisperFinalUnfinishedTextNoLongerDefersASmartTurnComplete() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.startPause(); r.result(.9f, 765)
            // The verdict is the endpoint authority on this route: a final transcript
            // that reads as unfinished no longer re-opens the turn it already ended.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs); assertEquals(1, r.finalCalls)
            assertEquals("what is the", r.capture.finalTranscript)
            assertFalse(r.logs.any { it.contains("native_endpoint_deferred") })
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun whisperCompleteBindsOnceTheRecognitionQueueDrains() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "What is the weather today?"
            r.startPause()
            r.hardwareBacklog = 100 // The recognition queue is not drained when the verdict lands.
            r.result(.9f, 765)
            // The backlog no longer vetoes the verdict at the fire condition or at
            // eligibility, and the final transcript cannot re-open the turn. The
            // seal itself waits until the queued audio is resolved, so words still
            // in the pipeline cannot be dropped or split into a separate turn.
            eventually { r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_finalization") } }
            assertNull(r.capture.endpointDecisionAtNs)
            assertFalse(r.logs.any { it.contains("native_endpoint_deferred") })
            r.hardwareBacklog = 0; r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(800_000_000L, r.capture.endpointDecisionAtNs)
            r.completeWav()
            assertEquals("What is the weather today?", r.capture.finalTranscript)
            assertEquals(1, r.finalCalls)
        } finally { r.close() }
    }

    @Test fun whisperShortSpeechCompleteBindsAtTheVerdictTarget() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1200)
        try {
            r.caption = "Yes."
            r.capture.start(); repeat(2) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 1 }; r.result(.9f, 665)
            // Input sanity is the only gate on this route: 200ms of real speech and a
            // snapshot covering it are enough for the verdict to bind; the old
            // strong-speech floor no longer vetoes it.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(665_000_000L, r.capture.endpointDecisionAtNs)
            r.completeWav(); assertEquals("Yes.", r.capture.finalTranscript)
            assertEquals(1, r.finalCalls)
        } finally { r.close() }
    }

    @Test fun whisperExplicitStopFinalizesTextWhileBusySmartTurnIsRevoked() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false)
        try {
            r.startPause(); r.capture.finishNow(); r.emit(.01f); r.completeWav()
            assertTrue(r.backend.cancellations.get() > 0)
            assertEquals(1, r.finalCalls); assertEquals("what is the", r.capture.finalTranscript)
            assertEquals("finalized", r.capture.finalAsrStatus)
            assertTrue(r.logs.any { it.contains("turn_endpoint reason=explicit_stop") })
        } finally { r.close() }
    }

    @Test fun quietSpeechCompleteBindsOnWhisperRouteButNativeRouteKeepsItsGate() = runBlocking<Unit> {
        for (native in listOf(false, true)) {
            val r = Rig(this, captions = true, native = native, fixed = 1800)
            try {
                r.capture.start(); repeat(5) { r.emit(.25f) }; repeat(4) { r.emit(.01f) }
                eventually { r.backend.calls.get() == 1 }; r.result(.9f, 965)
                if (native) {
                    // The native route keeps its corroboration and strong-speech gate.
                    assertNull(r.capture.endpointDecisionAtNs)
                    while (r.samples / 16 < 2300) r.emit(.01f)
                    r.completeWav(); assertTrue(r.finalCalls > 0)
                } else {
                    // ASR-corroborated weak speech earlier in the turn latched the old
                    // veto; on the Whisper route the verdict binds despite the latch.
                    eventually { r.capture.endpointDecisionAtNs != null }
                    assertEquals(965_000_000L, r.capture.endpointDecisionAtNs)
                    r.completeWav(); assertTrue(r.finalCalls > 0)
                }
            } finally { r.close() }
        }
    }

    @Test fun readyAndPcmOrderingAlwaysRetainsReceivedSuffixOnce() = runBlocking<Unit> {
        for (readyFirst in listOf(false, true)) {
            val r = Rig(this)
            try {
                r.startPause()
                if (readyFirst) {
                    r.hardwareBacklog = 100 // The next hardware read exists before callback admission.
                    r.result(.9f, 800); assertNull(r.capture.endpointDecisionAtNs)
                    r.hardwareBacklog = 0; r.emit(.01f)
                } else {
                    r.emit(.01f); r.result(.9f, 800)
                }
                r.completeWav(); assertEquals(12800L, r.samples)
                assertEquals(800_000_000L, r.capture.endpointDecisionAtNs)
            } finally { r.close() }
        }
    }

    @Test fun irregularReadsKeepExactModelBoundaryAndNoPcmPadding() = runBlocking<Unit> {
        val r = Rig(this)
        try {
            r.capture.start(); repeat(3) { r.emit(.99f) }
            for (count in listOf(800, 2400, 1280, 1920)) r.emit(.01f, count)
            eventually { r.backend.calls.get() == 1 }; r.result(.9f, 765)
            r.completeWav(); assertEquals(11200L, r.samples)
        } finally { r.close() }
    }

    @Test fun eightEarlierPausesRemainModelControlledAndBudgetExhaustionIsExplicit() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765)
            repeat(7) { index ->
                repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
                eventually { r.backend.calls.get() == index + 2 }
                r.result(.1f, r.wall.get() + 65)
                assertNull(r.capture.endpointDecisionAtNs)
            }
            repeat(3) { r.emit(.99f) }; repeat(7) { r.emit(.01f) }
            r.completeWav(); assertEquals(8, r.backend.calls.get())
            assertEquals("attempt_budget_exhausted", r.telemetry.config["smart_turn_endpoint_fallback"])
        } finally { r.close() }
    }

    @Test fun undrainedCancelledWorkerCannotCreateSecondOwnerOrHoldFallback() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); repeat(3) { r.emit(.99f) }; repeat(7) { r.emit(.01f) }
            r.completeWav()
            assertEquals(1, r.backend.calls.get()); assertTrue(r.backend.cancellations.get() > 0)
            assertFalse(r.backend.closed.get()) // The old native borrow is still outstanding.
            assertTrue((r.telemetry.metrics["smart_turn_busy_skips"]?.toInt() ?: 0) > 0)
        } finally { r.close() }
    }

    @Test fun nativeRouteStillRequiresClassifiedQuietCoverageForComplete() {
        val raw = RawVadCoverage(16000, 7680, 7168, 1) // 32ms of actual quiet, despite a wall-time gap.
        val eligibility = NativePauseEndpointPolicy.eligibility(true, 300, false, 5000, raw, false, 0, true)
        val fallback = AdaptiveTurnEnd.Decision(3000, "no_transcript")
        assertEquals(fallback, SmartTurnEndpointPolicy.decision(fallback,
            CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 8000), eligibility, true, raw, 16000, 5000,
            lastSpeechSample = 7168, bindVerdict = false))
    }

    @Test fun whisperRouteBindsCompleteOnInputSanityAlone() {
        val fallback = AdaptiveTurnEnd.Decision(3000, "no_transcript")
        val sane = NativePauseEndpointPolicy.smartTurnSanity(true, 8000, 7168)
        assertTrue(sane.allowed)
        // The trailing pause needs no byte-exact classification on this route: the
        // verdict judged the speech span, and the snapshot covers all of it.
        assertEquals(AdaptiveTurnEnd.Decision(350, "smart_turn_complete"), SmartTurnEndpointPolicy.decision(fallback,
            CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 8000), sane, true, null, 16000, 5000,
            lastSpeechSample = 7168, bindVerdict = true))
    }

    @Test fun whisperRouteCompleteStillNeedsSpeechAndASnapshotCoveringTheSpeech() {
        val fallback = AdaptiveTurnEnd.Decision(3000, "no_transcript")
        val complete = CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 8000)
        assertEquals(fallback, SmartTurnEndpointPolicy.decision(fallback, complete,
            NativePauseEndpointPolicy.smartTurnSanity(false, 8000, 7168), true, null, 16000, 5000,
            lastSpeechSample = 7168, bindVerdict = true))
        assertEquals(fallback, SmartTurnEndpointPolicy.decision(fallback, complete,
            NativePauseEndpointPolicy.smartTurnSanity(true, 8000, 9000), true, null, 16000, 5000,
            lastSpeechSample = 9000, bindVerdict = true))
        assertEquals(fallback, SmartTurnEndpointPolicy.decision(fallback,
            CaptureEndpointDecision(CaptureEndpointState.COMPLETE, null),
            NativePauseEndpointPolicy.smartTurnSanity(true, null, 7168), true, null, 16000, 5000,
            lastSpeechSample = 7168, bindVerdict = true))
    }

    @Test fun timestampGapCannotExhaustContinueUntilEnoughQuietPcmIsClassified() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765)
            r.blocker = "thermal_severe" // Keep the first CONTINUE while refresh is unavailable.
            r.captureTimeGapMs = 5000
            r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs) // 5,500ms elapsed, only 500ms quiet captured.
            while (r.samples / 16 < 3900) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs) // A partial raw frame is not classified quiet.
            r.emit(.01f); r.completeWav()
            assertEquals(9_000_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(1, r.backend.calls.get())
            assertEquals(64_000L, r.samples)
            println("TRACE continue_time_gap: gap=5000 endpoint=9000 retained=64000 quiet_pcm_ms=3700 requests=1")
        } finally { r.close() }
    }

    @Test fun continueCapCannotSealAnAdmittedUnclassifiedSpeechSuffix() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650)
        try {
            r.startPause(); r.result(.1f, 765); r.blocker = "thermal_severe"
            while (r.samples < 60800) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            // 128 quiet samples finish the prior raw frame; the last 72 loud samples
            // belong to a new unclassified frame. The quiet count alone exceeds the cap.
            r.riskyTailSamples = 72; r.emit(.01f, 200)
            assertEquals(61000L, r.samples)
            assertNull(r.capture.endpointDecisionAtNs)
            // The next real classification confirms continuation in the same retained turn.
            r.riskyTailSamples = 0; r.emit(.99f, 1536)
            assertNull(r.capture.endpointDecisionAtNs)
            assertEquals(1, r.telemetry.metrics["smart_turn_resumption_invalidations"])
            assertEquals(1, r.backend.calls.get())
            r.blocker = null; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 2 }; r.result(.9f, r.wall.get() + 65)
            r.completeWav()
            assertEquals(4_373_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(68_936L, r.samples)
        } finally { r.close() }
    }

    @Test fun continueCapRemainsLiveAcrossEightPhasesOfHundredMsReads() = runBlocking<Unit> {
        val delays = mutableListOf<Long>()
        for (speechReads in 3..10) {
            val r = Rig(this, fixed = 650)
            try {
                r.capture.start(); repeat(speechReads) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
                eventually { r.backend.calls.get() == 1 }; r.result(.1f, r.wall.get() + 65)
                r.blocker = "thermal_severe"
                var reads = 0
                while (r.capture.endpointDecisionAtNs == null && reads++ < 45) r.emit(.01f)
                r.completeWav()
                val speechEnd = speechReads * 100L
                val expectedEnd = ((speechEnd + 3500 + 799) / 800) * 800
                assertEquals(expectedEnd * 1_000_000, r.capture.endpointDecisionAtNs)
                assertEquals(0L, r.samples % 512)
                assertEquals(1, r.backend.calls.get())
                delays += expectedEnd - speechEnd
            } finally { r.close() }
        }
        assertEquals(listOf(3700L, 3600L, 3500L, 4200L, 4100L, 4000L, 3900L, 3800L), delays)
        println("TRACE continue_cap_100ms_read_phases: speech_end_ms=300..1000 delays_ms=$delays; extra_over_3500=0..700")
    }

    @Test fun continueVerdictSurvivesFinalDrainUntilItsPartialTailIsResolved() = runBlocking<Unit> {
        for (captions in listOf(false, true)) {
            val r = Rig(this, captions = captions, fixed = 650)
            try {
                r.startPause(); r.result(.1f, 765); r.blocker = "thermal_severe"
                // Hardware reports a queued frame after this endpoint's finalization phase.
                r.finalizeLogHook = { r.hardwareBacklog = 100; r.finalizeLogHook = null }
                while (r.samples < 64000) r.emit(.01f)
                assertEquals(if (captions) 1 else 0, r.finalCalls)
                assertNull(r.capture.endpointDecisionAtNs)
                assertTrue(r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_finalization") })
                r.hardwareBacklog = 0; r.riskyTailSamples = 72; r.emit(.01f, 200)
                assertNull(r.capture.endpointDecisionAtNs)
                r.riskyTailSamples = 0; r.emit(.99f, 1536)
                assertNull(r.capture.endpointDecisionAtNs)
                repeat(7) { r.emit(.01f) }
                r.completeWav()
                assertEquals(4_808_000_000L, r.capture.endpointDecisionAtNs)
                assertEquals(if (captions) 2 else 0, r.finalCalls) // Resumed segmented caption still finalizes normally.
            } finally { r.close() }
        }
    }

    @Test fun continueCountsOnlyCurrentRawQuietAfterLastConfirmedSpeech() {
        val endpoint = AdaptiveTurnEnd.Decision(3500, "smart_turn_continue")
        val raw = RawVadCoverage(121600, 60800, 4608, 3)
        assertEquals(3500L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, raw, 121600, 4800, 9000))
        assertEquals(3488L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, raw.copy(receivedPcmBytes = 121216, classifiedThroughSample = 60608), 121216, 4800, 9000))
        assertEquals(3000L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, raw.copy(quietFromSample = 12800), 121600, 4800, 9000))
        assertEquals(0L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, raw.copy(receivedPcmBytes = 122000), 122000, 4800, 9000))
        assertEquals(0L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, raw, 122002, 4800, 9000))
        assertEquals(0L, SmartTurnEndpointPolicy.silenceEvidenceMs(endpoint, null, 122000, 4800, 9000))
        assertEquals(9000L, SmartTurnEndpointPolicy.silenceEvidenceMs(AdaptiveTurnEnd.Decision(650, "fixed"), null, 122000, 4800, 9000))
    }

    @Test fun followupRawCaptureNeverWaitsForOldReplyButModelWaitsForExactDrain() = runBlocking<Unit> {
        val r = Rig(this)
        try {
            r.blocker = "previous_reply_native_cleanup"
            r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
            assertEquals(0, r.backend.calls.get()); assertEquals(r.original.size(), r.retained.size())
            assertEquals("previous_reply_native_cleanup", r.telemetry.config["smart_turn_last_admission_blocker"])
            repeat(3) { r.emit(.99f) }; r.blocker = null; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 1 }; r.result(.9f, r.wall.get() + 65)
            r.completeWav(); assertEquals(1, r.backend.calls.get())
        } finally { r.close() }
    }

    @Test fun readyEndpointDoesNotSpendEncoderOnUncertifiablePartialSnapshot() = runBlocking<Unit> {
        for (exactRaw in listOf(false, true)) {
            val r = Rig(this, speculate = true)
            try {
                r.startPause()
                assertTrue(r.proposals.isEmpty()) // Pending model has first use of the pause.
                if (exactRaw) r.emit(.01f)
                r.result(.9f, if (exactRaw) 820 else 765)
                val wav = r.completeWav()
                if (exactRaw) {
                    assertEquals(1, r.proposals.size)
                    assertTrue(r.capture.nativePauseCertificate!!.matchesCompleteWav(wav))
                } else {
                    assertTrue(r.proposals.isEmpty()); assertNull(r.capture.nativePauseCertificate)
                    assertEquals(r.original.size(), r.retained.size())
                }
            } finally { r.close() }
        }
    }

    @Test fun cancellationRevokesBusyModelAndNeverClosesItsNativeBorrowEarly() = runBlocking<Unit> {
        for (native in listOf(false, true)) {
            val r = Rig(this, captions = !native, native = native)
            try {
                r.startPause(); r.capture.stop(); r.worker.close()
                assertTrue(r.backend.cancellations.get() > 0); assertFalse(r.backend.closed.get())
                assertNull(r.capture.endpointDecisionAtNs); assertEquals(0, r.finalCalls)
                r.backend.releases.release(); eventually { r.backend.closed.get() }
            } finally { r.close() }
        }
    }

    // REFERENCE turn-stop (the Pipecat pattern the conversation captures use):
    // the analyzer is the stop strategy, the 5s turn-stop timeout is only a
    // backstop, and with no analyzer the plain speech-timeout window decides.

    @Test fun referenceTimeoutBackstopIsTheOnlyEndingWhenTheModelNeverDecides() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, reference = true)
        try {
            r.caption = "What is the weather today?"
            r.startPause() // Speech ends at 300ms; the ask stays in flight, never released.
            // AdaptiveTurnEnd would end this turn at a transcript target (350ms
            // complete-and-stable, 1100ms settling). REFERENCE has no second
            // decider: nothing ends the turn before the backstop.
            while (r.samples / 16 < 5200) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(5_300_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=turn_stop_timeout") && it.contains("targetSilenceMs=5000") })
            r.completeWav()
            // The transcript is still an ingredient of the seal: finalization ran.
            assertEquals(1, r.finalCalls); assertEquals("What is the weather today?", r.capture.finalTranscript)
        } finally { r.close() }
    }

    @Test fun referenceContinueKeepsTheTurnOpenAndOnlyTheBackstopEndsIt() = runBlocking<Unit> {
        val r = Rig(this, fixed = 650, reference = true)
        try {
            r.startPause(); r.result(.1f, 765) // CONTINUE
            // One bounded refresh re-judges the same pause after 0.5s of new PCM.
            while (r.samples / 16 < 1200) r.emit(.01f)
            eventually { r.backend.calls.get() == 2 }; r.result(.1f, 1265) // CONTINUE again
            // LEGACY ended this turn at speech end + 3500ms (decision at 4000ms).
            // A CONTINUE is the analyzer saying "not done", not an endpoint.
            while (r.samples / 16 < 4000) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            while (r.samples / 16 < 5200) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(5_300_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(2, r.backend.calls.get())
            assertTrue(r.logs.any { it.contains("endpointCue=turn_stop_timeout") })
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun referenceNoModelUsesSpeechTimeoutInsteadOfAdaptiveTranscriptTargets() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, available = false, reference = true)
        try {
            // The partial ("what is the") reads as unfinished, so AdaptiveTurnEnd
            // would wait 1800ms. With no analyzer attached, REFERENCE uses the
            // plain speech-timeout strategy: a fixed 600ms window after the stop.
            r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(5) { r.emit(.01f) }
            assertNull(r.capture.endpointDecisionAtNs) // 800ms wall: only 500ms of silence.
            r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(900_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=speech_timeout") && it.contains("targetSilenceMs=600") })
            r.completeWav()
            assertEquals(1, r.finalCalls); assertEquals("what is the", r.capture.finalTranscript)
        } finally { r.close() }
    }

    @Test fun referenceRouteGateNoLongerVetoesACompleteVerdict() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1200, reference = true)
        try {
            r.routeAllowed = false // Onset inside the playback echo window.
            r.startPause(); r.result(.9f, 765)
            // LEGACY kept the ordinary endpoint here (see
            // playbackRiskKeepsOrdinaryEndpointAndRequiredFinalRecognitionOnBothPaths).
            // REFERENCE judges every conversation stop: echo stays an
            // acceptance-side rejection, never a stop-decision veto.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=smart_turn_complete") })
            r.completeWav(); assertEquals(1, r.finalCalls)
        } finally { r.close() }
    }

    @Test fun referenceWeakSpeechCompleteBindsOnTheNativeRouteOnSanityAlone() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = true, fixed = 1800, reference = true)
        try {
            r.capture.start(); repeat(5) { r.emit(.25f) }; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 1 }; r.result(.9f, 965)
            // The native conjunction vetoed this verdict in LEGACY
            // (ASR-corroborated weak speech). Input sanity is the only gate in
            // REFERENCE: speech existed and the snapshot covers its span.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(965_000_000L, r.capture.endpointDecisionAtNs)
            r.completeWav()
            assertEquals("skipped_smart_turn_caption", r.capture.finalAsrStatus)
            assertEquals("", r.capture.finalTranscript)
        } finally { r.close() }
    }

    @Test fun referenceEchoWindowCompleteStillFinalizesTheCaptionOnTheNativeRoute() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = true, reference = true)
        try {
            r.routeAllowed = false // Onset inside the playback echo window: the
            // factory closes the Smart Turn route gate exactly when the final
            // transcript is required for playback-echo rejection.
            r.startPause(); r.result(.9f, 765) // COMPLETE
            // The verdict still binds (under REFERENCE the gate is not a
            // stop-decision veto); what the closed gate removes is the caption
            // omission, the one place a binding COMPLETE used to discard the
            // transcript acceptance-side echo rejection depends on.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertTrue(r.capture.endpointDecisionAtNs!! < 5_300_000_000L)
            assertTrue(r.logs.any { it.contains("endpointCue=smart_turn_complete") })
            r.completeWav()
            assertEquals(1, r.finalCalls)
            assertEquals("finalized", r.capture.finalAsrStatus)
            assertNotEquals("smart_turn_complete_native_audio", r.capture.captionFinalizationReason)
            assertEquals("what is the", r.capture.finalTranscript)
        } finally { r.close() }
    }

    @Test fun referenceNoCaptionEchoWindowCompleteIsRejectedAtAcceptance() = runBlocking<Unit> {
        val r = Rig(this, captions = false, native = true, fixed = 650, reference = true)
        try {
            // Direct audio with captions off: the capture has no transcriber,
            // so no final transcript can ever exist for it. This onset is
            // inside the playback echo window — the route gate is closed and
            // echo rejection would require exactly that transcript.
            r.routeAllowed = false; r.needsTranscript = true
            r.startPause(); r.result(.9f, 765) // COMPLETE
            repeat(2) { r.emit(.01f) }
            // The verdict still binds the stop (REFERENCE never vetoes it);
            // the candidate dies at acceptance instead: unverifiable as
            // non-echo, it is rejected through the final-candidate path and
            // the retained playback audio is never submitted as a request.
            eventually { r.logs.any { it.contains("final_candidate_rejected policy=existing_playback_echo") } }
            assertNull(r.capture.endpointDecisionAtNs)
            assertEquals("", r.capture.finalTranscript)
            assertEquals("pending", r.capture.finalAsrStatus)
            // The microphone stays open. Speech after the echo window is a
            // fresh candidate and is accepted and submitted normally.
            r.routeAllowed = true; r.needsTranscript = false
            repeat(3) { r.emit(.99f) }; repeat(4) { r.emit(.01f) }
            eventually { r.backend.calls.get() == 2 }
            r.result(.9f, r.wall.get() + 65)
            assertTrue(withTimeout(1000) { r.capture.awaitTurnCompletion() })
            assertTrue(r.capture.stop().size > 44)
        } finally { r.close() }
    }

    @Test fun referenceDecisionBindsCompleteOnSanityAlone() {
        val sane = NativePauseEndpointPolicy.smartTurnSanity(true, 8000, 7168)
        assertEquals(AdaptiveTurnEnd.Decision(350, "smart_turn_complete"),
            SmartTurnEndpointPolicy.referenceDecision(
                CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 8000), sane, 16000, 350, 7168))
    }

    @Test fun referenceDecisionLeavesEveryOtherStateToTheBackstop() {
        val sane = NativePauseEndpointPolicy.smartTurnSanity(true, 8000, 7168)
        val complete = CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 8000)
        // Anything but the analyzer's COMPLETE leaves the turn open.
        assertNull(SmartTurnEndpointPolicy.referenceDecision(
            CaptureEndpointDecision(CaptureEndpointState.CONTINUE, 8000), sane, 16000, 5000, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(
            CaptureEndpointDecision(CaptureEndpointState.PENDING, 8000), sane, 16000, 5000, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(
            CaptureEndpointDecision(CaptureEndpointState.FALLBACK), sane, 16000, 5000, 7168))
        // Sanity failures: no speech, no snapshot, a snapshot ending before the
        // speech span, a snapshot ahead of everything collected, a short pause,
        // and a COMPLETE whose snapshot never arrived.
        assertNull(SmartTurnEndpointPolicy.referenceDecision(complete,
            NativePauseEndpointPolicy.smartTurnSanity(false, 8000, 7168), 16000, 5000, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(complete,
            NativePauseEndpointPolicy.smartTurnSanity(true, null, 7168), 16000, 5000, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(complete,
            NativePauseEndpointPolicy.smartTurnSanity(true, 8000, 9000), 16000, 5000, 9000))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(
            CaptureEndpointDecision(CaptureEndpointState.COMPLETE, 9000), sane, 16000, 5000, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(complete, sane, 16000, 349, 7168))
        assertNull(SmartTurnEndpointPolicy.referenceDecision(
            CaptureEndpointDecision(CaptureEndpointState.COMPLETE, null), sane, 16000, 5000, 7168))
    }

    // The reply/barge-in capture (ReplyVoiceCapture) constructs AudioTurnCapture
    // in exactly two shapes, both now under REFERENCE: the ASR-free Gemma shape
    // (captionOnly, no final transcriber, 650ms window, 28s cap with
    // reject-at-limit, follow-up speech guard) and the recognition shape
    // (transcriber present, no fixed window, 25s cap). The gated input decides
    // only whether an interruption happened; these tests pin that the retained
    // speech's stop is judged like any conversation turn's.

    @Test fun replyShapeCompleteBindsUnderReference() = runBlocking<Unit> {
        val r = Rig(this, captions = false, native = true, fixed = 650, reference = true,
            guardFollowup = true, rejectAtLimit = true, maxDurationMs = 28_000)
        try {
            r.startPause(); r.result(.9f, 765) // COMPLETE
            // LEGACY left this shape on the fixed window plus the native
            // conjunction. REFERENCE binds the verdict at the decision point.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=smart_turn_complete") })
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun replyShapeContinueKeepsTheCaptureOpenUntilTheBackstopUnderReference() = runBlocking<Unit> {
        val r = Rig(this, captions = false, native = true, fixed = 650, reference = true,
            guardFollowup = true, rejectAtLimit = true, maxDurationMs = 28_000)
        try {
            r.startPause(); r.result(.1f, 765) // CONTINUE
            while (r.samples / 16 < 1200) r.emit(.01f)
            eventually { r.backend.calls.get() == 2 }; r.result(.1f, 1265) // CONTINUE again
            // No 650ms fixed ending and no legacy CONTINUE cap: the capture
            // stays open until the 5s turn-stop backstop.
            while (r.samples / 16 < 4000) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            while (r.samples / 16 < 5200) r.emit(.01f)
            assertNull(r.capture.endpointDecisionAtNs)
            r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(5_300_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(2, r.backend.calls.get())
            assertTrue(r.logs.any { it.contains("endpointCue=turn_stop_timeout") })
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun replyShapeWithoutAnObserverEndsOnTheReferenceSpeechTimeout() = runBlocking<Unit> {
        val r = Rig(this, captions = false, native = true, fixed = 650, available = false, reference = true,
            guardFollowup = true, rejectAtLimit = true, maxDurationMs = 28_000)
        try {
            // No analyzer attached (setting off / model unavailable / comparison
            // trial): the reference no-analyzer stop is this capture's own fixed
            // speech-timeout window, under the speech_timeout cue — LEGACY
            // recorded the same 650ms as the legacy fixed decision instead.
            r.capture.start(); repeat(3) { r.emit(.99f) }; repeat(6) { r.emit(.01f) }
            assertNull(r.capture.endpointDecisionAtNs) // 900ms wall: 600ms of silence, window is 650ms.
            r.emit(.01f)
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(1_000_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=speech_timeout") && it.contains("targetSilenceMs=650") })
            r.completeWav()
        } finally { r.close() }
    }

    @Test fun replyShapeCompleteBindsOnTheAsrRouteUnderReference() = runBlocking<Unit> {
        // The recognition shape (Whisper route and the accepted-action pump):
        // transcriber present, no fixed window, follow-up speech guard on.
        val r = Rig(this, captions = true, native = false, reference = true, guardFollowup = true)
        try {
            r.caption = "no, use the other one"
            r.startPause(); r.result(.9f, 765) // COMPLETE
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("endpointCue=smart_turn_complete") })
            r.completeWav(); assertEquals(1, r.finalCalls)
        } finally { r.close() }
    }
}
