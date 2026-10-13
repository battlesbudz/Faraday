package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceStopRequestTest {
    @Test fun clearFinalControlsStopWithoutAnotherAnswer() {
        for (text in listOf("Stop", "Please stop, Jarvis.", "I'll be. I did not request a story. Stop.",
            "Cancel that!", "Stop speaking", "Never mind", "That's enough.")) assertTrue(text, VoiceStopRequest.matches(text))
    }
    @Test fun quotedNegatedAndTaskSpecificUsesAreNotReplyCancellation() {
        for (text in listOf("Don't stop", "Do not stop.", "Don't stop. Keep talking.",
            "Say stop", "Tell me a story about a bus stop", "Stop the music", "Open Facebook",
            "What does stop mean?", "He said \"Stop.\"", "Goodbye", "Stop listening")) assertFalse(text, VoiceStopRequest.matches(text))
    }
}
