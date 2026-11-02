package com.battlesbudz.jarvis.v2.memory

/** Only a finalized storage receipt may make a persistence claim. */
object MemoryCaptureAcknowledgment {
    fun explicitRequest(text: String): Boolean = Regex("(?i)^(?:please )?remember(?: that)?\\s+").containsMatchIn(text.trim())
    fun reply(result: ConversationMemoryResult): String = when (result.outcome) {
        ConversationMemoryOutcome.PROPOSED -> when (result.memory?.reviewStatus) {
            // Capture commits immediately; there is no approval step to wait for.
            MemoryReviewStatus.APPROVED -> "Saved to memory."
            else -> "That memory is not saved as an approved memory."
        }
        ConversationMemoryOutcome.STORAGE_FAILURE -> "I couldn't save that memory. Please try again after storage recovers."
        ConversationMemoryOutcome.EXCLUDED -> "That information is excluded from memory storage."
        ConversationMemoryOutcome.CONFLICT -> "I couldn't add the memory because its source conflicts with an existing record."
        else -> "I couldn't add a memory from that request."
    }
    fun section(result: ConversationMemoryResult?): String = result?.let {
        "Memory capture receipt for this finalized message: outcome=${it.outcome}; reviewStatus=${it.memory?.reviewStatus ?: "none"}. " +
            "${reply(it)} Claim a save only when this receipt reports one; an ignored, excluded, or failed capture saved nothing."
    } ?: "No memory capture receipt is available. Do not claim to save or remember this message permanently."
}
