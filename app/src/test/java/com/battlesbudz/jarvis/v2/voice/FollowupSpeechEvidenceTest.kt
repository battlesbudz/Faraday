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
    @Test fun sustainedSpeechAllowsEmptyAsrFallback() {
        val e = FollowupSpeechEvidence()
        e.observe(8000, 0.8f, null, false, true); assertTrue(e.accepts())
        e.reset(); assertFalse(e.accepts())
    }
    @Test fun shortLiveCommandAndCorroboratedWhisperSurvive() {
        val e = FollowupSpeechEvidence()
        e.observe(3200, 0.9f, "No", false, true); assertTrue(e.accepts())
        e.reset(); e.observe(3200, 0.2f, "Please stop", true, false); assertTrue(e.accepts())
    }
}
