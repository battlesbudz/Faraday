package com.battlesbudz.jarvis.v2.voice.speaker

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun interface SpeakerEmbedder : AutoCloseable {
    fun embed(samples: FloatArray, sampleRate: Int): FloatArray?
    override fun close() {}
}

/** Decoder for the canonical PCM16 mono WAV produced by WavEncoder. */
object WavPcm {
    fun decode(wav: ByteArray): Pair<FloatArray, Int>? = decodeCanonicalWav(wav) ?: decodeRawPcm16(wav)

    private fun decodeCanonicalWav(wav: ByteArray): Pair<FloatArray, Int>? = runCatching {
        require(wav.size >= 46)
        fun label(o: Int) = String(wav, o, 4, Charsets.US_ASCII)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(label(0) == "RIFF" && label(8) == "WAVE" && label(12) == "fmt " && label(36) == "data")
        val rate = b.getInt(24)
        require(b.getInt(4) == wav.size - 8 && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 &&
            b.getShort(22).toInt() == 1 && rate == 16_000 && b.getInt(28) == rate * 2 &&
            b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == 16 &&
            b.getInt(40) == wav.size - 44 && (wav.size - 44) % 2 == 0)
        FloatArray((wav.size - 44) / 2) { i -> b.getShort(44 + 2 * i) / 32768f } to rate
    }.getOrNull()

    private fun decodeRawPcm16(pcm: ByteArray): Pair<FloatArray, Int>? {
        if (pcm.isEmpty() || pcm.size % 2 != 0) return null
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pcm.size / 2) { b.getShort(it * 2) / 32768f } to 16_000
    }
}
