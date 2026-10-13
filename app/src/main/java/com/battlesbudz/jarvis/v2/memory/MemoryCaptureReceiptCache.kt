package com.battlesbudz.jarvis.v2.memory

/** One-shot finalized receipts; a late capture cannot repopulate a newer memory boundary. */
class MemoryCaptureReceiptCache(private val currentEpoch: () -> Long, private val boundaryPending: () -> Boolean) {
    private data class Entry(val eventId: String, val epoch: Long, val result: ConversationMemoryResult)
    private val entries = LinkedHashMap<String, Entry>()
    @Synchronized fun put(eventId: String, conversationId: String, text: String, epoch: Long, result: ConversationMemoryResult) {
        if (epoch != currentEpoch() || boundaryPending()) return
        entries[conversationId + "\u0000" + text] = Entry(eventId, epoch, result)
        while (entries.size > 8) entries.remove(entries.keys.first())
    }
    @Synchronized fun take(conversationId: String, text: String): ConversationMemoryResult? =
        entries.remove(conversationId + "\u0000" + text)?.takeIf { it.epoch == currentEpoch() && !boundaryPending() }?.result
    @Synchronized fun clear() { entries.clear() }
}
