package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class TranscriptContentTest {
    @Test fun knownSoundCaptionsCannotBecomeRequests() {
        listOf("(crying)", "[dramatic music]", "(breathing) [noise]", "[NO_SPEECH]", "♪♫", "(crying).").forEach {
            assertTrue(it, TranscriptContent.isSoundOnly(it))
        }
    }
    @Test fun preserveActualWordsAndUnrecognizedBrackets() {
        assertEquals("Can you hear me?", TranscriptContent.speech("[music] Can you hear me?"))
        assertEquals("I am crying", TranscriptContent.speech("I am crying"))
        assertEquals("(open Facebook)", TranscriptContent.speech("(open Facebook)"))
        assertFalse(TranscriptContent.isSoundOnly("Stop the music"))
        assertFalse(TranscriptContent.isSoundOnly("(crying) Help me"))
    }
}
