package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.CodeModeEngine

/**
 * The Code screen's door to the one shared model engine (scope
 * Unit 6). Code rounds generate on the same engine instance chat and
 * voice use — the codebase keeps exactly one resident engine because
 * a second idle KV cache is not affordable — so an operation borrows
 * it under the model-operation lease chat already respects
 * (`ModelStore.tryBeginModelOperation`), and the borrow fails
 * cleanly while a chat reply, a voice turn, or another code round
 * holds the lease.
 *
 * The production implementation lives on `JarvisRuntime`; tests hand
 * the driver a fake that runs the block against a fake engine, or
 * refuses the borrow.
 */
interface CodeEngineAccess {

    /**
     * Runs [block] with the borrowed engine and that model's context
     * size in tokens (null when the catalog does not pin one), or
     * returns null when the borrow is unavailable — the lease is
     * held, no model is downloaded, or the engine could not be
     * prepared. The lease is released when [block] returns or throws.
     */
    suspend fun <T> withEngine(block: suspend (engine: CodeModeEngine, contextTokens: Int?) -> T): T?
}
