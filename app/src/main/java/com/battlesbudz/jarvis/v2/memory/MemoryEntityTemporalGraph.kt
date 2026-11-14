package com.battlesbudz.jarvis.v2.memory

/**
 * Experimental, in-memory projection of the canonical ledger. This is deliberately
 * not wired into MemoryOs, embeddings, or ordinary recall.
 */
object MemoryEntityTemporalPrototype {
    /** Owner must explicitly change this and wire a caller before this can affect a turn. */
    const val ENABLED = false
}

data class MemoryEntity(val canonicalName: String, val memoryIds: Set<String>)

data class MemoryFactVersion(
    val memoryId: String,
    val content: String,
    val validFromMs: Long,
    val validToMs: Long?,
    val supersededBy: String?,
)

data class MemoryGraphAnswer(val text: String, val memories: List<MemoryRecord>)

/** Read-only projection rebuilt from a single ledger snapshot; it owns no durable state. */
class MemoryEntityTemporalGraph private constructor(
    private val entities: Map<String, MemoryEntity>,
    private val records: Map<String, MemoryRecord>,
    private val versions: Map<String, MemoryFactVersion>,
) {
    fun entity(name: String): MemoryEntity? = entities[key(name)]

    fun history(memoryId: String): List<MemoryFactVersion> {
        val chain = records.values.filter { it.id == memoryId || it.correctsMemoryId == memoryId }
        val ancestors = linkedSetOf<String>()
        var cursor = chain.firstOrNull { it.id == memoryId }
        while (cursor != null && ancestors.add(cursor.id)) {
            cursor = records.values.firstOrNull { it.id == cursor!!.correctsMemoryId }
        }
        val lineage = ancestors.toMutableSet()
        var grew: Boolean
        do {
            grew = false
            records.values.filter { it.reviewStatus == MemoryReviewStatus.APPROVED && it.correctsMemoryId in lineage }
                .forEach { if (lineage.add(it.id)) grew = true }
        } while (grew)
        return lineage.mapNotNull(versions::get).sortedBy { it.validFromMs }
    }

    /** Small deterministic query surface used to prove the study examples on fixtures. */
    fun answer(question: String): MemoryGraphAnswer {
        val normalized = key(question)
        val entityMatch = entities.values.firstOrNull { normalized.contains(key(it.canonicalName)) }
        if (entityMatch != null) {
            val matches = entityMatch.memoryIds.mapNotNull(records::get).sortedBy { it.createdAtMs }
            return MemoryGraphAnswer(matches.joinToString("\n") { it.content }, matches)
        }
        val tokens = normalized.split(' ').filter { it.length > 2 && it !in QUESTION_WORDS }.toSet()
        val current = records.values.filter { record ->
            record.reviewStatus == MemoryReviewStatus.APPROVED && tokens.any { token ->
                key(record.content).split(' ').any { word -> word == token || (token.length >= 4 && word.startsWith(token)) }
            }
        }
        val relevantVersions = current.flatMap { history(it.id) }.distinctBy { it.memoryId }
        val asksHistory = listOf("when", "change", "old", "used to", "previous", "before", "former")
            .any(normalized::contains)
        if (asksHistory && relevantVersions.size > current.size) {
            val lines = relevantVersions.map { version ->
                "${version.content} (${version.validFromMs}${version.validToMs?.let { "–$it" } ?: "–present"})"
            }
            return MemoryGraphAnswer(lines.joinToString("; "), relevantVersions.mapNotNull { records[it.memoryId] })
        }
        return MemoryGraphAnswer(current.joinToString("\n") { it.content }, current)
    }

    companion object {
        private val personCue = Regex("\\b(?:my|our|friend|sister|brother|mother|father|wife|husband|colleague|called|named)\\s+([A-Z][\\p{L}'’-]*(?:\\s+[A-Z][\\p{L}'’-]*)?)")
        private val capitalizedName = Regex("(?<![.!?]\\s)\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)\\b")
        private val QUESTION_WORDS = setOf("what", "when", "did", "change", "know", "about", "the", "for", "does", "is", "was", "my", "who", "how")

        fun build(snapshot: MemorySnapshot, nowMs: Long): MemoryEntityTemporalGraph {
            require(nowMs > 0)
            // Deleted rows are absent from the canonical snapshot. Tombstones intentionally
            // contain only opaque event metadata and can never recreate graph nodes.
            val erasedEvents = snapshot.tombstones.mapTo(HashSet()) { it.eventId }
            val safe = snapshot.memories.filter { record ->
                record.source.eventId !in erasedEvents &&
                record.source.sensitivity == MemorySensitivity.NORMAL &&
                    record.source.provenance.none { it.restricted } &&
                    !MemoryPolicy.containsRawRestrictedContent(record.content) &&
                    record.reviewStatus in setOf(MemoryReviewStatus.APPROVED, MemoryReviewStatus.SUPERSEDED) &&
                    (record.expiresAtMs == null || record.expiresAtMs > nowMs)
            }.associateBy { it.id }
            val active = safe.values.filter { it.reviewStatus == MemoryReviewStatus.APPROVED }
            val superseder = active.mapNotNull { child -> child.correctsMemoryId?.let { it to child } }.toMap()
            val versions = safe.values.associate { record ->
                val next = superseder[record.id]
                record.id to MemoryFactVersion(
                    record.id, record.content, record.source.createdAtMs,
                    next?.source?.createdAtMs, next?.id
                )
            }
            val linked = linkedMapOf<String, MutableSet<String>>()
            for (record in active) {
                val names = linkedSetOf<String>()
                personCue.findAll(record.content).forEach { names += it.groupValues[1].trim() }
                capitalizedName.findAll(record.content).forEach { names += it.groupValues[1].trim() }
                names.filter { it.length >= 2 }.forEach { name -> linked.getOrPut(key(name)) { linkedSetOf() } += record.id }
            }
            return MemoryEntityTemporalGraph(
                linked.mapValues { (name, ids) -> MemoryEntity(nameFrom(ids.first(), safe) ?: name, ids.toSet()) },
                // Retain eligible superseded rows for correction-chain traversal. Query
                // selection and entity links remain restricted to approved active rows.
                safe, versions,
            )
        }

        private fun nameFrom(id: String, records: Map<String, MemoryRecord>): String? {
            val content = records[id]?.content ?: return null
            return personCue.find(content)?.groupValues?.get(1)?.trim()
                ?: capitalizedName.find(content)?.groupValues?.get(1)?.trim()
        }

        private fun key(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
