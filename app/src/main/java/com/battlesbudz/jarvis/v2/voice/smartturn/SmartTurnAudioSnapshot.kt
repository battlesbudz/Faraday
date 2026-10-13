package com.battlesbudz.jarvis.v2.voice.smartturn

/** A capture attempt plus observation revision, invalidated on every speech resumption/reset. */
internal data class SmartTurnGeneration(val turnId: String, val captureGeneration: Long, val revision: Long) {
    init { require(turnId.isNotBlank() && turnId.length <= 128); require(captureGeneration >= 0 && revision >= 0) }
}

/** Immutable bounded reader-local audio evidence. Never a session queue/replay cursor. */
internal class SmartTurnAudioSnapshot private constructor(
    val generation: SmartTurnGeneration,
    val captureSampleBoundary: Long,
    val capturedAtNanos: Long,
    private val samples: FloatArray,
) {
    val sampleCount: Int get() = samples.size
    // A private worker copy avoids mutation through both producer and native adapters.
    internal fun copySamples(): FloatArray = samples.copyOf()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLES = 128_000

        /** Producer must call while its own PCM buffer is stable; only the last 8 s is copied. */
        fun fromPcm16(
            generation: SmartTurnGeneration,
            captureSampleBoundary: Long,
            capturedAtNanos: Long,
            pcm: ShortArray,
            sampleRate: Int = SAMPLE_RATE,
        ): SmartTurnAudioSnapshot {
            require(sampleRate == SAMPLE_RATE) { "Smart Turn accepts 16 kHz mono PCM only" }
            require(pcm.isNotEmpty())
            val count = minOf(pcm.size, MAX_SAMPLES)
            require(captureSampleBoundary >= count) { "Audio boundary precedes snapshot" }
            val start = pcm.size - count
            val copy = FloatArray(count) { pcm[start + it] / 32768f }
            return SmartTurnAudioSnapshot(generation, captureSampleBoundary, capturedAtNanos, copy)
        }
    }
}
