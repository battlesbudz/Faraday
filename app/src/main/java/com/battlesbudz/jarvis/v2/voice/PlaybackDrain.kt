package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.delay

/** Wait for consumed PCM, not merely a successful write or an expired timer. */
internal object PlaybackDrain {
    fun startThreshold(sampleRate: Int, firstPhraseFrames: Int): Int =
        minOf((sampleRate / 10).coerceAtLeast(1), firstPhraseFrames.coerceAtLeast(1))

    suspend fun await(
        frames: Long, sampleRate: Int, speed: Float,
        head: () -> Long, stopped: () -> Boolean,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) }
    ): Boolean {
        val remaining = (frames - head()).coerceAtLeast(0)
        val deadline = nowMs() + (remaining * 1000.0 / sampleRate / speed).toLong().coerceAtLeast(1000) + 2000
        while (!stopped()) {
            if (head() >= frames) return true
            if (nowMs() >= deadline) return false
            pause(20)
        }
        return false
    }
}
