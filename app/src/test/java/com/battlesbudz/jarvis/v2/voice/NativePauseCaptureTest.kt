package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class NativePauseCaptureTest {
    private class Observer : NativePauseObserver {
        var proposed: NativePauseProposal? = null
        val invalidations = mutableListOf<NativePauseInvalidation>()
        var admit = true
        override fun onProposal(proposal: NativePauseProposal): Boolean { proposed = proposal; return admit }
        override fun onInvalidated(reason: NativePauseInvalidation) { invalidations += reason }
    }
    private fun coverage(received: Long, classified: Long = received, quietFrom: Long = 0) =
        SpeechDecision(false, .01f, rawCoverage = RawVadCoverage(received * 2, classified, quietFrom, 1))
    private fun bytes(samples: Int, marker: Int = 1) = ByteArray(samples * 2) { marker.toByte() }

    @Test fun exactFrozenInputAndFullFallbackHaveDifferentImmutableIdentities() {
        val o = Observer(); val capture = NativePauseCapture(o, "turn", 7)
        val prefix = bytes(512); capture.observeRaw(coverage(512)); assertTrue(capture.propose(prefix, 512, 100))
        prefix[0] = 9
        val tail = bytes(512, 2); capture.retain(tail); capture.observeRaw(coverage(1024))
        val full = bytes(512) + tail
        val certificate = capture.certificateAfterJoin(full, 200, 0)!!
        assertEquals(512, certificate.proposal.sampleCount); assertEquals(512, certificate.excludedSampleCount)
        assertTrue(certificate.matchesCompleteWav(WavEncoder.pcm16Mono(full)))
        assertFalse(certificate.matchesCompleteWav(certificate.proposal.wav()))
        val exposed = certificate.proposal.pcm16(); exposed[0] = 7
        assertEquals(1.toByte(), certificate.proposal.pcm16()[0])
        full[0] = 4; assertFalse(certificate.matchesCompleteWav(WavEncoder.pcm16Mono(full)))
    }

    @Test fun splitFrameUsesFreshPositiveCoverageRatherThanStaleLowProbability() {
        val detector = FrameSpeechDetector({ .01f })
        val o = Observer(); val capture = NativePauseCapture(o, "turn", 1)
        capture.observeRaw(detector.accept(bytes(512)))
        assertTrue(capture.propose(bytes(512), 512, 1))
        val partial = detector.accept(bytes(64))
        assertEquals(.01f, partial.probability)
        assertEquals(0, partial.rawCoverage!!.completedFrames)
        capture.retain(bytes(64)); capture.observeRaw(partial)
        assertNull(capture.certificateAfterJoin(bytes(576), 2, 0))
        assertEquals(listOf(NativePauseInvalidation.UNKNOWN_RAW_COVERAGE), o.invalidations)
    }

    @Test fun split1600ChunksAreCoveredOnlyByReal512FramesWithoutPadding() {
        val detector = FrameSpeechDetector({ .01f }); val o = Observer(); val capture = NativePauseCapture(o, "turn", 1)
        capture.observeRaw(detector.accept(bytes(1600)))
        assertTrue(capture.propose(bytes(1600), 1600, 1))
        capture.retain(bytes(1600)); capture.observeRaw(detector.accept(bytes(1600)))
        assertEquals(3072L, detector.accept(byteArrayOf()).rawCoverage!!.classifiedThroughSample)
        // Actual subsequent raw samples can complete a straddling frame; they are not padded or retained twice.
        capture.observeRaw(detector.accept(bytes(384)))
        assertNotNull(capture.certificateAfterJoin(bytes(3200), 2, 0))
    }

    @Test fun rawWeakSpeechRejectedByNoiseGateStillInvalidatesSynchronously() {
        val o = Observer(); val capture = NativePauseCapture(o, "turn", 1)
        capture.observeRaw(coverage(512)); capture.propose(bytes(512), 512, 1)
        val raw = SpeechDecision(false, .25f, rawCoverage = RawVadCoverage(2048, 1024, 1024, 1))
        val gated = CaptureSpeechGate().accept(raw, 0.0, 100)
        assertEquals(0f, gated.probability)
        capture.observeRaw(raw)
        assertEquals(listOf(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO), o.invalidations)
        assertTrue(capture.encoderFrozen)
        assertFalse(capture.propose(bytes(1024), 1024, 3))
    }

    @Test fun unknownDetectorCoverageCannotProposeAndHardwareBacklogCannotCertify() {
        val o = Observer(); val capture = NativePauseCapture(o, "turn", 1)
        capture.observeRaw(SpeechDecision(false, 0f)); assertFalse(capture.propose(bytes(512), 512, 1))
        capture.observeRaw(coverage(512)); assertTrue(capture.propose(bytes(512), 512, 1))
        assertNull(capture.certificateAfterJoin(bytes(512), 2, 1))
        assertEquals(listOf(NativePauseInvalidation.HARDWARE_BACKLOG), o.invalidations)
    }

    @Test fun boundedTailOverflowInvalidatesWithoutChangingFullFallbackBytes() {
        val o = Observer(); val capture = NativePauseCapture(o, "turn", 1, maxTailBytes = 1024)
        capture.observeRaw(coverage(512)); capture.propose(bytes(512), 512, 1)
        val full = bytes(512) + bytes(513, 2)
        capture.retain(full.copyOfRange(1024, full.size))
        assertEquals(listOf(NativePauseInvalidation.TAIL_LIMIT), o.invalidations)
        assertEquals(2050, full.size); assertEquals(2.toByte(), full.last())
    }

    @Test fun deniedAdmissionKeepsEncoderLiveButNeverRetriesProposal() {
        val o = Observer().also { it.admit = false }; val capture = NativePauseCapture(o, "turn", 1)
        capture.observeRaw(coverage(512)); assertFalse(capture.propose(bytes(512), 512, 1))
        o.admit = true; assertFalse(capture.propose(bytes(512), 512, 2))
        assertFalse(capture.encoderFrozen); assertTrue(o.invalidations.isEmpty())
    }

    @Test fun prefixTailAndEndpointMismatchFailClosed() {
        for (mode in 0..2) {
            val o = Observer(); val capture = NativePauseCapture(o, "turn", 1)
            capture.observeRaw(coverage(512)); capture.propose(bytes(512), 512, 1)
            capture.retain(bytes(512, 2)); capture.observeRaw(coverage(1024))
            val full = bytes(512) + bytes(512, 2)
            if (mode == 0) full[0] = 3
            if (mode == 1) full[1024] = 3
            assertNull(capture.certificateAfterJoin(full, 2L.takeIf { mode != 2 }, 0))
        }
    }
}
