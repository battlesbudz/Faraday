package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInputModeTest {
    @Test fun existingInstallKeepsRecognizedTextInput() {
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId(null))
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId("unknown_future_mode"))
    }
    @Test fun persistedAudioChoiceDoesNotChangeOtherInputModes() {
        assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.fromId("gemma_audio"))
        assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.fromId("transcribed_text"))
    }

    @Test fun legacyCaptionSettingKeepsOffAndWhisper() {
        assertNull(VoiceInputMode.resolveCaptionEngine(null, legacyEnabled = false))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.resolveCaptionEngine(null, legacyEnabled = true))
        assertNull(VoiceInputMode.resolveCaptionEngine("future_engine", legacyEnabled = false))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.resolveCaptionEngine("future_engine", legacyEnabled = true))
    }
    @Test fun retiredMoonshineCaptionsResolveToCpuWhisperUnlessPreviouslyOff() {
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.resolveCaptionEngine("moonshine_small", true))
        assertNull(VoiceInputMode.resolveCaptionEngine("moonshine_small", false))
    }
    @Test fun explicitDropdownChoiceSurvivesReopeningWithoutOverridingLegacyOff() {
        assertNull(VoiceInputMode.resolveCaptionEngine("off", legacyEnabled = true))
        AsrEngine.entries.forEach { engine ->
            assertEquals(engine, VoiceInputMode.resolveCaptionEngine(engine.id, legacyEnabled = true))
            assertNull(VoiceInputMode.resolveCaptionEngine(engine.id, legacyEnabled = false))
        }
    }
    @Test fun captionsOffGatesRecognitionButLeavesNormalAsrAvailable() {
        val engine = VoiceInputMode.resolveCaptionEngine(null, legacyEnabled = false)
        assertFalse(GemmaAudioInputPolicy.usesRecognizer(directAudio = true, captions = engine != null))
        assertTrue(GemmaAudioInputPolicy.usesRecognizer(directAudio = false, captions = engine != null))
        AsrEngine.entries.forEach {
            assertTrue(GemmaAudioInputPolicy.usesRecognizer(directAudio = true,
                captions = VoiceInputMode.resolveCaptionEngine(it.id, legacyEnabled = true) != null))
        }
    }
}
