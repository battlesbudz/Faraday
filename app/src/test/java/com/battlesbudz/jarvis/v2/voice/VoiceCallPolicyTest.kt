package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCallPolicyTest {
    @Test fun reportedRecognitionArtifactsDoNotHideAnExplicitEndRequest() {
        for (text in listOf("1. Stop listening. 2.", "10. Stop listening.", "Stop listening. Yeah", "Goodbye. Thanks."))
            assertTrue(text, VoiceCallPolicy.isGoodbye(text))
        for (text in listOf("He said. Stop listening. Yeah", "List 1. Stop listening. 2. Open apps", "Don't stop listening. Yeah"))
            assertFalse(text, VoiceCallPolicy.isGoodbye(text))
    }
    @Test fun explicitWholeUtteranceGoodbyeEndsCall() {
        for (text in listOf("Goodbye!", "Goodbye, Jarvis.", "Stop listening", "Jarvis, stop listening.")) {
            assertTrue(text, VoiceCallPolicy.isGoodbye(text))
        }
    }
    @Test fun MentioningGoodbyeOrQuotingItInARequestDoesNotHangUp() {
        for (text in listOf("Tell me a story about saying goodbye", "Don't stop listening", "What does goodbye mean?", "")) {
            assertFalse(text, VoiceCallPolicy.isGoodbye(text))
        }
    }
    @Test fun turnSilenceAndCallInactivityAreSeparate() {
        assertEquals(3000L, VoiceCallPolicy.TURN_SILENCE_MS)
        assertEquals(20000L, VoiceCallPolicy.CALL_INACTIVITY_MS)
    }
    @Test fun politeGoodbyeEndsButReportedOrNegatedGoodbyeDoesNot() {
        for (text in listOf("Uh, no thank you. Goodbye.", "Okay goodbye Jarvis", "Thanks, goodbye!", "Goodbye Jarvis please"))
            assertTrue(text, VoiceCallPolicy.isGoodbye(text))
        for (text in listOf("Don't say goodbye", "He said goodbye", "Say goodbye Jarvis", "No goodbye yet", "No goodbye", "The story ends with goodbye.", "He said. Goodbye.", "Say \"goodbye\""))
            assertFalse(text, VoiceCallPolicy.isGoodbye(text))
    }

    @Test fun singleQuotedFarewellsAreNotDirectControlsButContractionsRemainValid() {
        for (text in listOf("'goodbye'", "‘goodbye’", "'stop listening'", "Okay, 'goodbye'.", "Goodbye'"))
            assertFalse(text, VoiceCallPolicy.isGoodbye(text))
        for (text in listOf("That's all, goodbye.", "That’s all, goodbye.", "That is all, goodbye."))
            assertTrue(text, VoiceCallPolicy.isGoodbye(text))
    }

}
