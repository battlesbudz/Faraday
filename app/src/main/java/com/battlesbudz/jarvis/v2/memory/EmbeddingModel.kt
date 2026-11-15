package com.battlesbudz.jarvis.v2.memory

/** Which side of a retrieval pair a text is embedded as; the model defines distinct role formats. */
enum class EmbeddingRole { DOCUMENT, QUERY }

/**
 * A downloadable embedding-model artifact. Deliberately separate from the chat-shaped
 * LocalModelSpec: an embedder has no selected-model or conversation semantics, only an
 * artifact identity, an integrity pin and an output geometry.
 */
data class EmbeddingModelSpec(
    val id: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String,
    val downloadBytes: Long,
    /** Native output width of the model (768 for EmbeddingGemma). */
    val outputDimension: Int,
    /** Matryoshka-truncated width persisted in the vector store. Stamped per vector row. */
    val storedDimension: Int,
)

/**
 * EmbeddingGemma 2 text-only (270M), Apache-2.0 and ungated. The artifact is pinned to a
 * fixed Hugging Face revision; the SHA-256 is the LFS object hash published for that file
 * (verified against the repository tree, 2026-10-30). Chip-specific variants exist in the
 * same repository; the generic file is the portable default.
 */
object EmbeddingModelCatalog {
    val embeddingGemma2Text = EmbeddingModelSpec(
        id = "embeddinggemma-2-text-270m",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm" +
            "/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm?download=true",
        expectedSha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        downloadBytes = 164_626_432L,
        outputDimension = 768,
        storedDimension = 256,
    )

    val default: EmbeddingModelSpec = embeddingGemma2Text
    val all: List<EmbeddingModelSpec> = listOf(embeddingGemma2Text)
    fun find(id: String): EmbeddingModelSpec? = all.firstOrNull { it.id == id }
}
