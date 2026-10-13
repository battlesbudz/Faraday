package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.*
import org.junit.Test

class TurnContinuityTest {
    @Test fun preferenceSurvivesLongExchangeWithoutBeingApprovedMemory() {
        val history = listOf("You" to "I like apricots") + (1..30).flatMap { listOf("You" to "Question $it", "Jarvis" to "Answer $it") }
        val section = TurnContinuity.section("what fruit do I like?", history)
        assertTrue(section.contains("I like apricots"))
        assertTrue(section.contains("not approved saved memory"))
        assertTrue(section.length <= 1600)
    }
    @Test fun correctionMarksAssistantClaimAsDisputedAndKeepsTopic() {
        val section = TurnContinuity.section("No, I was asking you to debate that", listOf("You" to "I'm making nutrients using Korean Natural Farming", "Jarvis" to "FPJ is a politician."))
        assertTrue(section.contains("claim is disputed"))
        assertTrue(section.contains("Korean Natural Farming"))
        assertTrue(TurnContinuity.isCorrection("no"))
        assertFalse(TurnContinuity.isCorrection("No problem, tell me a story"))
    }
    @Test fun acronymUsesDeclaredDomainAfterSeveralGenericQuestions() {
        val history = listOf("You" to "I'm making nutrients using Korean Natural Farming", "You" to "how do I make any of it? ingredients and ratios?")
        assertTrue(TurnContinuity.lookupContext("what is fpj", history)!!.contains("Korean Natural Farming"))
        assertNull(TurnContinuity.lookupContext("Who is Jack Herer?", history))
    }
    @Test fun restrictedInputIsNotCopiedAndCutoffHistoryCannotReappear() {
        assertFalse(TurnContinuity.section("recall", listOf("You" to "My password is superSecret123!")).contains("superSecret"))
        assertEquals("", TurnContinuity.section("what fruit do I like?", emptyList()))
    }
    @Test fun topicSwitchIncludesNewDeclarationAndExcludesPriorDomain() {
        val history = listOf("You" to "I'm making dinner using Italian cooking", "You" to "Let's talk about Korean Natural Farming")
        val domain = TurnContinuity.lookupContext("what is fpj?", history)!!
        assertTrue(domain.contains("Korean Natural Farming"))
        assertFalse(domain.contains("Italian"))
        assertTrue(TurnContinuity.lookupContext("what is fpj?", listOf("You" to "New topic: Korean Natural Farming"))!!.contains("Korean Natural Farming"))
        assertEquals("Let's talk about Korean Natural Farming", TurnContinuity.precedingSubstantiveRequest("https://example.org/ref.pdf", history + listOf("You" to "no")))
    }
    @Test fun disputedAsrDateIsCorrectionAndSourceSelectionIsNotAQuestion() {
        assertTrue(TurnContinuity.isCorrection("I didn't say anything about a year."))
        val history = listOf("You" to "Where was the first one opened?", "You" to "Use Wikipedia.")
        assertEquals("Where was the first one opened?", TurnContinuity.precedingSubstantiveRequest("https://example.org/article", history))
    }

}
