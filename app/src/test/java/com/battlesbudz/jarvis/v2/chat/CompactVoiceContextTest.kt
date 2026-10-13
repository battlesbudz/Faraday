package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.*
import org.junit.Test

class CompactVoiceContextTest {
    @Test fun onlyExactDuplicateAnchorIsRemoved() {
        val context = ShortTermConversationContext()
        val history = listOf("You" to "My dog is Kiko.", "Jarvis" to "Tell me about Kiko.")
        val compact = context.promptContext(history, compact = true)
        assertEquals(1, Regex(Regex.escape("My dog is Kiko.")).findAll(compact).count())
        assertTrue(compact.contains("Jarvis: Tell me about Kiko."))
        assertEquals(2, Regex(Regex.escape("My dog is Kiko.")).findAll(context.promptContext(history)).count())
    }

    @Test fun longAnchorRetainsInformationBeyondRecentTurnLimit() {
        val topic = "x".repeat(350) + " Important old detail."
        val compact = ShortTermConversationContext().promptContext(listOf("You" to topic), compact = true)
        assertTrue(compact.contains("Important old detail."))
    }

    @Test fun newestRequestSurvivesAnOversizedSummaryAndOlderReplies() {
        val context = ShortTermConversationContext().apply { updateSummary("s".repeat(2_000)) }
        val history = listOf("You" to "Original dog topic.") +
            (1..7).map { "Jarvis" to "x".repeat(450) } + ("You" to "Original dog topic.")
        val compact = context.promptContext(history, compact = true)
        assertTrue(compact.endsWith("You: Original dog topic."))
        assertTrue(compact.length <= 3_000)
    }

    @Test fun immediateRapExchangeSurvivesMaximalSummaryAndHistory() {
        val context = ShortTermConversationContext().apply { updateSummary("s".repeat(2000)) }
        val history = (1..6).map { "Jarvis" to "Old reply $it ${"x".repeat(450)}" } +
            listOf("You" to "No, a text rap, like rap for me.",
                "Jarvis" to "Yo, check the mic, one two, the flow is precise. A text-based rhythm, a story without end.")
        val prompt = context.promptContext(history, compact = true)
        assertTrue(prompt.contains("You: No, a text rap, like rap for me."))
        assertTrue(prompt.endsWith("A text-based rhythm, a story without end."))
        assertTrue(prompt.length <= 3000)
        assertTrue(prompt.indexOf("You: No, a text rap") < prompt.indexOf("Jarvis: Yo, check"))
    }

    @Test fun oldAnchorOutsideRecentWindowAndSummaryArePreserved() {
        val context = ShortTermConversationContext().apply { updateSummary("Persistent summary detail.") }
        val history = listOf("You" to "Original dog topic.") + (1..10).map { "You" to "Recent request $it." }
        val compact = context.promptContext(history, compact = true)
        assertTrue(compact.contains("Original dog topic."))
        assertTrue(compact.contains("Persistent summary detail."))
        assertTrue(compact.contains("Recent request 10."))
    }
}
