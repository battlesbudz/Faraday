package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptResolverTest {
    @Test fun emptyAsrSendsOriginalAudioToGemma() = runBlocking {
        val wav = byteArrayOf(1, 2, 3, 4)
        var calls = 0
        val text = VoiceTranscriptResolver.resolve("", wav) {
            calls++
            assertSame(wav, it)
            "Open YouTube"
        }
        assertEquals("Open YouTube", text)
        assertEquals(1, calls)
    }
    @Test fun usableAsrDoesNotRequireAnExtraRecognitionPass() = runBlocking {
        assertEquals("Hello", VoiceTranscriptResolver.resolve("Hello", byteArrayOf()) { error("Unexpected fallback") })
    }
    @Test fun explicitNoSpeechRemainsNonverbalAndCannotTriggerAnAnswer() = runBlocking {
        val result = VoiceTranscriptResolver.resolve("", byteArrayOf()) { "[NO_SPEECH]" }
        assertTrue(TranscriptContent.isSoundOnly(result))
    }
    @Test fun emptyAudioRetriesOnceAndCanRecover() = runBlocking {
        val attempts = mutableListOf<Int>()
        val text = VoiceTranscriptResolver.retryEmptyAudio { attempt ->
            attempts += attempt
            if (attempt == 1) " " else "Turn on the light"
        }
        assertEquals(listOf(1, 2), attempts)
        assertEquals("Turn on the light", text)
    }
    @Test fun exhaustedAttemptsNeverBecomeAUserMessage() = runBlocking {
        var calls = 0
        val text = VoiceTranscriptResolver.resolve("", byteArrayOf()) {
            VoiceTranscriptResolver.retryEmptyAudio { calls++; "" }
        }
        assertEquals(2, calls)
        assertEquals("", text)
        assertFalse(VoiceTranscriptResolver.hasTranscript(text))
        assertFalse(VoiceTranscriptResolver.hasTranscript(VoiceTranscriptResolver.UNTRANSCRIBED))
    }
    @Test fun noSpeechIsNotRetriedAndExceptionsAreNotSwallowed() = runBlocking {
        var calls = 0
        assertEquals("[NO_SPEECH]", VoiceTranscriptResolver.retryEmptyAudio { calls++; "[NO_SPEECH]" })
        assertEquals(1, calls)
        try {
            VoiceTranscriptResolver.retryEmptyAudio { throw kotlinx.coroutines.CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
