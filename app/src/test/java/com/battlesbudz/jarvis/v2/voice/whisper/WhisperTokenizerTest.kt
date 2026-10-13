package com.battlesbudz.jarvis.v2.voice.whisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class WhisperTokenizerTest {
    @Test fun concatenatesTokenBytesBeforeUtf8AndOmitsSpecials() {
        val file = File.createTempFile("vocabulary", ".txt")
        try {
            file.bufferedWriter().use { out -> repeat(50257) { rank ->
                val bytes = when (rank) { 0 -> byteArrayOf(0xe2.toByte()); 1 -> byteArrayOf(0x82.toByte(), 0xac.toByte()); else -> byteArrayOf(65) }
                out.appendLine("${Base64.getEncoder().encodeToString(bytes)} $rank")
            } }
            val tokenizer = WhisperTokenizer(file)
            assertEquals("€", tokenizer.decode(intArrayOf(50258, 0, 1, 50257)))
            assertThrows(IllegalArgumentException::class.java) { tokenizer.decode(intArrayOf(-1)) }
            assertThrows(IllegalArgumentException::class.java) { tokenizer.decode(IntArray(200)) }
        } finally { file.delete() }
    }
    @Test fun missingOrDuplicateRanksFailClosed() {
        val file = File.createTempFile("vocabulary", ".txt")
        try {
            file.writeText("QQ== 0\n"); assertThrows(IllegalArgumentException::class.java) { WhisperTokenizer(file) }
            file.writeText("QQ== 0\nQQ== 0\n"); assertThrows(IllegalArgumentException::class.java) { WhisperTokenizer(file) }
        } finally { file.delete() }
    }
}
