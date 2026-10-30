package com.battlesbudz.jarvis.v2.memory

/**
 * Owns everything about turning text into a stored vector that must stay identical
 * across runtime bindings: role formatting, output-width validation, Matryoshka
 * truncation and normalization. Role templates follow the model's published
 * instruction formats ("task: search result | query: …" for queries,
 * "title: none | text: …" for documents without a title); they live in exactly this
 * one place so a binding change can never silently change the embedding space.
 */
class MemoryEmbeddingService(
    private val engine: MemoryEmbeddingEngine,
    val spec: EmbeddingModelSpec = EmbeddingModelCatalog.default,
) {
    init {
        require(engine.modelId == spec.id) { "Embedding engine is bound to a different model than the service spec." }
        require(spec.storedDimension in 1..spec.outputDimension) { "Stored dimension must be within the model output." }
    }

    val isAvailable: Boolean get() = engine.available

    /** Stored-width, unit-normalized embedding of a memory's content (document side). */
    fun embedDocument(text: String): FloatArray = embed(text, EmbeddingRole.DOCUMENT)

    /** Stored-width, unit-normalized embedding of a user query (query side). */
    fun embedQuery(text: String): FloatArray = embed(text, EmbeddingRole.QUERY)

    private fun embed(text: String, role: EmbeddingRole): FloatArray {
        if (!engine.available) throw EmbeddingEngineUnavailable("Embedding engine is unavailable.")
        val raw = engine.embed(format(text, role), role)
        require(raw.size == spec.outputDimension) { "Embedding engine returned ${raw.size} dimensions; expected ${spec.outputDimension}." }
        return MemoryVectorMath.truncateAndNormalize(raw, spec.storedDimension)
    }

    internal fun format(text: String, role: EmbeddingRole): String = when (role) {
        EmbeddingRole.DOCUMENT -> "title: none | text: $text"
        EmbeddingRole.QUERY -> "task: search result | query: $text"
    }
}
