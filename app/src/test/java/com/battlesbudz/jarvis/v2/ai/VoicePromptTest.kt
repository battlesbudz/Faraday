package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy
import org.junit.Assert.*
import org.junit.Test

class VoicePromptTest {
    @Test fun voicePromptKeepsSummaryRecentTurnsCurrentCorrectionAndToolEvidence() {
        val context = ShortTermConversationContext().apply { updateSummary("The dog's name is Kiko.") }
        val builder = ConversationPromptBuilder(context)
        val history = listOf(ChatEntry("You", "Tell me about my dog."), ChatEntry("Jarvis", "Kiko likes walks."))
        val prompt = builder.buildGemmaPrompt("No, set it to 30 percent.", "Verified volume: 30 percent.", history, true, voice = true)
        for (text in listOf("The dog's name is Kiko.", "You: Tell me about my dog.",
            "Jarvis: Kiko likes walks.", "No, set it to 30 percent.", "Verified volume: 30 percent.")) {
            assertTrue("Missing context: $text", prompt.contains(text))
        }
        assertEquals(1, Regex(Regex.escape(VoiceResponsePolicy.instructions)).findAll(prompt).count())
    }

    @Test fun omittedSeedNeverReinjectsSummaryOrHistory() {
        val context = ShortTermConversationContext().apply { updateSummary("OLD_SUMMARY") }
        val prompt = ConversationPromptBuilder(context).buildGemmaPrompt("Hello", null,
            listOf(ChatEntry("You", "OLD_TURN")), false, voice = true)
        assertFalse(prompt.contains("OLD_SUMMARY")); assertFalse(prompt.contains("OLD_TURN"))
        assertTrue(prompt.contains("Hello"))
    }

    @Test fun storyAcceptanceAndRecallKeepTheirConditionalInstructions() {
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        val history = listOf(ChatEntry("You", "Tell me a story"), ChatEntry("Jarvis", "How about a pirate called Kiko?"))
        assertTrue(builder.buildGemmaPrompt("Yes", null, history, true, voice = true)
            .contains("accepting the pending story"))
        assertTrue(builder.buildGemmaPrompt("What did you say?", null, history, true, voice = true)
            .contains("recall of dialogue"))
    }

    @Test fun defaultChatPromptRemainsSeparateAndVoiceOverheadIsBounded() {
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        val chat = builder.buildGemmaPrompt("Hello", null, emptyList(), true)
        val voice = builder.buildGemmaPrompt("Hello", null, emptyList(), true, voice = true)
        assertTrue(chat.contains("Do not list your capabilities"))
        assertFalse(chat.contains("4–8 words"))
        assertTrue(voice.contains("Address the user as \"sir.\""))
        assertFalse(voice.contains("4–8 words"))
        assertTrue("Fixed voice overhead grew: ${voice.length}", voice.length < 1100)
        assertFalse(voice.contains("Answer what changed in the latest follow-up"))
    }
}
