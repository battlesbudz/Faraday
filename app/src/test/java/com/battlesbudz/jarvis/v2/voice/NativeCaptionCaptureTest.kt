package com.battlesbudz.jarvis.v2.voice

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NativeCaptionCaptureTest {
    @Test fun cleanIdleCaptionAndLegacyRouteSealIdenticalPcmCountHashGenerationAndNativeContent() = runBlocking<Unit> {
        suspend fun run(optimized: Boolean): Pair<ByteArray, AsrCaptureMetrics> {
            val decoder = CaptionDecoder()
            val encoder = RecordingEncoder()
            val generations = mutableListOf<Long>()
            val owner = RetainedPcmEncoderWorker({ generation -> generations += generation; encoder }, { true })
            val fixture = Fixture(this, decoder, allowed = { optimized }, observer = owner)
            try {
                fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
                assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
                val wav = fixture.capture.stop()
                val pcm = wav.copyOfRange(44, wav.size)
                val sealed = owner.sealAfterCaptureJoined(pcm)
                assertEquals(1L, sealed.candidate)
                assertEquals(listOf(1L), generations)
                assertEquals(pcm.size / 2, sealed.pcmSampleCount)
                assertEquals("native-content", sealed.content)
                assertEquals(1, encoder.seals)
                assertEquals(1, encoder.closes)
                assertArrayEquals(pcmToFloat(pcm), encoder.samples.toFloatArray(), 0f)
                assertEquals(if (optimized) 0 else 1, decoder.finishes)
                assertEquals(if (optimized) "" else "Verified final request", fixture.capture.finalTranscript)
                assertEquals(if (optimized) "skipped_idle_caption" else "finalized", fixture.capture.finalAsrStatus)
                assertEquals(if (optimized) "clean_native_idle" else "route_or_playback_guard", fixture.capture.captionFinalizationReason)
                assertEquals(1, decoder.closes)
                assertEquals(1, fixture.microphoneStops)
                assertEquals(1, fixture.detectorCloses)
                assertEquals(1_500_000_000L, fixture.capture.endpointDecisionAtNs)
                assertEquals(listOf("What time is it?"), fixture.partials)
                return pcm to fixture.metrics.single()
            } finally { fixture.capture.stop(); assertTrue(owner.closeAndDrain()) }
        }
        val fast = run(true); val legacy = run(false)
        assertArrayEquals(legacy.first, fast.first)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(legacy.first), MessageDigest.getInstance("SHA-256").digest(fast.first))
        assertEquals(legacy.second.endpointCue, fast.second.endpointCue)
        assertEquals(legacy.second.targetSilenceMs, fast.second.targetSilenceMs)
        assertEquals(legacy.second.endpointDetectionMs, fast.second.endpointDetectionMs)
    }

    @Test fun shortStrongSpeechRetainsFinalAsrEvenWithIdleCaption() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        try {
            fixture.capture.start(); fixture.emit(100); fixture.emit(200); fixture.emit(1400, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("short_strong_audio", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
        } finally { fixture.capture.stop() }
    }

    @Test fun authoritativeTextAsrNeverUsesCaptionCapability() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder, captionOnly = false)
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("authoritative_asr", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
            assertEquals("Verified final request", fixture.capture.finalTranscript)
        } finally { fixture.capture.stop() }
    }

    @Test fun busyOrUnsupportedCapabilityFallsBackWithoutChangingFinalRecognition() = runBlocking<Unit> {
        val decoder = CaptionDecoder(retirable = false); val fixture = Fixture(this, decoder)
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("worker_busy_or_unsupported", fixture.capture.captionFinalizationReason)
            assertEquals(1, decoder.retireAttempts); assertEquals(1, decoder.finishes)
            assertEquals("Verified final request", fixture.capture.finalTranscript)
        } finally { fixture.capture.stop() }
    }

    @Test fun quietAsrExtensionStaysGuardedAfterSustainedStrongSpeech() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        try {
            fixture.capture.start(); fixture.strongPhrase()
            fixture.emit(450, speech = false, probability = .3f, amplitude = 1800)
            fixture.emit(600, speech = false, probability = .3f, amplitude = 1800)
            fixture.emit(1800, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("quiet_asr_evidence", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
        } finally { fixture.capture.stop() }
    }

    @Test fun quietAsrOnsetRemainsGuardedEvenIfStrongSpeechFollows() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        try {
            fixture.capture.start()
            for (at in listOf(100L, 200L, 400L)) fixture.emit(at, speech = false, probability = .3f, amplitude = 1800)
            assertTrue(fixture.capture.hasSpeech)
            for (at in listOf(500L, 600L, 700L)) fixture.emit(at)
            fixture.emit(1900, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("quiet_asr_evidence", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
        } finally { fixture.capture.stop() }
    }

    @Test fun playbackTailMissingOnsetAndOverlappingPlaybackKeepTheLexicalGuard() = runBlocking<Unit> {
        val echo = FollowupPlaybackEcho()
        echo.remember("What time is it?"); echo.ended(1000)
        assertTrue(echo.needsFinalTranscript(null))
        for (onset in listOf(999L, 1000L, 1350L)) assertTrue(echo.needsFinalTranscript(onset))
        assertFalse(echo.needsFinalTranscript(1351))
        val decoder = CaptionDecoder()
        val fixture = Fixture(this, decoder, allowed = { !echo.needsFinalTranscript(it) })
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("route_or_playback_guard", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
        } finally { fixture.capture.stop() }
    }

    @Test fun incompleteAndAudioLimitCandidatesNeverRetireCaptions() = runBlocking<Unit> {
        for (rejectLimit in listOf(false, true)) {
            val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder, maxAudioMs = 200, rejectLimit = rejectLimit)
            try {
                fixture.capture.start()
                fixture.emit(100, samples = 4800)
                if (!rejectLimit) fixture.emit(1300, speech = false)
                assertTrue(fixture.capture.awaitTurnCompletion())
                assertEquals(if (rejectLimit) "endpoint_audio_input_limit" else "incomplete_audio", fixture.capture.captionFinalizationReason)
                assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
                if (rejectLimit) assertEquals("gemma_audio_request_exceeds_limit", fixture.capture.recognitionIssue)
            } finally { fixture.capture.stop() }
        }
    }

    @Test fun explicitFinishAndNoSpeechKeepExistingBehavior() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.capture.finishNow(); fixture.emit(400, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("endpoint_explicit_stop", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoder.retireAttempts); assertEquals(1, decoder.finishes)
        } finally { fixture.capture.stop() }
        val silentDecoder = CaptionDecoder(); val silent = Fixture(this, silentDecoder)
        try {
            silent.capture.start(1000); silent.emit(1100, speech = false)
            assertFalse(silent.capture.awaitTurnCompletion())
            assertEquals(0, silentDecoder.retireAttempts); assertEquals(0, silentDecoder.finishes)
            assertEquals(null, silent.capture.endpointDecisionAtNs)
        } finally { silent.capture.stop() }
    }

    @Test fun segmentedRequestRetainsFinalAndRecoveryOwnership() = runBlocking<Unit> {
        val decoders = mutableListOf<CaptionDecoder>()
        val fixture = Fixture(this, factory = { CaptionDecoder(hardLimit = 100).also(decoders::add) })
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("worker_busy_or_unsupported", fixture.capture.captionFinalizationReason)
            assertEquals(0, decoders.sumOf { it.retireAttempts })
            assertTrue(decoders.sumOf { it.finishes } > 0)
        } finally { fixture.capture.stop() }
        assertTrue(decoders.all { it.closes == 1 })
    }

    @Test fun pendingEndpointCannotRetireCaptionAfterLegacyFinalization() = runBlocking<Unit> {
        val decoder = CaptionDecoder(retirable = false)
        val fixture = Fixture(this, decoder)
        decoder.onFinish = { fixture.bufferedMs = 100 }
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            decoder.retirable = true
            decoder.onFinish = {}
            fixture.bufferedMs = 0
            fixture.emit(1600, speech = false)
            assertTrue(fixture.capture.awaitTurnCompletion())
            assertEquals("pending_endpoint", fixture.capture.captionFinalizationReason)
            assertEquals(1, decoder.retireAttempts)
            assertEquals(1, decoder.finishes) // Segmented finish is idempotent while sealed.
        } finally { fixture.capture.stop() }
    }

    @Test fun successfulRetirementFollowedByStrongOrQuietContinuationKeepsOriginalAsrOwnerAndPcm() = runBlocking<Unit> {
        for (quietTail in listOf(false, true)) {
            val decoders = mutableListOf<CaptionDecoder>()
            lateinit var fixture: Fixture
            fixture = Fixture(this, factory = {
                CaptionDecoder().also { decoder ->
                    decoders += decoder
                    decoder.onRetire = { fixture.bufferedMs = 100 }
                }
            })
            try {
                fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
                assertEquals(null, fixture.capture.endpointDecisionAtNs)
                decoders.single().onRetire = {}
                decoders.single().partial = "Please continue explaining"
                fixture.bufferedMs = 0
                fixture.emit(1600); fixture.emit(1700); fixture.emit(1800)
                if (quietTail) {
                    fixture.emit(1950, speech = false, probability = .3f, amplitude = 1800)
                    fixture.emit(2100, speech = false, probability = .3f, amplitude = 1800)
                }
                fixture.emit(if (quietTail) 3300 else 3000, speech = false)
                assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
                assertEquals("The invalidated caption proposal must not manufacture a segment", 1, decoders.size)
                assertEquals(null, fixture.capture.recognitionIssue)
                assertEquals(if (quietTail) "quiet_asr_evidence" else "clean_native_idle", fixture.capture.captionFinalizationReason)
                assertEquals(if (quietTail) 1 else 0, decoders.single().finishes)
                val pcm = fixture.capture.stop().drop(44).toByteArray()
                assertArrayEquals(pcm, decoders.single().received.toByteArray())
            } finally { fixture.capture.stop() }
        }
    }

    @Test fun successfulRetirementWithOnlyPendingSilenceRemainsExplicitlyNotFinalAsr() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        decoder.onRetire = { fixture.bufferedMs = 100 }
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            fixture.bufferedMs = 0; fixture.emit(1600, speech = false)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("skipped_idle_caption", fixture.capture.finalAsrStatus)
            assertEquals("", fixture.capture.finalTranscript)
            assertEquals(0, decoder.finishes)
        } finally { fixture.capture.stop() }
    }

    @Test fun explicitFinishAfterRetiredPendingProposalRestoresFinalAsrGuard() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        decoder.onRetire = { fixture.bufferedMs = 100 }
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            fixture.capture.finishNow(); fixture.bufferedMs = 0; fixture.emit(1600, speech = false)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("finalized", fixture.capture.finalAsrStatus)
            assertEquals("endpoint_explicit_stop", fixture.capture.captionFinalizationReason)
            assertEquals("Verified final request", fixture.capture.finalTranscript)
            assertEquals(1, decoder.finishes)
            assertArrayEquals(fixture.capture.stop().drop(44).toByteArray(), decoder.received.toByteArray())
            assertFalse(decoder.partialPermissions.last())
        } finally { fixture.capture.stop() }
    }

    @Test fun capacityAfterRetiredPendingProposalReplaysEveryPendingFrameExactlyOnce() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder, maxAudioMs = 700, rejectLimit = true)
        decoder.onRetire = { fixture.bufferedMs = 100 }
        try {
            fixture.capture.start(); fixture.strongPhrase(); fixture.emit(1500, speech = false)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            fixture.emit(1600, speech = false); fixture.emit(1700, speech = false)
            assertEquals(0, decoder.finishes)
            fixture.emit(1800, speech = false)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("gemma_audio_request_exceeds_limit", fixture.capture.recognitionIssue)
            assertEquals("endpoint_audio_input_limit", fixture.capture.captionFinalizationReason)
            assertEquals("finalized", fixture.capture.finalAsrStatus)
            assertEquals(1, decoder.finishes)
            assertEquals(700 * 32, decoder.received.size())
            assertArrayEquals(fixture.capture.stop().drop(44).toByteArray(), decoder.received.toByteArray())
            assertFalse(decoder.partialPermissions.last())
        } finally { fixture.capture.stop() }
    }

    @Test fun adaptiveCuesAndSilenceThresholdsAreIdenticalWithAndWithoutFastPath() = runBlocking<Unit> {
        for ((text, threshold, cue) in listOf(
            Triple("What time is it?", 350L, "complete_and_stable"),
            Triple("Tell me about", 1800L, "unfinished"),
            Triple("Let me think", 3500L, "explicit_hesitation"),
            Triple("A general idea", 1500L, "uncertain"),
            Triple("", 900L, "no_transcript"))) {
            for (optimized in listOf(false, true)) {
                val decoder = CaptionDecoder(partial = text)
                val fixture = Fixture(this, decoder, allowed = { optimized }, silenceMs = null)
                try {
                    fixture.capture.start(); fixture.strongPhrase()
                    fixture.emit(300 + threshold, speech = false)
                    assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
                    assertEquals(threshold, fixture.metrics.single().targetSilenceMs)
                    assertEquals(cue, fixture.metrics.single().endpointCue)
                    assertEquals((300 + threshold) * 1_000_000, fixture.capture.endpointDecisionAtNs)
                    assertEquals(if (optimized) 0 else 1, decoder.finishes)
                } finally { fixture.capture.stop() }
            }
        }
    }

    @Test fun cancelBeforeEndpointClosesWithoutFinalDecodeOrLateCaptionPublication() = runBlocking<Unit> {
        val decoder = CaptionDecoder(); val fixture = Fixture(this, decoder)
        fixture.capture.start(); fixture.strongPhrase()
        fixture.capture.stop(); fixture.capture.stop()
        assertEquals(0, decoder.finishes); assertEquals(0, decoder.retireAttempts)
        assertEquals(1, decoder.closes)
        assertEquals(listOf("What time is it?"), fixture.partials)
        assertEquals("", fixture.capture.finalTranscript)
        assertEquals(null, fixture.capture.endpointDecisionAtNs)
    }

    private class RecordingEncoder : RetainedPcmEncoderWorker.Encoder<String> {
        val samples = mutableListOf<Float>()
        var seals = 0; var closes = 0
        override fun append(pcm: FloatArray) { samples.addAll(pcm.toList()) }
        override fun seal(): RetainedPcmEncoderWorker.Sealed<String> {
            seals++; return RetainedPcmEncoderWorker.Sealed(samples.size, "native-content")
        }
        override fun requestCancel() = Unit
        override fun closeOnWorker(timeoutMs: Long): Boolean { closes++; return true }
    }
    private fun pcmToFloat(pcm: ByteArray) = FloatArray(pcm.size / 2) { i ->
        ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
    }
    private class CaptionDecoder(
        var retirable: Boolean = true,
        var partial: String = "What time is it?",
        val hardLimit: Long = 22_000
    ) : StreamingTranscriber {
        var retireAttempts = 0; var finishes = 0; var closes = 0
        var onFinish: () -> Unit = {}
        var onRetire: () -> Unit = {}
        val received = java.io.ByteArrayOutputStream()
        val partialPermissions = mutableListOf<Boolean>()
        override val noTextSilenceMs get() = 900L
        override val segmentHardLimitMs get() = hardLimit
        override fun accept(pcm: ByteArray): String = accept(pcm, true)
        override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
            received.write(pcm); partialPermissions += allowPartial; return partial
        }
        override fun retireIdleCaption(): Boolean { retireAttempts++; if (retirable) onRetire(); return retirable }
        override fun resumeRetiredCaption(): Boolean = true
        override fun finish(): String { finishes++; onFinish(); return "Verified final request" }
        override fun close() { closes++ }
    }
    private class Fixture(
        scope: CoroutineScope,
        decoder: StreamingTranscriber? = null,
        factory: () -> StreamingTranscriber = { requireNotNull(decoder) },
        allowed: (Long?) -> Boolean = { true },
        captionOnly: Boolean = true,
        silenceMs: Long? = 1200,
        maxAudioMs: Int = 28_000,
        rejectLimit: Boolean = false,
        observer: RetainedPcmObserver? = null,
    ) {
        var clock = 0L
        var bufferedMs = 0L
        var microphoneStops = 0
        var detectorCloses = 0
        var speechDecision = SpeechDecision(false, .01f)
        private val chunks = MutableSharedFlow<ByteArray>()
        val partials = mutableListOf<String>()
        val metrics = mutableListOf<AsrCaptureMetrics>()
        private val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override val bufferedAudioMs get() = bufferedMs
            override fun chunks() = chunks
            override suspend fun start() = Unit
            override suspend fun stop() { microphoneStops++ }
        }
        val capture = AudioTurnCapture(input, scope,
            createDetector = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = speechDecision
                override fun close() { detectorCloses++ }
            } }, nowMs = { clock }, nowNs = { clock * 1_000_000 },
            captureDispatcher = Dispatchers.Unconfined, createTranscriber = factory,
            onPartialTranscript = partials::add, onMetrics = { metric, _ -> metrics += metric },
            trailingSilenceMs = silenceMs, allowAudioOnlyTurns = true, captionOnly = captionOnly,
            maxAudioDurationMs = maxAudioMs, rejectAtAudioLimit = rejectLimit,
            retainedPcmObserver = observer, canRetireIdleCaption = allowed)
        suspend fun strongPhrase() { emit(100); emit(200); emit(300) }
        suspend fun emit(at: Long, speech: Boolean = true, probability: Float = if (speech) .95f else .01f,
                         amplitude: Int = if (speech) 1800 else 0, samples: Int = 1600) {
            clock = at; speechDecision = SpeechDecision(speech, probability)
            chunks.emit(ByteArray(samples * 2) { i -> if (i % 2 == 0) amplitude.toByte() else (amplitude shr 8).toByte() })
            yield()
        }
    }
}
