package com.battlesbudz.jarvis.v2.voice

/** Pace optional hypotheses after costly decoding; feeding PCM and finalization never wait. */
internal class CaptureRecognitionBudget {
    private var nextPartialAtMs = Long.MIN_VALUE
    var largestWorkMs = 0L
        private set
    fun allows(nowMs: Long, backlogMs: Long): Boolean = backlogMs < 200 && nowMs >= nextPartialAtMs
    fun completed(startMs: Long, endMs: Long) {
        val work = (endMs - startMs).coerceAtLeast(0)
        largestWorkMs = maxOf(largestWorkMs, work)
        // Also recover after an expensive segment finalization in feed-only mode.
        if (work >= 100) {
            nextPartialAtMs = maxOf(nextPartialAtMs, endMs + work.coerceIn(250, 3000))
        }
    }
}
