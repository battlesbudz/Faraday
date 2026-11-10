package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest

/** Similarity band for a formed group; grouping asserts geometry only, never a verb. */
enum class DreamBand {
    /** Above the merge-candidate threshold; the judge must still confirm any proposal. */
    MERGE_CANDIDATE,

    /** In the judge-only band; grouping alone asserts nothing beyond relatedness. */
    JUDGE_ONLY,

    /** Below the link floor after capping; no verb is available and the group dissolves. */
    LINK_ONLY,
}

data class DreamGroup(
    val key: String,
    val records: List<MemoryRecord>,
    val seedIds: Set<String>,
    val band: DreamBand,
    val maxSimilarity: Double,
) {
    val recordIds: List<String> get() = records.map { it.id }
    val categories: Set<MemoryCategory> get() = records.mapTo(LinkedHashSet()) { it.category }

    /** The category a synthesized record takes: unanimous if possible, else the modal category. */
    val category: MemoryCategory get() = records.groupingBy { it.category }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<MemoryCategory, Int>> { it.value }.thenBy { it.key.ordinal })
        .first().key

    /** PERSON/PREFERENCE groups take the gate's conservative lane. */
    val conservative: Boolean get() = records.any {
        it.category == MemoryCategory.PERSON || it.category == MemoryCategory.PREFERENCE
    }
    val containsSettingsEntered: Boolean get() = records.any { MemoryDreamGrouping.isSettingsEntered(it) }
    val allDerived: Boolean get() = records.all { MemoryDreamGrouping.isDreamDerived(it) }
    val hasCanonicalSource: Boolean get() = records.any { !MemoryDreamGrouping.isDreamDerived(it) }
}

data class DreamGroupingResult(
    val eligible: List<MemoryRecord>,
    val changed: List<MemoryRecord>,
    val groups: List<DreamGroup>,
    /** Changed seeds with a valid vector and no formed group: fully processed singletons. */
    val singletonSeedIds: Set<String>,
    /** Changed seeds lacking a valid vector: not group-processed by this result. */
    val unvectorizedSeedIds: Set<String>,
)

/**
 * Unit 3's deterministic candidate generation. It reads only canonical,
 * APPROVED, non-expired, NORMAL records; starts from records changed since
 * the watermark; expands each seed by vector nearest neighbours (a partner
 * may be older than the watermark); and forms capped similarity components.
 * No LLM runs here and no record is written here.
 */
object MemoryDreamGrouping {
    const val MERGE_CANDIDATE_THRESHOLD = 0.92
    const val LINK_THRESHOLD = 0.85
    const val MAX_GROUP_RECORDS = 7

    const val SETTINGS_EVENT_SOURCE = "manual entry"
    const val DREAM_EVENT_SOURCE_PREFIX = "dream_cycle/"

    fun eligible(records: List<MemoryRecord>, nowMs: Long): List<MemoryRecord> = records.filter { record ->
        record.reviewStatus == MemoryReviewStatus.APPROVED &&
            record.source.sensitivity == MemorySensitivity.NORMAL &&
            (record.expiresAtMs == null || record.expiresAtMs > nowMs) &&
            !MemoryPolicy.containsRawRestrictedContent(record.content)
    }.sortedBy { it.id }

    fun isDreamDerived(record: MemoryRecord): Boolean =
        record.source.eventSource.startsWith(DREAM_EVENT_SOURCE_PREFIX) ||
            record.source.provenance.any { it.kind == "dream_cycle" }

    fun isSettingsEntered(record: MemoryRecord): Boolean =
        record.source.eventSource == SETTINGS_EVENT_SOURCE

    /** Changed records that may seed a group. Dream outputs never seed their own reprocessing. */
    fun changedSeeds(eligible: List<MemoryRecord>, watermarkMs: Long): List<MemoryRecord> =
        eligible.filter { it.updatedAtMs > watermarkMs && !isDreamDerived(it) }
            .sortedWith(compareBy<MemoryRecord> { it.updatedAtMs }.thenBy { it.id })

    fun formGroups(
        records: List<MemoryRecord>,
        watermarkMs: Long,
        vectors: Map<String, FloatArray>,
        nowMs: Long,
    ): DreamGroupingResult {
        val eligible = eligible(records, nowMs)
        val changed = changedSeeds(eligible, watermarkMs)
        val changedIds = changed.mapTo(HashSet()) { it.id }
        val vectorized = eligible.filter { record ->
            vectors[record.id]?.let { it.isNotEmpty() && it.any { component -> component != 0f } } == true
        }
        val vectorizedIds = vectorized.mapTo(HashSet()) { it.id }

        // Union-find over every pair at or above the link floor. Personal scale
        // (<=500 records) makes the exact O(n^2) scan the honest choice.
        val parent = HashMap<String, String>()
        vectorized.forEach { parent[it.id] = it.id }
        fun find(id: String): String {
            var root = id
            while (parent[root] != root) root = parent.getValue(root)
            var current = id
            while (parent[current] != root) {
                val next = parent.getValue(current)
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val similarities = HashMap<Pair<String, String>, Double>()
        for (i in vectorized.indices) {
            for (j in i + 1 until vectorized.size) {
                val a = vectorized[i]; val b = vectorized[j]
                val va = vectors.getValue(a.id); val vb = vectors.getValue(b.id)
                if (va.size != vb.size) continue
                val similarity = MemoryVectorMath.cosine(va, vb)
                if (similarity >= LINK_THRESHOLD) {
                    similarities[a.id to b.id] = similarity
                    union(a.id, b.id)
                }
            }
        }

        val components = vectorized.groupBy { find(it.id) }.values
            .filter { component -> component.size >= 2 && component.any { it.id in changedIds } }

        val groupedSeedIds = HashSet<String>()
        val groups = components.map { component ->
            val selected = selectGroupMembers(component, changedIds, vectors)
            val selectedIds = selected.mapTo(HashSet()) { it.id }
            groupedSeedIds += selectedIds.intersect(changedIds)
            var maxSimilarity = 0.0
            for (i in selected.indices) {
                for (j in i + 1 until selected.size) {
                    val key = minOf(selected[i].id, selected[j].id) to maxOf(selected[i].id, selected[j].id)
                    val direct = similarities[key]
                    if (direct != null && direct > maxSimilarity) maxSimilarity = direct
                }
            }
            DreamGroup(
                key = groupKey(selectedIds),
                records = selected.sortedBy { it.id },
                seedIds = selectedIds.intersect(changedIds),
                band = when {
                    maxSimilarity > MERGE_CANDIDATE_THRESHOLD -> DreamBand.MERGE_CANDIDATE
                    maxSimilarity >= LINK_THRESHOLD -> DreamBand.JUDGE_ONLY
                    else -> DreamBand.LINK_ONLY
                },
                maxSimilarity = maxSimilarity,
            )
        }.sortedBy { it.key }

        val vectorizedChanged = changedIds.intersect(vectorizedIds)
        return DreamGroupingResult(
            eligible = eligible,
            changed = changed,
            groups = groups,
            singletonSeedIds = vectorizedChanged - groupedSeedIds,
            unvectorizedSeedIds = changedIds - vectorizedIds,
        )
    }

    /**
     * Caps a component at the provenance ceiling: changed seeds first (newest
     * first), then neighbours by their strongest similarity to a selected seed.
     */
    private fun selectGroupMembers(
        component: List<MemoryRecord>,
        changedIds: Set<String>,
        vectors: Map<String, FloatArray>,
    ): List<MemoryRecord> {
        if (component.size <= MAX_GROUP_RECORDS) return component
        val seeds = component.filter { it.id in changedIds }
            .sortedWith(compareByDescending<MemoryRecord> { it.updatedAtMs }.thenBy { it.id })
        val selected = seeds.take(MAX_GROUP_RECORDS).toMutableList()
        if (selected.size == MAX_GROUP_RECORDS) return selected
        val selectedIds = selected.mapTo(HashSet()) { it.id }
        val neighbours = component.filter { it.id !in selectedIds }.map { candidate ->
            val best = selected.maxOf { chosen ->
                val a = vectors.getValue(candidate.id); val b = vectors.getValue(chosen.id)
                if (a.size == b.size) MemoryVectorMath.cosine(a, b) else 0.0
            }
            candidate to best
        }.sortedWith(compareByDescending<Pair<MemoryRecord, Double>> { it.second }.thenBy { it.first.id })
        neighbours.take(MAX_GROUP_RECORDS - selected.size).forEach { selected += it.first }
        return selected
    }

    /** Stable group identity from the exact source set; used for resume and event ids. */
    fun groupKey(recordIds: Collection<String>): String {
        val joined = recordIds.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return "group-" + digest.joinToString("") { "%02x".format(it) }
    }
}
