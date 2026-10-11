package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class GemmaVoicePrefaceTest {
    @Test fun onlyFixedPolicyMovesAndDynamicContextRemainsUserText() {
        val dynamic = "Recent conversation: private history\n\nCurrent user message:\nListen.\n\n[Supporting context]\nprivate memory"
        assertEquals(dynamic, GemmaVoicePreface.userText(GemmaVoicePreface.instructions + "\n\n" + dynamic))
        assertFalse(GemmaVoicePreface.instructions.contains("private history"))
        assertFalse(GemmaVoicePreface.instructions.contains("private memory"))
    }
    @Test fun unknownCompactOrModifiedPrefixesCannotBeStripped() {
        for (prompt in listOf("", "Current user message:\nhello", GemmaVoicePreface.instructions,
            GemmaVoicePreface.instructions + "\n\n", " " + GemmaVoicePreface.instructions + "\n\nrequest",
            "You are Jarvis, a private assistant.\n\nCurrent user message:\nhello")) {
            assertNull(GemmaVoicePreface.userText(prompt))
        }
    }
    @Test fun repeatedPolicyInsideDynamicInputIsNeverRemoved() {
        val dynamic = "Current user message:\nQuote: " + GemmaVoicePreface.instructions
        assertEquals(dynamic, GemmaVoicePreface.userText(GemmaVoicePreface.instructions + "\n\n" + dynamic))
    }
}
