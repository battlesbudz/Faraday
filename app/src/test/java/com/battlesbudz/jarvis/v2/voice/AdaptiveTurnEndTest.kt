package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class AdaptiveTurnEndTest {
    @Test fun shortPunctuatedQuestionsCanFinishQuickly() {
        for (text in listOf("Who are you?", "What’s your name?", "Are you okay?")) {
            val policy = AdaptiveTurnEnd()
            policy.update(text, 0)
            assertEquals(text, 350, policy.decision(350).silenceMs.toInt())
        }
    }

    @Test fun completeQuestionWaitsForStableTextThenUsesShortSilence() {
        val policy = AdaptiveTurnEnd()
        policy.update("What is the current battery percentage?", 100)
        assertEquals(1100, policy.decision(399).silenceMs.toInt())
        assertEquals(350, policy.decision(400).silenceMs.toInt())
        policy.update("What is the current volume?", 450)
        assertEquals(1100, policy.decision(451).silenceMs.toInt())
    }

    @Test fun unchangedPartialDoesNotRestartStabilityClock() {
        val policy = AdaptiveTurnEnd()
        policy.update("WHAT is the current volume?", 0)
        policy.update("what  is the current volume?", 250)
        assertEquals(350, policy.decision(350).silenceMs.toInt())
    }

    @Test fun explicitHesitationStillAllowsThinkingTime() {
        for (text in listOf("Tell me about, um...", "What is the battery percentage, um", "No, I mean", "Hold on")) {
            val policy = AdaptiveTurnEnd()
            policy.update(text, 0)
            assertEquals(text, 3500, policy.decision(1000).silenceMs.toInt())
        }
    }

    @Test fun asrEllipsisDoesNotInventAnExplicitHesitation() {
        for (text in listOf("What does...", "What does…", "Tell me about...")) {
            val policy = AdaptiveTurnEnd()
            policy.update(text, 0)
            assertEquals(text, "unfinished", policy.decision(1000).cue)
            assertEquals(1800, policy.decision(1000).silenceMs.toInt())
        }
    }

    @Test fun uncertainLastWordDoesNotForceThreeAndAHalfSeconds() {
        for (text in listOf("What is the?", "Can you tell me?", "Set the volume to", "Do you know why?")) {
            val policy = AdaptiveTurnEnd()
            policy.update(text, 0)
            assertEquals(text, 1800, policy.decision(1000).silenceMs.toInt())
        }
    }
    @Test fun shortAnswersCanFinishButUnknownAndMissingTextStayConservative() {
        val policy = AdaptiveTurnEnd()
        assertEquals(3000, policy.decision(1000).silenceMs.toInt())
        policy.update("yes please", 0)
        assertEquals(350, policy.decision(1000).silenceMs.toInt())
        policy.update("a longer idea without punctuation", 0)
        assertEquals(1500, policy.decision(1000).silenceMs.toInt())
        policy.reset()
        assertEquals(3000, policy.decision(2000).silenceMs.toInt())
    }
}
