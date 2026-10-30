package com.battlesbudz.jarvis.v2.memory

/**
 * Raised when no on-device embedding runtime is bound. Callers must surface this as a
 * state (the diagnostics line reports engine=unavailable); nothing may fabricate a
 * vector in its place.
 */
class EmbeddingEngineUnavailable(message: String) : Exception(message)

/**
 * The narrow port between the memory package and whichever runtime ends up running the
 * embedding model on-device. Implementations return a raw model-width embedding of the
 * text exactly as given; role formatting, truncation and normalization belong to
 * [MemoryEmbeddingService] so they are identical for every binding.
 */
interface MemoryEmbeddingEngine {
    val modelId: String

    /** True only when this binding can actually embed right now. */
    val available: Boolean

    /** Raw model-width embedding. Throws [EmbeddingEngineUnavailable] when unbound. */
    fun embed(text: String, role: EmbeddingRole): FloatArray
}

/**
 * Production placeholder until the runtime-binding spike (plan Unit 1.5) lands a real
 * engine behind this port. Reporting unavailable is the honest state: the pinned
 * LiteRT-LM bridge predates EmbeddingGemma 2 and its embedding surface is unverified.
 */
object UnavailableMemoryEmbeddingEngine : MemoryEmbeddingEngine {
    override val modelId: String = EmbeddingModelCatalog.default.id
    override val available: Boolean = false
    override fun embed(text: String, role: EmbeddingRole): FloatArray =
        throw EmbeddingEngineUnavailable("No embedding runtime is bound yet.")
}
