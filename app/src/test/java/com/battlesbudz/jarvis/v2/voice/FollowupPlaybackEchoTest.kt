package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FollowupPlaybackEchoTest {
    private fun evidence() = FollowupPlaybackEcho().also {
        it.remember("One moment, please, sir.")
        it.remember("Rocket science covers propulsion and spacecraft engineering.")
        it.ended(1000)
    }

    @Test fun fillerAndAnswerEchoRemainKnownAcrossFollowupBoundary() {
        val echo = evidence()
        assertTrue(echo.rejects("One moment please sir", 1000))
        assertTrue(echo.rejects("Rocket science covers propulsion and spacecraft engineering", 1100))
        assertTrue(echo.rejects("Rocket science covers proportion and spacecraft engineering", 1200))
    }
    @Test fun genuineNewRequestAndMixedSpeechArePreserved() {
        val echo = evidence()
        assertFalse(echo.rejects("What do you think of vaping?", 1050))
        assertFalse(echo.rejects("One moment please sir. What about vaping?", 1050))
        assertFalse(echo.rejects("One moment please sir open settings", 1050))
        assertFalse(echo.rejects("No one moment please sir", 1050))
        assertFalse(echo.rejects("Stop", 1050))
        assertFalse(echo.rejects("No", 1050))
    }
    @Test fun laterDeliberateRepetitionAndMissingAcousticTimingAreNotDiscarded() {
        val echo = evidence()
        assertFalse(echo.rejects("One moment please sir", 1351))
        assertFalse(echo.rejects("One moment please sir", null))
        assertFalse(echo.rejects("One moment please sir", 999))
    }
    @Test fun captionRetirementRequiresKnownSafeTimingWhenPlaybackIsRemembered() {
        val echo = FollowupPlaybackEcho()
        assertFalse(echo.needsFinalTranscript(null)) // No playback exists to match.
        echo.remember("One moment please sir")
        assertTrue(echo.needsFinalTranscript(null))
        assertTrue(echo.needsFinalTranscript(10_000)) // No ended-at evidence yet.
        echo.ended(1000)
        assertTrue(echo.needsFinalTranscript(null))
        assertTrue(echo.needsFinalTranscript(999))
        assertTrue(echo.needsFinalTranscript(1000))
        assertTrue(echo.needsFinalTranscript(1350))
        assertFalse(echo.needsFinalTranscript(1351))
        echo.clear()
        assertFalse(echo.needsFinalTranscript(null))
    }
    @Test fun closingCallErasesPriorPlaybackEvidence() {
        val echo = evidence()
        echo.clear()
        assertFalse(echo.rejects("One moment please sir", 1100))
    }
}
