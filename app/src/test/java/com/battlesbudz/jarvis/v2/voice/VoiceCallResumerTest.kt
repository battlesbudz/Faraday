package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCallResumerTest {
    private class Store : VoiceCallStore {
        val records = linkedMapOf<String, VoiceCallRecord>()
        override fun list() = records.values.toList()
        override fun save(call: VoiceCallRecord) { records[call.id] = call }
        override fun delete(callId: String) { records.remove(callId) }
    }

    @Test fun waitsForAudioRetirementAndRejectsDuplicateTapWithoutLosingHistory() = runBlocking {
        val store = Store()
        val sessions = VoiceSessionController(store)
        sessions.beginCall()
        sessions.appendTranscript("Jarvis", "The astronauts landed on the moon.")
        sessions.updateTask(VoiceTaskStatus(VoiceTaskState.INTERRUPTED, pendingSteps = listOf("Wait for user")))
        val history = sessions.end()
        val active = sessions.beginCall()
        val resumer = VoiceCallResumer(sessions)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val pending = async {
            resumer.resume(history) { entered.complete(Unit); released.await() }
        }
        entered.await()
        assertEquals(active.id, sessions.currentCallId())
        assertTrue(resumer.resume(history) { error("Duplicate must not retire audio") }.isFailure)
        released.complete(Unit)
        val resumed = pending.await().getOrThrow()
        assertNotEquals(history.id, resumed.id)
        assertEquals(history.transcript, sessions.currentTranscript())
        assertEquals(history.taskStatus, resumed.taskStatus)
        assertEquals(history, store.records[history.id])
        assertNotNull(store.records[active.id]?.endedAtMs)
    }

    @Test fun failureCanBeRetriedAndCancellationDoesNotResume() = runBlocking {
        val sessions = VoiceSessionController(Store())
        val history = VoiceCallRecord("saved", 1)
        val resumer = VoiceCallResumer(sessions)
        assertTrue(resumer.resume(history) { error("Audio cleanup failed") }.isFailure)
        assertNull(sessions.currentCallId())
        try {
            resumer.resume(history) { throw CancellationException("Activity destroyed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(sessions.currentCallId())
        assertTrue(resumer.resume(history) {}.isSuccess)
    }
}
