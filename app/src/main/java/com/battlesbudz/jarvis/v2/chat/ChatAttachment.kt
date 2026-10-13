package com.battlesbudz.jarvis.v2.chat

import androidx.annotation.Keep
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Keep enum class AttachmentKind { IMAGE, AUDIO }
@Keep data class ChatAttachment(val uri: String, val kind: AttachmentKind)

object AttachmentPolicy {
    const val MAX_BYTES = 12 * 1024 * 1024
    fun accepts(model: LocalModelSpec, kind: AttachmentKind): Boolean = when (kind) {
        AttachmentKind.IMAGE -> model.supportsVision
        AttachmentKind.AUDIO -> model.supportsAudio
    }
    fun readBounded(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= MAX_BYTES) { "Choose a file smaller than 12 MB." }
            result.write(buffer, 0, count)
        }
        require(result.size() > 0) { "The selected file is empty." }
        return result.toByteArray()
    }
    /** Native audio input contract: short PCM WAV, not an arbitrary renamed audio file. */
    fun validateAudio(bytes: ByteArray) {
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Choose a WAV audio clip." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var formatOk = false
        var audioBytes = 0L
        while (at + 8 <= bytes.size) {
            val size = b.getInt(at + 4).toLong() and 0xffffffffL
            require(size <= bytes.size - at - 8) { "This WAV file is incomplete." }
            val tag = String(bytes, at, 4, Charsets.US_ASCII)
            if (tag == "fmt ") {
                require(size >= 16) { "Invalid WAV format." }
                formatOk = b.getShort(at + 8).toInt() == 1 && b.getShort(at + 10).toInt() == 1 &&
                    b.getInt(at + 12) == 16000 && b.getShort(at + 22).toInt() == 16
            }
            if (tag == "data") audioBytes += size
            at += 8 + size.toInt() + (size.toInt() and 1)
        }
        require(formatOk) { "Use a 16 kHz, mono, 16-bit PCM WAV clip." }
        require(audioBytes in 1..(30 * 16000 * 2).toLong()) { "Choose an audio clip up to 30 seconds long." }
    }
}
