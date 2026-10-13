package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class NaturalCorrectionTextTest {
    @Test fun build709MisheardPlaybackIsNotSubmittedAsACorrection() {
        val reference = "This phenomenon occurs when sunlight interacts with the Earth's atmosphere. " +
            "The shorter blue wavelengths of light are scattered more effectively by the tiny molecules of air."
        assertNull(NaturalCorrectionText.resolve("tracks with the Earth's atmosphere. To shorter, blue wavelengths of light are scattered more effectively by the tiny molecules of", reference))
        assertEquals("No.", NaturalCorrectionText.resolve("No. tracks with the Earth's atmosphere.", reference))
    }
    @Test fun interruptionWordDoesNotBecomeAFreshQuestionOrIncludeTrailingEcho() {
        for (word in listOf("No", "Yes", "I")) {
            val text = NaturalCorrectionText.resolve("$word. One moment, please, sir.",
                "One moment, please, sir. A brave squirrel found an acorn.")!!
            assertEquals("$word.", text)
            assertTrue(NaturalCorrectionText.isFloorOnly(text))
        }
        assertFalse(NaturalCorrectionText.isFloorOnly("Goodbye"))
        assertFalse(NaturalCorrectionText.isFloorOnly("No, explain MVP instead"))
    }
    @Test fun retainsFinalAppCorrectionAfterRemovingLeadingEcho() {
        val text = NaturalCorrectionText.resolve(
            "The sky is blue. Open YouTube. Actually open Settings.", "The sky is blue.")!!
        assertEquals("Open YouTube. Actually open Settings.", text)
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
        assertTrue(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "Settings")))
    }
    @Test fun laterCancellationMustNeverBeDropped() {
        val text = NaturalCorrectionText.resolve("Open YouTube. Never mind, cancel.", "Hello there")!!
        assertTrue(text.contains("cancel"))
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
    }
    @Test fun echoOnlyOrEmptyFinalCannotDispatchProvisionalRequest() {
        assertNull(NaturalCorrectionText.resolve("Can you open Settings?", "Can you open Settings?"))
        assertNull(NaturalCorrectionText.resolve("", "The sky is blue"))
        assertEquals("mm hmm", NaturalCorrectionText.resolve("mm hmm", "The sky is blue"))
    }
    @Test fun punctuationAndNamesRemainIntact() {
        assertEquals("Actually, tell me about New York.",
            NaturalCorrectionText.resolve("Actually, tell me about New York.", "Hello there"))
    }
    @Test fun goodbyeCanPassTheInterruptionPathButAssistantEchoCannot() {
        assertEquals("Goodbye", NaturalCorrectionText.resolve("Goodbye", "Let me tell you a story"))
        assertEquals("Goodbye Jarvis", NaturalCorrectionText.resolve("Goodbye Jarvis", "Let me tell you a story"))
        assertNull(NaturalCorrectionText.resolve("Goodbye", "Goodbye sir"))
    }

}
