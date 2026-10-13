package com.battlesbudz.jarvis.v2.memory

/**
 * Boundary between finalized user conversation input and MemoryOS. It has no access to drafts,
 * assistant text, tool calls, or automatic model extraction; callers submit only final user input.
 */
class ConversationMemory(private val memoryOs: MemoryOs, private val sourceArchive: MemorySourceArchive? = null) {
    fun capture(input: FinalMemoryInput): ConversationMemoryResult {
        // Store eligible finalized user sources even when they contain no deterministic fact.
        // Source text never enters approvedContext; only an explicit history reader can access it.
        val archived = sourceArchive?.captureSource(input)?.outcome
        if (archived == SourceArchiveOutcome.EXCLUDED) return ConversationMemoryResult(ConversationMemoryOutcome.EXCLUDED, "Secret-bearing input is excluded from the memory archive.")
        if (archived == SourceArchiveOutcome.STORAGE_FAILURE || archived == SourceArchiveOutcome.FULL) return ConversationMemoryResult(ConversationMemoryOutcome.STORAGE_FAILURE, "Memory source archive is unavailable; conversation continues.")
        if (archived == SourceArchiveOutcome.CONFLICT) return ConversationMemoryResult(ConversationMemoryOutcome.CONFLICT, "Source event was already recorded with different content.")
        if (archived == SourceArchiveOutcome.EXPIRED || archived == SourceArchiveOutcome.INVALID || archived == SourceArchiveOutcome.IGNORED) return ConversationMemoryResult(ConversationMemoryOutcome.IGNORED, "Source input is not eligible for archival or new memory.")
        val ignored = finalInputError(input)
        if (ignored != null) return ConversationMemoryResult(ConversationMemoryOutcome.IGNORED, ignored)
        if (MemoryPolicy.containsRawRestrictedContent(input.text)) return ConversationMemoryResult(ConversationMemoryOutcome.EXCLUDED, "Restricted content is never proposed as memory.")
        val candidate = extract(input) ?: return ConversationMemoryResult(ConversationMemoryOutcome.IGNORED, "No conservative memory candidate in this input.")
        val source = MemorySource(
            eventId = input.eventId,
            eventSource = if (input.source == ConversationMemorySource.VOICE) "final_voice_input" else "final_text_input",
            createdAtMs = input.capturedAtMs,
            provenance = buildList {
                add(MemoryProvenance("conversation", input.conversationId))
                input.callId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(MemoryProvenance("call", it)) }
            },
        )
        // Wiki suggestions are derived when rendering. Persisting them here would alter the identity
        // of captures created before wiki support and break their replay/tombstones.
        val result = memoryOs.propose(MemoryProposal(candidate.content, source, candidate.category, candidate.tier, candidate.type, candidate.confidence))
        return when (result.outcome) {
            MemoryOutcome.CREATED, MemoryOutcome.ALREADY_RECORDED -> ConversationMemoryResult(ConversationMemoryOutcome.PROPOSED, result.message, result.memory)
            MemoryOutcome.EXCLUDED -> ConversationMemoryResult(ConversationMemoryOutcome.EXCLUDED, result.message)
            MemoryOutcome.CONFLICT -> ConversationMemoryResult(ConversationMemoryOutcome.CONFLICT, result.message, result.memory)
            MemoryOutcome.STORAGE_FAILURE -> ConversationMemoryResult(ConversationMemoryOutcome.STORAGE_FAILURE, result.message)
            else -> ConversationMemoryResult(ConversationMemoryOutcome.INVALID, result.message)
        }
    }

    /** Returned packet is historical quoted data only; it never grants instruction, state, or tool authority. */
    fun approvedContext(rawQuery: String, maxChars: Int, limit: Int = 8): MemoryPacketResult =
        memoryOs.contextPacket(rawQuery, maxChars, limit)

    private data class Candidate(val content: String, val category: MemoryCategory, val tier: MemoryTier = MemoryTier.LONG_TERM, val type: MemoryType = MemoryType.SEMANTIC, val confidence: Int = 80)

    private fun finalInputError(input: FinalMemoryInput): String? {
        if (!input.complete) return "Draft input is not eligible for memory."
        if (input.source == ConversationMemorySource.VOICE && !input.recognitionSucceeded) return "Unrecognized voice input is not eligible for memory."
        if (input.eventId.isBlank() || input.conversationId.isBlank() || input.text.isBlank() || input.capturedAtMs <= 0) return "Final input metadata is invalid."
        if (input.text.length > MemoryPolicy.MAX_CONTENT_CHARS) return "Final input exceeds the memory limit."
        return null
    }

    private fun extract(input: FinalMemoryInput): Candidate? {
        val text = input.text.trim().replace(Regex("\\s+"), " ")
        if (text.endsWith("?") || text.startsWith("/") || text.startsWith("!")) return null
        explicitRemember(text)?.let { return it }
        val lower = text.lowercase()
        val preference = Regex("^i (?:really )?(?:like|love|prefer|enjoy|hate|dislike) (.{2,180})[.!]?$", RegexOption.IGNORE_CASE).matchEntire(text)
        if (preference != null) return Candidate(text.trimEnd('.', '!'), MemoryCategory.PREFERENCE)
        if (Regex("^my favorite .{2,80} is .{1,120}[.!]?$", RegexOption.IGNORE_CASE).matches(text)) return Candidate(text.trimEnd('.', '!'), MemoryCategory.PREFERENCE)
        if (Regex("^(?:i live in|i'm based in|i work (?:at|as)|my name is) .{2,160}[.!]?$", RegexOption.IGNORE_CASE).matches(text)) return Candidate(text.trimEnd('.', '!'), if (lower.startsWith("my name")) MemoryCategory.PERSON else MemoryCategory.FACT)
        return null
    }

    private fun explicitRemember(text: String): Candidate? {
        val matched = Regex("^(?:please )?remember(?: that)?\\s+(.{2,300})[.!]?$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val fact = matched.groupValues[1].trim().trimEnd('.', '!')
        if (fact.endsWith("?") || fact.startsWith("/") || fact.startsWith("!")) return null
        val category = if (Regex("^(?:i )?(?:like|love|prefer|enjoy|hate|dislike)\\b|^my favorite\\b", RegexOption.IGNORE_CASE).containsMatchIn(fact)) MemoryCategory.PREFERENCE else MemoryCategory.FACT
        return Candidate(fact, category, confidence = 90)
    }
}
