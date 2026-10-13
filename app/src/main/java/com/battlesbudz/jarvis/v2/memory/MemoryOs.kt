package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Explicit-review memory lifecycle. It never observes chat/voice itself: callers must propose an event. */
class MemoryOs(private val store: MemoryPersistence, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val approvedStateObservers = CopyOnWriteArraySet<(String) -> Unit>()

    /** In-process fence notification after a durable approved-history mutation. Pending proposals never notify. */
    fun addApprovedStateObserver(observer: (stateToken: String) -> Unit): AutoCloseable {
        approvedStateObservers += observer
        return AutoCloseable { approvedStateObservers -= observer }
    }

    private fun notifyApprovedStateChanged() {
        val snapshot = read().snapshot ?: return
        val token = MemoryRetrieval.approvedStateToken(snapshot.memories, clock())
        approvedStateObservers.forEach { observer -> runCatching { observer(token) } }
    }
    constructor(file: File, clock: () -> Long = { System.currentTimeMillis() }) : this(MemoryStore(file), clock)

    fun propose(proposal: MemoryProposal): MemoryResult {
        val now = clock()
        val decision = MemoryPolicy.assess(proposal, now)
        if (!decision.allowed) return MemoryResult(decision.outcome!!, decision.message)
        val canonicalProposal = MemoryPolicy.canonicalize(proposal)
        val opaqueProposal = canonicalProposal.copy(source = canonicalProposal.source.copy(eventId = MemoryPolicy.sourceKey(canonicalProposal.source.eventId)))
        val fingerprint = MemoryPolicy.fingerprint(opaqueProposal)
        val update = store.update { before ->
            val prior = before.memories.firstOrNull { it.source.eventId == opaqueProposal.source.eventId }
            if (prior != null) {
                val priorFingerprint = prior.payloadFingerprint ?: MemoryPolicy.fingerprint(prior.toProposal())
                return@update before to if (priorFingerprint == fingerprint) MemoryResult(MemoryOutcome.ALREADY_RECORDED, "Source event was already recorded.", prior)
                else MemoryResult(MemoryOutcome.CONFLICT, "Source event id was reused with different data.")
            }
            val erased = before.tombstones.firstOrNull { it.eventId == opaqueProposal.source.eventId }
            if (erased != null) return@update before to if (erased.payloadFingerprint == fingerprint) MemoryResult(MemoryOutcome.DELETED, "This source event was erased.")
            else MemoryResult(MemoryOutcome.CONFLICT, "Source event id was reused after erasure.")
            if (before.memories.size >= MemoryPolicy.MAX_MEMORIES || before.memories.size + before.tombstones.size >= MemoryPolicy.MAX_TOMBSTONES) return@update before to MemoryResult(MemoryOutcome.INVALID, "Memory capacity reached while preserving erasure capacity.")
            val target = opaqueProposal.correctsMemoryId?.let { id -> before.memories.firstOrNull { it.id == id } }
            if (opaqueProposal.correctsMemoryId != null && (target == null || target.reviewStatus != MemoryReviewStatus.APPROVED)) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "The memory being corrected is no longer active.")
            if (target != null && opaqueProposal.expectedTargetRevision != null && target.revision != opaqueProposal.expectedTargetRevision) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "The memory changed before this correction was proposed.")
            if (target != null && before.memories.any { it.correctsMemoryId == target.id && it.reviewStatus == MemoryReviewStatus.PENDING }) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "A correction for this memory is already pending.")
            val assignment = opaqueProposal.wikiAssignment ?: target?.wikiAssignment
            val record = MemoryRecord(UUID.randomUUID().toString(), opaqueProposal.content, opaqueProposal.category, opaqueProposal.tier, opaqueProposal.type, opaqueProposal.confidence, opaqueProposal.source, MemoryReviewStatus.PENDING, now, now, 1, opaqueProposal.expiresAtMs, opaqueProposal.correctsMemoryId, assignment, fingerprint)
            before.copy(generation = before.generation + 1, memories = before.memories + record) to MemoryResult(MemoryOutcome.CREATED, "Memory proposal is awaiting review.", record)
        }
        return update.value ?: MemoryResult(MemoryOutcome.STORAGE_FAILURE, update.error ?: "Memory store failed.")
    }

    /** Changes only user-controlled wiki metadata. Pending and approved memories remain reviewable. */
    fun assignWiki(id: String, expectedRevision: Long? = null, assignment: MemoryWikiAssignment): MemoryResult {
        val normalized = MemoryPolicy.canonicalize(MemoryProposal("x", MemorySource("a", "a", 1), wikiAssignment = assignment)).wikiAssignment!!
        MemoryPolicy.assessWikiAssignment(normalized)?.let { return MemoryResult(MemoryOutcome.INVALID, it) }
        if (MemoryPolicy.isRestrictedMetadata(normalized.topic) || MemoryPolicy.containsRawRestrictedContent(normalized.topic)) return MemoryResult(MemoryOutcome.EXCLUDED, "Restricted wiki metadata is not stored in memory.")
        val now = clock(); var approvedChanged = false
        val update = store.update { before ->
            val record = before.memories.firstOrNull { it.id == id } ?: return@update before to MemoryResult(MemoryOutcome.NOT_FOUND, "Memory not found.")
            if (expectedRevision != null && record.revision != expectedRevision) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Memory changed before classification.", record)
            if (record.reviewStatus !in setOf(MemoryReviewStatus.PENDING, MemoryReviewStatus.APPROVED)) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Only pending or approved memories can be classified.", record)
            if (record.wikiAssignment == normalized) return@update before to MemoryResult(MemoryOutcome.ALREADY_RECORDED, "Memory is already in this wiki topic.", record)
            // A legacy record has no immutable fingerprint. Seed its pre-assignment v2 payload
            // before changing placement so original event replay and its future tombstone stay valid.
            val originalFingerprint = record.payloadFingerprint ?: MemoryPolicy.fingerprint(record.toProposal())
            val changed = record.copy(wikiAssignment = normalized, payloadFingerprint = originalFingerprint, updatedAtMs = now, revision = record.revision + 1)
            approvedChanged = record.reviewStatus == MemoryReviewStatus.APPROVED
            before.copy(generation = before.generation + 1, memories = before.memories.map { if (it.id == id) changed else it }) to MemoryResult(MemoryOutcome.APPROVED, "Memory wiki classification recorded.", changed)
        }
        val result = update.value ?: MemoryResult(MemoryOutcome.STORAGE_FAILURE, update.error ?: "Memory store failed.")
        if (approvedChanged && result.outcome == MemoryOutcome.APPROVED) notifyApprovedStateChanged()
        return result
    }

    fun approve(id: String, expectedRevision: Long? = null): MemoryResult = transition(id, expectedRevision, MemoryReviewStatus.APPROVED)
    fun reject(id: String, expectedRevision: Long? = null): MemoryResult = transition(id, expectedRevision, MemoryReviewStatus.REJECTED)

    private fun transition(id: String, expectedRevision: Long?, status: MemoryReviewStatus): MemoryResult {
        val now = clock()
        val update = store.update { before ->
            val record = before.memories.firstOrNull { it.id == id } ?: return@update before to MemoryResult(MemoryOutcome.NOT_FOUND, "Memory not found.")
            if (record.reviewStatus != MemoryReviewStatus.PENDING) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Only pending memories can be reviewed.", record)
            if (expectedRevision != null && record.revision != expectedRevision) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Memory changed before review.", record)
            var changed = record.copy(reviewStatus = status, updatedAtMs = now, revision = record.revision + 1)
            var all = before.memories.map { if (it.id == id) changed else it }
            if (status == MemoryReviewStatus.APPROVED && record.correctsMemoryId != null) {
                val target = all.firstOrNull { it.id == record.correctsMemoryId }
                    ?: return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Correction target was deleted.")
                if (target.reviewStatus != MemoryReviewStatus.APPROVED) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Correction target is no longer active.")
                all = all.map { if (it.id == target.id) it.copy(reviewStatus = MemoryReviewStatus.SUPERSEDED, updatedAtMs = now, revision = it.revision + 1) else it }
            }
            before.copy(generation = before.generation + 1, memories = all) to MemoryResult(if (status == MemoryReviewStatus.APPROVED) MemoryOutcome.APPROVED else MemoryOutcome.REJECTED, "Memory review recorded.", changed)
        }
        val result = update.value ?: MemoryResult(MemoryOutcome.STORAGE_FAILURE, update.error ?: "Memory store failed.")
        if (result.outcome == MemoryOutcome.APPROVED) notifyApprovedStateChanged()
        return result
    }

    /** Erases the entire correction lineage and retains only non-content idempotency tombstones. */
    fun delete(id: String, expectedRevision: Long? = null): MemoryResult = erase deletion@{ memories ->
        val seed = memories.firstOrNull { it.id == id } ?: return@deletion emptySet<String>()
        if (expectedRevision != null && seed.revision != expectedRevision) return@deletion emptySet<String>()
        lineage(memories, seed.id)
    }.let { result -> if (result.outcome == MemoryOutcome.NOT_FOUND && expectedRevision != null) MemoryResult(MemoryOutcome.CONFLICT, "Memory changed before deletion.") else result }

    fun deleteAll(expectedGeneration: Long? = null): MemoryResult = erase(expectedGeneration) { it.map { m -> m.id }.toSet() }

    private fun erase(expectedGeneration: Long? = null, selector: (List<MemoryRecord>) -> Set<String>): MemoryResult {
        val now = clock()
        var approvedChanged = false
        val update = store.update { before ->
            if (expectedGeneration != null && before.generation != expectedGeneration) return@update before to MemoryResult(MemoryOutcome.CONFLICT, "Memory store changed before deletion.")
            val ids = selector(before.memories)
            if (ids.isEmpty()) return@update before to MemoryResult(MemoryOutcome.NOT_FOUND, "Memory not found.")
            val erased = before.memories.filter { it.id in ids }
            approvedChanged = erased.any { it.reviewStatus == MemoryReviewStatus.APPROVED }
            if (before.tombstones.size + erased.size > MemoryPolicy.MAX_TOMBSTONES) return@update before to MemoryResult(MemoryOutcome.INVALID, "Erasure tombstone capacity reached.")
            val tombstones = before.tombstones + erased.map { MemoryTombstone(it.source.eventId, it.payloadFingerprint ?: MemoryPolicy.fingerprint(it.toProposal()), now) }
            before.copy(generation = before.generation + 1, memories = before.memories.filterNot { it.id in ids }, tombstones = tombstones) to MemoryResult(MemoryOutcome.DELETED, "Memory content and correction lineage erased.")
        }
        val result = update.value ?: MemoryResult(MemoryOutcome.STORAGE_FAILURE, update.error ?: "Memory store failed.")
        if (approvedChanged && result.outcome == MemoryOutcome.DELETED) notifyApprovedStateChanged()
        return result
    }

    /** UI callers should use this to distinguish empty history from a corrupt/unavailable store. */
    fun read(): MemoryStore.Read = store.read()
    @Deprecated("Use read() so storage failures are not represented as an empty history.")
    fun list(includeReviewed: Boolean = true): List<MemoryRecord> = read().snapshot?.memories
        ?.filter { includeReviewed || it.reviewStatus == MemoryReviewStatus.PENDING }?.sortedWith(compareByDescending<MemoryRecord> { it.updatedAtMs }.thenBy { it.id }) ?: emptyList()

    fun retrieveResult(query: String, limit: Int = 8, nowMs: Long = clock()): MemorySearchResult {
        val read = read()
        val snapshot = read.snapshot ?: return MemorySearchResult(MemoryOutcome.STORAGE_FAILURE, read.error ?: "Memory store failed.")
        if (query.isBlank() || limit !in 1..50) return MemorySearchResult(MemoryOutcome.INVALID, "A query and limit 1-50 are required.")
        return MemorySearchResult(null, "ok", MemoryRetrieval.retrieve(snapshot.memories, query, limit, nowMs))
    }
    @Deprecated("Use retrieveResult() so storage failures are not represented as no matches.")
    fun retrieve(query: String, limit: Int = 8, nowMs: Long = clock()): List<RetrievedMemory> = retrieveResult(query, limit, nowMs).memories
    fun contextPacket(query: String, maxChars: Int, limit: Int = 8, nowMs: Long = clock()): MemoryPacketResult {
        val read = read()
        val snapshot = read.snapshot ?: return MemoryPacketResult(MemoryOutcome.STORAGE_FAILURE, read.error ?: "Memory store failed.")
        if (query.isBlank() || limit !in 1..50 || maxChars < 0) return MemoryPacketResult(MemoryOutcome.INVALID, "A query, limit 1-50, and non-negative budget are required.")
        val candidates = MemoryRetrieval.retrieve(snapshot.memories, query, limit, nowMs)
        val nextApprovedExpiryMs = snapshot.memories.asSequence()
            .filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
            .mapNotNull { it.expiresAtMs }
            .filter { it > nowMs }
            .minOrNull()
        return MemoryPacketResult(null, "ok", MemoryRetrieval.packetFromRetrieved(candidates, maxChars), MemoryRetrieval.approvedStateToken(snapshot.memories, nowMs), nextApprovedExpiryMs)
    }

    private fun lineage(memories: List<MemoryRecord>, id: String): Set<String> {
        val result = linkedSetOf(id)
        var changed = true
        while (changed) {
            changed = false
            for (memory in memories) if (memory.id in result || memory.correctsMemoryId in result) {
                changed = result.add(memory.id) || changed
                memory.correctsMemoryId?.let { changed = result.add(it) || changed }
            }
        }
        return result
    }

    private fun MemoryRecord.toProposal() = MemoryProposal(content, source, category, tier, type, confidence, expiresAtMs, correctsMemoryId, null, wikiAssignment)
}
