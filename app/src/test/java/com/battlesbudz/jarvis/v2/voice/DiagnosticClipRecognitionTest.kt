package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiagnosticClipRecognitionTest {
    private class Fake(private val fail: Boolean = false) : StreamingTranscriber {
        val audio = ByteArrayOutputStream()
        var closed = false
        var finished = false
        override fun prepareForBoundedProbe(maxAudioMs: Long): String = error("Not a four-second interruption probe")
        override fun accept(pcm: ByteArray): String = error("Partial decode must be disabled")
        override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
            check(!allowPartial)
            if (fail) error("Decode failed")
            audio.write(pcm); return ""
        }
        override fun finish(): String { finished = true; return "final words" }
        override fun close() { closed = true }
    }
    @Test fun fullTwelveSecondClipReachesFinalizerWithoutProbeLimit() = runBlocking {
        val fake = Fake()
        val pcm = ByteArray(12 * 32000) { it.toByte() }
        assertEquals("final words", recognizeDiagnosticClip(fake, pcm))
        assertArrayEquals(pcm, fake.audio.toByteArray())
        assertTrue(fake.finished)
        assertTrue(fake.closed)
    }
    @Test fun recognizerReleasedOnFeedFailure() = runBlocking {
        val fake = Fake(fail = true)
        try { recognizeDiagnosticClip(fake, ByteArray(3200)); fail("Expected decode failure") }
        catch (expected: IllegalStateException) { assertEquals("Decode failed", expected.message) }
        assertTrue(fake.closed)
        assertFalse(fake.finished)
    }
}
