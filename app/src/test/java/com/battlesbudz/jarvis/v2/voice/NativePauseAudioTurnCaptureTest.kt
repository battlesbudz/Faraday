package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class NativePauseAudioTurnCaptureTest {
    private class Fixture(scope: CoroutineScope, val chunkSamples: Int = 512, useSession: Boolean = false) {
        val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 0)
        var samples = 0L
        var probability = .01f
        var hardwareBacklog = 0L
        var routeAllowed = true
        val original = ByteArrayOutputStream()
        val encoder = ByteArrayOutputStream()
        val invalidations = mutableListOf<NativePauseInvalidation>()
        val proposals = mutableListOf<NativePauseProposal>()
        val logs = mutableListOf<String>()
        var encoderDiscards = 0
        var cue: CompletedEndpointCue? = null
        var caption = ""
        var finalCaptionOverride: String? = null
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override val lastChunkCaptureTimeMs get() = samples / 16
            override val bufferedAudioMs get() = hardwareBacklog
            override val stoppedUnconsumedPcmBytes get() = hardwareBacklog * 32
            override fun chunks() = frames
            override suspend fun start() {}
            override suspend fun stop() {}
        }
        val session = if (useSession) VoiceAudioSession(input, scope) else null
        val reader = session?.borrow("command") ?: input
        val captureInput = if (useSession) QuietSpeechAudioInput(reader, maxGain = 1.0) else reader
        val capture = AudioTurnCapture(captureInput, scope,
            createDetector = { FrameSpeechDetector({ probability }) },
            nowMs = { samples / 16 }, nowNs = { samples * 62_500 },
            captureDispatcher = Dispatchers.Unconfined,
            captionOnly = true, allowAudioOnlyTurns = true,
            createTranscriber = { object : StreamingTranscriber {
                override val noTextSilenceMs = 900L
                override val completedEndpointCue get() = cue
                override fun accept(pcm: ByteArray) = caption
                override fun finish() = finalCaptionOverride ?: cue?.text ?: caption
                override fun close() {}
            } },
            retainedPcmObserver = object : RetainedPcmObserver {
                override fun onPcm(retainedPcm16: ByteArray) { encoder.write(retainedPcm16) }
                override fun onCandidateDiscarded() { encoderDiscards++ }
                override fun onCaptureInvalidated(reason: RetainedPcmObserver.Invalidation) {}
            },
            nativePauseObserver = object : NativePauseObserver {
                override fun onProposal(proposal: NativePauseProposal): Boolean { proposals += proposal; return true }
                override fun onInvalidated(reason: NativePauseInvalidation) { invalidations += reason }
            }, nativePauseTurnId = "capture-turn", nativePauseGeneration = 17,
            canUseNativePause = { routeAllowed }, log = logs::add)
        suspend fun emit(p: Float, count: Int = chunkSamples) {
            probability = p
            val amplitude = if (p >= .5f) 5000 else 64
            val pcm = ByteArray(count * 2) { if (it % 2 == 0) amplitude.toByte() else (amplitude shr 8).toByte() }
            samples += count; original.write(pcm)
            frames.emit(pcm)
            yield()
        }
        suspend fun speech() { repeat(8) { emit(.95f) } }
    }

    @Test fun frozenCandidateStopsEncoderWhileExactFullRecordingAndCertifiedTailRemain() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); f.speech(); repeat(10) { f.emit(.01f) }
            assertEquals(1, f.proposals.size)
            val proposal = f.proposals.single()
            assertArrayEquals(proposal.pcm16(), f.encoder.toByteArray())
            assertNull(f.capture.nativePauseCertificate)
            repeat(11) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            assertNull(f.capture.nativePauseCertificate)
            val fullWav = f.capture.stop()
            val certificate = f.capture.nativePauseCertificate!!
            assertArrayEquals(f.original.toByteArray(), fullWav.copyOfRange(44, fullWav.size))
            assertTrue(certificate.matchesCompleteWav(fullWav))
            assertArrayEquals(proposal.pcm16(), f.encoder.toByteArray())
            assertEquals(17L, certificate.proposal.generation)
            assertEquals(512 * 11, certificate.excludedSampleCount)
            assertEquals(928_000_000L, certificate.endpointDecisionAtNs)
            assertEquals(576_000_000L, proposal.proposedAtNs)
            assertEquals(256L, f.capture.lastSpeechAtMs)
            assertTrue(f.invalidations.isEmpty())
        } finally { f.capture.stop() }
    }

    @Test fun rawWeakMaskedByGateRevokesBeforeCollectorAndNeverRestartsSpentEncoder() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); f.speech(); repeat(10) { f.emit(.01f) }
            val frozenSize = f.encoder.size()
            f.emit(.25f)
            assertEquals(listOf(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO), f.invalidations)
            repeat(8) { f.emit(.95f) }
            repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val full = f.capture.stop()
            assertNull(f.capture.nativePauseCertificate)
            assertEquals(frozenSize, f.encoder.size())
            assertEquals(1, f.proposals.size)
            assertArrayEquals(f.original.toByteArray(), full.copyOfRange(44, full.size))
        } finally { f.capture.stop() }
    }

    @Test fun partial1600SampleTailFallsBackBeforeStoppingAndKeepsNewOnset() = runBlocking<Unit> {
        val f = Fixture(this, chunkSamples = 1600)
        try {
            f.capture.start()
            repeat(3) { f.emit(.95f) }
            // End300ms. At1000ms there are700ms of silence but the collector's raw tail is unclassified.
            repeat(7) { f.emit(.01f) }
            assertNull(f.capture.endpointDecisionAtNs)
            assertTrue(f.logs.any { it.contains("native_pause_frozen") })
            // A real new word before the900ms fallback must remain in this same full turn.
            repeat(3) { f.emit(.95f) }
            repeat(8) { f.emit(.01f) }
            assertNull(f.capture.endpointDecisionAtNs) // 2100ms cursor has no full raw coverage.
            f.emit(.01f) // Ordinary900ms fallback is unchanged.
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val full = f.capture.stop()
            assertNull(f.capture.nativePauseCertificate)
            assertArrayEquals(f.original.toByteArray(), full.copyOfRange(44, full.size))
            assertEquals(1300L, f.capture.lastSpeechAtMs)
        } finally { f.capture.stop() }
    }

    @Test fun explicitStopAndCancellationCannotPromoteOrFeedFrozenEncoder() = runBlocking<Unit> {
        for (explicit in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.capture.start(); f.speech(); repeat(10) { f.emit(.01f) }
                if (explicit) { f.capture.finishNow(); f.emit(.01f); f.capture.awaitTurnCompletion() }
                f.capture.stop()
                assertNull(f.capture.nativePauseCertificate)
                assertEquals(listOf(if (explicit) NativePauseInvalidation.ENDPOINT_REJECTED else NativePauseInvalidation.CANCELLED), f.invalidations)
                assertEquals(f.proposals.single().sampleCount * 2, f.encoder.size())
            } finally { f.capture.stop() }
        }
    }

    @Test fun completedCueDeliveredDuringSilenceOnlyChangesEndpointNotSpeechClock() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.caption = "what is the"
            f.capture.start(); f.speech()
            repeat(2) { f.emit(.01f) }
            f.cue = CompletedEndpointCue(1, "what is the weather today", 4096)
            repeat(12) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            assertEquals(256L, f.capture.lastSpeechAtMs)
            assertTrue(f.logs.any { it.startsWith("native_endpoint_cue") })
            assertTrue(f.logs.any { it.contains("endpointCue=complete_and_stable") || it.contains("endpointCue=native_complete_settling") })
        } finally { f.capture.stop() }
    }

    @Test fun playbackTailGuardBlocksBothProposalAndNewCompletedCueAcceleration() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.routeAllowed = false; f.caption = "what is the"
            f.capture.start(); f.speech()
            f.cue = CompletedEndpointCue(1, "what is the weather today", 4096)
            repeat(20) { f.emit(.01f) }
            assertTrue(f.proposals.isEmpty())
            assertNull(f.capture.endpointDecisionAtNs)
            assertEquals(256L, f.capture.lastSpeechAtMs)
            assertTrue(f.logs.any { it.contains("route_or_playback_guard") })
        } finally { f.capture.stop() }
    }

    @Test fun boundedIdlePrerollKeepsExactOpeningOnceAtAbsoluteCaptureBoundary() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); repeat(50) { f.emit(.01f) }; f.speech(); repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val full = f.capture.stop().drop(44).toByteArray()
            val candidate = f.proposals.single()
            assertTrue(candidate.captureSampleBoundary > candidate.sampleCount)
            assertArrayEquals(f.original.toByteArray().takeLast(full.size).toByteArray(), full)
            assertArrayEquals(candidate.pcm16(), f.encoder.toByteArray())
            assertNotNull(f.capture.nativePauseCertificate)
        } finally { f.capture.stop() }
    }

    @Test fun postEndpointUnclassifiedHardwareBacklogRejectsPromotionAfterJoin() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); f.speech(); repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            f.hardwareBacklog = 1
            val full = f.capture.stop()
            assertNull(f.capture.nativePauseCertificate)
            assertEquals(listOf(NativePauseInvalidation.HARDWARE_BACKLOG), f.invalidations)
            assertArrayEquals(f.original.toByteArray(), full.drop(44).toByteArray())
        } finally { f.capture.stop() }
    }

    @Test fun qualifying1600SampleNativeNoCaptionTurnEndsAt700InsteadOf900Ms() = runBlocking<Unit> {
        val f = Fixture(this, chunkSamples = 1600)
        try {
            f.capture.start(); repeat(9) { f.emit(.95f) }; repeat(7) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            assertEquals(900L, f.capture.lastSpeechAtMs)
            assertEquals(1_600_000_000L, f.capture.endpointDecisionAtNs)
            assertTrue(f.logs.any { it.contains("endpointCue=native_clean_no_caption") })
            assertNotNull(f.capture.stop())
            assertNotNull(f.capture.nativePauseCertificate)
        } finally { f.capture.stop() }
    }

    @Test fun busyFinalCaptionCanRevokeNewFastEndpointWhenItRevealsThinkingOrUnfinishedWords() = runBlocking<Unit> {
        for ((text, margin) in listOf("what is the" to 1800L, "let me think" to 3500L, "open settings" to 1500L)) {
            val f = Fixture(this)
            try {
                f.finalCaptionOverride = text
                f.capture.start(); f.speech()
                f.cue = CompletedEndpointCue(1, "what is two plus two", 4096)
                repeat(21) { f.emit(.01f) }
                assertNull(f.capture.endpointDecisionAtNs)
                assertEquals(listOf(NativePauseInvalidation.ENDPOINT_REJECTED), f.invalidations)
                assertTrue(f.logs.any { it.startsWith("native_endpoint_deferred") })
                while (f.samples / 16 - 256 < margin) f.emit(.01f)
                assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
                val full = f.capture.stop()
                assertNull(f.capture.nativePauseCertificate)
                assertArrayEquals(f.original.toByteArray(), full.drop(44).toByteArray())
                assertEquals(256L, f.capture.lastSpeechAtMs)
            } finally { f.capture.stop() }
        }
    }

    @Test fun actualSessionStopReceiptPreservesQueuedUnknownAudioAndNextReaderReplay() = runBlocking<Unit> {
        val f = Fixture(this, useSession = true)
        try {
            f.capture.start(); f.speech(); repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val completedRecording = f.original.toByteArray()
            f.emit(.95f) // Collector has joined; the live call reader queues an unclassified new word.
            assertTrue(f.reader.bufferedAudioMs > 0)
            val full = f.capture.stop()
            assertEquals(0L, f.reader.bufferedAudioMs) // Real stop clears its queue.
            assertEquals(1024L, f.reader.stoppedUnconsumedPcmBytes)
            assertNull(f.capture.nativePauseCertificate)
            assertEquals(listOf(NativePauseInvalidation.HARDWARE_BACKLOG), f.invalidations)
            assertArrayEquals(completedRecording, full.drop(44).toByteArray())
            val next = f.session!!.borrow("command")
            next.start()
            val replayed = withTimeout(1000) { next.chunks().first() }
            assertArrayEquals(f.original.toByteArray().takeLast(1024).toByteArray(), replayed)
            next.stop()
        } finally { f.capture.stop(); f.session?.close() }
    }

    @Test fun weakRawContinuationCannotBorrowOlderConfirmedSpeechSilenceForNewFastEndpoint() = runBlocking<Unit> {
        for (completeCue in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.capture.start(); f.speech()
                if (completeCue) f.cue = CompletedEndpointCue(1, "what is two plus two", 4096)
                repeat(8) { f.emit(.25f) } // Uncorroborated weak tail; confirmed speech clock stays256.
                repeat(10) { f.emit(.01f) } // Only320ms of genuine raw quiet, despite576ms since strong speech.
                assertNull(f.capture.endpointDecisionAtNs)
                assertEquals(256L, f.capture.lastSpeechAtMs)
                if (completeCue) {
                    f.emit(.01f) // 352ms fresh raw quiet can satisfy the newly complete350ms cue.
                    assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
                    assertEquals(864_000_000L, f.capture.endpointDecisionAtNs)
                } else {
                    repeat(3) { f.emit(.01f) } // 416ms raw quiet still cannot satisfy new650ms.
                    assertNull(f.capture.endpointDecisionAtNs)
                    repeat(8) { f.emit(.01f) } // 672ms raw quiet, exact actual-frame coverage.
                    assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
                }
                val full = f.capture.stop()
                assertArrayEquals(f.original.toByteArray(), full.drop(44).toByteArray())
            } finally { f.capture.stop() }
        }
    }
}
