package com.battlesbudz.jarvis.v2.memory

/** How the semantic candidate source actually fared on one turn; reported, never hidden. */
enum class SemanticRetrievalState {
    /** The query was embedded once and searched over current-stamp vectors. */
    AVAILABLE,

    /** No embedding runtime can embed right now (e.g. the model is not installed). */
    UNAVAILABLE,

    /** The runtime reported available but no eligible record has a current vector yet. */
    EMPTY,

    /** The vector store or the embedding call failed; the turn continued without it. */
    ERROR,

    /** The query sits on the action/public-information boundary; no source ran. */
    SKIPPED,
}

data class HybridRetrievalResult(
    /** Always-injected profile slice (name/preferences class), in packet order. */
    val profile: List<MemoryRecord>,
    /** Fused, relevance-filtered searched results, best first, profile ids excluded. */
    val searched: List<RetrievedMemory>,
    val lexicalCandidates: Int,
    val semanticCandidates: Int,
    val wikiCandidates: Int,
    val semanticState: SemanticRetrievalState,
) {
    /**
     * Content-free diagnostics in the house style: candidate counts by source, the
     * semantic source's honest state, and the fused top ids. Ids and counts only —
     * never memory content.
     */
    fun diagnosticsLine(): String {
        val top = searched.take(TOP_IDS_IN_DIAGNOSTICS).joinToString(",") { it.memory.id }
        return "Memory retrieval: lexical=$lexicalCandidates semantic=$semanticCandidates " +
            "wiki=$wikiCandidates fused=${searched.size} profile=${profile.size} " +
            "semantic=${semanticState.name.lowercase()} top=[$top]"
    }

    companion object { const val TOP_IDS_IN_DIAGNOSTICS = 5 }
}

/**
 * Hybrid recall for ordinary turns (memory RAG Unit 2). The raw request has already
 * set the action boundary upstream (context is only built for non-action turns);
 * this pipeline then generates candidates from three peer sources over the same
 * canonical snapshot —
 * (a) the existing lexical ranker, unchanged, personal-recall fallbacks included;
 * (b) semantic search over the Unit 1 vector projection, the query embedded exactly
 *     once through [MemoryEmbeddingService]'s query role, restricted to vectors whose
 *     stamp still matches an eligible record in this snapshot (a stale vector is
 *     rejected here exactly as the indexer rejects it at sync time);
 * (c) wiki pages via [MemoryWikiIndex.search] —
 * fuses them with reciprocal rank fusion (k=60), applies a separate relevance filter,
 * and splits the result into the always-injected profile slice and the searched
 * collection. Eligibility is retrieval's existing rule everywhere: APPROVED and
 * non-expired; pending, rejected, superseded and expired records can never surface.
 *
 * Degradation is honest and never blocks the turn: when the embedding model is not
 * installed the semantic source is simply absent, lexical + wiki carry the turn, and
 * the diagnostics state says `unavailable` instead of dressing the gap up as an
 * empty memory.
 */
class MemoryHybridRetrieval(
    private val service: MemoryEmbeddingService,
    private val vectors: MemoryVectorStore,
) {
    fun retrieve(snapshot: MemorySnapshot, query: String, limit: Int, nowMs: Long): HybridRetrievalResult {
        require(limit in 1..50) { "Hybrid retrieval limit must be within 1..50." }
        if (MemoryRetrieval.isPublicInformationOrDeviceCommand(query)) {
            return HybridRetrievalResult(emptyList(), emptyList(), 0, 0, 0, SemanticRetrievalState.SKIPPED)
        }
        val eligible = snapshot.memories.filter {
            it.reviewStatus == MemoryReviewStatus.APPROVED && (it.expiresAtMs == null || it.expiresAtMs > nowMs)
        }
        val eligibleById = eligible.associateBy { it.id }
        val profile = profileSlice(eligible)
        val profileIds = profile.mapTo(HashSet()) { it.id }

        val lexical = MemoryRetrieval.retrieve(snapshot.memories, query, CANDIDATE_LIMIT, nowMs)
        val wiki = MemoryWiki.build(snapshot, nowMs).search(query).take(CANDIDATE_LIMIT)

        val (semantic, semanticState) = semanticCandidates(query, eligibleById)
        val semanticIds = semantic.mapTo(HashSet()) { it.memory.id }
        val wikiIds = wiki.mapTo(HashSet()) { it.id }

        val fused = MemoryRankFusion.fuse(listOf(
            lexical.map { it.memory.id },
            semantic.map { it.memory.id },
            wiki.map { it.id },
        ))
        val lexicalById = lexical.associateBy { it.memory.id }
        val evidence = if (semanticState == SemanticRetrievalState.AVAILABLE) {
            MemoryRetrieval.lexicalEvidence(snapshot.memories, query, nowMs)
        } else emptyMap()

        val searched = fused.asSequence()
            .filter { (id, _) -> id !in profileIds }
            .filter { (id, _) ->
                id in eligibleById && passesRelevanceFilter(
                    lexicalEntry = lexicalById[id],
                    evidence = evidence[id],
                    corroborated = id in semanticIds || id in wikiIds,
                    semanticAvailable = semanticState == SemanticRetrievalState.AVAILABLE,
                )
            }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenByDescending { eligibleById[it.first]?.confidence ?: 0 }
                    .thenByDescending { eligibleById[it.first]?.updatedAtMs ?: 0L }
                    .thenBy { it.first }
            )
            .take(limit)
            .map { (id, rrf) -> RetrievedMemory(eligibleById.getValue(id), (rrf * SCORE_SCALE).toInt().coerceAtLeast(1)) }
            .toList()

        return HybridRetrievalResult(
            profile = profile,
            searched = searched,
            lexicalCandidates = lexical.size,
            semanticCandidates = semantic.size,
            wikiCandidates = wiki.size,
            semanticState = semanticState,
        )
    }

    /**
     * The profile slice of the LangMem profile-vs-collection split: name/person-class
     * and preference-class records, always injected ahead of searched results when a
     * packet is built. Deterministic selection: PERSON class first, then PREFERENCE;
     * within a class, confidence descending, most recently updated first, id ascending;
     * capped at [PROFILE_LIMIT] records so the slice stays a small profile, not a dump.
     */
    private fun profileSlice(eligible: List<MemoryRecord>): List<MemoryRecord> =
        eligible.asSequence()
            .filter { it.category == MemoryCategory.PERSON || it.category == MemoryCategory.PREFERENCE }
            .sortedWith(
                compareBy<MemoryRecord> { if (it.category == MemoryCategory.PERSON) 0 else 1 }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.id }
            )
            .take(PROFILE_LIMIT)
            .toList()

    /**
     * Semantic nominations: current-stamp vectors for eligible records only, searched
     * with the once-embedded query, floored by cosine similarity. Every failure mode
     * returns an honest state and no candidates; nothing here throws or fabricates.
     */
    private fun semanticCandidates(
        query: String,
        eligibleById: Map<String, MemoryRecord>,
    ): Pair<List<RetrievedMemory>, SemanticRetrievalState> {
        if (!service.isAvailable) return emptyList<RetrievedMemory>() to SemanticRetrievalState.UNAVAILABLE
        val stored = vectors.read(service.spec.id)
        val entries = stored.entries ?: return emptyList<RetrievedMemory>() to SemanticRetrievalState.ERROR
        val usable = entries.filter { entry ->
            val record = eligibleById[entry.memoryId] ?: return@filter false
            entry.modelId == service.spec.id &&
                entry.dimension == service.spec.storedDimension &&
                entry.contentFingerprint == AutomaticMemory.contentFingerprint(record.content)
        }
        if (usable.isEmpty()) return emptyList<RetrievedMemory>() to SemanticRetrievalState.EMPTY
        val queryVector = try {
            service.embedQuery(query)
        } catch (unavailable: EmbeddingEngineUnavailable) {
            return emptyList<RetrievedMemory>() to SemanticRetrievalState.UNAVAILABLE
        } catch (failure: Exception) {
            return emptyList<RetrievedMemory>() to SemanticRetrievalState.ERROR
        }
        val nominations = MemoryVectorSearch.search(queryVector, usable, CANDIDATE_LIMIT)
            .filter { it.score >= SEMANTIC_COSINE_FLOOR }
            .map { RetrievedMemory(eligibleById.getValue(it.entry.memoryId), (it.score * SCORE_SCALE).toInt().coerceAtLeast(1)) }
        return nominations to SemanticRetrievalState.AVAILABLE
    }

    /**
     * Relevance filtering is separate from fusion (a fused rank is not a relevance
     * probability). Corroboration rule, applied only when the semantic source actually
     * ran over current vectors: a candidate survives when another source nominated it
     * too, or its lexical evidence is stronger than a single shared token — at least
     * two distinct matched query tokens, a full-phrase hit, or a strict personal-recall
     * fallback match (score 1 in the lexical ranker). A lone shared word ("brother"
     * in "what is my brother's phone number?") is not evidence the memory answers the
     * question. When semantic never ran (model absent, index empty, source error),
     * corroboration was impossible, so every fused candidate passes exactly as
     * lexical + wiki behaved before this unit.
     */
    private fun passesRelevanceFilter(
        lexicalEntry: RetrievedMemory?,
        evidence: MemoryRetrieval.LexicalEvidence?,
        corroborated: Boolean,
        semanticAvailable: Boolean,
    ): Boolean {
        if (!semanticAvailable || corroborated) return true
        if (lexicalEntry == null) return true
        if (lexicalEntry.score == 1) return true
        val matched = evidence?.matchedTokens ?: 0
        return matched >= 2 || evidence?.phraseHit == true
    }

    companion object {
        /** Per-source ranked lists are wider than the packet so fusion sees real ranks. */
        const val CANDIDATE_LIMIT = 24

        /** Profile slice cap: a small always-injected profile, never the whole ledger. */
        const val PROFILE_LIMIT = 4

        /**
         * Cosine floor for semantic nominations — a calibration starting point set
         * before the golden-corpus measurement (Unit 2 evaluation), in line with the
         * in-repo plan's rule that thresholds are fixed from the corpus, not chosen
         * retrospectively to pass a build. Personal facts are short and distinctive;
         * unrelated pairs sit far below this in the corpus geometry.
         */
        const val SEMANTIC_COSINE_FLOOR = 0.50

        /** RetrievedMemory scores are ints; fused/cosine scores scale into that space. */
        const val SCORE_SCALE = 100_000.0
    }
}
