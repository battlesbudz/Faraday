package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the code system prompt as a reviewed constant (scope section
 * 4.1): the four verbs are the whole contract, and the prompt's char
 * count — which [CodeBudget] charges against every round — must fit
 * the chat layer's fallback budget beside the floored snapshot, so an
 * edit that bloats the prompt fails here instead of on the phone.
 */
class CodeSystemPromptTest {

    @Test fun promptCarriesExactlyTheFourVerbEnvelopes() {
        for (verb in listOf("write_file", "edit_file", "update_brief", "reply")) {
            assertTrue(
                "prompt must show the $verb envelope",
                CodeSystemPrompt.TEXT.contains("<action name=\"$verb\">")
            )
        }
    }

    @Test fun promptStatesTheHouseRules() {
        assertTrue(CodeSystemPrompt.TEXT.contains("One self-contained index.html by default"))
        assertTrue(CodeSystemPrompt.TEXT.contains("Fully offline"))
        assertTrue(CodeSystemPrompt.TEXT.contains("Never stop inside an action"))
    }

    @Test fun promptFitsTheFallbackBudgetBesideTheFlooredSnapshot() {
        // The current Gemma bundles declare no contextTokens, so the
        // fallback limit governs their rounds.
        assertNotNull(CodeBudget.compute(null, CodeSystemPrompt.TEXT.length))
    }

    @Test fun samplerCarriesTheScopesMochuValues() {
        assertEquals(20, CodeSampling.TOP_K)
        assertEquals(0.9, CodeSampling.TOP_P, 0.0)
        assertEquals(0.35, CodeSampling.TEMPERATURE, 0.0)
        assertEquals(0, CodeSampling.SEED)
    }

    @Test fun turnReminderAsksForTheActions() {
        assertTrue(CodeSystemPrompt.TURN_REMINDER.contains("XML action"))
        assertTrue(CodeSystemPrompt.TURN_REMINDER.contains("edit_file"))
    }
}
