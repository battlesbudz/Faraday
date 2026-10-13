package com.battlesbudz.jarvis.v2.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Encoder-only barrier; microphone collection continues under existing bounded PCM ownership. */
internal class EarlyGemmaPreparationGate(private val ownerIsCurrent: () -> Boolean) {
    private val released = CountDownLatch(1)
    private val completed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    val ready: Boolean get() = completed.get() && !revoked.get() && ownerIsCurrent()
    fun complete() { completed.set(true); released.countDown() }
    fun cancel() { revoked.set(true); released.countDown() }
    fun awaitReady() {
        while (true) {
            check(!revoked.get() && ownerIsCurrent()) { "early_gemma_preparation_stale" }
            if (released.await(25, TimeUnit.MILLISECONDS)) break
        }
        check(ready) { "early_gemma_preparation_incomplete" }
    }
}
