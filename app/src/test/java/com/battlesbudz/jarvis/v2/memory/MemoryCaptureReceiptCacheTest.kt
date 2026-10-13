package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryCaptureReceiptCacheTest {
    @Test fun lateCaptureCannotRepopulateAnErasedOrApprovedBoundary() {
        var epoch = 1L; var pending = false
        val cache = MemoryCaptureReceiptCache({ epoch }, { pending })
        val receipt = ConversationMemoryResult(ConversationMemoryOutcome.PROPOSED, "pending")
        val captureEpoch = epoch
        pending = true; cache.clear(); epoch++; pending = false
        cache.put("event", "chat", "remember a preference", captureEpoch, receipt)
        assertNull(cache.take("chat", "remember a preference"))
        cache.put("new", "chat", "remember a preference", epoch, receipt)
        pending = true
        assertNull(cache.take("chat", "remember a preference"))
    }
    @Test fun receiptsAreConversationScopedAndConsumedOnce() {
        val cache = MemoryCaptureReceiptCache({ 1 }, { false })
        val receipt = ConversationMemoryResult(ConversationMemoryOutcome.IGNORED, "none")
        cache.put("event", "one", "hello", 1, receipt)
        assertNull(cache.take("two", "hello"))
        assertEquals(receipt, cache.take("one", "hello"))
        assertNull(cache.take("one", "hello"))
    }
}
