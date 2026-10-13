package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class DialogueContextPolicyTest {
    private val proposal = listOf("You" to "Tell me a story. Your choice, maybe monkeys or pirates.",
        "Jarvis" to "How about a tale about a clever monkey who became a pirate?")
    private val story = "Once upon a time, Kiko was a clever monkey who became a pirate. " +
        "He sailed across the ocean, found an island, and helped his crew discover a hidden library. ".repeat(5)
    private val history = proposal + listOf("You" to "Sure, that works for me.", "Jarvis" to story)

    @Test fun recallOfStoryStaysLocalEvenWithFactualQuestionShape() {
        val router = TurnOrchestrator(ReferenceGroundingClient())
        val plan = router.plan("What was the name of the monkey?", history)
        assertEquals(TurnKind.NORMAL_CHAT, plan.kind)
        assertNull(plan.lookupQuery)
        assertTrue(DialogueContextPolicy.resolve("What was his name?", history).recall)
        assertTrue(DialogueContextPolicy.resolve("What did you say?", history).recall)
    }
    @Test fun externalFactsAndExplicitSearchStillUseGrounding() {
        val router = TurnOrchestrator(ReferenceGroundingClient())
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, router.plan("Who was Harry J. Anslinger?", history).kind)
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, router.plan("What is gravity?", history).kind)
        assertEquals(TurnKind.EXPLICIT_LOOKUP, router.plan("Search Wikipedia for monkeys", history).kind)
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, router.plan("What was the name of the monkey?").kind)
    }
    @Test fun approvalOfPendingStoryContinuesInsteadOfClosingConversation() {
        assertNotNull(DialogueContextPolicy.resolve("Sure, that works for me.", proposal).storyInstruction)
        val stalled = proposal + listOf("You" to "Sure, that works for me.",
            "Jarvis" to "I'm glad that works for you. Is there anything else I can do?")
        assertNotNull(DialogueContextPolicy.resolve("Yeah, do what you actually said you were going to do.", stalled).storyInstruction)
        assertNotNull(DialogueContextPolicy.resolve("I really do. Yes, do that.", stalled).storyInstruction)
    }
    @Test fun completedStoryOrNewTaskDoesNotGetRestartedByYes() {
        assertNull(DialogueContextPolicy.resolve("Sure, that works for me.", history).storyInstruction)
        assertNull(DialogueContextPolicy.resolve("Yes", proposal + listOf("You" to "Open Facebook", "Jarvis" to "Opened Facebook.")).storyInstruction)
        assertNull(DialogueContextPolicy.resolve("No, don't tell that story.", proposal).storyInstruction)
        assertNull(DialogueContextPolicy.resolve("Yes, open Facebook.", proposal).storyInstruction)
        assertNull(DialogueContextPolicy.resolve("Search Wikipedia for the monkey in that story", history).storyInstruction)
        assertFalse(DialogueContextPolicy.resolve("Search Wikipedia for the monkey in that story", history).recall)
    }
    @Test fun openEndedStoryRequestDoesNotBecomeFactualLookupFromPeopleOrPlaces() {
        val router = TurnOrchestrator(ReferenceGroundingClient())
        assertEquals(TurnKind.NORMAL_CHAT, router.plan("Make me a story about people on the moon. Your choice.").kind)
        assertNotNull(DialogueContextPolicy.resolve("I would like you to tell me a story of your choice.", emptyList()).storyInstruction)
    }
    @Test fun build604CharacterFollowupUsesDialogueInsteadOfWikipedia() {
        val question = "Okay, so... Who was the person you were talking about?"
        val router = TurnOrchestrator(ReferenceGroundingClient())
        assertEquals(TurnKind.NORMAL_CHAT, router.plan(question, history).kind)
        assertNull(router.plan(question, history).lookupQuery)
        assertFalse(DialogueContextPolicy.resolve(question, emptyList()).recall)
        assertEquals(TurnKind.EXPLICIT_LOOKUP,
            router.plan("Search Wikipedia for the person you were talking about", history).kind)
    }
}
