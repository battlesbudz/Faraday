package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SuppliedReferenceTest {
    @Test fun urlOnlyFollowupUsesProvidedPdfForThePostCutoffQuestion() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ReferenceGroundingClient(readBytes = { url -> calls += url.toString(); "%PDF-fixture".toByteArray() },
            pdfText = { "Water soluble calcium uses roasted eggshells and vinegar." })
        val url = "https://example.org/reference.pdf"
        val plan = TurnOrchestrator(client).plan(url, listOf("You" to "how do you make water soluble calcium?", "Jarvis" to "This is not a recognized method.", "You" to "no"))
        val result = client.fetchIfRequested(plan.lookupQuery!!)!!
        assertTrue(result.context.contains("eggshells and vinegar"))
        // Partial evidence must not dead-end the answer: uncovered parts are
        // answered from the model's own knowledge, not announced as missing.
        assertTrue(result.context.contains("answer that part from"))
        assertTrue(result.context.contains("your own knowledge rather than refusing"))
        assertFalse(result.context.contains("If the evidence is insufficient, say so"))
        assertEquals(listOf(url), result.sources)
        assertEquals(listOf(url), calls)
    }
    @Test fun sourceOnlyInspectionWorksWithoutInventingPreviousContext() = runBlocking {
        val client = ReferenceGroundingClient(readBytes = { "%PDF-fixture".toByteArray() }, pdfText = { "A document about apricot trees." })
        val plan = TurnOrchestrator(client).plan("https://example.org/reference.pdf", emptyList())
        assertTrue(client.fetchIfRequested(plan.lookupQuery!!)!!.context.contains("apricot trees"))
    }
}
