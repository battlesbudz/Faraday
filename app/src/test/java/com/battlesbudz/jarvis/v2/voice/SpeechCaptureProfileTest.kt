package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechCaptureProfileTest {
    private fun assertCallProcessing(id: String?) {
        val profile = SpeechCaptureProfile.fromId(id)
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, profile)
        assertEquals("communication_noise_filtered", profile.id)
        assertTrue(profile.communicationInput)
        assertTrue(profile.noiseSuppression)
    }

    @Test fun freshInstallUsesCallProcessing() = assertCallProcessing(null)
    @Test fun legacySpeechClarityUsesCallProcessing() = assertCallProcessing("speech_preserving")
    @Test fun existingCallProcessingRemainsSelected() = assertCallProcessing("communication_noise_filtered")
    @Test fun emptyAndUnknownPreferencesUseCallProcessing() {
        for (id in listOf("", "missing_profile", "SPEECH_PRESERVING")) assertCallProcessing(id)
    }
    @Test fun repeatedResolutionAndCanonicalRoundTripsKeepCallProcessing() {
        for (original in listOf(null, "speech_preserving", "communication_noise_filtered", "unknown")) {
            var id = original
            repeat(5) {
                assertCallProcessing(id)
                id = SpeechCaptureProfile.fromId(id).id
            }
        }
    }
    @Test fun noRetiredProfileCanBeSelected() {
        assertEquals(listOf(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED), SpeechCaptureProfile.entries)
    }
}
