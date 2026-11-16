package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniTurnEndpointPolicyTest {
    @Test
    fun configuredTurnUsesThirtySecondWindowAndSixSecondPreRoll() {
        assertEquals(30_000, OmniTurnCapture.MAX_TURN_MS)
        assertEquals(6_000, OmniTurnCapture.PRE_ROLL_MS)
        assertEquals(16_000, OmniTurnCapture.SAMPLE_RATE_HZ)
    }

    @Test
    fun scriptedVadSilenceTimelineSubmitsAtDeadlineWithoutSmartTurn() {
        val fallback = CaptureEndpointDecision(CaptureEndpointState.PENDING)
        val eligible = NativePauseEndpointPolicy.Eligibility(false, "not_required")
        val endpoint = SmartTurnEndpointPolicy.deadlineDecision(
            fallback, eligible, collectedBytes = 6_400, lastSpeechSample = 1_600,
            vetoHoldActive = false,
        )
        val silenceTimelineMs = listOf(100L, 200L, 299L, 300L)

        val firstEndpointSilence = silenceTimelineMs.firstOrNull { it >= endpoint.silenceMs }

        assertEquals(SmartTurnEndpointPolicy.DEADLINE_MS, endpoint.silenceMs)
        assertEquals(300L, firstEndpointSilence)
        assertEquals(SmartTurnEndpointPolicy.CUE_DEADLINE, endpoint.cue)
    }

    @Test
    fun smartTurnOnlyBuysOneHighConfidenceUnfinishedExtension() {
        val sane = NativePauseEndpointPolicy.Eligibility(true, "verdict_sane")
        val confidentContinue = CaptureEndpointDecision(CaptureEndpointState.CONTINUE,
            modelThroughSample = 1_600, probability = 0.01f)
        val uncertainContinue = confidentContinue.copy(probability = 0.03f)

        assertTrue(SmartTurnEndpointPolicy.vetoQualifies(confidentContinue, sane, 6_400, 1_600))
        assertFalse(SmartTurnEndpointPolicy.vetoQualifies(uncertainContinue, sane, 6_400, 1_600))
        val extended = SmartTurnEndpointPolicy.deadlineDecision(
            confidentContinue, sane, 6_400, 1_600, vetoHoldActive = true,
        )
        assertEquals(600L, extended.silenceMs)
        assertEquals(SmartTurnEndpointPolicy.CUE_VETO_HOLD, extended.cue)
    }
}
