package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenVolumeLevelTest {
    @Test fun acceptsAsrWordsAndLateCorrections() {
        assertEquals(20, SpokenVolumeLevel.fromTranscript("SET THE VOLUME TO FIFTY ACTUALLY TWENTY"))
        assertEquals(65, SpokenVolumeLevel.fromTranscript("set volume to sixty five percent"))
        assertEquals(100, SpokenVolumeLevel.fromTranscript("set volume to one hundred"))
        assertEquals(20, SpokenVolumeLevel.fromTranscript("set volume to 50 actually 20"))
        assertNull(SpokenVolumeLevel.fromTranscript("a story about twenty pirates"))
    }
    @Test fun guardAcceptsSpacedAppNamesAndSpokenNumbers() {
        assertTrue(FinalVoiceToolGuard.allows("open you tube", "open_app", mapOf("app" to "YouTube")))
        assertTrue(FinalVoiceToolGuard.allows("set volume to fifty actually twenty", "set_volume", mapOf("level" to "20")))
        assertFalse(FinalVoiceToolGuard.allows("set volume to fifty actually twenty", "set_volume", mapOf("level" to "50")))
    }
}
