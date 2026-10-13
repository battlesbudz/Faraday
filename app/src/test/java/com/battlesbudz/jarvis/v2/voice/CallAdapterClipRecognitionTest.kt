package com.battlesbudz.jarvis.v2.voice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallAdapterClipRecognitionTest {
    @Test fun qualifiesBeforeFeedingAndClosesAfterFinal() = runBlocking {
        val order = mutableListOf<String>()
        val transcriber = object : StreamingTranscriber {
            override fun observeSpeech(speech: Boolean) { order += "observe:$speech" }
            override fun accept(pcm: ByteArray): String = error("Expected explicit partial policy")
            override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
                order += "accept:$allowPartial"; return ""
            }
            override fun finish(): String { order += "finish"; return "no" }
            override fun close() { order += "close" }
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(false, 0f)
            override fun close() = Unit
        }
        assertEquals("no", recognizeCallAdapterClip(transcriber, ByteArray(3200), detector) {})
        assertEquals(listOf("observe:false", "accept:false", "finish", "close"), order)
    }
    @Test fun closesNativeOwnerWhenDetectorFails() = runBlocking {
        var closed = false
        val transcriber = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = ""
            override fun finish() = ""
            override fun close() { closed = true }
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray): SpeechDecision = error("failed")
            override fun close() = Unit
        }
        try { recognizeCallAdapterClip(transcriber, ByteArray(3200), detector) {}; fail() }
        catch (_: IllegalStateException) { assertTrue(closed) }
    }
}
