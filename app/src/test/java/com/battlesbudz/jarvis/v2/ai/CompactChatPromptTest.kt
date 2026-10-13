package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import org.junit.Assert.*
import org.junit.Test

class CompactChatPromptTest {
    @Test fun smallCachePreservesCurrentRequestAndNewestDialogue() {
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        val history = (1..8).map { ChatEntry(if (it % 2 == 0) "Jarvis" else "You", "Earlier text ".repeat(100)) } +
            listOf(ChatEntry("You", "My dog is Luna"), ChatEntry("Jarvis", "Luna is a lovely name"))
        val prompt = builder.buildGemmaPrompt("What is her name?", null, history, true, compactInstructions = true)
        assertTrue(prompt.contains("What is her name?"))
        assertTrue(prompt.contains("Luna"))
        assertTrue(prompt.length < 1840)
    }
}
