package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class LastReplyRecallTest {
    private val history = listOf("You" to "How are you?", "Jarvis" to "I'm doing well today.",
        "You" to "What is my battery?", "Jarvis" to "Battery is at 21 percent.")
    @Test fun repeatsLatestToolResultInsteadOfEarlierSmallTalk() {
        for (prompt in listOf("What did you say?", "Sorry, can you say that one more time?", "Repeat that please."))
            assertEquals("Battery is at 21 percent.", LastReplyRecall.resolve(prompt, history))
    }
    @Test fun compoundRequestsAndSpecificEarlierReferencesRemainModelTurns() {
        for (prompt in listOf("What did you say about the story?", "Repeat that and open Facebook", "What is my battery?"))
            assertNull(LastReplyRecall.resolve(prompt, history))
        assertNull(LastReplyRecall.resolve("What did you say?", emptyList()))
    }
}
