package com.battlesbudz.jarvis.v2.voice.whisper

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** OpenAI multilingual.tiktoken ranks encode bytes, not Unicode characters. */
internal class WhisperTokenizer(file: File) {
    private val vocabulary = arrayOfNulls<ByteArray>(50257)
    init {
        file.forEachLine { line ->
            val fields = line.trim().split(' ')
            require(fields.size == 2) { "Invalid Whisper vocabulary" }
            val rank = fields[1].toInt()
            require(rank in vocabulary.indices && vocabulary[rank] == null)
            vocabulary[rank] = Base64.getDecoder().decode(fields[0])
        }
        require(vocabulary.all { it != null }) { "Incomplete Whisper vocabulary" }
    }
    fun decode(tokens: IntArray): String {
        require(tokens.size <= 199)
        val bytes = ByteArrayOutputStream()
        tokens.forEach { token ->
            require(token in 0 until 51865) { "Invalid Whisper token" }
            if (token < vocabulary.size) bytes.write(requireNotNull(vocabulary[token]))
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).trim()
    }
}
