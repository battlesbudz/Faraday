package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Bounded FIFO of immutable final typed inputs for the active call owner. */
data class CallFinalInput(val id: String, val callId: String, val conversationId: String, val text: String, val capturedAtMs: Long)
sealed interface CallInputAdmission { data object Queued : CallInputAdmission; data object Full : CallInputAdmission; data object Ended : CallInputAdmission }
class CallInputQueue(private val capacity: Int = 4) {
    private val pending = ArrayDeque<CallFinalInput>()
    private val owned = linkedMapOf<String, CallFinalInput>()
    /** End wins an in-flight UI admission even when it captured the former call ID first. */
    private val endedCallIds = linkedSetOf<String>()
    private val arrivals = Channel<String>(Channel.CONFLATED)
    // Optional observers must never consume a dispatcher's Channel wakeup. This revision is
    // broadcast, and retains arrival authority after claim/promotion removes an input.
    private val inputChanges = MutableStateFlow(0L)
    private val acceptedVersions = mutableMapOf<String, Long>()
    internal class CaptionBoundary internal constructor(val callId: String, internal val version: Long)
    @Synchronized internal fun captionBoundary(callId: String) = CaptionBoundary(callId, acceptedVersions[callId] ?: 0L)
    @Synchronized internal fun hasInputAfter(boundary: CaptionBoundary): Boolean =
        boundary.callId in endedCallIds || (acceptedVersions[boundary.callId] ?: 0L) != boundary.version ||
            pending.any { it.callId == boundary.callId } || owned.values.any { it.callId == boundary.callId }
    internal suspend fun awaitInputAfter(boundary: CaptionBoundary) {
        inputChanges.first { hasInputAfter(boundary) }
    }

    @Synchronized fun offer(input: CallFinalInput, activeCallId: String?, priorityControl: Boolean = false): CallInputAdmission {
        if (activeCallId == null || input.callId != activeCallId || input.callId in endedCallIds) return CallInputAdmission.Ended
        if (pending.any { it.id == input.id }) return CallInputAdmission.Queued
        // Reserve one bounded admission beyond ordinary capacity for a scoped control so a
        // full ordinary FIFO cannot make cancellation impossible.
        if (pending.size >= capacity + if (priorityControl) 1 else 0) return CallInputAdmission.Full
        pending += input
        acceptedVersions[input.callId] = inputChanges.value + 1L
        inputChanges.value += 1L
        arrivals.trySend(input.callId)
        return CallInputAdmission.Queued
    }
    /** Atomically moves an input into explicit runtime ownership; End drains both sets. */
    @Synchronized fun claim(activeCallId: String, accepts: (CallFinalInput) -> Boolean = { true }): CallFinalInput? =
        pending.firstOrNull { it.callId == activeCallId && accepts(it) }?.also { input ->
            pending.remove(input); owned[input.id] = input
        }

    /** Restored handoffs re-enter the same owned state without a pending-window race. */
    @Synchronized fun claimExternal(input: CallFinalInput): Boolean {
        if (input.callId in endedCallIds) return false
        if (owned[input.id] == input) return true
        if (owned.containsKey(input.id)) return false
        owned[input.id] = input
        return true
    }

    /**
     * Transfers a deferred typed final into the runtime handoff slot under the same End gate.
     * End either observes the published slot afterwards or rejects this transfer before it exists.
     */
    @Synchronized fun publishHandoff(input: CallFinalInput, publish: () -> Unit): Boolean {
        if (input.callId in endedCallIds) return false
        publish()
        return true
    }

    /** Promotion is the only point where an owned input may become visible/admitted. */
    @Synchronized fun promote(input: CallFinalInput, callback: () -> Unit): Boolean {
        if (input.callId in endedCallIds || owned[input.id] != input) return false
        callback()
        owned.remove(input.id)
        return true
    }

    @Synchronized fun terminalize(input: CallFinalInput): Boolean = owned.remove(input.id) != null

    @Synchronized fun poll(activeCallId: String): CallFinalInput? =
        pending.firstOrNull { it.callId == activeCallId }?.also { pending.remove(it) }

    @Synchronized fun pollDispatchable(activeCallId: String, accepts: (CallFinalInput) -> Boolean): CallFinalInput? =
        pending.firstOrNull { it.callId == activeCallId && accepts(it) }?.also { pending.remove(it) }

    /** Wait only for an input that the current owner can actually dispatch; retained ordinary
     * inputs cannot spin the pump or block a later scoped control. */
    suspend fun awaitDispatchable(activeCallId: String, accepts: (CallFinalInput) -> Boolean) {
        while (true) {
            val available = synchronized(this) { pending.any { it.callId == activeCallId && accepts(it) } }
            if (available) return
            arrivals.receive()
        }
    }

    /** Returns a rejected-at-ownership input to its original FIFO position. */
    @Synchronized fun restore(input: CallFinalInput, activeCallId: String?): Boolean {
        if (input.callId != activeCallId || input.callId in endedCallIds || owned[input.id] != input ||
            pending.any { it.id == input.id } || pending.size >= capacity) return false
        owned.remove(input.id)
        pending.addFirst(input)
        arrivals.trySend(input.callId)
        return true
    }

    /** Wakes an idle ASR owner without consuming or reordering the retained input. */
    suspend fun awaitAvailable(activeCallId: String) {
        while (true) {
            if (hasPending(activeCallId)) return
            arrivals.receive()
        }
    }

    /** Optional caption publication must not overtake an admitted final typed input. */
    @Synchronized internal fun publishWithoutNextInput(boundary: CaptionBoundary, publish: () -> Boolean): Boolean {
        if (hasInputAfter(boundary)) return false
        return publish()
    }

    @Synchronized fun hasPending(activeCallId: String): Boolean = pending.any { it.callId == activeCallId }
    @Synchronized fun end(callId: String): List<CallFinalInput> {
        endedCallIds += callId
        acceptedVersions.remove(callId)
        inputChanges.value += 1L
        val drained = (pending.filter { it.callId == callId } + owned.values.filter { it.callId == callId })
            .distinctBy { it.id }
        pending.removeAll { it.callId == callId }
        owned.entries.removeIf { it.value.callId == callId }
        return drained
    }
}
