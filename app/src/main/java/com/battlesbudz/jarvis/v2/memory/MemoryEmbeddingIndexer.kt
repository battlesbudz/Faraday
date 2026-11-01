package com.battlesbudz.jarvis.v2.memory

data class MemoryEmbeddingSyncReport(
    val engineAvailable: Boolean,
    val embedded: Int,
    val removed: Int,
    val upToDate: Int,
    /** Eligible records still lacking a current-stamp vector after this sync. */
    val pending: Int,
    val error: String? = null,
) {
    /** Content-free one-line diagnostics in the house style; safe for logs and support pastes. */
    fun diagnosticsLine(modelId: String, dimension: Int, totalVectors: Int, unavailableReason: String? = null): String {
        val engineState = when {
            error != null -> "error"
            engineAvailable -> "available"
            else -> "unavailable"
        }
        val reason = if (engineState == "unavailable" && !unavailableReason.isNullOrBlank()) " ($unavailableReason)" else ""
        return "Memory embeddings: model=$modelId dim=$dimension vectors=$totalVectors pending=$pending engine=$engineState$reason"
    }
}

/**
 * Reconciles the vector projection with the canonical ledger. Eligibility is exactly
 * retrieval's rule — APPROVED and not expired — so pending, rejected, superseded and
 * expired memories are never embedded, and their stale vectors are removed. A record
 * whose content changed (fingerprint mismatch) is re-embedded; everything else is a
 * no-op, which makes sync idempotent and safe to run on any approved-state change or
 * app start. Storage failure on either side aborts with an error report rather than a
 * partial projection presented as current.
 */
class MemoryEmbeddingIndexer(
    private val memoryOs: MemoryOs,
    private val vectors: MemoryVectorStore,
    private val service: MemoryEmbeddingService,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun sync(): MemoryEmbeddingSyncReport {
        val spec = service.spec
        val read = memoryOs.read()
        val snapshot = read.snapshot ?: return failure(read.error ?: "Memory store failed.")
        val stored = vectors.read(spec.id)
        val entries = stored.entries ?: return failure(stored.error ?: "Memory vector store failed.")

        val now = clock()
        val eligible = eligibleRecords(snapshot, now)
        val eligibleIds = eligible.mapTo(HashSet()) { it.id }
        val byId = entries.associateBy { it.memoryId }

        var removed = 0
        val staleIds = entries.filter { it.memoryId !in eligibleIds }.map { it.memoryId }
        if (staleIds.isNotEmpty()) {
            if (!vectors.remove(staleIds)) return failure("Memory vector removal failed.")
            removed = staleIds.size
        }

        if (!service.isAvailable) {
            val current = eligible.count { isCurrent(it, byId) }
            return MemoryEmbeddingSyncReport(
                engineAvailable = false,
                embedded = 0,
                removed = removed,
                upToDate = current,
                pending = eligible.size - current,
            )
        }

        var embedded = 0
        var upToDate = 0
        var failedPuts = 0
        for (record in eligible) {
            if (isCurrent(record, byId)) { upToDate++; continue }
            val vector = try {
                service.embedDocument(record.content)
            } catch (unavailable: EmbeddingEngineUnavailable) {
                // The engine reported available and then refused: stop embedding and
                // report the honest state instead of a partial success.
                return MemoryEmbeddingSyncReport(
                    engineAvailable = false,
                    embedded = embedded,
                    removed = removed,
                    upToDate = upToDate,
                    pending = eligible.size - upToDate - embedded,
                )
            }
            val entry = MemoryVectorEntry(record.id, fingerprintOf(record), spec.id, spec.storedDimension, vector, clock())
            if (vectors.put(entry)) embedded++ else failedPuts++
        }
        return MemoryEmbeddingSyncReport(
            engineAvailable = true,
            embedded = embedded,
            removed = removed,
            upToDate = upToDate,
            pending = eligible.size - upToDate - embedded,
            error = if (failedPuts > 0) "Memory vector write failed." else null,
        )
    }

    private fun failure(message: String) = MemoryEmbeddingSyncReport(
        engineAvailable = service.isAvailable,
        embedded = 0,
        removed = 0,
        upToDate = 0,
        pending = 0,
        error = message,
    )

    private fun fingerprintOf(record: MemoryRecord) = AutomaticMemory.contentFingerprint(record.content)

    private fun isCurrent(record: MemoryRecord, byId: Map<String, MemoryVectorEntry>): Boolean {
        val entry = byId[record.id] ?: return false
        return entry.contentFingerprint == fingerprintOf(record) && entry.dimension == service.spec.storedDimension
    }

    /** Exactly retrieval's eligibility rule, shared by [sync] and [diagnosticsLine]. */
    private fun eligibleRecords(snapshot: MemorySnapshot, now: Long): List<MemoryRecord> =
        snapshot.memories.filter {
            it.reviewStatus == MemoryReviewStatus.APPROVED && (it.expiresAtMs == null || it.expiresAtMs > now)
        }

    /**
     * Read-only diagnostics for the standard Copy Diagnostics export, computed at
     * call time from the live ledger and vector store: the same eligibility and
     * currency predicates as [sync], but nothing is embedded, removed or written,
     * so the reported vector count is the store exactly as it stands (stale rows
     * included — removing them is a sync's job). The engine field is the engine's
     * live availability, with its reason when unavailable; a store failure renders
     * engine=error rather than invented counts.
     */
    fun diagnosticsLine(): String {
        val spec = service.spec
        val stored = vectors.read(spec.id)
        val entries = stored.entries ?: return failureLine(spec, stored.error ?: "Memory vector store failed.", totalVectors = 0)
        val read = memoryOs.read()
        val snapshot = read.snapshot ?: return failureLine(spec, read.error ?: "Memory store failed.", totalVectors = entries.size)
        val eligible = eligibleRecords(snapshot, clock())
        val byId = entries.associateBy { it.memoryId }
        val current = eligible.count { isCurrent(it, byId) }
        val report = MemoryEmbeddingSyncReport(
            engineAvailable = service.isAvailable,
            embedded = 0,
            removed = 0,
            upToDate = current,
            pending = eligible.size - current,
        )
        return report.diagnosticsLine(spec.id, spec.storedDimension, entries.size, service.unavailableReason)
    }

    private fun failureLine(spec: EmbeddingModelSpec, message: String, totalVectors: Int): String =
        MemoryEmbeddingSyncReport(
            engineAvailable = service.isAvailable,
            embedded = 0,
            removed = 0,
            upToDate = 0,
            pending = 0,
            error = message,
        ).diagnosticsLine(spec.id, spec.storedDimension, totalVectors, service.unavailableReason)
}
