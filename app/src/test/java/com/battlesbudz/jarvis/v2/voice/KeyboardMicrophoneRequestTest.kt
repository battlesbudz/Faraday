package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class KeyboardMicrophoneRequestTest {
    private val keyboard = "com.samsung.android.honeyboard"
    @Test fun microphoneLabelOrKnownIdInSelectedKeyboardRequestsPriority() {
        assertTrue(KeyboardMicrophoneRequest.matches(keyboard, keyboard, "Voice input", null, false))
        assertTrue(KeyboardMicrophoneRequest.matches(keyboard, keyboard, " Voice typing ", null, false))
        assertTrue(KeyboardMicrophoneRequest.matches(keyboard, keyboard, null, "$keyboard:id/voice_input", false))
    }
    @Test fun ordinaryTypingAndKeyboardVisibilityNeverRequestPriority() {
        for (description in listOf(null, "Keyboard", "a", "Space", "Enter", "Voice input settings", "Emoji")) {
            assertFalse(KeyboardMicrophoneRequest.matches(keyboard, keyboard, description, null, false))
        }
    }
    @Test fun otherAppsAndEditableFieldsCannotImpersonateTheMicrophoneButton() {
        assertFalse(KeyboardMicrophoneRequest.matches(keyboard, "com.facebook.orca", "Voice input", null, false))
        assertFalse(KeyboardMicrophoneRequest.matches(keyboard, keyboard, "Voice input", null, true))
        assertFalse(KeyboardMicrophoneRequest.matches(null, keyboard, "Voice input", null, false))
        assertFalse(KeyboardMicrophoneRequest.matches(keyboard, keyboard, null, "other:id/mic", false))
    }
}
