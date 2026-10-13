package com.battlesbudz.jarvis.v2.voice

/** Hardware read sizes vary. Queue capacity must describe audio duration, not read count. */
class PcmChunkAssembler(private val chunkBytes: Int) {
    init { require(chunkBytes > 0 && chunkBytes % 2 == 0) }
    private val buffer = ByteArray(chunkBytes)
    private var filled = 0
    fun accept(bytes: ByteArray, count: Int, emit: (ByteArray) -> Unit) {
        require(count in 0..bytes.size)
        var offset = 0
        while (offset < count) {
            val length = minOf(count - offset, chunkBytes - filled)
            bytes.copyInto(buffer, filled, offset, offset + length)
            offset += length
            filled += length
            if (filled == chunkBytes) {
                emit(buffer.copyOf())
                filled = 0
            }
        }
    }
}
class AudioBacklogException : IllegalStateException("Microphone processing exceeded the bounded audio buffer.")
