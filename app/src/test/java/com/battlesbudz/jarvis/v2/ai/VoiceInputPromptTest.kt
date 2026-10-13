package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import org.junit.Assert.*
import org.junit.Test

class VoiceInputPromptTest {
    @Test fun livePrefixMatchesFinalPromptIncludingRecallAndStoryInstructions() {
        val context = ShortTermConversationContext()
        context.updateSummary("We discussed Rome.")
        val builder = ConversationPromptBuilder(context)
        val history = listOf(ChatEntry("You", "Tell me about Rome"), ChatEntry("Jarvis", "Rome is in Italy."))
        for (message in listOf("Tell me more.", "What did I ask?", "Tell me a story.", "How do computers work?")) {
            val prefix = builder.voiceInputPrefix(history)
            val final = builder.buildGemmaPrompt(message, null, history, true, voice = true)
            assertTrue(final.startsWith(prefix + message))
            assertEquals(1, Regex("Current user message:").findAll(final).count())
        }
    }
    @Test fun freshCallHasNoEarlierDialogueInItsPrefix() {
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        val prefix = builder.voiceInputPrefix(emptyList())
        assertTrue(builder.buildGemmaPrompt("Hello.", null, emptyList(), true, voice = true).startsWith(prefix))
        assertFalse(prefix.contains("Recent conversation:"))
    }
}
