package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.Flow

/** Audio is deliberately separated from recognition and Jarvis reasoning. */
interface AudioInput {
    val sampleRateHz: Int
    val channelCount: Int
    /** Monotonic microphone-read time of the currently delivered chunk, when available. */
    val lastChunkCaptureTimeMs: Long? get() = null
    val bufferedAudioMs: Long get() = 0
    /** Atomic reader-stop receipt; null means this input cannot certify its cutoff. */
    val stoppedUnconsumedPcmBytes: Long? get() = null
    /** Ordinary listening only; never learn a persistent room floor from assistant playback. */
    val captureNoiseProfile: CaptureNoiseProfile? get() = null
    /** Optional session cursor: prefetch must not consume audio on behalf of the next reader. */
    val lastChunkSequence: Long? get() = null
    fun deferConsumptionAcknowledgement() {}
    fun acknowledgeConsumed(sequence: Long) {}
    /** Already consumed microphone history; keyword warmup only, never a new command. */
    val priorAudioForKeywords: ByteArray get() = byteArrayOf()

    /** Emits short PCM16 mono chunks while the input is active. */
    fun chunks(): Flow<ByteArray>

    suspend fun start()
    suspend fun stop()
}

data class AudioFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16
)
