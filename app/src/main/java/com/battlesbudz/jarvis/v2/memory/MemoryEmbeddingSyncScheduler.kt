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
    private val mutex = Mutex()

    /** Queue a background sync; a no-op while one is already queued or running. */
    fun request() {
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            try {
                var polls = 0
                while (!idleProbe() && polls < maxPolls) {
                    delay(pollIntervalMs)
                    polls++
                }
                if (idleProbe()) runSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Surfaced through diagnostics, never thrown into the host process.
            } finally {
                queued.set(false)
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
