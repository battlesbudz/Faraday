package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class FactualityVerifierTest {
    private val prompt = FactualityVerifier().buildPrompt(
        "When was the first Taco Bell opened?",
        "The first Taco Bell opened in Downey in 1962."
    )

    @Test fun lookupCriteriaNameTheCasesThatWarrantChecking() {
        assertTrue(prompt.contains("current, recent, or changeable information"))
        assertTrue(prompt.contains("exact date, number, quotation"))
        assertTrue(prompt.contains("specific named"))
        assertTrue(prompt.contains("genuinely uncertain"))
        assertTrue(prompt.contains("choose LOOKUP; otherwise choose PASS"))
    }

    @Test fun passCriteriaCoverStableKnowledgeAndOpenEndedTurns() {
        assertTrue(prompt.contains("General explanations, common definitions, broad history or"))
        assertTrue(prompt.contains("open-ended conversation"))
        assertTrue(prompt.contains("Do not return LOOKUP merely because a question is factual or"))
        assertTrue(prompt.contains("merely because a named subject appears"))
    }

    @Test fun verdictParsingStillAcceptsLookupMarkers() {
        val verifier = FactualityVerifier()
        assertTrue(verifier.requestsLookup("LOOKUP"))
        assertTrue(verifier.requestsLookup("NEEDS_WIKIPEDIA"))
        assertFalse(verifier.requestsLookup("PASS"))
    }
}
