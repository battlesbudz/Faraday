package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class FollowupSpeechEvidenceTest {
    @Test fun silentAndIsolatedSoundsDoNotQualify() {
        val e = FollowupSpeechEvidence()
        e.observe(32000, 0.01f, "Thank you", false, false); assertFalse(e.accepts())
        e.observe(3200, 0.9f, null, false, true); assertFalse(e.accepts())
    }
    @Test fun unconfirmedVadSpikesCannotAccumulateIntoFollowupSpeech() {
        val e = FollowupSpeechEvidence()
        repeat(20) { e.observe(3200, 0.6f, "Thank you", false, false) }
        assertFalse(e.accepts())
        assertEquals(0L, e.strongMs)
    }
    @Test fun sustainedStrongAudioWithoutWordsOrCorroborationIsRejected() {
        // Build 1669 device specimen: 704 ms of strong audio with no live
        // words and no quiet corroboration was admitted as a follow-up turn
        // and ended in a spoken clarify to an empty room. It must die silently.
        val e = FollowupSpeechEvidence()
        e.observe(22_528, 0.9f, null, false, true)
        assertEquals(704L, e.strongMs)
        assertEquals("strongAudioMs=704 liveWords=false quietCorroborated=false", e.diagnostic())
        assertFalse(e.accepts())
        e.reset(); assertFalse(e.accepts())
    }
    @Test fun sustainedStrongAudioStillAdmitsOnAudioOnlyTurns() {
        // Same evidence, different judge: on captionOnly captures the retained
        // audio is the request and Gemma hears it, so the historical
        // sustained-audio admission stands there (no ASR chain downstream).
        val e = FollowupSpeechEvidence()
        e.observe(22_528, 0.9f, null, false, true)
        assertEquals(704L, e.strongMs)
        assertFalse(e.accepts())
        assertTrue(e.acceptsAudioOnly())
        e.reset(); assertFalse(e.acceptsAudioOnly())
    }
    @Test fun shortOrWordlessAudioOnlyCandidatesStillNeedEvidence() {
        val e = FollowupSpeechEvidence()
        e.observe(3200, 0.9f, null, false, true); assertFalse(e.acceptsAudioOnly())
    }
    @Test fun sustainedSpeechWithLiveWordsStillAdmits() {
        val e = FollowupSpeechEvidence()
        e.observe(8000, 0.8f, "what time is it", false, true); assertTrue(e.accepts())
    }
    @Test fun sustainedSpeechWithCorroborationStillAdmits() {
        val e = FollowupSpeechEvidence()
        e.observe(8000, 0.8f, null, true, true); assertTrue(e.accepts())
    }
    @Test fun liveWordsAdmitBeforeAnyAsrOutcome() {
        // The gate reads live evidence only: speech whose partials produced
        // words is admitted even if the final ASR later fails, and that
        // failure still reaches the existing clarify path downstream.
        val e = FollowupSpeechEvidence()
        e.observe(3200, 0.9f, "mumbled request", false, true); assertTrue(e.accepts())
    }
    @Test fun shortLiveCommandAndCorroboratedWhisperSurvive() {
        val e = FollowupSpeechEvidence()
        e.observe(3200, 0.9f, "No", false, true); assertTrue(e.accepts())
        e.reset(); e.observe(3200, 0.2f, "Please stop", true, false); assertTrue(e.accepts())
    }
}
