package com.battlesbudz.jarvis.v2.voice

/** Initial headroom from measured first-chunk speed, bounded for conversational latency. */
internal object PlaybackBufferPolicy {
    /** Keep pitch unchanged and choose one pace per answer, never varying it mid-sentence. */
    fun playbackSpeed(synthesisMs: Long, audioMs: Long): Float {
        if (synthesisMs <= 0 || audioMs <= 0) return 1f
        return (audioMs.toDouble() * 0.9 / synthesisMs).coerceIn(0.90, 1.0).toFloat()
    }

    fun startupWaitMs(synthesisMs: Long, audioMs: Long): Long {
        if (audioMs <= 0) return 0
        // The first completed sentence is the latency-critical opening. Start it now;
        // later normal passages provide the usual queue headroom.
        if (audioMs < 1200) return 0
        return (synthesisMs * 3 / 2 - audioMs * 4 / 5).coerceIn(0, 1200)
    }

    /** Budget the next native call against PCM already produced but not yet played. */
    fun nextChunkChars(queuedAudioMs: Long, previousChars: Int, previousSynthesisMs: Long): Int {
        if (previousChars <= 0 || previousSynthesisMs <= 0) return SpeechChunker.DEFAULT_OPENING_CHARS
        // Keep 25% of the available audio for scheduling/word-complexity variation.
        val affordable = queuedAudioMs.coerceAtLeast(0).toDouble() * 0.75 * previousChars / previousSynthesisMs
        return affordable.toInt().coerceIn(SpeechChunker.DEFAULT_OPENING_CHARS, 180)
    }
}
