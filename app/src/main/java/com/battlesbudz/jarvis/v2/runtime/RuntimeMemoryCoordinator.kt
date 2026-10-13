package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.memory.ConversationMemory
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryOutcome
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.FinalMemoryInput
import com.battlesbudz.jarvis.v2.memory.MemoryCaptureReceiptCache
import com.battlesbudz.jarvis.v2.memory.MemoryContextPersistence
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates approved-memory snapshots with durable transcript/summary boundaries.
 * The epoch advances only after both persistence ports succeed. Capture receipts remain
 * single-use and epoch-bound; a failed boundary leaves subsequent context fail-closed.
 * Output cancellation stays with the voice owner, using [deliveryFence].
 */
internal class RuntimeMemoryCoordinator(
    private val memory: () -> ConversationMemory,
    private val currentConversationId: () -> String,
    private val persistHistoryCutoff: () -> Boolean,
    private val clearSummary: () -> Boolean,
    private val readStateToken: () -> String?,
    private val persistStateToken: (String?) -> Boolean,
    private val recordDiagnostic: (String) -> Unit
) {
    private val epoch = AtomicLong(0)
    private val boundaryPending = AtomicBoolean(false)
    private val historyCutoff = AtomicBoolean(false)
    private val captureReceipts = MemoryCaptureReceiptCache(
        currentEpoch = { epoch.get() }, boundaryPending = { boundaryPending.get() })
    val deliveryFence = MemoryDeliveryFence()
    @Volatile var stateToken: String? = null

    /** Close immediately, before the caller cancels in-flight delivery or persists history. */
    fun beginMemoryBoundary() { boundaryPending.set(true) }

    fun publishMemoryContextBoundary() {
        try {
            if (!persistHistoryCutoff()) error("memory_context_cutoff_persist_failed")
            if (!clearSummary()) error("memory_summary_clear_failed")
            captureReceipts.clear()
            historyCutoff.set(true)
            // Last: no fresh turn may pair the new approved token with pre-mutation context.
            epoch.incrementAndGet()
            boundaryPending.set(false)
        } catch (failure: Throwable) {
            // Leave the barrier closed: storage recovery must never reopen stale resident context.
            recordDiagnostic("Memory context boundary persistence failed: ${failure.javaClass.simpleName}")
        }
    }

    fun freshMemoryTurnContext(query: String, maxChars: Int): MemoryTurnContext? {
        if (boundaryPending.get()) return null
        // An approved mutation can happen between a store read and context construction. Retry
        // once rather than labeling that older packet with the newer epoch.
        repeat(2) {
            val before = epoch.get()
            val result = memory().approvedContext(query, maxChars, limit = 8)
            if (boundaryPending.get() || before != epoch.get()) return@repeat
            val packet = result.packet ?: return null
            // Core supplies the earliest approved expiry from this same coherent snapshot,
            // including facts absent from this packet but retained by history/native context.
            return MemoryTurnContext(packet.text, result.stateToken, query, result.nextApprovedExpiryMs, before,
                hasApprovedMemories = packet.memories.isNotEmpty()) {
                if (boundaryPending.get()) Long.MIN_VALUE else epoch.get()
            }
        }
        return null
    }

    fun adoptMemoryState(context: MemoryTurnContext): Boolean {
        // Persist only the opaque snapshot token, never memory text, so a process restart still
        // recognizes expiry/correction before it can reseed saved dialogue.
        val previous = stateToken ?: readStateToken()
        val changed = MemoryContextPersistence.adopt(
            previousToken = previous,
            newToken = context.stateToken,
            persistCutoff = {
                historyCutoff.set(true)
                persistHistoryCutoff()
            },
            clearSummary = {
                clearSummary()
            },
            persistToken = { token ->
                persistStateToken(token)
            }
        )
        stateToken = context.stateToken
        return changed
    }

    fun isMemoryTurnCurrent(context: MemoryTurnContext): Boolean {
        if (!context.isCurrent()) return false
        // Expiry changes the approved token without a mutation observer, so recheck the one-read
        // state token at the user-visible completion boundary.
        return memory().approvedContext(context.query, 0, limit = 8).stateToken == context.stateToken
    }

    fun consumeMemoryHistoryCutoff(): Boolean = historyCutoff.getAndSet(false)

    fun captureFinalMemory(eventId: String, conversationId: String, callId: String?,
                                   source: ConversationMemorySource, text: String, capturedAtMs: Long): Boolean {
        val captureEpoch = epoch.get()
        val result = memory().capture(FinalMemoryInput(eventId, conversationId, callId, source, text, capturedAtMs))
        captureReceipts.put(eventId, conversationId, text, captureEpoch, result)
        recordDiagnostic("Memory capture event=$eventId source=$source outcome=${result.outcome} reviewStatus=${result.memory?.reviewStatus ?: "none"}")
        val stored = result.outcome != ConversationMemoryOutcome.STORAGE_FAILURE
        if (!stored) recordDiagnostic("Memory capture storage failure for finalized $source input; conversation continues.")
        return stored
    }

    fun takeMemoryCaptureReceipt(text: String): ConversationMemoryResult? =
        captureReceipts.take(currentConversationId(), text)

}
