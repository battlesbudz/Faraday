package com.battlesbudz.jarvis.v2.work

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit turn sequence guard (reliability epic item 5 / M7).
 *
 * Every admitted conversation turn claims a monotonically increasing
 * sequence number. Shared turn state (the native-mutation fence slot, the
 * resident engine's callbacks, the session's context accounting, the
 * visible failure surface) may only be touched by the turn that still owns
 * the current sequence. Once a newer turn claims the sequence, the older
 * turn is superseded: its teardown becomes a no-op for shared state and
 * its errors are dropped silently instead of surfacing over the
 * replacement turn.
 *
 * This is the turn-layer guard. It complements, and does not replace, the
 * two stronger mechanisms below it, which the M7 audit kept per path:
 * admission itself stays exclusive (a compare-and-set claim in
 * [ProcessConversationAdmission] plus the model-operation gate in
 * `admitConversationTurn`), so at most one turn executes at a time; and
 * the native layer keeps object-identity fencing
 * (`CheckedConversationLifecycle` accepts only the exact active turn /
 * child lease instance). The sequence guard covers what neither of those
 * can: late teardown, posted callbacks and recovery continuations of a
 * turn that finish after its replacement has been admitted.
 */
internal class TurnGuard {
    private val nextSequence = AtomicLong(0)
    private val currentSequence = AtomicLong(NO_TURN)

    /** Claim the next sequence for a newly admitted turn, superseding any prior owner. */
    fun claim(): Long {
        val sequence = nextSequence.incrementAndGet()
        currentSequence.set(sequence)
        return sequence
    }

    /** True only while [sequence] is the latest claimed turn. */
    fun isCurrent(sequence: Long): Boolean =
        sequence != NO_TURN && currentSequence.get() == sequence

    /** The currently owned sequence, or [NO_TURN] when no turn owns it. */
    fun current(): Long = currentSequence.get()

    /** Release ownership after the owning turn's teardown; a superseded turn cannot release its replacement. */
    fun release(sequence: Long) {
        currentSequence.compareAndSet(sequence, NO_TURN)
    }

    companion object {
        const val NO_TURN = 0L
    }
}

/** One turn's ownership claim against a [TurnGuard]. */
internal class TurnOwnership(val guard: TurnGuard, val sequence: Long) {
    fun isCurrent(): Boolean = guard.isCurrent(sequence)

    /** Abort the calling turn silently when a newer turn has superseded it. */
    fun checkCurrent() {
        if (!isCurrent()) throw TurnSupersededException(sequence)
    }
}

/**
 * Signals that the throwing turn was superseded by a newer turn. It is a
 * [CancellationException] so the coordinator's existing cancellation path
 * handles it: nothing is published, no failure surfaces, and the discarded
 * turn signal fires exactly once for the turn.
 */
internal class TurnSupersededException(sequence: Long) :
    CancellationException("Turn $sequence was superseded by a newer turn")

/**
 * The discarded-turn signal (M7, epic item 5 part 2): emitted when a turn
 * ends with nothing persisted (cancelled, failed before publication, or
 * superseded), so UI/state layers already listening on the turn's
 * callback channel can clear in-flight presentation state. Exactly-once
 * per turn regardless of how many teardown paths observe the ending.
 */
internal class DiscardedTurnSignal(private val onDiscarded: () -> Unit) {
    private val emitted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun emit() {
        if (emitted.compareAndSet(false, true)) runCatching { onDiscarded() }
    }
}
