package com.battlesbudz.jarvis.v2.voice

/** Producer-thread-only observer: an empty phrase is not PCM and cannot claim first audio. */
internal class PiperFirstPcmTiming(
    private val onFirstPcm: (Long) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var observed = false
    fun observe(sampleCount: Int) {
        if (sampleCount <= 0 || observed) return
        observed = true
        val at = nowMs()
        // Telemetry failure must not change synthesis, PCM ownership or playback.
        runCatching { onFirstPcm(at) }
    }
}
