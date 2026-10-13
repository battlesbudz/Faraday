package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class CaptureShadowWiringTest {
    private class Fixture(scope: CoroutineScope, val chunkSamples: Int = 512, useSession: Boolean = false, val throwShadow: Boolean = false) {
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
        val shadowPcm = ByteArrayOutputStream()
        val shadowFrames = mutableListOf<CaptureShadowFrame>()
        val shadowBoundaries = mutableListOf<Long>()
        val shadowChunkBytes = mutableListOf<Int>()
        val shadowPriorities = mutableListOf<String>()
        val shadowInvalidations = mutableListOf<String>()
        var shadowClosed = 0
        private fun maybeThrow() { if (throwShadow) throw IllegalStateException("test_shadow_failure") }
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
            shadowObserver = object : CaptureShadowObserver {
                override fun onPcm(pcm16: ByteArray, captureSampleBoundary: Long) { shadowPcm.write(pcm16); shadowChunkBytes += pcm16.size; shadowBoundaries += captureSampleBoundary; maybeThrow() }
                override fun onFrame(frame: CaptureShadowFrame) { shadowFrames += frame; maybeThrow() }
                override fun onPriority(reason: String) { shadowPriorities += reason; maybeThrow() }
                override fun onInvalidated(reason: String) { shadowInvalidations += reason; maybeThrow() }
                override fun close() { shadowClosed++; maybeThrow() }
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


    @Test fun exactRetainedPcmContinuesAfterNativeEncoderFreezes() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); f.speech(); repeat(10) { f.emit(.01f) }
            assertEquals(1, f.proposals.size)
            val frozenBytes = f.encoder.size()
            assertEquals(frozenBytes, f.shadowPcm.size())
            repeat(11) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertTrue(f.shadowPcm.size() > frozenBytes)
            assertEquals(frozenBytes, f.encoder.size())
            assertArrayEquals(wav.drop(44).toByteArray(), f.shadowPcm.toByteArray())
            assertEquals(f.samples, f.shadowBoundaries.last())
            assertTrue(f.shadowPriorities.contains("gemma_speculation"))
            assertTrue(f.shadowPriorities.contains("endpoint_finalization"))
            assertTrue(f.shadowClosed > 0)
        } finally { f.capture.stop() }
    }
    @Test fun boundedPrerollIsObservedExactlyOnceWithAbsoluteCursor() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); repeat(50) { f.emit(.01f) }; f.speech(); repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertArrayEquals(wav.drop(44).toByteArray(), f.shadowPcm.toByteArray())
            assertTrue(f.shadowBoundaries.first() > f.shadowChunkBytes.first() / 2L)
            assertEquals(f.samples, f.shadowBoundaries.last())
        } finally { f.capture.stop() }
    }
    @Test fun rawPossibleSpeechResumptionIsVisibleDespiteNativeEncoderRevocation() = runBlocking<Unit> {
        val f = Fixture(this)
        try {
            f.capture.start(); f.speech(); repeat(10) { f.emit(.01f) }
            assertFalse(f.shadowFrames.last().possibleSpeech)
            val frozenBytes = f.encoder.size()
            f.emit(.25f)
            assertTrue(f.shadowFrames.last().possibleSpeech)
            assertEquals(listOf(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO), f.invalidations)
            repeat(8) { f.emit(.95f) }; repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertArrayEquals(wav.drop(44).toByteArray(), f.shadowPcm.toByteArray())
            assertEquals(frozenBytes, f.encoder.size())
            assertNull(f.capture.nativePauseCertificate)
        } finally { f.capture.stop() }
    }
    @Test fun everyThrowingShadowCallbackIsEndpointAndPcmNeutral() = runBlocking<Unit> {
        val f = Fixture(this, throwShadow=true)
        try {
            f.capture.start(); f.speech(); repeat(21) { f.emit(.01f) }
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            val wav = f.capture.stop()
            assertEquals(928_000_000L, f.capture.endpointDecisionAtNs)
            assertArrayEquals(f.original.toByteArray(), wav.drop(44).toByteArray())
            assertTrue(f.capture.nativePauseCertificate!!.matchesCompleteWav(wav))
            assertTrue(f.shadowClosed > 0)
        } finally { f.capture.stop() }
    }
    @Test fun throwingShadowCannotBlockExplicitStop() = runBlocking<Unit> {
        val f = Fixture(this, throwShadow=true)
        try {
            f.capture.start(); f.speech(); f.capture.finishNow(); f.emit(.01f)
            assertTrue(withTimeout(1000) { f.capture.awaitTurnCompletion() })
            assertTrue(f.shadowPriorities.contains("explicit_stop"))
            assertArrayEquals(f.original.toByteArray(), f.capture.stop().drop(44).toByteArray())
        } finally { f.capture.stop() }
    }
}
