package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryCaptureAcknowledgmentTest {
    private fun result(status: MemoryReviewStatus) = ConversationMemoryResult(ConversationMemoryOutcome.PROPOSED, "receipt",
        MemoryRecord("one", "I like apricots", MemoryCategory.PREFERENCE, MemoryTier.LONG_TERM, MemoryType.SEMANTIC,
            90, MemorySource("event", "final_text_input", 1), status, 1, 1, 1))
    @Test fun pendingCannotClaimApprovedPersistence() {
        assertEquals("I've added a pending memory for your review. It isn't approved yet.", MemoryCaptureAcknowledgment.reply(result(MemoryReviewStatus.PENDING)))
        assertTrue(MemoryCaptureAcknowledgment.reply(result(MemoryReviewStatus.APPROVED)).contains("already approved"))
        assertFalse(MemoryCaptureAcknowledgment.reply(result(MemoryReviewStatus.REJECTED)).contains("already approved"))
    }
    @Test fun storageFailureExclusionAndMissingReceiptAreExplicit() {
        assertTrue(MemoryCaptureAcknowledgment.reply(ConversationMemoryResult(ConversationMemoryOutcome.STORAGE_FAILURE, "disk")).contains("couldn't save"))
        assertTrue(MemoryCaptureAcknowledgment.reply(ConversationMemoryResult(ConversationMemoryOutcome.EXCLUDED, "secret")).contains("excluded"))
        assertTrue(MemoryCaptureAcknowledgment.section(null).contains("Do not claim"))
    }
    @Test fun naturalCategoryRecallRetrievesApprovedPreferencesOnly() {
        val pending = result(MemoryReviewStatus.PENDING).memory!!
        val approved = pending.copy(id = "two", reviewStatus = MemoryReviewStatus.APPROVED)
        for (query in listOf("what fruit do I like?", "what fruits do I prefer?", "I like what?")) {
            assertTrue(MemoryTurnContext.isPersonalRecall(query))
            assertEquals(listOf("two"), MemoryRetrieval.retrieve(listOf(pending, approved), query, 8, 2).map { it.memory.id })
        }
        assertTrue(MemoryRetrieval.retrieve(listOf(pending), "what fruit do I like?", 8, 2).isEmpty())
    }
}
