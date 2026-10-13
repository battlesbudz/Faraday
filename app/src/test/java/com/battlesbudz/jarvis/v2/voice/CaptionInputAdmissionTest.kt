package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CaptionInputAdmissionTest {
    @Test fun replayedPlaybackTailCannotRevokeCaptionBeforeFinalEchoCheck() {
        var revocations = 0
        val echo = FollowupPlaybackEcho().also { it.remember("Goodbye Jarvis"); it.ended(1000) }
        val gate = CaptionInputAdmission({ echo.needsFinalTranscript(950) }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(4096))
        assertEquals(0, revocations)
        val resolved = NaturalCorrectionText.resolve("Goodbye Jarvis", "Goodbye Jarvis")
        assertNull(resolved)
        gate.onFinalCandidate(true, resolved.orEmpty(), true, resolved == null)
        assertEquals(0, revocations)
    }
    @Test fun freshVerifiedOnsetRevokesImmediatelyWithoutWaitingForAnyTranscript() {
        var revocations = 0
        val echo = FollowupPlaybackEcho().also { it.remember("old answer"); it.ended(1000) }
        val gate = CaptionInputAdmission({ echo.needsFinalTranscript(2000) }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(4096)); gate.onPcm(ByteArray(4096))
        assertEquals(1, revocations)
        gate.onCandidateDiscarded()
        gate.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CANCELLED)
        assertEquals(1, revocations)
    }
    @Test fun finalNonEchoTailRequestRevokesOnce() {
        var revocations = 0
        val gate = CaptionInputAdmission({ true }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(4096))
        gate.onFinalCandidate(true, "new request", true, false)
        gate.onFinalCandidate(true, "new request", true, false)
        assertEquals(1, revocations)
    }
    @Test fun missingFailedProvisionalOrQuietTailEvidenceKeepsBackstop() {
        var revocations = 0
        val gate = CaptionInputAdmission({ true }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(4096))
        gate.onFinalCandidate(true, "", true, false)
        gate.onFinalCandidate(true, "words", false, false)
        gate.onFinalCandidate(false, "words", true, false)
        assertEquals(0, revocations)
    }
    @Test fun asrDisabledVerifiedOnsetRevokesWithoutReadingAnyCaption() {
        var revocations = 0
        val gate = CaptionInputAdmission({ false }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(3200))
        assertEquals(1, revocations)
        gate.onFinalCandidate(true, "", false, false, nativeAudioAccepted = true)
        assertEquals(1, revocations)
    }
    @Test fun validNativeAudioNeedsNoFinalTextButKnownEchoStillCannotRevoke() {
        var revocations = 0
        val gate = CaptionInputAdmission({ true }, onAccepted = { revocations++ })
        gate.onPcm(ByteArray(3200))
        gate.onFinalCandidate(true, "old answer words", true, true, nativeAudioAccepted = true)
        assertEquals(0, revocations)
        gate.onFinalCandidate(true, "", false, false, nativeAudioAccepted = false)
        assertEquals(0, revocations)
        gate.onFinalCandidate(true, "", false, false, nativeAudioAccepted = true)
        assertEquals(1, revocations)
    }

}
