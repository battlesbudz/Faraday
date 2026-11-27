package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.voice.speaker.SpeakerDecision
import com.battlesbudz.jarvis.v2.voice.speaker.SpeakerState
import org.junit.Assert.*
import org.junit.Test

class VoiceSpeakerAdmissionGateTest {
    @Test fun noMatchStopsBeforeEitherReplyStageCanBeEntered() {
        var evaluations = 0
        val admission = VoiceSpeakerAdmissionGate.evaluate(true, byteArrayOf(1)) {
            evaluations++
            SpeakerDecision(SpeakerState.NO_MATCH, .2f, admitted = false, actionEligible = false)
        }
        assertEquals(1, evaluations)
        assertFalse(admission.proceed)
        assertEquals(SpeakerState.NO_MATCH, admission.decision?.state)
    }

    @Test fun typedTurnDoesNotEvaluateSpeakerGate() {
        var evaluations = 0
        val admission = VoiceSpeakerAdmissionGate.evaluate(false, byteArrayOf()) {
            evaluations++
            error("typed turns must bypass speaker evaluation")
        }
        assertEquals(0, evaluations)
        assertTrue(admission.proceed)
        assertNull(admission.decision)
    }
}
