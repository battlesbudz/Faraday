package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.diagnostics.InferenceTiming
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext

/**
 * Per-invocation measurements and publication authority. Queued callbacks check the memory
 * ticket at delivery time, after any intervening mutation, rather than only at generation time.
 * A caller-supplied benchmark belongs to the voice owner and must not be finalized here.
 */
internal class ConversationReply(
    val id: String,
    val benchmark: PipelineBenchmarkCapture,
    val ownsBenchmark: Boolean,
    private val fence: MemoryDeliveryFence,
    private val post: (() -> Unit) -> Unit,
    private val onToken: (String) -> Unit,
    private val onComplete: (String) -> Unit,
    private val onLatency: (TurnLatency) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val finishOwnedBenchmark: (PipelineBenchmarkCapture, PipelineBenchmarkOutcome, String?) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    startedNanos: Long = nowNanos()
) {
    private val started = startedNanos
    private var firstVisibleMs: Long? = null
    private var memoryTicket: MemoryDeliveryFence.Ticket? = null
    var memoryContext: MemoryTurnContext? = null
        private set
    var loadMs = 0L
    var lookupMs = 0L
    val inferencePasses = mutableListOf<InferenceTiming>()
    var outcome = PipelineBenchmarkOutcome.COMPLETE
    var failure: String? = null

    fun elapsed(): Long = (nowNanos() - started) / 1_000_000

    fun bindMemory(context: MemoryTurnContext, ticket: MemoryDeliveryFence.Ticket) {
        memoryContext = context
        memoryTicket = ticket
    }

    fun recordOutcome(outcome: PipelineBenchmarkOutcome, failure: String?) {
        this.outcome = outcome
        this.failure = failure
    }

    fun finishBenchmark() {
        benchmark.metric("reply_processing_ms", elapsed())
        benchmark.noteOutcome(outcome, failure)
        if (ownsBenchmark) finishOwnedBenchmark(benchmark, outcome, failure)
    }

    private fun markVisible(text: String) {
        if (firstVisibleMs == null && text.isNotBlank()) {
            firstVisibleMs = elapsed()
            benchmark.mark("first_reply_text")
        }
    }

    private fun publish(block: () -> Unit): Boolean {
        val ticket = memoryTicket
        if (ticket == null) { block(); return true }
        return fence.publish(ticket, { memoryContext?.isCurrent() != false }, block)
    }

    fun token(text: String) {
        publish { markVisible(text); onToken(text) }
    }

    fun finish(text: String) {
        if (!publish {
                markVisible(text)
                onLatency(TurnLatency(id, elapsed(), firstVisibleMs, loadMs, lookupMs,
                    inferencePasses.toList()))
                onAnswer(text)
                onComplete(text)
            }) {
            // Resolve the owning voice deferred/typed cleanup even when its stale answer is fenced.
            onComplete("Memory changed while I was responding. Please ask again.")
        }
    }

    fun postFinish(text: String) {
        if (text.isNotBlank()) benchmark.mark("first_reply_text_ready")
        post { finish(text) }
    }

    fun postToken(text: String) {
        if (text.isNotBlank()) benchmark.mark("first_reply_text_ready")
        post { token(text) }
    }
}
