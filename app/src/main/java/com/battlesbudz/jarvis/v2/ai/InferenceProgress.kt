package com.battlesbudz.jarvis.v2.ai

/** Timing events emitted at the native SDK boundary; no generated text is exposed here. */
data class InferenceProgress(val submittedAtMs: Long? = null, val firstRawTokenAtMs: Long? = null)

/** Estimates a live decode rate without treating callback chunks as tokenizer IDs. */
internal class LiveTokenRateEstimator(private val nowMs: () -> Long) {
    private var firstRawTokenAtMs = 0L
    private var chars = 0L
    private var lastPublishedAtMs = 0L
    @Synchronized fun rawToken(atMs: Long) { if (firstRawTokenAtMs == 0L) firstRawTokenAtMs = atMs }
    @Synchronized fun addRawChunk(chunk: String): Double? {
        if (chunk.isBlank()) return null
        chars += chunk.length
        val now = nowMs()
        val first = firstRawTokenAtMs
        if (first == 0L || now - first < 100L || now - lastPublishedAtMs < 250L) return null
        lastPublishedAtMs = now
        return (chars / 4.0 * 1000.0 / (now - first)).takeIf { it.isFinite() && it > 0.0 }
    }
}
