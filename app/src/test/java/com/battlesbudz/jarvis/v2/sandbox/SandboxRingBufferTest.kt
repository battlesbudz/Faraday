package com.battlesbudz.jarvis.v2.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxRingBufferTest {

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test fun keepsSmallInputVerbatim() {
        val ring = SandboxRingBuffer()
        ring.append(bytes("hello"), 0, 5)
        assertEquals(5L, ring.totalBytes)
        assertFalse(ring.truncated)
        assertEquals("hello", ring.text())
        assertEquals("hello", ring.surfacedText())
    }

    @Test fun appendRespectsOffsetAndLength() {
        val ring = SandboxRingBuffer()
        // The session tap hands over a reused buffer; only [offset, offset+length) is new data.
        ring.append(bytes("xxhelloyy"), 2, 5)
        assertEquals("hello", ring.text())
    }

    @Test fun wrapsAndKeepsTheNewestBytes() {
        val ring = SandboxRingBuffer()
        val capacity = SandboxRingBuffer.CAPACITY_BYTES
        // Fill exactly, then push 10 more bytes: the first 10 must be gone.
        val first = ByteArray(capacity) { 'a'.code.toByte() }
        ring.append(first, 0, first.size)
        ring.append(bytes("0123456789"), 0, 10)
        assertEquals((capacity + 10).toLong(), ring.totalBytes)
        assertTrue(ring.truncated)
        val text = ring.text()
        assertEquals(capacity, text.length)
        assertEquals("a".repeat(capacity - 10) + "0123456789", text)
    }

    @Test fun singleOversizeAppendKeepsOnlyItsTail() {
        val ring = SandboxRingBuffer()
        val capacity = SandboxRingBuffer.CAPACITY_BYTES
        val big = ByteArray(capacity * 2) { i -> ('A'.code + (i % 26)).toByte() }
        ring.append(big, 0, big.size)
        assertTrue(ring.truncated)
        val expected = String(big.copyOfRange(big.size - capacity, big.size), Charsets.UTF_8)
        assertEquals(expected, ring.text())
    }

    @Test fun crlfIsNormalizedToLf() {
        val ring = SandboxRingBuffer()
        ring.append(bytes("one\r\ntwo\r\n"), 0, 10)
        assertEquals("one\ntwo\n", ring.text())
    }

    @Test fun surfacedTextKeepsTheLast8000Chars() {
        val ring = SandboxRingBuffer()
        val text = "x".repeat(9_000) + "END"
        ring.append(bytes(text), 0, text.length)
        val surfaced = ring.surfacedText()
        assertEquals(SandboxRingBuffer.SURFACED_CHARS, surfaced.length)
        assertTrue(surfaced.endsWith("END"))
        assertEquals(text.substring(text.length - SandboxRingBuffer.SURFACED_CHARS), surfaced)
    }
}
