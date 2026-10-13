package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Lock-free ingress authority. No callback, persistence or model work runs inside state mutation. */
internal class CaptionPublicationFence {
    private data class State(
        val revision: Long = 0,
        val transferred: Boolean = false,
        val replyPending: Boolean = false,
        val retainedPending: Boolean = false,
        val offered: Long = 0,
        val classified: Long = 0,
        val risk: Long = 0,
        val resolved: Long = 0,
        val processed: Long = 0,
        val revoked: Boolean = false,
        val committed: Boolean = false,
        val farewellCommitted: Boolean = false,
    ) {
        val pending get() = replyPending || retainedPending || offered > classified ||
            (risk > 0 && (resolved <= risk || processed < risk))
    }
    internal class Claim internal constructor(internal val revision: Long)
    private val state = AtomicReference(State())
    private val changes = MutableStateFlow(State())
    private fun mutate(change: (State) -> State) {
        while (true) {
            val before = state.get()
            val changed = change(before)
            if (changed == before) return
            val after = changed.copy(revision = before.revision + 1)
            if (state.compareAndSet(before, after)) { changes.value = after; return }
        }
    }

    fun replyCandidate(retained: Boolean) = mutate {
        if (!it.transferred && !it.revoked && !it.committed) it.copy(replyPending = retained) else it
    }
    /** Exact raw prefix becomes unclassified ownership before the old listener is retired. */
    fun transferToCapture(firstSequence: Long? = null, throughSequence: Long? = null) = mutate {
        it.copy(transferred = true, offered = throughSequence ?: 0,
            classified = firstSequence?.minus(1) ?: (throughSequence ?: 0))
    }
    fun rawOffered(sequence: Long) = mutate {
        if (it.transferred && !it.revoked && !it.committed && sequence > it.offered) it.copy(offered = sequence) else it
    }
    /** Source sequence is read in the synchronous VAD producer, before the next input emission. */
    fun rawClassified(sequence: Long?, raw: SpeechDecision, admitted: SpeechDecision) = mutate {
        if (sequence == null || !it.transferred || it.revoked || it.committed) it else {
            val coverage = raw.rawCoverage
            val complete = coverage != null && coverage.classifiedThroughSample * 2 == coverage.receivedPcmBytes
            val risky = raw.isSpeech || admitted.isSpeech || raw.probability >= .15f
            it.copy(classified = if (complete) maxOf(it.classified, sequence) else it.classified,
                risk = if (risky) maxOf(it.risk, sequence) else it.risk,
                resolved = if (complete && !risky) maxOf(it.resolved, sequence) else it.resolved)
        }
    }
    /** Acknowledging the next frame proves the prior ordered ASR/retention callback finished. */
    fun rawConsumed(sequence: Long) = mutate { it.copy(processed = maxOf(it.processed, sequence - 1)) }
    fun candidatePending() = mutate { if (!it.revoked && !it.committed) it.copy(retainedPending = true) else it }
    // A candidate verdict has no authority over later raw ingress or classification risk.
    fun candidateRejected() = mutate { it.copy(replyPending = false, retainedPending = false) }

    /** Returns false once a farewell's bounded call-state commit won; no new input is admitted then. */
    fun revoke(accept: () -> Unit): Boolean {
        while (true) {
            val before = state.get()
            if (before.farewellCommitted) return false
            if (before.revoked) return true
            val after = before.copy(revision = before.revision + 1, revoked = true)
            if (state.compareAndSet(before, after)) { changes.value = after; accept(); return true }
        }
    }

    /** Called at the final bounded call-state mutation, after queue/call/revision checks. */
    fun commit(claim: Claim, farewell: Boolean = false): Boolean {
        val before = state.get()
        if (before.revision != claim.revision || before.pending || before.revoked || before.committed) return false
        val after = before.copy(revision = before.revision + 1, committed = true, farewellCommitted = farewell)
        if (!state.compareAndSet(before, after)) return false
        changes.value = after
        return true
    }

    /** Claim is revocable until commit. Slow publication effects never hold an ingress lock. */
    suspend fun publishWhenResolved(isCurrent: () -> Boolean, publish: (Claim) -> Boolean): Boolean {
        while (true) {
            changes.first { val current = state.get(); current.revoked || current.committed || !current.pending }
            val observed = state.get()
            if (observed.revoked || observed.committed || !isCurrent()) return false
            if (observed.pending) continue
            val result = publish(Claim(observed.revision))
            val current = state.get()
            if (result || current.revoked || current.committed || current.revision == observed.revision || !isCurrent()) return result
            // A newly arrived raw frame invalidated the claim; wait for its existing verdict.
        }
    }
}
