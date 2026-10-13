package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.*
import org.junit.Test
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard

class AnswerQualityPolicyTest {
    @Test fun paraphrasedRecipeEvasionIsRejected() {
        assertEquals("generic_instead_of_requested_details", AnswerQualityPolicy.rejection("name any recipe and ingredients", "Because it is highly individualized, there isn't one recipe.", null))
        assertNull(AnswerQualityPolicy.rejection("name a recipe", "Fermented plant juice uses plant material and brown sugar.", null))
    }
    @Test fun longRepeatedAnswerAndDefensiveCorrectionAreRejected() {
        val old = "The nutrients depend on your plants and conditions. You need to research a local practitioner."
        assertEquals("repeated_previous_answer", AnswerQualityPolicy.rejection("give an example", old, old))
        assertEquals("defends_or_loses_correction", AnswerQualityPolicy.rejection("no you said that was wrong", "As I mentioned, this is not recognized.", old))
    }
    @Test fun policyRejectionTriggersRepairBeforeAnySpeechOrTextPublication() {
        val output = StringBuilder()
        val guard = VoiceRepetitionGuard("name a recipe", null) { output.append(it) }
        guard.isPublishable = { AnswerQualityPolicy.rejection("name a recipe", it, null) == null }
        guard.accept("There isn't one recipe because it is highly individualized.")
        guard.finish()
        assertEquals("", output.toString()); assertTrue(guard.needsRepair)
    }
    @Test fun unrelatedPersonalEvidenceDoesNotBlockTruthfulUncertainty() {
        assertNull(AnswerQualityPolicy.rejection("what fruit do I like?", "I don't know what fruit you like.", "You work at Acme."))
        assertNull(AnswerQualityPolicy.rejection("what fruit do I like?", "You've mentioned jazz, but no fruit preference.", "I like jazz."))
    }
    @Test fun typedMarkdownAndFencedCodeKeepExactSeparatorsAndIndentation() {
        for (draft in listOf("def answer():\n    return 42\n", "First paragraph.\n\n- One\n- Two\n", "```python\ndef answer():\n    return 42\n```\n")) {
            val output = StringBuilder()
            val guard = VoiceRepetitionGuard("show an example", null) { output.append(it) }
            guard.preserveFormatting = true
            draft.chunked(3).forEach(guard::accept)
            assertEquals(draft, guard.finish())
            assertEquals(draft, output.toString())
        }
    }
}
