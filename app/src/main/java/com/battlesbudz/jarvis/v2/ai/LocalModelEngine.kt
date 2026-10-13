package com.battlesbudz.jarvis.v2.ai

/**
 * Boundary for any local language-model runtime.
 * LiteRT-LM integration belongs behind this interface.
 */
interface LocalModelEngine {
    val modelId: String

    suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult
}

data class GenerationResult(
    val text: String,
    val timeToFirstTokenMs: Long,
    val decodeTokensPerSecond: Double?,
    /** LiteRT-LM does not expose token IDs; this is a tokenizer-free estimate. */
    val outputTokens: Int? = null,
    val totalGenerationTimeMs: Long = -1L,
    val streamEvents: Int = 0,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Wall time inside the SDK submission call, not a measured audio encoder stage. */
    val nativeSubmitMs: Long? = null,
    /** First native message callback of any kind, including tool/control output. */
    val firstCallbackMs: Long? = null
)


data class ToolCall(val name: String, val arguments: String)
