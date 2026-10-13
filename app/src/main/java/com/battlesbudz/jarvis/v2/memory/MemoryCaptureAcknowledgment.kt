package com.battlesbudz.jarvis.v2.memory

/** Only a finalized storage receipt may make a persistence claim. */
object MemoryCaptureAcknowledgment {
    fun explicitRequest(text: String): Boolean = Regex("(?i)^(?:please )?remember(?: that)?\\s+").containsMatchIn(text.trim())
    fun reply(result: ConversationMemoryResult): String = when (result.outcome) {
        ConversationMemoryOutcome.PROPOSED -> when (result.memory?.reviewStatus) {
            MemoryReviewStatus.APPROVED -> "That memory is already approved and saved."
            MemoryReviewStatus.PENDING -> "I've added a pending memory for your review. It isn't approved yet."
            else -> "That proposal was already reviewed; it hasn't been added as an approved memory."
        }
        ConversationMemoryOutcome.STORAGE_FAILURE -> "I couldn't save the memory proposal. Please try again after storage recovers."
        ConversationMemoryOutcome.EXCLUDED -> "That information is excluded from memory storage."
        ConversationMemoryOutcome.CONFLICT -> "I couldn't add the memory because its source conflicts with an existing record."
        else -> "I couldn't add a memory proposal from that request."
    }
    fun section(result: ConversationMemoryResult?): String = result?.let {
        "Memory capture receipt for this finalized message: outcome=${it.outcome}; reviewStatus=${it.memory?.reviewStatus ?: "none"}. " +
            "${reply(it)} Never claim an unapproved proposal is saved as approved memory or promise future recall."
    } ?: "No memory capture receipt is available. Do not claim to save or remember this message permanently."
}
