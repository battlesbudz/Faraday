package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnCoordinatorTest {
    private class MemoryStore : VoiceCallStore {
        val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) {
            calls.removeAll { it.id == call.id }
            calls += call
        }
        override fun delete(callId: String) {
            calls.removeAll { it.id == callId }
        }
    }

    @Test
    fun streamedResponseIsCheckpointedAndCompleted() = runBlocking {
        val store = MemoryStore()
        val coordinator = VoiceTurnCoordinator(VoiceSessionController(store))
        coordinator.processTurn("What is my battery?",) { emit ->
            emit("Your battery ")
            emit("is at 87 percent.")
            "done"
        }
        val call = store.calls.single()
        assertEquals("Your battery is at 87 percent.", call.transcript.last().text)
        assertEquals(true, call.transcript.last().complete)
    }

    @Test
    fun failedStreamRemainsIncompleteAndIsInterrupted() = runBlocking {
        val store = MemoryStore()
        val session = VoiceSessionController(store)
        val coordinator = VoiceTurnCoordinator(session)
        session.beginCall()
        session.updateTask(VoiceTaskStatus(VoiceTaskState.WAITING_FOR_USER))
        runCatching {
            coordinator.processTurn(null) { emit ->
                emit("I am checking")
                error("model stopped")
            }
        }
        val call = store.calls.single()
        assertFalse(call.transcript.last().complete)
        assertEquals(VoiceTaskState.INTERRUPTED, call.taskStatus?.state)
    }
    @Test fun stoppingReplyKeepsSameCallAndAllowsNextCommand() = runBlocking {
        val store = MemoryStore()
        val session = VoiceSessionController(store)
        val coordinator = VoiceTurnCoordinator(session)
        val id = session.beginCall().id
        runCatching {
            coordinator.processTurn("Tell me a story") { emit ->
                emit("Once upon a time")
                throw VoiceControlCancellation(VoiceControl.STOP_REPLY)
            }
        }
        assertEquals(id, session.currentCallId())
        assertFalse(store.calls.single().transcript.last().complete)
        coordinator.processTurn("Actually, what is my battery?") { emit -> emit("Battery is 50 percent") }
        assertEquals(id, session.currentCallId())
        assertEquals(4, session.currentTranscript().size)
    }

    @Test fun spokenInterruptionCancelsGenerationAndRetainsCallForCorrection() = runBlocking {
        val store = MemoryStore()
        val session = VoiceSessionController(store)
        val coordinator = VoiceTurnCoordinator(session)
        val id = session.beginCall().id
        val generating = CompletableDeferred<Unit>()
        val result = runInterruptibleReply(
            reply = { coordinator.processTurn("Tell me a story") { emit ->
                emit("Once upon a time")
                generating.complete(Unit)
                awaitCancellation()
            } },
            listen = { confirmed -> generating.await(); confirmed(); CapturedVoiceTurn("Open YouTube", byteArrayOf()) },
            stopReply = {})
        assertEquals(id, session.currentCallId())
        assertEquals(VoiceSessionState.ACTIVELY_LISTENING, session.state.value)
        assertFalse(session.currentTranscript().last().complete)
        coordinator.processTurn((result as ReplyOutcome.Interrupted).correction.transcript) { emit -> emit("Opening YouTube") }
        assertEquals(id, session.currentCallId())
        assertEquals(4, session.currentTranscript().size)
    }

    @Test fun revokedPublicationGatePreventsTokenAndCatchFallbackRepublish() = runBlocking {
        val store = MemoryStore(); val session = VoiceSessionController(store)
        val coordinator = VoiceTurnCoordinator(session)
        runCatching { coordinator.processTurn("secret", publish = { false }) { emit -> emit("old memory"); "done" } }
        assertTrue(session.currentTranscript().none { it.text.contains("old memory") })
    }

}
