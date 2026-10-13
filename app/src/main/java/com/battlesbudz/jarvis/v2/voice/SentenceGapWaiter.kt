package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

/** Refill only after a completed sentence; never inject words into an unfinished sentence. */
internal class SentenceGapWaiter(
    private val lowWaterMs: Long = 250,
    private val headroomMs: Long = 1000,
    private val pollMs: Long = 20,
    private val prolongedStallMs: Long = 2500,
    private val maxRefillMs: Long = 4500,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(lowWaterMs >= 0 && headroomMs > lowWaterMs && pollMs > 0)
        require(prolongedStallMs > 0 && maxRefillMs >= prolongedStallMs) }
    private var spokenRecoveryUsed = false
    suspend fun <T> receive(channel: ReceiveChannel<T>, remainingMs: () -> Long,
                           bufferedMs: () -> Long, allowed: () -> Boolean = { true },
                           productionFinished: () -> Boolean = { false },
                           onRefill: (Boolean) -> Unit = {},
                           play: suspend () -> Unit): ChannelResult<T> {
        if (!allowed()) return channel.receiveCatching()
        var pending: ChannelResult<T>? = null
        var refillStarted: Long? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (pending == null) {
                    val next = channel.tryReceive()
                    if (next.isClosed) return next
                    if (next.isSuccess) pending = next
                }
                if (!allowed() || bufferedMs() >= headroomMs || productionFinished() ||
                    (refillStarted == null && remainingMs() > lowWaterMs)) {
                    if (pending != null) return pending
                    if (!allowed() || productionFinished()) return channel.receiveCatching()
                } else if (remainingMs() == 0L) {
                    if (refillStarted == null) { refillStarted = nowMs(); onRefill(true) }
                    val elapsed = nowMs() - refillStarted
                    if (!spokenRecoveryUsed && elapsed >= prolongedStallMs) {
                        spokenRecoveryUsed = true
                        withTimeoutOrNull(4000) { play() }
                        // Recheck actual supply, completion and cancellation after the complete clip.
                        continue
                    }
                    if (elapsed >= maxRefillMs && pending != null) return pending
                }
                delay(pollMs)
            }
        } finally { if (refillStarted != null) onRefill(false) }
    }
}
