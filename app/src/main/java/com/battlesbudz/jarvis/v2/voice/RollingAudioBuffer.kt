package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream

/**
 * Bounded PCM buffer used for wake-word context and turn pre-roll.
 * It never grows with the length of a Voice Call.
 */
class RollingAudioBuffer(
    private val format: AudioFormat = AudioFormat(),
    maxDurationMs: Long = 15 * 60 * 1_000L
) {
    private val maxBytes = (format.sampleRateHz.toLong() * format.channelCount *
        (format.bitsPerSample / 8) * maxDurationMs) / 1_000L
    private val chunks = ArrayDeque<ByteArray>()
    private var byteCount = 0L

    @Synchronized
    fun append(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val copy = pcm.copyOf()
        chunks.addLast(copy)
        byteCount += copy.size.toLong()
        while (byteCount > maxBytes && chunks.isNotEmpty()) {
            val oldest = chunks.removeFirst()
            val bytesToDrop = (byteCount - maxBytes).coerceAtMost(oldest.size.toLong()).toInt()
            if (bytesToDrop < oldest.size) {
                chunks.addFirst(oldest.copyOfRange(bytesToDrop, oldest.size))
            }
            byteCount -= bytesToDrop.toLong()
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val output = ByteArrayOutputStream(byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        chunks.forEach { output.write(it) }
        return output.toByteArray()
    }

    @Synchronized
    fun clear() {
        chunks.clear()
        byteCount = 0
    }

    @Synchronized
    fun sizeBytes(): Long = byteCount
}
