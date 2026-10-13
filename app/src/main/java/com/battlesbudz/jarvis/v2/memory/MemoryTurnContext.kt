package com.battlesbudz.jarvis.v2.memory

/** Immutable approved-memory snapshot attached only to one ordinary answer. */
class MemoryTurnContext(
    private val packetText: String,
    val stateToken: String?,
    val query: String,
    val expiresAtMs: Long?,
    val observedEpoch: Long,
    val hasApprovedMemories: Boolean = false,
    private val currentEpoch: () -> Long,
) {
    fun promptSection(): String = packetText
    fun isCurrent(): Boolean = observedEpoch == currentEpoch() && (expiresAtMs == null || System.currentTimeMillis() < expiresAtMs)

    companion object {
        /** Explicit lookup and confirmation always retain their requested reference route.
         * Broader personal questions may stay local only when reviewed matching evidence exists. */
        fun shouldUseLocalRecall(rawPrompt: String, hasApprovedMemories: Boolean,
                                 explicitLookup: Boolean, phoneAction: Boolean = false): Boolean =
            !explicitLookup && !phoneAction && (isPersonalRecall(rawPrompt) ||
                (hasApprovedMemories && personalWording.containsMatchIn(rawPrompt)))

        private val personalWording = Regex(
            "\\b(my|mine)\\b|\\babout\\s+me\\b|\\bwhat\\s+(?:did|have|do)\\s+i\\b|\\b(?:remembered|saved)\\s+(?:about\\s+)?me\\b",
            RegexOption.IGNORE_CASE
        )
        fun isPersonalRecall(rawPrompt: String): Boolean {
            val text = rawPrompt.trim().lowercase()
            if (Regex("(?i)^what(?: [a-z]+){0,3} (?:do|did) i (?:like|love|prefer|enjoy|hate|dislike)[?!.]*$|^i like what[?!.]*$").matches(text)) return true
            return Regex(
                "^\\s*(?:what(?:\\'s| is)|who(?:\\'s| is)|do you know) my " +
                    "(?:name|wife|husband|favorite(?: [a-z ]+)?|preference)|" +
                    "^\\s*what do i (?:like|love|prefer|enjoy|hate|dislike)\\??\\s*$|" +
                    "^\\s*(?:where do i live|where am i based)\\??\\s*$|" +
                    "^\\s*(?:what|tell me what) do you remember about me\\??\\s*$",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(text)
        }
    }
}

/**
 * Persists a resident-history boundary before publishing a new opaque approved-memory token.
 * The callbacks are deliberately synchronous: a crash after any failed step leaves the prior
 * token in place, so the next process conservatively fences/reconciles rather than reseeding.
 */
object MemoryContextPersistence {
    fun adopt(
        previousToken: String?,
        newToken: String?,
        persistCutoff: () -> Boolean,
        clearSummary: () -> Boolean,
        persistToken: (String?) -> Boolean
    ): Boolean {
        val changed = previousToken != newToken
        if (changed && previousToken != null) {
            if (!persistCutoff()) error("memory_context_cutoff_persist_failed")
            if (!clearSummary()) error("memory_summary_clear_failed")
        }
        if (!persistToken(newToken)) error("memory_token_persist_failed")
        return changed
    }
}
