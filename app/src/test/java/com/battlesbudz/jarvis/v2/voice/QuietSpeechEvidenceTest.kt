package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class QuietSpeechEvidenceTest {
    @Test fun weakWhisperNeedsStableWords() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("what time", 0.23f, 0, false))
        assertFalse(evidence.accept("what time", 0.23f, 200, false))
        assertTrue(evidence.accept("what time", 0.23f, 350, false))
    }
    @Test fun noiseAndBlankTranscriptDoNotBecomeSpeech() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("", 0.23f, 0, false))
        assertFalse(evidence.accept("what time", 0.02f, 1000, false))
        assertFalse(evidence.accept("what time", 0.02f, 2000, false))
    }
    @Test fun weakSingleWordAndChangingFragmentsStayUnconfirmed() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("the", 0.23f, 0, false))
        assertFalse(evidence.accept("the", 0.23f, 500, false))
        assertFalse(evidence.accept("the time", 0.23f, 600, false))
        assertFalse(evidence.accept("the time is", 0.23f, 700, false))
    }
    @Test fun staleWordsCannotTurnLaterWeakNoiseIntoSpeech() {
        val evidence = QuietSpeechEvidence()
        evidence.accept("what time", 0.23f, 0, false)
        assertFalse(evidence.accept("what time", 0.23f, 2000, false))
    }
    @Test fun deferredDecodeAndPunctuationCannotRefreshCachedWords() {
        val evidence = QuietSpeechEvidence()
        evidence.accept("What time is it?", 0.95f, 100, true)
        assertFalse(evidence.accept(null, 0.23f, 900, true))
        assertFalse(evidence.accept("What time is it.", 0.23f, 1000, true))
        assertFalse(evidence.accept("", 0.23f, 1200, true))
        assertFalse(evidence.accept("What time is it?", 0.23f, 1400, true))
    }
    @Test fun newQuietWordsCanStillCorroborateAfterStrongSpeech() {
        val evidence = QuietSpeechEvidence()
        evidence.accept("Open", 0.95f, 100, true)
        assertFalse(evidence.accept("Open YouTube", 0.23f, 200, true))
        assertTrue(evidence.accept("Open YouTube", 0.23f, 400, true))
        assertFalse(evidence.accept("Open YouTube", 0.23f, 1000, true))
    }
    @Test fun singleProbabilitySpikeCannotBypassFrameConfirmation() {
        assertFalse(QuietSpeechEvidence().accept("yes", 0.8f, 0, false))
        assertFalse(QuietSpeechEvidence().accept("yes", 0.8f, 0, true))
    }
}
