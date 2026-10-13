package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureHandoffTest {
    private val input = CapturedVoiceTurn("words", ByteArray(128) { it.toByte() }, utteranceId = "next")
    @Test fun sealBindsExactCallConversationAndUtterance() {
        val handoff = VoiceCaptureHandoff("call", "conversation", "old-turn", "next", input.wav)
        assertFalse(handoff.claim("new-call", "conversation", input))
        assertFalse(handoff.claim("call", "new-conversation", input))
        assertFalse(handoff.claim("call", "conversation", input.copy(utteranceId = "wrong")))
        assertTrue(handoff.claim("call", "conversation", input))
        assertFalse(handoff.claim("call", "conversation", input))
    }
    @Test fun byteMutationCannotBecomeASealedRequest() {
        val handoff = VoiceCaptureHandoff("call", "conversation", "old-turn", "next", input.wav)
        val mutated = input.copy(wav = input.wav.copyOf().also { it[47] = 0 })
        assertFalse(handoff.claim("call", "conversation", mutated))
        assertTrue(handoff.claim("call", "conversation", input))
    }
}
