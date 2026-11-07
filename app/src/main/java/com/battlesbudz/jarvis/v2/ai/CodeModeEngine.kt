package com.battlesbudz.jarvis.v2.ai

/**
 * Engine surface code mode needs (code-and-export scope sections 3.5 and
 * 4.2): the one shared native conversation, prepared with the code system
 * prompt as its system instruction and tools disabled; a readable
 * conversation generation for the fingerprint reuse rule; and an explicit
 * release when code mode is left.
 *
 * Code mode borrows the shared conversation — there is no second engine
 * and no second KV cache. [prepareCodeConversation] always resets into
 * code mode, and every ordinary conversation reset leaves code mode
 * again, so an intervening chat turn, voice turn, tool-policy change, or
 * model switch is visible to the code session controller twice over: the
 * generation moves, and [codeModeActive] drops.
 *
 * [LiteRtLmEngine] implements this against the real bridge;
 * `FakeModelEngine` mirrors it in test sources, which is what the code
 * session policy tests drive.
 */
interface CodeModeEngine : ToolCallEngine {

    /** Native conversation sessions so far; increments on every conversation reset. */
    val conversationGeneration: Long

    /** True while the shared conversation is prepared for code mode. */
    val codeModeActive: Boolean

    /**
     * Resets the shared conversation and marks it for code mode: the next
     * conversation created carries tools disabled, the code prompt as its
     * system instruction, and the code sampler. Always prepares fresh;
     * returns true.
     */
    suspend fun prepareCodeConversation(): Boolean

    /**
     * Leaves code mode and releases the conversation, so chat rebuilds
     * from confirmed history exactly as it does after a voice mode
     * change. A no-op when code mode is not active.
     */
    suspend fun releaseCodeConversation()
}
