package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun pcm16Mono(pcm: ByteArray, sampleRateHz: Int = 16_000): ByteArray {
        val output = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun int(value: Int) = output.write(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        )
        fun short(value: Int) = output.write(
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        )
        ascii("RIFF")
        int(36 + pcm.size)
        ascii("WAVEfmt ")
        int(16)
        short(1)
        short(1)
        int(sampleRateHz)
        int(sampleRateHz * 2)
        short(2)
        short(16)
        ascii("data")
        int(pcm.size)
        output.write(pcm)
        return output.toByteArray()
    }
}
