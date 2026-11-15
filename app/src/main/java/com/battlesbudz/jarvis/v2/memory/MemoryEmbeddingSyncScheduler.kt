package com.battlesbudz.jarvis.v2.memory

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns when projection sync runs so it never lands on a conversation turn:
 * triggers coalesce into one queued run, the run waits for the host's idle probe
 * (the same idleness rule the automatic-memory evaluator uses) within a bounded
 * window, and the sync body itself is serialized. A trigger that never sees idle
 * inside its window is dropped, not forced — sync is idempotent, so the next
 * trigger (a later approval change, the next app start) catches up, and the
 * diagnostics line keeps reporting the honest pending count in the meantime.
 * A request that arrives while a pass is already queued or running is not
 * discarded either: it raises a rerun flag, and the current pass is followed by
 * one more pass, so a change that lands after the sync body has read the ledger
 * is still picked up without waiting for an unrelated trigger.
 * Sync failures are swallowed here on purpose: they surface as the diagnostics
 * state (pending counts, engine state), never as fabricated vectors and never
 * as a crash on a background path.
 */
class MemoryEmbeddingSyncScheduler(
    private val scope: CoroutineScope,
    private val idleProbe: () -> Boolean = { true },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val maxPolls: Int = DEFAULT_MAX_POLLS,
    private val sync: suspend () -> Unit,
) {
    private val queued = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val mutex = Mutex()

    /**
     * Queue a background sync. While a pass is queued or running, further
     * requests coalesce into the rerun flag instead of launching again: the
     * active coroutine drains the flag, running one follow-up pass per
     * mid-pass request burst, so a request is never silently lost.
     */
    fun request() {
        rerunRequested.set(true)
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (rerunRequested.getAndSet(false)) {
                    var polls = 0
                    while (!idleProbe() && polls < maxPolls) {
                        delay(pollIntervalMs)
                        polls++
                    }
                    if (idleProbe()) runSync()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Surfaced through diagnostics, never thrown into the host process.
            } finally {
                queued.set(false)
                // A request that arrived while this coroutine was finishing
                // saw queued still set and could only raise the rerun flag;
                // re-dispatch so that request gets its pass after all.
                if (rerunRequested.get()) request()
            }
        }
    }

    /**
     * Run a sync on the caller's context right away (the user just installed the
     * model in Settings and is waiting for exactly this). Still serialized with
     * any queued background sync.
     */
    suspend fun syncNow() {
        try {
            runSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // Same rule: the diagnostics line carries the failure state.
        }
    }

    private suspend fun runSync() = mutex.withLock { sync() }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        const val DEFAULT_MAX_POLLS = 60
    }
}
