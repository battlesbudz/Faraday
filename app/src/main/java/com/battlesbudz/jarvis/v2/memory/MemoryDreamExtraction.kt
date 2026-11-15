package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest

/**
 * Unit 3's extraction step (scope §3.3 step 3, Vessel's semantic-extraction
 * shape): recent EPISODIC records clustered by category are the raw material
 * for durable facts. Clustering is deterministic and LLM-free; the judge
 * call and the commit gate live in the pass, unchanged from group outputs.
 *
 * A cluster forms only when at least one member changed since the watermark
 * (the seed rule that keeps the step idempotent across nights), but its
 * membership is every recent episode in the category — an old episode can
 * corroborate a new one, exactly as grouping admits older neighbours.
 */
object MemoryDreamExtraction {
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val MIN_CLUSTER_RECORDS = 3
    const val MAX_CLUSTER_RECORDS = 20
    const val MAX_CLUSTERS_PER_RUN = 4

    fun clusters(
        eligible: List<MemoryRecord>,
        watermarkMs: Long,
        nowMs: Long,
    ): List<DreamExtractionCluster> {
        val recent = eligible.filter {
            it.type == MemoryType.EPISODIC && it.createdAtMs >= nowMs - WINDOW_MS
        }
        return recent.groupBy { it.category }.entries.mapNotNull { (category, records) ->
            if (records.size < MIN_CLUSTER_RECORDS) return@mapNotNull null
            if (records.none { it.updatedAtMs > watermarkMs && !MemoryDreamGrouping.isDreamDerived(it) }) {
                return@mapNotNull null
            }
            val shown = records
                .sortedWith(compareByDescending<MemoryRecord> { it.createdAtMs }.thenBy { it.id })
                .take(MAX_CLUSTER_RECORDS)
            DreamExtractionCluster(
                key = clusterKey(category, shown.map { it.id }),
                category = category,
                records = shown,
            )
        }.sortedBy { it.key }.take(MAX_CLUSTERS_PER_RUN)
    }

    /** Changed, non-derived records inside [cluster]: the seeds its processing discharges. */
    fun seedIds(cluster: DreamExtractionCluster, watermarkMs: Long): Set<String> =
        cluster.records.filter {
            it.updatedAtMs > watermarkMs && !MemoryDreamGrouping.isDreamDerived(it)
        }.mapTo(HashSet()) { it.id }

    fun clusterKey(category: MemoryCategory, recordIds: Collection<String>): String {
        val joined = category.name + "\n" + recordIds.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return "cluster-" + digest.joinToString("") { "%02x".format(it) }
    }
}
