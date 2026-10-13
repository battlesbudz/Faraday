package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class CandidateConfirmationTest {
    private val weak = CandidateSpeechEvidence.Snapshot(100, 0)
    @Test fun oneHypothesisCannotBecomeConfirmationByWaitingOrRepeatingItsAudio() {
        val gate = CandidateConfirmation()
        assertFalse(gate.observe("Thank you", 100, 16000, weak))
        assertFalse(gate.observe("Thank you", 100, 16000, weak))
        assertFalse(gate.observe("Thank you", 200, 16000, weak))
    }
    @Test fun weakSpeechNeedsFreshAudioAndConsistentWords() {
        val gate = CandidateConfirmation()
        assertFalse(gate.observe("Thank you", 100, 16000, weak))
        assertFalse(gate.observe("You", 400, 25600, weak))
        assertTrue(gate.observe("You can stop", 700, 35200, weak))
        assertEquals("fresh_audio_agreement", gate.reason)
    }
    @Test fun emptyOrEchoFilteredResultBreaksAgreement() {
        val gate = CandidateConfirmation()
        assertFalse(gate.observe("Thank you", 100, 16000, weak))
        assertFalse(gate.observe("", 400, 25600, weak))
        assertFalse(gate.observe("Thank you", 700, 35200, weak))
    }
    @Test fun strongShortWordsKeepSingleProbePathAndNoSpeechCannotPass() {
        for (word in listOf("No", "Yes", "I", "You", "Actually", "Thank you")) {
            assertTrue(CandidateConfirmation().observe(word, 100, 16000, CandidateSpeechEvidence.Snapshot(300, 300)))
        }
        val gate = CandidateConfirmation()
        assertFalse(gate.observe("Thank you", 100, 16000, CandidateSpeechEvidence.Snapshot(0, 0)))
        assertFalse(gate.observe("Thank you", 400, 25600, CandidateSpeechEvidence.Snapshot(0, 0)))
    }
}
