package com.battlesbudz.jarvis.v2.memory

/** Native, local-only memory contract. All timestamps are UTC epoch milliseconds. */
enum class MemoryCategory { FACT, PREFERENCE, PERSON, GOAL, TASK_GUIDANCE, OTHER }
enum class MemoryTier { WORKING, RECENT, LONG_TERM }
enum class MemoryType { SEMANTIC, EPISODIC, PROCEDURAL }
enum class MemoryReviewStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }
enum class MemorySensitivity { NORMAL, RESTRICTED }
enum class MemoryOutcome { CREATED, APPROVED, REJECTED, DELETED, ALREADY_RECORDED, INVALID, CONFLICT, EXCLUDED, NOT_FOUND, STORAGE_FAILURE }

/** User-visible wiki classification; it is metadata only and never changes a memory’s content authority. */
enum class WikiCategory(val id: String, val title: String) {
    ABOUT_YOU("about-you", "About You"), PEOPLE("people", "People"), PROJECTS("projects", "Projects"),
    PREFERENCES("preferences", "Preferences"), PLACES("places", "Places"), KNOWLEDGE("knowledge", "Knowledge");
}

data class MemoryWikiAssignment(val category: WikiCategory, val topic: String)

data class MemoryProvenance(
    val kind: String,
    val id: String,
    val label: String? = null,
    val restricted: Boolean = false,
)

data class MemorySource(
    val eventId: String,
    val eventSource: String,
    val createdAtMs: Long,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val provenance: List<MemoryProvenance> = emptyList(),
)

data class MemoryRecord(
    val id: String,
    val content: String,
    val category: MemoryCategory,
    val tier: MemoryTier,
    val type: MemoryType,
    val confidence: Int,
    val source: MemorySource,
    val reviewStatus: MemoryReviewStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val revision: Long,
    val expiresAtMs: Long? = null,
    val correctsMemoryId: String? = null,
    val wikiAssignment: MemoryWikiAssignment? = null,
    /** Immutable proposal fingerprint for idempotency after mutable metadata edits. */
    val payloadFingerprint: String? = null,
)

data class MemoryProposal(
    val content: String,
    val source: MemorySource,
    val category: MemoryCategory = MemoryCategory.FACT,
    val tier: MemoryTier = MemoryTier.LONG_TERM,
    val type: MemoryType = MemoryType.SEMANTIC,
    val confidence: Int = 70,
    val expiresAtMs: Long? = null,
    val correctsMemoryId: String? = null,
    val expectedTargetRevision: Long? = null,
    val wikiAssignment: MemoryWikiAssignment? = null,
)

data class MemoryResult(
    val outcome: MemoryOutcome,
    val message: String,
    val memory: MemoryRecord? = null,
)

data class MemoryTombstone(val eventId: String, val payloadFingerprint: String, val erasedAtMs: Long)

data class MemorySnapshot(
    val generation: Long,
    val memories: List<MemoryRecord>,
    val tombstones: List<MemoryTombstone>,
)

/** Retrieval outcome preserves storage failures for UI rather than treating them as an empty history. */
data class MemorySearchResult(val outcome: MemoryOutcome?, val message: String, val memories: List<RetrievedMemory> = emptyList())
data class MemoryPacketResult(val outcome: MemoryOutcome?, val message: String, val packet: MemoryContextPacket? = null, val stateToken: String? = null, val nextApprovedExpiryMs: Long? = null)

enum class ConversationMemorySource { TEXT, VOICE }

data class FinalMemoryInput(
    val eventId: String,
    val conversationId: String,
    val callId: String? = null,
    val source: ConversationMemorySource,
    val text: String,
    val capturedAtMs: Long,
    val recognitionSucceeded: Boolean = true,
    val complete: Boolean = true,
)

enum class ConversationMemoryOutcome { PROPOSED, IGNORED, EXCLUDED, CONFLICT, STORAGE_FAILURE, INVALID }

data class ConversationMemoryResult(
    val outcome: ConversationMemoryOutcome,
    val message: String,
    val memory: MemoryRecord? = null,
)
