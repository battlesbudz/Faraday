package com.battlesbudz.jarvis.v2.ai

/**
 * Engine surface the tool-call reliability benchmark needs: initialization,
 * tool-enabled generation over a resettable native conversation, and close.
 *
 * LiteRtLmEngine implements this. Keeping the benchmark behind this interface
 * lets its owner path (acquire, integrity check, cleanup, cancellation) and
 * its per-fixture conversation reset be tested on the JVM without native code.
 */
interface ToolCallEngine {
    suspend fun initialize()
    suspend fun setToolsEnabled(enabled: Boolean): Boolean
    /** Closes the current native conversation so the next utterance starts with no history. */
    suspend fun resetConversation()
    suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult
    fun close()
}
