package com.battlesbudz.jarvis.v2.sandbox

/**
 * Bounded byte capture for one sandbox run (scope §11, decision D4): keeps
 * the last [CAPACITY_BYTES] of raw output — the tail is what a failing
 * command's diagnostics need — while counting every byte seen so the
 * caller can report truncation honestly. Pure JVM: unit-tested directly.
 *
 * Terminal output arrives as raw PTY bytes from the session's output tap;
 * [text] decodes the retained tail as UTF-8 and normalizes CRLF to LF (a
 * PTY in canonical output mode emits CRLF for every newline).
 */
internal class SandboxRingBuffer {
    private val ring = ByteArray(CAPACITY_BYTES)
    private var head = 0 // next write position
    private var filled = 0

    var totalBytes: Long = 0
        private set

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        totalBytes += length
        var src = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, CAPACITY_BYTES - head)
            System.arraycopy(bytes, src, ring, head, chunk)
            head = (head + chunk) % CAPACITY_BYTES
            filled = minOf(CAPACITY_BYTES, filled + chunk)
            src += chunk
            remaining -= chunk
        }
    }

    /** True when bytes were dropped from the front of the stream. */
    val truncated: Boolean
        get() = totalBytes > filled

    /** The retained tail in stream order. */
    @Synchronized
    fun snapshot(): ByteArray {
        val out = ByteArray(filled)
        val start = (head - filled + CAPACITY_BYTES) % CAPACITY_BYTES
        val first = minOf(filled, CAPACITY_BYTES - start)
        System.arraycopy(ring, start, out, 0, first)
        if (first < filled) {
            System.arraycopy(ring, 0, out, first, filled - first)
        }
        return out
    }

    /** Decoded, line-ending-normalized tail (see class doc). */
    fun text(): String = String(snapshot(), Charsets.UTF_8).replace("\r\n", "\n")

    /** The last [SURFACED_CHARS] characters of [text] (decision D4). */
    fun surfacedText(): String {
        val decoded = text()
        return if (decoded.length <= SURFACED_CHARS) {
            decoded
        } else {
            decoded.substring(decoded.length - SURFACED_CHARS)
        }
    }

    companion object {
        const val CAPACITY_BYTES = 64 * 1024
        const val SURFACED_CHARS = 8_000
    }
}
