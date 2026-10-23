package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import org.junit.Assert.*
import org.junit.Test

class ConversationPromptTest {
    private fun prompt() = ConversationPrompt(ConversationPromptBuilder(ShortTermConversationContext()),
        voice = false, contextTokens = { 4096 }, memoryContext = { null })

    @Test fun budgetFallbackDropsBackgroundButPreservesResolvedQuestionAndEvidence() {
        val prompt = prompt()
        val current = "Explain the current topic"
        val evidence = "Verified reference evidence for this question."
        val base = prompt.assemble(current, null, emptyList(), false, "current topic", current,
            evidence, 10_000)
        val history = listOf(ChatEntry("You", "old background ".repeat(100)),
            ChatEntry("Jarvis", "old reply ".repeat(100)))
        val result = prompt.assemble(current, null, history, true, "current topic", current,
            evidence, base.text.length + ConversationPolicy.GENERATION_HEADROOM + 1)
        assertFalse(result.seeded)
        assertEquals(base.text, result.text)
        assertTrue(result.text.contains("Resolved subject for this turn: current topic"))
        assertTrue(result.text.contains("Resolved current question: $current"))
        assertTrue(result.text.endsWith(evidence))
        assertFalse(result.text.contains("old background"))
    }

    @Test fun resolvedQuestionWithoutEvidenceDirectsAKnowledgeAnswer() {
        val result = prompt().assemble("What is the capital of France?", null, emptyList(), false,
            "France", "What is the capital of France?", null, 10_000)
        assertTrue(result.text.contains("Answer this question directly from your own knowledge"))
        assertTrue(result.text.contains("Do not mention reference material, sources, or lookup unless the user asked about them"))
        assertFalse(result.text.contains("using the reference evidence included below"))
    }

    @Test fun resolvedQuestionWithEvidenceUsesTheIncludedEvidence() {
        val evidence = "Verified reference evidence for this question."
        val result = prompt().assemble("What is the capital of France?", null, emptyList(), false,
            "France", "What is the capital of France?", evidence, 10_000)
        assertTrue(result.text.contains("Answer this question using the reference evidence included below"))
        assertFalse(result.text.contains("directly from your own knowledge"))
        assertTrue(result.text.endsWith(evidence))
    }

    @Test fun sufficientBudgetKeepsTheCurrentNativeSessionSeed() {
        val result = prompt().assemble("Keep going", null,
            listOf(ChatEntry("You", "The newest context is a garden")), true,
            null, null, null, 10_000)
        assertTrue(result.seeded)
        assertTrue(result.text.contains("The newest context is a garden"))
        assertTrue(result.text.contains("Keep going"))
    }

    @Test fun smallContextAccountsForVisionReservationAndDefaultCap() {
        assertEquals(10_000, ConversationPrompt.contextLimit(null, true))
        assertEquals(6_144, ConversationPrompt.contextLimit(2048, false))
        assertEquals(4_344, ConversationPrompt.contextLimit(2048, true))
        assertEquals(10_000, ConversationPrompt.contextLimit(4096, false))
    }
}
