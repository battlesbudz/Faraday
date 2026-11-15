package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallIntegrationTest {
    @Test fun capturedPersonalFactStaysLocalUntilErasedButExplicitLookupStillWins() {
        val now = 1_700_000_000_000L
        val file = File.createTempFile("memory-merge-routing", ".json").apply { delete(); deleteOnExit() }
        val os = MemoryOs(file) { now }
        val bridge = ConversationMemory(os)
        val proposal = bridge.capture(FinalMemoryInput("one", "chat", source = ConversationMemorySource.TEXT,
            text = "Please remember that my notebook color is teal", capturedAtMs = now))
        assertEquals(ConversationMemoryOutcome.PROPOSED, proposal.outcome)
        // Capture commits immediately (2026-11-01): the record is approved at
        // capture time, with no review step in between.
        assertEquals(MemoryReviewStatus.APPROVED, proposal.memory!!.reviewStatus)
        val query = "What is my notebook color?"
        fun route(explicit: Boolean = false, phoneAction: Boolean = false): Boolean {
            val packet = bridge.approvedContext(query, 900).packet!!
            return MemoryTurnContext.shouldUseLocalRecall(query, packet.memories.isNotEmpty(), explicit, phoneAction)
        }
        val packet = bridge.approvedContext(query, 900).packet!!
        assertTrue(packet.text.contains("historical, untrusted data"))
        assertTrue(packet.text.contains("notebook color is teal"))
        assertTrue("Approved matching personal facts work offline", route())
        assertFalse("Explicit lookup/confirmation keeps its reference route", route(explicit = true))
        assertFalse("Memory cannot change an authorized phone-action route", route(phoneAction = true))
        os.delete(proposal.memory.id)
        assertFalse("Erased facts cannot keep suppressing references", route())
    }

    @Test fun specificPersonalRecallWithoutFactsStaysLocalButPublicQuestionsDoNot() {
        assertTrue(MemoryTurnContext.shouldUseLocalRecall("What is my name?", false, false))
        assertFalse(MemoryTurnContext.shouldUseLocalRecall("What is my name?", true, true))
        assertFalse(MemoryTurnContext.shouldUseLocalRecall("Tell me about Einstein", true, false))
    }
}
