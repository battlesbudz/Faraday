package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PiperFirstPcmTimingTest {
    @Test fun emptyFirstPhraseCannotClaimAudioAndSecondNonemptyPhraseReportsOnlyOnce() {
        var now = 10L
        val observations = mutableListOf<Long>()
        val timing = PiperFirstPcmTiming({ observations += it }) { now }
        timing.observe(0)
        assertTrue(observations.isEmpty())
        now = 25; timing.observe(240)
        now = 30; timing.observe(480)
        assertEquals(listOf(25L), observations)
    }
    @Test fun allEmptyOrCancelledBeforePcmStaysUnavailableAndObserverCannotBreakSynthesis() {
        var observations = 0
        val timing = PiperFirstPcmTiming({ observations++; error("observer failure") }) { 10 }
        timing.observe(0); timing.observe(0)
        assertEquals(0, observations)
        timing.observe(1); timing.observe(2)
        assertEquals(1, observations)
    }
}
