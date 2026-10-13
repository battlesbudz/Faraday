package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PacedStreamingTranscriberTest {
    @Test fun expensiveRecognitionLeavesRecoveryTimeAndPreservesEverySample() {
        var now = 0L
        val accepted = mutableListOf<Byte>()
        var calls = 0
        var closed = 0
        val probe = PacedStreamingTranscriber(object : StreamingTranscriber {
            override fun accept(pcm: ByteArray): String {
                calls++; accepted.addAll(pcm.toList()); now += 1000; return "open settings"
            }
            override fun finish() = "open settings"
            override fun close() { closed++ }
        }, nowMs = { now })
        val chunk = ByteArray(16000) { (it % 127).toByte() }
        probe.accept(chunk)
        assertEquals(1, calls)
        probe.accept(chunk)
        assertEquals(1, calls) // Buffered microphone backlog cannot immediately start another decode.
        now = 2000
        probe.accept(chunk)
        assertEquals(2, calls)
        probe.accept(byteArrayOf(3, 4))
        probe.finish()
        assertArrayEquals(chunk + chunk + chunk + byteArrayOf(3, 4), accepted.toByteArray())
        probe.close()
        assertEquals(1, closed)
    }
}
