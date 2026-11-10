package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.memory.AutoMemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice stages' automatic-memory queue port: candidate shape and blank guard.
 * The blob shape (finalized user text + the answer actually given) mirrors the chat
 * path, whose prompt+answer candidates the evaluator's patterns were tuned on.
 */
class VoiceMemoryAccessTest {
    private data class Queued(val eventId: String, val source: AutoMemorySource, val text: String, val capturedAtMs: Long)

    private fun recording(): Pair<VoiceMemoryAccess, MutableList<Queued>> {
        val queued = mutableListOf<Queued>()
        val memory = VoiceMemoryAccess(
            MemoryDeliveryFence(), VoiceMemoryDeliveryOwner(),
            autoCapture = { eventId, source, text, capturedAtMs -> queued += Queued(eventId, source, text, capturedAtMs) },
            capture = { _, _, _, _, _, _ -> false },
        )
        return memory to queued
    }

    @Test fun completedTurnQueuesUserTextPlusAnswerBlob() {
        val (memory, queued) = recording()
        memory.autoCaptureTurn("turn-1", AutoMemorySource.VOICE, "I also love ice cream.", "You enjoy ice cream.", 42L)
        assertEquals(
            listOf(Queued("turn-1", AutoMemorySource.VOICE, "I also love ice cream.\nYou enjoy ice cream.", 42L)),
            queued,
        )
    }

    @Test fun blankAnswerQueuesUserTextAloneAndBlankUserTextQueuesNothing() {
        val (memory, queued) = recording()
        memory.autoCaptureTurn("turn-2", AutoMemorySource.CHAT, "I live in Watertown", "", 7L)
        memory.autoCaptureTurn("turn-3", AutoMemorySource.VOICE, "  ", "Some answer", 9L)
        assertEquals(listOf(Queued("turn-2", AutoMemorySource.CHAT, "I live in Watertown", 7L)), queued)
    }

    @Test fun defaultPortIsANoOpSoUnwiredCallSitesQueueNothing() {
        val memory = VoiceMemoryAccess(MemoryDeliveryFence(), VoiceMemoryDeliveryOwner()) { _, _, _, _, _, _ -> false }
        // Must not throw: stages wired against an access without an auto-capture
        // binding (tests, previews) queue nothing.
        memory.autoCaptureTurn("turn-4", AutoMemorySource.VOICE, "I live in Watertown", "Noted.", 1L)
    }
}
