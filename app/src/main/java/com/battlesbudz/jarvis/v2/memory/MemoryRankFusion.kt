package com.battlesbudz.jarvis.v2.memory

/**
 * Reciprocal Rank Fusion over per-source ranked candidate lists (the field-consensus
 * hybrid combiner used by GBrain, Graphiti and Hindsight): a memory's fused score is
 * Σ 1/(k + rank) over every source list containing it, with 1-based ranks and k = 60.
 * A fused score is a ranking signal only — it is not a probability that a memory is
 * relevant; relevance filtering happens separately in [MemoryHybridRetrieval].
 * Ordering is deterministic: score descending, then memory id ascending, independent
 * of source-list iteration order.
 */
object MemoryRankFusion {
    const val RRF_K = 60

    fun fuse(rankings: List<List<String>>): List<Pair<String, Double>> {
        val scores = LinkedHashMap<String, Double>()
        for (ranking in rankings) {
            val seen = HashSet<String>()
            ranking.forEachIndexed { index, id ->
                if (!seen.add(id)) return@forEachIndexed
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (RRF_K + index + 1)
            }
        }
        return scores.entries
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
    }
}
