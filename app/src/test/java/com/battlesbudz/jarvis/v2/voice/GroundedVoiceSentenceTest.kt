package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import org.junit.Assert.*
import org.junit.Test

class GroundedVoiceSentenceTest {
    @Test fun groundedSentenceStreamsBeforeGenerationFinishesAndIsNotRepeatedAtFinish() {
        val output = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("Tell me about music", null) { output += it }
        val grounding = ReferenceGroundingClient()
        guard.isPublishable = { !grounding.isInsufficientAnswer(it) }
        guard.accept("Rock music emerged in the 1950s. ")
        assertEquals(1, output.size)
        guard.accept("I don't know the rest. ")
        assertEquals(1, output.size)
        assertEquals("Rock music emerged in the 1950s.", guard.finish("Unused final draft"))
        assertEquals(1, output.size)
    }
    @Test fun rejectedDraftAllowsFinalSafeFallback() {
        val output = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("Tell me about music", null) { output += it }
        val grounding = ReferenceGroundingClient()
        guard.isPublishable = { !grounding.isInsufficientAnswer(it) }
        guard.accept("I don't know. ")
        assertTrue(output.isEmpty())
        val fallback = "The lookup did not produce a usable answer."
        assertEquals(fallback, guard.finish(fallback))
        assertEquals(listOf(fallback), output)
    }
}
