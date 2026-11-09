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
        val candidates = extractAll(input)
        if (candidates.isEmpty()) return ConversationMemoryResult(ConversationMemoryOutcome.IGNORED, "No conservative memory candidate in this input.")
        // Capture commits immediately (Battles's 2026-11-01 decision: no user
        // approval step for captured memories). Each candidate is proposed and
        // approved on the spot, exactly the way AutomaticMemory commits; the
        // MemoryOs review lifecycle remains only as the storage primitive.
        // Segment event ids derive from the turn's event id by position, so a
        // replayed message replays onto the same records.
        val results = candidates.mapIndexed { index, candidate ->
            val source = MemorySource(
                eventId = if (candidates.size == 1) input.eventId else "${input.eventId}#fact${index + 1}",
                eventSource = if (input.source == ConversationMemorySource.VOICE) "final_voice_input" else "final_text_input",
                createdAtMs = input.capturedAtMs,
                provenance = buildList {
                    add(MemoryProvenance("conversation", input.conversationId))
                    input.callId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(MemoryProvenance("call", it)) }
                },
            )
            // Wiki suggestions are derived when rendering. Persisting them here would alter the identity
            // of captures created before wiki support and break their replay/tombstones.
            commitCaptured(memoryOs.propose(MemoryProposal(candidate.content, source, candidate.category, candidate.tier, candidate.type, candidate.confidence)))
        }
        val saved = results.filter { it.memory?.reviewStatus == MemoryReviewStatus.APPROVED }
        if (saved.isNotEmpty()) {
            val message = if (results.size > 1) "Saved ${saved.size} memories." else "Memory saved."
            return ConversationMemoryResult(ConversationMemoryOutcome.PROPOSED, message, saved.first().memory)
        }
        val result = results.first()
        return when (result.outcome) {
            MemoryOutcome.EXCLUDED -> ConversationMemoryResult(ConversationMemoryOutcome.EXCLUDED, result.message)
            MemoryOutcome.CONFLICT -> ConversationMemoryResult(ConversationMemoryOutcome.CONFLICT, result.message, result.memory)
            MemoryOutcome.STORAGE_FAILURE -> ConversationMemoryResult(ConversationMemoryOutcome.STORAGE_FAILURE, result.message)
            else -> ConversationMemoryResult(ConversationMemoryOutcome.INVALID, result.message)
        }
    }

    /** Approves a freshly captured record on the spot; a replayed capture of a still-pending legacy record approves it too. */
    private fun commitCaptured(result: MemoryResult): MemoryResult {
        val record = result.memory ?: return result
        if (result.outcome != MemoryOutcome.CREATED && result.outcome != MemoryOutcome.ALREADY_RECORDED) return result
        if (record.reviewStatus != MemoryReviewStatus.PENDING) return result
        val approved = memoryOs.approve(record.id)
        return if (approved.outcome == MemoryOutcome.APPROVED) approved else result
    }

    /** Returned packet is historical quoted data only; it never grants instruction, state, or tool authority. */
    fun approvedContext(rawQuery: String, maxChars: Int, limit: Int = 8,
                        maxEntryChars: Int = Int.MAX_VALUE): MemoryPacketResult =
        memoryOs.contextPacket(rawQuery, maxChars, limit, maxEntryChars = maxEntryChars)

    private data class Candidate(val content: String, val category: MemoryCategory, val tier: MemoryTier = MemoryTier.LONG_TERM, val type: MemoryType = MemoryType.SEMANTIC, val confidence: Int = 80)

    private fun finalInputError(input: FinalMemoryInput): String? {
        if (!input.complete) return "Draft input is not eligible for memory."
        if (input.source == ConversationMemorySource.VOICE && !input.recognitionSucceeded) return "Unrecognized voice input is not eligible for memory."
        if (input.eventId.isBlank() || input.conversationId.isBlank() || input.text.isBlank() || input.capturedAtMs <= 0) return "Final input metadata is invalid."
        if (input.text.length > MemoryPolicy.MAX_CONTENT_CHARS) return "Final input exceeds the memory limit."
        return null
    }

    /**
     * Extracts one candidate per fact statement in the input. The message is
     * segmented first (see [MemoryFactSegmenter]); each segment is then matched
     * on its own, so a multi-fact message yields one candidate per fact with
     * the fact's own text — never one candidate holding the whole raw message.
     * A single-segment message follows the exact pre-segmentation behavior.
     */
    private fun extractAll(input: FinalMemoryInput): List<Candidate> {
        val text = input.text.trim().replace(Regex("\\s+"), " ")
        if (text.startsWith("/") || text.startsWith("!")) return emptyList()
        explicitRemember(text)?.let { return it }
        return MemoryFactSegmenter.segment(text)
            .mapNotNull { extractFromSegment(it) }
            .distinctBy { it.content }
    }

    private fun extractFromSegment(segment: String): Candidate? {
        if (segment.endsWith("?") || segment.startsWith("/") || segment.startsWith("!")) return null
        // Spoken turns often open with a discourse lead-in ("So …", "Yeah, and …").
        // The patterns below match against the lead-in-stripped core so natural spoken
        // phrasing of the covered fact classes is recognized; the candidate content
        // stays the segment's original text, and the question/command gates above
        // still apply to that segment first.
        val core = segment.replace(Regex("^(?:(?:so|yeah|well|okay|ok|right|actually|basically|and|now)[,\\s]+)+", RegexOption.IGNORE_CASE), "")
        val content = segment.trimEnd('.', '!')
        val preference = Regex("^i (?:(?:really|also|just|actually) )?(?:like|love|prefer|enjoy|hate|dislike) (.{2,180})[.!]?$", RegexOption.IGNORE_CASE).matchEntire(core)
        if (preference != null) return Candidate(content, MemoryCategory.PREFERENCE)
        if (Regex("^my favorite .{2,80} is .{1,120}[.!]?$", RegexOption.IGNORE_CASE).matches(core)) return Candidate(content, MemoryCategory.PREFERENCE)
        if (Regex("^my (?:full )?name is .{2,160}[.!]?$", RegexOption.IGNORE_CASE).matches(core)) return Candidate(content, MemoryCategory.PERSON)
        if (Regex("^(?:i live in|i'm based in|i work (?:at|as)) .{2,160}[.!]?$", RegexOption.IGNORE_CASE).matches(core)) return Candidate(content, MemoryCategory.FACT)
        // Biography / past-activity forms ("I used to …", "I've been to …", "I have traveled to …").
        if (Regex("\\bi used to .{2,140}|\\bi(?:'ve| have) been to .{2,140}|\\bi(?:'ve | have | )travel(?:ed|led) to .{2,140}", RegexOption.IGNORE_CASE).containsMatchIn(core)) return Candidate(content, MemoryCategory.FACT)
        return null
    }

    private fun explicitRemember(text: String): List<Candidate>? {
        val matched = Regex("^(?:please )?remember(?: that)?\\s+(.{2,300})[.!]?$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val fact = matched.groupValues[1].trim().trimEnd('.', '!')
        if (fact.endsWith("?") || fact.startsWith("/") || fact.startsWith("!")) return null
        val segments = MemoryFactSegmenter.segment(fact)
        if (segments.size <= 1) {
            val category = if (Regex("^(?:i )?(?:like|love|prefer|enjoy|hate|dislike)\\b|^my favorite\\b", RegexOption.IGNORE_CASE).containsMatchIn(fact)) MemoryCategory.PREFERENCE else MemoryCategory.FACT
            return listOf(Candidate(fact, category, confidence = 90))
        }
        // A "remember …" carrying several facts stores one record per fact,
        // each classified by the statement patterns where they match.
        return segments.map { segment ->
            val content = segment.trimEnd('.', '!')
            val category = extractFromSegment(segment)?.category
                ?: if (Regex("^(?:i )?(?:like|love|prefer|enjoy|hate|dislike)\\b|^my favorite\\b", RegexOption.IGNORE_CASE).containsMatchIn(segment)) MemoryCategory.PREFERENCE else MemoryCategory.FACT
            Candidate(content, category, confidence = 90)
        }.distinctBy { it.content }
    }
}
