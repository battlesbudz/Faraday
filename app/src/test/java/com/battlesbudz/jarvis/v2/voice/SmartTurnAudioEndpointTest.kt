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
                      segmentSoftMs: Long = 15_000, segmentHardMs: Long = 22_000) {
        val wall = AtomicLong(); var samples = 0L; var acoustic = .99f; var captureTimeGapMs = 0L
        var riskyTailSamples = 0; var current = true; var routeAllowed = true; var blocker: String? = null
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
            createTranscriber = if (!captions) null else { { object : StreamingTranscriber {
                override val segmentSoftLimitMs = segmentSoftMs
                override val segmentHardLimitMs = segmentHardMs
                override fun accept(pcm: ByteArray) = caption
                override fun finish(): String { finalCalls++; finishHook?.invoke(); return finalCaption ?: caption }
                override fun recover(pcm: ByteArray): String { recoveredPcm = pcm.copyOf(); recoverHook?.invoke(); return recoveryText }
                override fun close() { captionClosed = true }
            } } },
            canUseSmartTurn = { routeAllowed }, shadowObserver = observer.takeIf { available },
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

    @Test fun whisperSmartCompleteSealsTurnDespiteAudioArrivingDuringSeamRecovery() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800, segmentHardMs = 500)
        try {
            r.caption = "Please tell me the weather"
            r.capture.start(); repeat(5) { r.emit(.99f) }
            r.caption = "A conflicting seam."
            r.recoveryText = "Please tell me the weather."
            r.recoverHook = { r.hardwareBacklog = 100; r.recoverHook = null }
            repeat(4) { r.emit(.01f) }; eventually { r.backend.calls.get() == 1 }; r.result(.9f, 965)
            eventually { r.recoveredPcm != null }
            // Seam recovery still runs and its text stays authoritative, but audio
            // that arrived during it no longer re-opens a Smart-Turn-decided endpoint.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(965_000_000L, r.capture.endpointDecisionAtNs)
            assertFalse(r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_recovery") })
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

    @Test fun whisperSmartCompleteSealsTurnDespiteAudioArrivingDuringBlankFinalRetry() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "What is the weather today?"
            r.finalCaption = ""
            r.recoveryText = r.caption
            r.recoverHook = { r.hardwareBacklog = 100; r.recoverHook = null }
            r.startPause(); r.result(.9f, 765)
            // The blank-final recovery still supplies the request text, but audio
            // that arrived during it no longer re-opens a Smart-Turn-decided endpoint.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertTrue(r.logs.any { it.contains("reason=empty_stream") })
            assertFalse(r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_recovery") })
            r.completeWav()
            assertEquals(r.recoveryText, r.capture.finalTranscript)
            assertArrayEquals(r.original.toByteArray(), r.recoveredPcm)
            assertNull(r.capture.recognitionIssue)
        } finally { r.close() }
    }

    @Test fun whisperSmartCompleteSealsTurnDespiteAudioArrivingDuringFinalization() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "Please tell me the weather."
            r.finishHook = { r.hardwareBacklog = 100; r.finishHook = null }
            r.startPause(); r.result(.9f, 765)
            // The finalization drain no longer defers a Smart-Turn-decided endpoint:
            // audio that arrives during finalization belongs to the next turn.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            assertEquals(1, r.finalCalls)
            assertEquals("Please tell me the weather.", r.capture.finalTranscript)
            assertEquals("finalized", r.capture.finalAsrStatus)
            assertFalse(r.logs.any { it.contains("turn_endpoint_deferred reason=audio_arrived_during_finalization") })
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

    @Test fun whisperCompleteBindsWithANonEmptyRecognitionQueue() = runBlocking<Unit> {
        val r = Rig(this, captions = true, native = false, fixed = 1800)
        try {
            r.caption = "What is the weather today?"
            r.startPause()
            r.hardwareBacklog = 100 // The recognition queue is not drained when the verdict lands.
            r.result(.9f, 765)
            // Finalization drains the queue after acceptance, as on no-verdict turns;
            // the backlog no longer vetoes the verdict at the fire condition.
            eventually { r.capture.endpointDecisionAtNs != null }
            assertEquals(765_000_000L, r.capture.endpointDecisionAtNs)
            r.hardwareBacklog = 0
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
}
