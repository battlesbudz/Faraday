package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceRepetitionRepairTest {
    private fun result(text: String) = GenerationResult(text, 0, null)

    @Test fun firstSentenceIsDeliveredWhileRepairIsStillGenerating() = runBlocking {
        val spoken = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("Tell a story.", "Old story.", spoken::add)
        val outcome = VoiceRepetitionRepair.run(guard) { emit ->
            emit("A tiny star lit the sky.")
            assertEquals(listOf("A tiny star lit the sky."), spoken)
            delay(5)
            result("A tiny star lit the sky.")
        }
        assertEquals("completed", outcome.reason)
        assertEquals(1, spoken.size)
    }

    @Test fun wholeParagraphCallbackStopsAtTwoCheckedSentencesAndCleansNativeOwner() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", null) {}
        var cleaned = false
        val outcome = VoiceRepetitionRepair.run(guard) { emit ->
            try {
                emit("A star rose. It helped a comet. This must not be spoken.")
                error("Should cancel generation after second sentence")
            } finally { cleaned = true }
        }
        assertTrue(cleaned)
        assertEquals("sentence_or_character_limit", outcome.reason)
        assertEquals("A star rose. It helped a comet.", guard.text)
    }

    @Test fun timeoutDiscardsPartialTextAndAllowsCleanFallback() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", null) {}
        var cleaned = false
        val outcome = VoiceRepetitionRepair.run(guard, timeoutMs = 20) { emit ->
            try { emit("Unfinished secret"); awaitCancellation() } finally { cleaned = true }
        }
        assertTrue(cleaned); assertEquals("timeout", outcome.reason)
        guard.accept("Please try again."); guard.finish()
        assertEquals("Please try again.", guard.text)
    }

    @Test fun duplicateLoopHasACharacterBudget() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", "Same answer.") {}
        val outcome = VoiceRepetitionRepair.run(guard) { emit ->
            while (true) emit("Same answer. ")
            @Suppress("UNREACHABLE_CODE") result("")
        }
        assertEquals("sentence_or_character_limit", outcome.reason)
        assertEquals("", guard.text)
    }

    @Test fun parentStopPropagatesInsteadOfBecomingARepairFallback() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", null) {}
        try {
            VoiceRepetitionRepair.run(guard) { throw CancellationException("user_stop") }
            fail("Stop must propagate")
        } catch (expected: CancellationException) { assertEquals("user_stop", expected.message) }
    }

    @Test fun controlMarkupAfterProseIsBlockedAcrossChunks() = runBlocking {
        for (chunks in listOf(listOf("A star rose. ", "<to", "ol_call>open_app."),
            listOf("A star rose. ", "tool_", "call open_app."))) {
            val guard = VoiceRepetitionGuard("Story?", null) {}
            val outcome = VoiceRepetitionRepair.run(guard) { emit ->
                chunks.forEach(emit)
                result(chunks.joinToString(""))
            }
            assertEquals("control_output", outcome.reason)
            assertEquals("A star rose.", guard.text)
        }
    }

    @Test fun callbacksAfterTimeoutCannotChangeAcceptedText() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", null) {}
        var late: ((String) -> Unit)? = null
        VoiceRepetitionRepair.run(guard, timeoutMs = 10) { emit -> late = emit; awaitCancellation() }
        late!!.invoke("Late sentence.")
        assertEquals("", guard.text)
    }

    @Test fun finalOnlyReplyIsDeliveredOnceAndToolProtocolIsSuppressed() = runBlocking {
        val guard = VoiceRepetitionGuard("Story?", null) {}
        VoiceRepetitionRepair.run(guard) { result("A comet arrived.") }
        assertEquals("A comet arrived.", guard.text)
        val blocked = VoiceRepetitionGuard("Story?", null) {}
        VoiceRepetitionRepair.run(blocked) { result("<tool_call>open_app.</tool_call>") }
        assertEquals("", blocked.text)
    }
    @Test fun repairAfterValidOpeningStopsSecondLoopAndCleansGeneration() = runBlocking {
        val guard = VoiceRepetitionGuard("Opinion?", null) {}
        try { guard.accept("Vaping needs care. " + "nicotine- ".repeat(100)) }
        catch (_: VoiceRepetitionGuard.RunawayLoop) {}
        assertTrue(guard.needsRepair)
        var cleaned = false
        val outcome = VoiceRepetitionRepair.run(guard) { emit ->
            try {
                emit("A useful consideration ")
                while (true) emit("nicotine- ")
                @Suppress("UNREACHABLE_CODE") result("")
            } finally { cleaned = true }
        }
        assertTrue(cleaned)
        assertEquals("runaway_repetition", outcome.reason)
        assertTrue(guard.loopDetected)
        assertEquals("Vaping needs care.", guard.finish())
    }

    @Test fun validOpeningDoesNotPreventBoundedRecovery() = runBlocking {
        val guard = VoiceRepetitionGuard("Opinion?", null) {}
        try { guard.accept("Vaping needs care. " + "nicotine- ".repeat(100)) }
        catch (_: VoiceRepetitionGuard.RunawayLoop) {}
        val outcome = VoiceRepetitionRepair.run(guard) { emit ->
            emit("There are several risks to consider.")
            result("There are several risks to consider.")
        }
        assertEquals("completed", outcome.reason)
        assertFalse(guard.needsRepair)
        assertEquals("Vaping needs care. There are several risks to consider.", guard.text)
    }

}
