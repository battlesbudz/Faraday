package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TurnOrchestratorTest {
    @Test fun newQuestionAndCorrectionCannotInheritMvpSubject() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        assertEquals("MVP", turns.plan("What is MVP?").activeSubject)
        assertEquals(null, turns.plan("You're not repeating the right information?").activeSubject)
        turns.plan("What is MVP?")
        turns.reset()
        assertEquals(null, turns.plan("What about that?").activeSubject)
    }

    @Test fun unspokenLookupOfferCannotArmConfirmation() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val plan = turns.plan("Who is Jack Herer?")
        turns.recordResponse("Who is Jack Herer?", "Would you like me to search Wikipedia?", plan)
        turns.reconcileVoiceDelivery("")
        assertTrue(turns.plan("Yes").kind != TurnKind.LOOKUP_CONFIRMATION)
    }
    @Test fun deliveredLookupOfferRetainsItsConfirmation() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        turns.plan("Who is Jack Herer?")
        turns.reconcileVoiceDelivery("Would you like me to search Wikipedia?")
        assertEquals(TurnKind.LOOKUP_CONFIRMATION, turns.plan("Yes").kind)
    }

    @Test
    fun namedEntityWithMiddleInitialTriggersAutomaticGrounding() {
        val grounding = ReferenceGroundingClient()

        assertTrue(grounding.shouldAutomaticallyLookup("Who is Harry J. Anslinger?"))
    }

    @Test
    fun lowercasePersonQuestionStillTriggersAutomaticGrounding() {
        assertTrue(ReferenceGroundingClient().shouldAutomaticallyLookup("who is jack herer"))
    }

    @Test
    fun whoNameWithoutIsStillTriggersAutomaticGrounding() {
        assertTrue(ReferenceGroundingClient().shouldAutomaticallyLookup("Who Jack Herer?"))
    }

    @Test
    fun knowledgeBaseFallbackAnswerIsInsufficient() {
        assertTrue(
            ReferenceGroundingClient().isInsufficientAnswer(
                "I do not have any specific information about Jack Herer in my current knowledge base."
            )
        )
    }

    @Test
    fun naturalApprovalPhrasesTriggerLookupConfirmation() {
        val grounding = ReferenceGroundingClient()

        assertTrue(grounding.isLookupConfirmation("Yes, of course."))
        assertTrue(grounding.isLookupConfirmation("Yeah, sounds like a good idea."))
        assertTrue(grounding.isLookupConfirmation("Go for it."))
        assertTrue(grounding.isLookupConfirmation("Yeah, of course."))
        assertTrue(grounding.isLookupConfirmation("Sure, I guess."))
    }

    @Test
    fun negativeReplyDoesNotTriggerLookupConfirmation() {
        assertTrue(!ReferenceGroundingClient().isLookupConfirmation("No, don't search."))
    }

    @Test
    fun factualTurnCarriesLookupQueryBeforeGeneration() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        val plan = orchestrator.plan("Who was Harry J. Anslinger?")

        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, plan.kind)
        assertEquals("Harry J. Anslinger", plan.lookupQuery)
    }

    @Test
    fun explicitLookupFollowUpRetainsTheNewestFactualSubject() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Hey there, Jarvis. Can you tell me about Harry J. Anslinger?")
        val plan = orchestrator.plan("Use Wikipedia.")

        assertEquals(TurnKind.EXPLICIT_LOOKUP, plan.kind)
        assertEquals("Harry J. Anslinger", plan.activeSubject)
        assertEquals("Harry J. Anslinger", plan.lookupQuery)
    }

    @Test
    fun explicitLookupDoesNotSearchForTheCommandAlone() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Who was Harry J. Anslinger?")
        val query = orchestrator.plan("Check Wikipedia for that.").lookupQuery

        assertEquals("Harry J. Anslinger", query)
    }
    @Test fun naturalActionsPreemptFactualLookupAndClearPriorSubject() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        turns.plan("Who is Jack Herer?")
        val plan = turns.plan("Can you open up Facebook and tell me what my battery percentage is?")
        assertEquals(TurnKind.NORMAL_CHAT, plan.kind)
        assertEquals(null, plan.lookupQuery)
        assertEquals(null, plan.activeSubject)
        val ready = plan.actionPlan as com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
        assertEquals(listOf("open_app", "read_battery"), ready.steps.map { it.request.name })
        assertEquals("Facebook", ready.steps.first().request.arguments["app"])
        val retry = turns.plan("I said, can you open up the fistbook and tell me what my battery percentage is?")
        assertEquals(TurnKind.NORMAL_CHAT, retry.kind)
        assertEquals("fistbook", (retry.actionPlan as com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready).steps.first().request.arguments["app"])
    }

    @Test fun pendingLookupIsClearedByNaturalAction() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val factual = turns.plan("Who is Jack Herer?")
        turns.recordResponse("Who is Jack Herer?", "Would you like me to search Wikipedia?", factual)
        val action = turns.plan("Can you open up Facebook and tell me what my battery percentage is?")
        assertTrue(action.actionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready)
        assertEquals(TurnKind.NORMAL_CHAT, action.kind)
        assertEquals(null, action.lookupQuery)
        assertTrue(turns.plan("Yes").kind != TurnKind.LOOKUP_CONFIRMATION)
    }

    @Test fun acronymLookupAndNegativeCorrectionRetainConversationDomain() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val history = listOf("You" to "I'm making nutrients using Korean Natural Farming", "You" to "how do I make any of it? ingredients and ratios?")
        val query = turns.plan("what is fpj", history).lookupQuery!!
        assertTrue(query.contains("Korean Natural Farming"))
        val retry = turns.plan("no", history + listOf("You" to "what is fpj", "Jarvis" to "FPJ is an actor."))
        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, retry.kind)
        assertTrue(retry.lookupQuery!!.contains("Korean Natural Farming"))
    }
    @Test fun recipesAreGroundedButCategoryRecallStaysLocal() {
        val grounding = ReferenceGroundingClient()
        assertTrue(grounding.shouldAutomaticallyLookup("how do you make water soluble calcium naturally?"))
        assertFalse(grounding.shouldAutomaticallyLookup("what fruit do I like?"))
    }
    @Test fun replacementCorrectionLooksUpNewQuestionAndProcedureKeepsItsRequestedInput() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val history = listOf("You" to "what is fpj", "Jarvis" to "FPJ is an actor.")
        assertTrue(turns.plan("No, what is water soluble calcium?", history).lookupQuery!!.contains("water soluble calcium"))
        assertFalse(turns.plan("No, what is water soluble calcium?", history).lookupQuery!!.contains("fpj"))
        assertTrue(turns.plan("how do you make water soluble calcium in KNF?").lookupQuery!!.contains("water soluble calcium"))
    }
    @Test fun topicSwitchDoesNotRequireOldDomainAndUrlOnlyTurnKeepsQuestion() {
        val turns = TurnOrchestrator(ReferenceGroundingClient())
        val history = listOf("You" to "I'm making nutrients using Korean Natural Farming", "You" to "Let's switch topics to cooking Italian food.")
        assertFalse(turns.plan("how do I make pasta?", history).lookupQuery!!.contains("Korean Natural Farming"))
        val source = turns.plan("https://example.org/reference.pdf", listOf("You" to "how do you make water soluble calcium?"))
        assertTrue(source.lookupQuery!!.contains("water soluble calcium"))
        assertTrue(source.lookupQuery!!.contains("https://example.org/reference.pdf"))
    }
}
