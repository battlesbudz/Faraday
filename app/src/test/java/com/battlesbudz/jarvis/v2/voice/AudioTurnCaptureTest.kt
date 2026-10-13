package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTurnCaptureTest {
    @Test fun endpointTimingStartsAtAcceptedProposalBeforeFinalization() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        val recognizer = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Hello"
            override fun finish(): String { fixture.spend(400); return "Hello" }
            override fun close() = Unit
        }
        fixture = CaptureFixture(this, recognizer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            fixture.emit(1400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals(1_400_000_000L, fixture.capture.endpointDecisionAtNs)
            assertEquals(100L, fixture.capture.lastSpeechAtMs)
            assertEquals(1600, fixture.capture.retainedPreRollSampleCount)
        } finally { fixture.capture.stop() }
    }

    @Test fun nonverbalWhisperCaptionCannotDiscardConfirmedNativeAudio() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("[music]", "[music]"),
            allowAudioOnlyTurns = true, captionOnly = true)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            fixture.emit(1400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertTrue(fixture.capture.hasSpeech)
            assertTrue(fixture.capture.audioIsComplete)
            assertEquals("", fixture.capture.finalTranscript)
            assertEquals(44 + 6400, fixture.capture.stop().size)
            assertFalse(fixture.events.any { it.startsWith("empty_speech_candidate") })
        } finally { fixture.capture.stop() }
    }

    @Test fun nativeAudioLimitRejectsWholeRequestInsteadOfAnsweringPrefix() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("open settings", "open settings"),
            allowAudioOnlyTurns = true, maxAudioDurationMs = 200, rejectAtAudioLimit = true)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            fixture.emit(200, 1800, speech = true, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("gemma_audio_request_exceeds_limit", fixture.capture.recognitionIssue)
            assertEquals("gemma_audio_request_exceeds_limit", GemmaAudioInputPolicy.retainedAudioIssue(
                fixture.capture.recognitionIssue, true, fixture.capture.audioIsComplete, fixture.capture.stop().size))
            assertEquals(100L, fixture.capture.firstSpeechCaptureAtMs)
        } finally { fixture.capture.stop() }
    }
    @Test fun shortNativeAudioNeedsNoCaptionRecognizerOrThirtySecondWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            maxAudioDurationMs = 28000, rejectAtAudioLimit = true)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            fixture.emit(1400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("", fixture.capture.finalTranscript)
            assertTrue(fixture.capture.audioIsComplete)
            assertEquals(null, fixture.capture.recognitionIssue)
            assertEquals(44 + 6400, fixture.capture.stop().size)
        } finally { fixture.capture.stop() }
    }

    @Test fun acousticallyConfirmedShortCorrectionSurvivesFinalOnlyRecognition() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("", "No"), allowAudioOnlyTurns = true,
            guardFollowupSpeech = true, initialConfirmedSpeech = { "No" })
        fixture.capture.start()
        try {
            val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
            fixture.emit(100, 1500, speech = true, samples = 1600)
            fixture.emit(1400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { completion.await() })
            assertEquals("No", fixture.capture.finalTranscript)
        } finally { fixture.capture.stop() }
    }
    @Test fun confirmedProbeTextWithoutSpeechCannotSubmitARequest() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("", "Thank you"), allowAudioOnlyTurns = true,
            guardFollowupSpeech = true, initialConfirmedSpeech = { "Thank you" })
        fixture.capture.start(initialSilenceTimeoutMs = 1000)
        try {
            val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
            fixture.emit(1100, 0, samples = 1600)
            assertFalse(withTimeout(1000) { completion.await() })
            assertEquals("", fixture.capture.finalTranscript)
        } finally { fixture.capture.stop() }
    }
    @Test fun isolatedFollowupSoundCannotSubmitHallucinatedThanksAndNextSpeechSurvives() = runBlocking<Unit> {
        val noise = FakeTranscriber("", "Thank you.")
        val real = FakeTranscriber("Yes", "Yes")
        var loads = 0
        val fixture = CaptureFixture(this, factory = { if (loads++ == 0) noise else real },
            allowAudioOnlyTurns = true, guardFollowupSpeech = true)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 1500, speech = true, samples = 1600)
        fixture.emit(1400, 0, samples = 1600)
        assertFalse(completion.isCompleted)
        assertEquals(0, noise.finishes)
        assertTrue(fixture.events.any { it.startsWith("followup_candidate_rejected") })
        fixture.emit(1600, 2000, speech = true, samples = 1600)
        fixture.emit(2900, 0, samples = 1600)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("Yes", fixture.capture.finalTranscript)
        assertEquals(1, fixture.microphoneStarts)
        fixture.capture.stop()
    }

    @Test fun soundCaptionsCannotExtendTheInitialListeningDeadline() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, factory = { FakeTranscriber("[music]", "[music]") }, allowAudioOnlyTurns = true)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(19500, 2000, speech = true)
        fixture.emit(20700, 0)
        assertFalse(withTimeout(1000) { completion.await() })
        assertEquals("", fixture.capture.finalTranscript)
        fixture.capture.stop()
    }

    @Test fun soundCaptionKeepsListeningEvenWhenAudioFallbackIsAllowed() = runBlocking<Unit> {
        val noise = FakeTranscriber("(crying)", "(crying).")
        val owner = FakeTranscriber("Can you hear me?", "Can you hear me?")
        var loads = 0
        val fixture = CaptureFixture(this, factory = { if (loads++ == 0) noise else owner }, allowAudioOnlyTurns = true)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        assertEquals(0, noise.recoveries)
        assertTrue(fixture.partials.none { it.contains("crying") })
        assertTrue(fixture.events.any { it.startsWith("nonverbal_candidate") })
        fixture.emit(1500, 2000, speech = true)
        fixture.emit(2700, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("Can you hear me?", fixture.capture.finalTranscript)
        assertEquals(1, fixture.microphoneStarts)
        fixture.capture.stop()
    }

    @Test fun whisperWithoutStableWordsDoesNotWaitThreeSeconds() = runBlocking<Unit> {
        val transcriber = object : StreamingTranscriber {
            override val noTextSilenceMs = 900L
            override fun accept(pcm: ByteArray) = ""
            override fun finish() = "Can you hear me?"
            override fun close() = Unit
        }
        val fixture = CaptureFixture(this, transcriber, trailingSilenceMs = null)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(999, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(1000, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals(900L, fixture.metrics.single().first.endpointDetectionMs)
        fixture.capture.stop()
    }
    @Test fun adaptiveQuestionEndsAfterShortStablePause() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("What is the current volume?", "What is the current volume?"),
            trailingSilenceMs = null)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(449, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(450, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("complete_and_stable", fixture.metrics.single().first.endpointCue)
        assertEquals(350L, fixture.metrics.single().first.endpointDetectionMs)
        fixture.capture.stop()
    }

    @Test fun adaptiveHesitationKeepsListeningAndAcousticContinuationInvalidatesDraft() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("Can you tell me", "Can you tell me a story"),
            trailingSilenceMs = null)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1100, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(1600, 2000, speech = true)
        assertEquals(1, fixture.resumed)
        fixture.emit(3399, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(3400, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test fun queuedMicrophoneAudioPreventsAcceptingAnOldPause() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("What is the current volume?", "What is the current volume?"),
            trailingSilenceMs = null)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.bufferedMs = 200
        fixture.emit(1000, 0)
        assertFalse(completion.isCompleted)
        fixture.bufferedMs = 0
        fixture.emit(1100, 2000, speech = true)
        fixture.emit(1449, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(1450, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun microphoneStartsBeforeModelsAndBufferedOpeningAudioReachesAsr() = runBlocking<Unit> {
        val chunks = kotlinx.coroutines.channels.Channel<ByteArray>(4)
        var microphoneStarted = false
        val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = kotlinx.coroutines.flow.flow { for (chunk in chunks) emit(chunk) }
            override suspend fun start() {
                microphoneStarted = true
                chunks.send(byteArrayOf(111, 0))
            }
            override suspend fun stop() { chunks.close() }
        }
        val transcriber = FakeTranscriber()
        val capture = AudioTurnCapture(input, this, captureDispatcher = Dispatchers.Unconfined,
            createDetector = { assertTrue(microphoneStarted); FakeDetector() },
            createTranscriber = { assertTrue(microphoneStarted); transcriber })
        capture.start()
        kotlinx.coroutines.yield()
        assertEquals(listOf(111), transcriber.receivedSamples)
        capture.stop()
    }

    @Test
    fun stopReturnsWavAndDoesNotPersistRawAudio() = runBlocking<Unit> {
        val chunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
        val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = chunks
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Unconfined), captureDispatcher = Dispatchers.Unconfined, createDetector = { FakeDetector() })
        capture.start()
        chunks.tryEmit(byteArrayOf(1, 2, 3, 4))
        val wav = capture.stop()
        assertEquals(48, wav.size)
        assertTrue(String(wav, 0, 4) == "RIFF")
    }

    @Test
    fun speechFollowedByQuietNegativeNoiseEndsAfterTwelveHundredMs() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1299, -1)
        assertFalse(completion.isCompleted)
        fixture.emit(1300, -1)
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.events.any { "reason=trailing_silence" in it })
        fixture.capture.stop()
    }

    @Test
    fun resumedSpeechRestartsTheTrailingSilenceWindow() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(100, -2000, speech = true)
        fixture.emit(1100, -1)
        fixture.emit(1200, 2000, speech = true)
        fixture.emit(2399, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(2400, -100)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun initialQuietNoiseTimesOutWithoutSpeech() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(6000, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(19999, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(20000, -100)
        assertFalse(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun followUpCanWaitOverTenSecondsThenEndNormallyAfterSpeech() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(11000, -1)
        assertFalse(completion.isCompleted)
        fixture.emit(12000, -2000, speech = true)
        fixture.emit(13200, -1)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun stoppingCaptureCancelsPendingEndpointWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.capture.stop()
        withTimeout(1000) { completion.join() }
        assertTrue(completion.isCancelled)
    }

    @Test
    fun loudNoiseAfterSpeechDoesNotResetTheSilenceTimer() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1100, -4314)
        assertFalse(completion.isCompleted)
        fixture.emit(1300, 4213)
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test
    fun loudNoiseWithoutSpeechIsNotSubmittedAsAUserTurn() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(1000, 4300)
        fixture.emit(20000, -3300)
        assertFalse(withTimeout(1000) { completion.await() })
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test
    fun longIdleCaptureKeepsOnlyTwelveHundredMsOfPreRoll() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        repeat(60) { fixture.emit((it + 1) * 1000L, 3000, samples = 16_000) }
        val wav = fixture.capture.stop()
        assertEquals(44 + 38_400, wav.size)
        assertEquals(1, fixture.detector.releases)
        fixture.capture.stop()
        assertEquals(1, fixture.detector.releases)
    }

    @Test
    fun continuousSpeechPassesTwentyFiveSecondsWhileRawAudioStaysBounded() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        repeat(260) { fixture.emit((it + 1) * 100L, 2000, speech = true, samples = 1600) }
        assertFalse(completion.isCompleted)
        assertFalse(fixture.capture.audioIsComplete)
        fixture.emit(27200, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.events.any { "reason=trailing_silence" in it })
        assertEquals(44 + 800_000, fixture.capture.stop().size)
    }

    @Test
    fun detectorFailureReachesOwnerAndCanBeCleanedUp() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        fixture.detector.fail = true
        fixture.emit(100, 2000)
        val error = runCatching { fixture.capture.awaitTurnCompletion() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        fixture.capture.stop()
        assertEquals(1, fixture.detector.releases)
    }

    @Test
    fun streamsProvisionalWordsAndSealsTheFlushedFinalTranscript() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        assertEquals(listOf("story about pirates"), fixture.partials)
        assertEquals("", fixture.capture.finalTranscript)
        fixture.emit(1300, -1)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals("story about astronauts", fixture.capture.finalTranscript)
        assertEquals(listOf("story about pirates"), fixture.partials)
        fixture.capture.stop()
        assertEquals(1, transcriber.finishes)
        assertEquals(0, transcriber.recoveries)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun explicitStopDoesNotFinalizeOrSubmitAsr() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.stop()
        assertEquals(0, transcriber.finishes)
        assertEquals(0, transcriber.recoveries)
        assertEquals("", fixture.capture.finalTranscript)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun noiseOnlyIsNotPublishedOrFinalized() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(20000, 4300)
        assertFalse(fixture.capture.awaitTurnCompletion())
        fixture.capture.stop()
        assertTrue(fixture.partials.isEmpty())
        assertEquals(1, transcriber.accepts)
        assertEquals(0, transcriber.finishes)
    }

    @Test
    fun recognizerReceivesOpeningAudioBeforeVadConfirmsSpeech() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 111)
        fixture.emit(900, 222)
        assertTrue(fixture.partials.isEmpty())
        fixture.emit(1000, 333, speech = true)
        assertEquals(listOf(111, 222, 333), transcriber.receivedSamples)
        assertTrue(fixture.partials.isNotEmpty())
        fixture.capture.stop()
    }

    @Test
    fun reportsAsrMeasurementsWithoutCountingFinalFlushAsPartial() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber())
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true, samples = 1600)
        fixture.emit(1300, 0, samples = 1600)
        assertTrue(fixture.capture.awaitTurnCompletion())
        val result = fixture.metrics.single()
        assertEquals("story about astronauts", result.second)
        assertEquals(200L, result.first.audioMs)
        assertEquals(1, result.first.partialUpdates)
        assertEquals(0L, result.first.firstPartialAfterSpeechMs)
        assertEquals("trailing_silence", result.first.endpointReason)
        fixture.capture.stop()
        assertEquals(1, fixture.metrics.size)
    }

    @Test
    fun cancellationDoesNotPublishACompletedComparison() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber())
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.stop()
        assertTrue(fixture.metrics.isEmpty())
    }

    @Test
    fun emptyPostPlaybackDetectionKeepsMicrophoneOpenAndAcceptsLaterSpeech() = runBlocking<Unit> {
        var created = 0
        val replacement = FakeTranscriber()
        val fixture = CaptureFixture(this, factory = {
            if (created++ == 0) FakeTranscriber("", "") else replacement
        })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        assertEquals(1, fixture.microphoneStarts)
        assertEquals(0, fixture.microphoneStops)
        assertEquals(2, created)
        assertEquals(listOf(2000), replacement.receivedSamples) // The pre-reset tail is replayed.
        fixture.emit(10000, 2000, speech = true)
        fixture.emit(11200, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("story about astronauts", fixture.capture.finalTranscript)
        assertEquals(1, fixture.metrics.single().first.emptyCandidates)
        fixture.capture.stop()
        assertEquals(1, fixture.microphoneStops)
    }

    @Test
    fun emptyStreamRetriesCapturedSpeechBeforeDiscardingIt() = runBlocking<Unit> {
        val transcriber = FakeTranscriber("", "", "can you hear me now")
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 111, samples = 1600)
        fixture.emit(200, 222, speech = true, samples = 1600)
        fixture.emit(1400, 0, samples = 1600)
        assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
        assertEquals("can you hear me now", fixture.capture.finalTranscript)
        assertTrue(fixture.partials.isEmpty())
        assertEquals(1, transcriber.recoveries)
        assertEquals(9600, transcriber.recoveredPcm.size)
        assertEquals(111.toByte(), transcriber.recoveredPcm[0])
        assertEquals(222.toByte(), transcriber.recoveredPcm[3200])
        assertEquals(0, fixture.metrics.single().first.emptyCandidates)
        assertEquals(1, fixture.microphoneStarts)
        assertEquals(listOf(true, false), fixture.recoveryStates)
        fixture.capture.stop()
    }

    @Test
    fun emptyBatchRetryKeepsListeningWithoutSubmittingNoise() = runBlocking<Unit> {
        val first = FakeTranscriber("", "", "")
        var created = 0
        val fixture = CaptureFixture(this, factory = { if (created++ == 0) first else FakeTranscriber() })
        fixture.capture.start()
        fixture.emit(100, 1000, speech = true)
        fixture.emit(1300, 0)
        assertEquals(1, first.recoveries)
        assertEquals(2, created)
        assertFalse(fixture.capture.hasSpeech)
        assertTrue(fixture.partials.isEmpty())
        assertEquals(listOf(true, false), fixture.recoveryStates)
        fixture.emit(2000, 2000, speech = true)
        fixture.emit(3200, 0)
        assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
        fixture.capture.stop()
    }

    @Test
    fun detectedSpeechRestartsInactivityEvenWhenRecognitionIsEmpty() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, factory = { FakeTranscriber("", "") })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        for (start in listOf(100L, 6000L, 12000L)) {
            fixture.emit(start, 2000, speech = true)
            fixture.emit(start + 1200, 0)
            assertFalse(completion.isCompleted)
        }
        fixture.emit(19999, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(20000, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(31999, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(32000, 0)
        assertFalse(withTimeout(1000) { completion.await() })
        assertEquals(3, fixture.metrics.single().first.emptyCandidates)
        assertEquals("initial_silence", fixture.metrics.single().first.endpointReason)
        fixture.capture.stop()
    }

    @Test
    fun thinkingPauseDoesNotFinishTurnAndResumedSpeechRestartsThreeSecondWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber(), trailingSilenceMs = VoiceCallPolicy.TURN_SILENCE_MS)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(2600, 2000, speech = true)
        fixture.emit(5599, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(5600, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun microphoneInterruptionEndsOnlyCaptureWithoutSubmittingPartialSpeech() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.yieldMicrophone()
        val error = runCatching { fixture.capture.awaitTurnCompletion() }.exceptionOrNull()
        assertTrue(error is MicrophoneBusyException)
        fixture.capture.stop()
        assertEquals(0, transcriber.finishes)
        assertEquals(0, transcriber.recoveries)
        assertEquals("", fixture.capture.finalTranscript)
        assertTrue(fixture.metrics.isEmpty())
        assertEquals(1, fixture.microphoneStops)
    }

    @Test fun isolatedVadWithTextWaitsForConfirmationAndPreservesOpeningWord() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("open", "open YouTube"))
        fixture.capture.start()
        fixture.emit(100, 1234, speech = false, probability = 0.8f, samples = 1024)
        assertFalse(fixture.capture.hasSpeech)
        fixture.emit(200, 1234, speech = true, samples = 512)
        assertTrue(fixture.capture.hasSpeech)
        fixture.emit(1500, 0)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals("open YouTube", fixture.capture.finalTranscript)
        val wav = fixture.capture.stop()
        assertEquals(1234.toByte(), wav[44])
    }

    @Test fun textWithoutSpeechEvidenceCannotPromoteNoiseToACommand() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("open YouTube", "open YouTube"))
        fixture.capture.start()
        fixture.emit(100, 3000, speech = false, probability = 0.1f)
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test fun fallbackRetainsWordsEarlierThanVadPreRoll() = runBlocking<Unit> {
        val asr = FakeTranscriber("", "", "open YouTube")
        val fixture = CaptureFixture(this, asr)
        fixture.capture.start()
        fixture.emit(100, 111, samples = 1600)
        repeat(15) { fixture.emit(200 + it * 100L, 0, samples = 1600) }
        fixture.emit(1800, 222, speech = true, samples = 1600)
        fixture.emit(3100, 0, samples = 1600)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals(111.toByte(), asr.recoveredPcm[0])
        assertEquals("open YouTube", fixture.capture.finalTranscript)
        fixture.capture.stop()
    }

    @Test fun audioModelReceivesConfirmedSpeechWhenAsrIsEmpty() = runBlocking<Unit> {
        val asr = FakeTranscriber("", "")
        val fixture = CaptureFixture(this, asr, allowAudioOnlyTurns = true)
        fixture.capture.start()
        fixture.emit(100, 1234, speech = true, samples = 1600)
        fixture.emit(1300, 0, samples = 1600)
        assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
        assertTrue(fixture.capture.hasSpeech)
        assertEquals("", fixture.capture.finalTranscript)
        assertEquals(0, asr.recoveries)
        val wav = fixture.capture.stop()
        assertEquals(44 + 6400, wav.size)
        assertEquals(1234.toByte(), wav[44])
        assertTrue(fixture.events.any { "destination=gemma" in it })
    }

    @Test fun audioModelDoesNotReceiveSilenceOnlyCapture() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("", ""), allowAudioOnlyTurns = true)
        fixture.capture.start()
        fixture.emit(20000, 100)
        assertFalse(fixture.capture.awaitTurnCompletion())
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test fun speechArrivingDuringFinalizationInvalidatesEndpoint() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        var finishes = 0
        var loads = 0
        val first = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Open Facebook"
            override fun finish(): String { finishes++; fixture.bufferedMs = 100; return "Open Facebook" }
            override fun close() {}
        }
        fixture = CaptureFixture(this, factory = {
            if (loads++ == 0) first else FakeTranscriber("actually use YouTube", "actually use YouTube")
        })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        fixture.bufferedMs = 0
        fixture.emit(1400, 2000, speech = true)
        assertFalse(completion.isCompleted)
        fixture.emit(2600, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("Open Facebook actually use YouTube", fixture.capture.finalTranscript)
        assertEquals(1, finishes)
        assertTrue(fixture.events.any { "turn_endpoint_invalidated" in it })
        fixture.capture.stop()
    }

    @Test fun silenceArrivingDuringFinalizationDoesNotFinalizeTwice() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        var finishes = 0
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Tell me a story"
            override fun finish(): String { finishes++; fixture.bufferedMs = 100; return "Tell me a story" }
            override fun close() {}
        }
        fixture = CaptureFixture(this, asr)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        fixture.bufferedMs = 0
        fixture.emit(1400, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals(1, finishes)
        assertEquals("Tell me a story", fixture.capture.finalTranscript)
        fixture.capture.stop()
    }

    @Test fun expensivePartialDefersOptionalWorkWithoutLosingPcmOrDelayingFinalization() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        val policies = mutableListOf<Boolean>()
        var bytes = 0
        var finishes = 0
        val recognizer = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray): String = error("policy required")
            override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
                bytes += pcm.size
                policies += allowPartial
                if (allowPartial) fixture.spend(1000)
                return "What time is it?"
            }
            override fun finish(): String { finishes++; return "What time is it?" }
            override fun close() {}
        }
        fixture = CaptureFixture(this, recognizer)
        fixture.capture.start()
        try {
            fixture.emit(100, 2000, speech = true, samples = 1600)
            fixture.emit(1200, 2000, speech = true, samples = 1600)
            fixture.emit(2400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals(listOf(true, false, false), policies)
            assertEquals(9600, bytes)
            assertEquals(1, finishes)
            assertEquals("What time is it?", fixture.capture.finalTranscript)
        } finally { fixture.capture.stop() }
    }

    @Test fun fullRecordingRecoveryCanRescueAnEmptyTailButCannotClearFailureWithBlankText() = runBlocking<Unit> {
        for (recovered in listOf("What is the capital of Idaho?", "")) {
            lateinit var fixture: CaptureFixture
            var loads = 0
            var recoveredBytes = 0
            fixture = CaptureFixture(this, factory = {
                val first = loads++ == 0
                object : StreamingTranscriber {
                    override fun accept(pcm: ByteArray) = if (first) "What is the capital of Idaho?" else ""
                    override fun finish(): String {
                        if (first) fixture.bufferedMs = 200
                        return if (first) "What is the capital of Idaho?" else ""
                    }
                    override fun recover(pcm: ByteArray): String { recoveredBytes = pcm.size; return recovered }
                    override fun close() {}
                }
            })
            fixture.capture.start()
            try {
                fixture.emit(100, 2000, speech = true, samples = 1600)
                fixture.emit(1300, 0, samples = 1600)
                fixture.bufferedMs = 0
                fixture.emit(1400, 2000, speech = true, samples = 1600)
                fixture.emit(2700, 0, samples = 1600)
                assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
                assertEquals(12800, recoveredBytes)
                assertEquals(if (recovered.isBlank()) "unrecognized_segment" else null, fixture.capture.recognitionIssue)
                assertEquals("What is the capital of Idaho?", fixture.capture.finalTranscript)
            } finally { fixture.capture.stop() }
        }
    }

    @Test fun roomLevelVadTailCannotReopenACompletedWhisperOrMoonshineTurn() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        var loads = 0
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "What is the capital of Idaho?"
            override fun finish(): String { fixture.bufferedMs = 300; return "What is the capital of Idaho?" }
            override fun close() {}
        }
        fixture = CaptureFixture(this, factory = { loads++; asr })
        fixture.capture.start()
        try {
            repeat(3) { fixture.emit(it * 100L, 131, samples = 1600) }
            fixture.emit(400, 2000, speech = true, samples = 1600)
            fixture.emit(9000, 2000, speech = true, samples = 1600)
            fixture.emit(10300, 131, samples = 1600)
            fixture.bufferedMs = 200
            fixture.emit(10400, 132, speech = true, probability = .806f, samples = 1600)
            fixture.bufferedMs = 100
            fixture.emit(10500, 141, speech = true, probability = .596f, samples = 1600)
            fixture.bufferedMs = 0
            fixture.emit(10600, 130, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals(1, loads)
            assertEquals(null, fixture.capture.recognitionIssue)
            assertEquals("What is the capital of Idaho?", fixture.capture.finalTranscript)
            assertFalse(fixture.events.any { "turn_endpoint_invalidated" in it })
        } finally { fixture.capture.stop() }
    }

    @Test fun nearFloorVadTailCannotKeepAnUtteranceAlive() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("What time is it?", "What time is it?"))
        fixture.capture.start()
        try {
            fixture.emit(0, 29, samples = 1600)
            fixture.emit(100, 29, samples = 1600)
            fixture.emit(200, 29, samples = 1600)
            fixture.emit(300, 2000, speech = true, samples = 1600)
            fixture.emit(600, 44, speech = true, probability = 0.525f, samples = 1600)
            fixture.emit(1000, 44, speech = true, probability = 0.525f, samples = 1600)
            fixture.emit(1500, 44, speech = true, probability = 0.525f, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals(300L, fixture.capture.lastSpeechAtMs)
            assertTrue(fixture.events.any { it.startsWith("capture_weak_noise_rejected") })
        } finally { fixture.capture.stop() }
    }

    @Test fun isolatedVadSpikeDuringFinalizationDoesNotCreateRepeatedSegment() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        var loads = 0
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Open YouTube"
            override fun finish(): String { fixture.bufferedMs = 100; return "Open YouTube" }
            override fun close() {}
        }
        fixture = CaptureFixture(this, factory = { loads++; asr })
        fixture.capture.start()
        try {
            fixture.emit(100, 2000, speech = true)
            fixture.emit(1300, 0)
            fixture.bufferedMs = 0
            fixture.emit(1400, 1000, speech = false, probability = 0.7f)
            fixture.emit(1500, 0)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertEquals("Open YouTube", fixture.capture.finalTranscript)
            assertEquals(1, loads)
            assertFalse(fixture.events.any { "turn_endpoint_invalidated" in it })
        } finally { fixture.capture.stop() }
    }

    @Test fun genuineOnsetDuringFinalizationGetsConfirmationAndPreservesPreRoll() = runBlocking<Unit> {
        lateinit var fixture: CaptureFixture
        var loads = 0
        val first = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = "Open Facebook"
            override fun finish(): String { fixture.bufferedMs = 100; return "Open Facebook" }
            override fun close() {}
        }
        val next = FakeTranscriber("actually YouTube", "actually YouTube")
        fixture = CaptureFixture(this, factory = { if (loads++ == 0) first else next })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        try {
            fixture.emit(100, 2000, speech = true)
            fixture.emit(1300, 0)
            fixture.bufferedMs = 0
            fixture.emit(1400, 1234, probability = 0.7f, samples = 512)
            assertFalse(completion.isCompleted)
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            fixture.emit(1464, 2000, speech = true, samples = 1024)
            fixture.emit(2664, 0)
            assertTrue(withTimeout(1000) { completion.await() })
            assertEquals("Open Facebook actually YouTube", fixture.capture.finalTranscript)
            assertEquals(1234, next.receivedSamples.first())
            assertEquals(2_664_000_000L, fixture.capture.endpointDecisionAtNs)
        } finally { fixture.capture.stop() }
    }

    @Test fun retainedPcmIncludesAcceptedPreRollOnceAndMatchesJoinedWav() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            repeat(15) { fixture.emit((it + 1) * 100L, it + 1, samples = 1600) }
            assertTrue(observer.packets.isEmpty())
            fixture.emit(1600, 1900, speech = true, samples = 1600)
            assertEquals(38_400, observer.packets.single().size)
            assertEquals(19_200, fixture.capture.retainedPreRollSampleCount)
            fixture.emit(2900, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            val wav = fixture.capture.stop()
            assertEquals(2, observer.packets.size)
            assertArrayEquals(wav.copyOfRange(44, wav.size), observer.bytes())
            assertEquals(0, observer.discards)
            assertTrue(observer.invalidations.isEmpty())
        } finally { fixture.capture.stop() }
    }

    @Test fun observerReceivesPrivatePcmCopiesAndCannotMutateTheRetainedRequest() = runBlocking<Unit> {
        val observer = RecordingPcmObserver(mutateReceived = true)
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1900, speech = true, samples = 1600)
            fixture.emit(1400, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            val wav = fixture.capture.stop()
            assertArrayEquals(wav.copyOfRange(44, wav.size), observer.bytes())
            assertEquals(1900.toByte(), wav[44])
        } finally { fixture.capture.stop() }
    }

    @Test fun discardedCandidateInvalidatesItsStreamBeforeReplacementPreRoll() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        var loads = 0
        val fixture = CaptureFixture(this,
            factory = { if (loads++ == 0) FakeTranscriber("", "Thank you") else FakeTranscriber("Yes", "Yes") },
            allowAudioOnlyTurns = true, guardFollowupSpeech = true, retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1500, speech = true, samples = 1600)
            fixture.emit(1400, 0, samples = 1600)
            assertEquals(1, observer.discards)
            assertTrue(observer.packets.isEmpty())
            assertEquals(null, fixture.capture.endpointDecisionAtNs)
            assertEquals(null, fixture.capture.retainedPreRollSampleCount)
            fixture.emit(1600, 2000, speech = true, samples = 1600)
            fixture.emit(2900, 0, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            val wav = fixture.capture.stop()
            assertArrayEquals(wav.copyOfRange(44, wav.size), observer.bytes())
            assertEquals(2000.toByte(), observer.packets.first()[0])
            assertEquals(1600, fixture.capture.retainedPreRollSampleCount)
            assertEquals(2_900_000_000L, fixture.capture.endpointDecisionAtNs)
        } finally { fixture.capture.stop() }
    }

    @Test fun rolledCaptureInvalidatesRatherThanStreamingOnlyItsRetainedSuffix() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            maxAudioDurationMs = 200, retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            fixture.emit(200, 1800, speech = true, samples = 1600)
            fixture.emit(300, 1800, speech = true, samples = 1600)
            assertFalse(fixture.capture.audioIsComplete)
            assertEquals(listOf(RetainedPcmObserver.Invalidation.WINDOW_ROLLED), observer.invalidations)
            assertEquals(6400, observer.bytes().size)
        } finally { fixture.capture.stop() }
    }

    @Test fun exactAudioLimitStillInvalidatesEvenWithoutRolling() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            maxAudioDurationMs = 200, rejectAtAudioLimit = true, retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            fixture.emit(200, 1800, speech = true, samples = 1600)
            assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
            assertTrue(fixture.capture.audioIsComplete)
            assertEquals(listOf(RetainedPcmObserver.Invalidation.AUDIO_LIMIT), observer.invalidations)
        } finally { fixture.capture.stop() }
    }

    @Test fun observerAdmissionFailureRejectsCaptureInsteadOfDroppingPacket() = runBlocking<Unit> {
        val observer = RecordingPcmObserver(failAdmission = true)
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            retainedPcmObserver = observer)
        fixture.capture.start()
        try {
            fixture.emit(100, 1800, speech = true, samples = 1600)
            val error = runCatching { withTimeout(1000) { fixture.capture.awaitTurnCompletion() } }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("bounded encoder queue full", error?.message)
            assertEquals(listOf(RetainedPcmObserver.Invalidation.CAPTURE_FAILED), observer.invalidations)
            assertTrue(fixture.metrics.isEmpty())
        } finally { fixture.capture.stop() }
    }

    @Test fun cancellationInvalidatesTheUnsealedRetainedStream() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            retainedPcmObserver = observer)
        fixture.capture.start()
        fixture.emit(100, 1800, speech = true, samples = 1600)
        fixture.capture.stop()
        assertEquals(listOf(RetainedPcmObserver.Invalidation.CANCELLED), observer.invalidations)
        assertTrue(fixture.metrics.isEmpty())
        assertEquals(null, fixture.capture.endpointDecisionAtNs)
    }

    @Test fun shortRetentionWindowPublishesItsExactAcceptedPreRoll() = runBlocking<Unit> {
        val observer = RecordingPcmObserver()
        val fixture = CaptureFixture(this, factory = null, allowAudioOnlyTurns = true,
            maxAudioDurationMs = 200, retainedPcmObserver = observer)
        fixture.capture.start()
        repeat(4) { fixture.emit((it + 1) * 100L, it + 1, samples = 1600) }
        fixture.emit(500, 1800, speech = true, samples = 1600)
        val wav = fixture.capture.stop()
        assertEquals(6400, observer.packets.single().size)
        assertEquals(3200, fixture.capture.retainedPreRollSampleCount)
        assertArrayEquals(wav.copyOfRange(44, wav.size), observer.bytes())
    }

    private class RecordingPcmObserver(
        val mutateReceived: Boolean = false,
        val failAdmission: Boolean = false,
    ) : RetainedPcmObserver {
        val packets = mutableListOf<ByteArray>()
        val invalidations = mutableListOf<RetainedPcmObserver.Invalidation>()
        var discards = 0
        override fun onPcm(retainedPcm16: ByteArray) {
            check(!failAdmission) { "bounded encoder queue full" }
            packets += retainedPcm16.copyOf()
            if (mutateReceived) retainedPcm16.fill(0)
        }
        override fun onCandidateDiscarded() { discards++; packets.clear() }
        override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) { invalidations += reason }
        fun bytes(): ByteArray = java.io.ByteArrayOutputStream().apply { packets.forEach { write(it) } }.toByteArray()
    }

    private class FakeTranscriber(
        private val partial: String = "story about pirates",
        private val final: String = "story about astronauts",
        private val recovered: String = ""
    ) : StreamingTranscriber {
        var accepts = 0
        val receivedSamples = mutableListOf<Int>()
        var finishes = 0
        var releases = 0
        var recoveries = 0
        var recoveredPcm = byteArrayOf()
        override fun accept(pcm: ByteArray): String {
            accepts++
            receivedSamples.add((pcm[0].toInt() and 255) or (pcm[1].toInt() shl 8))
            return partial
        }
        override fun finish(): String { finishes++; return final }
        override fun recover(pcm: ByteArray): String {
            assertEquals(1, finishes)
            recoveries++
            recoveredPcm = pcm.copyOf()
            return recovered
        }
        override fun close() { releases++ }
    }

    private class FakeDetector : SpeechDetector {
        var decision = SpeechDecision(false, 0.01f)
        var releases = 0
        var fail = false
        override fun accept(pcm: ByteArray): SpeechDecision {
            check(!fail) { "Test model failure" }
            return decision
        }
        override fun close() { releases++ }
    }

    private class CaptureFixture(scope: CoroutineScope, transcriber: StreamingTranscriber? = null,
        factory: (() -> StreamingTranscriber)? = transcriber?.let { { it } },
        trailingSilenceMs: Long? = 1200L,
        allowAudioOnlyTurns: Boolean = false,
        guardFollowupSpeech: Boolean = false,
        maxAudioDurationMs: Int = 25000,
        rejectAtAudioLimit: Boolean = false,
        captionOnly: Boolean = false,
        initialConfirmedSpeech: () -> String = { "" },
        retainedPcmObserver: RetainedPcmObserver? = null
    ) {
        var microphoneStarts = 0
        var microphoneStops = 0
        var bufferedMs = 0L
        var resumed = 0
        val partials = mutableListOf<String>()
        val recoveryStates = mutableListOf<Boolean>()
        val metrics = mutableListOf<Pair<AsrCaptureMetrics, String>>()
        private var clock = 0L
        fun spend(ms: Long) { clock += ms }
        private val chunks = MutableSharedFlow<ByteArray>()
        val events = mutableListOf<String>()
        private val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override val bufferedAudioMs: Long get() = bufferedMs
            override fun chunks() = chunks
            override suspend fun start() { microphoneStarts++ }
            override suspend fun stop() { microphoneStops++ }
        }
        val detector = FakeDetector()
        val capture = AudioTurnCapture(input, scope, captureDispatcher = Dispatchers.Unconfined, createDetector = { detector }, nowMs = { clock }, log = events::add,
            createTranscriber = factory, onPartialTranscript = { text -> partials.add(text) },
            onMetrics = { stats, text -> metrics.add(stats to text) }, trailingSilenceMs = trailingSilenceMs,
            onRecognitionRecovery = recoveryStates::add, allowAudioOnlyTurns = allowAudioOnlyTurns,
            maxAudioDurationMs = maxAudioDurationMs, rejectAtAudioLimit = rejectAtAudioLimit, captionOnly = captionOnly,
            guardFollowupSpeech = guardFollowupSpeech, initialConfirmedSpeech = initialConfirmedSpeech, onSpeechResumed = { resumed++ },
            retainedPcmObserver = retainedPcmObserver, nowNs = { clock * 1_000_000L })

        suspend fun emit(atMs: Long, sample: Int, speech: Boolean = false, samples: Int = 1, probability: Float = if (speech) 0.95f else 0.01f) {
            clock = atMs
            detector.decision = SpeechDecision(speech, probability)
            chunks.emit(ByteArray(samples * 2) { if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte() })
            // Let the collector process the delivered chunk before assertions.
            kotlinx.coroutines.yield()
        }
    }
}
