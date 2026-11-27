package com.battlesbudz.jarvis.v2.voice.speaker

import org.junit.Assert.*
import org.junit.Test

class SpeakerGatePolicyTest {
    @Test fun enrollmentOffPreservesBehavior() {
        val d = SpeakerGatePolicy.decide(false, null, 0)
        assertEquals(SpeakerState.NOT_ENROLLED, d.state); assertTrue(d.admitted); assertTrue(d.actionEligible)
    }
    @Test fun thresholdsAndUnverifiable() {
        assertEquals(SpeakerState.MATCH, SpeakerGatePolicy.decide(true, .65f, 900).state)
        val mid = SpeakerGatePolicy.decide(true, .55f, 900); assertTrue(mid.admitted); assertFalse(mid.actionEligible)
        assertFalse(SpeakerGatePolicy.decide(true, .45f, 900).admitted)
        val short = SpeakerGatePolicy.decide(true, .8f, 799); assertEquals(SpeakerState.UNVERIFIABLE, short.state)
        assertTrue(short.admitted); assertFalse(short.actionEligible)
    }
}
