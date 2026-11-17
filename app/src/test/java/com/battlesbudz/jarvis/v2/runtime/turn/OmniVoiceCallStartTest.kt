package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import com.battlesbudz.jarvis.v2.voice.VoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build-1942 fix coverage: the Omni route must establish its own call
 * record, its listening status must reach the call UI's listening phase, and
 * its start/prep failures must surface as a failed call state — the three
 * gaps that left a fresh-session Omni call stuck at Preparing.
 */
class OmniVoiceCallStartTest {
    @After fun reset() {
        VoiceSessionUi.failure.value = null
        VoiceSessionUi.status.value = ""
        VoiceSessionUi.phase.value = VoicePhase.IDLE
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
    }

    @Test fun omniCallStartsWithNoPreExistingCallRecord() {
        val controller = VoiceSessionController(MemoryStore())
        assertNull(controller.currentCallId())

        val callId = OmniVoiceCallStart.ensureCallRecord(controller, "chat-1")

        assertEquals(callId, controller.currentCallId())
        assertEquals("chat-1", controller.currentConversationId())
        assertEquals(VoiceSessionState.ACTIVELY_LISTENING, controller.state.value)
    }

    @Test fun ensureCallRecordAdoptsAnExistingCallInsteadOfBeginningAnother() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store)
        val existing = controller.beginCall("chat-1")

        val callId = OmniVoiceCallStart.ensureCallRecord(controller, "chat-2")

        assertEquals(existing.id, callId)
        assertEquals("chat-1", controller.currentConversationId())
        assertEquals(1, store.calls.size)
    }

    @Test fun omniListeningStatusMapsOutOfPreparing() {
        VoiceSessionUi.report("Preparing Omni Voice Call — loading the speech stack…")
        assertEquals(VoicePhase.PREPARING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report(OmniVoiceCallOwnerImpl.LISTENING_STATUS)
        assertEquals(VoicePhase.LISTENING, VoiceSessionUi.phase.value)
    }

    @Test fun omniStartFailureSurfacesAsFailedCallState() {
        // The exact surface the owner's failCall uses, at the same level the
        // Gemma route's failure surface is tested (VoiceSessionUiTest).
        VoiceSessionUi.reportFailure("chat-1",
            "Voice Call turn failed: Omni Voice Call is unavailable: boom")
        val failure = requireNotNull(VoiceSessionUi.failure.value)
        assertEquals("chat-1", failure.conversationId)
        assertEquals(VoicePhase.IDLE, VoiceSessionUi.phase.value)
    }

    private class MemoryStore : VoiceCallStore {
        val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) {
            calls.removeAll { it.id == call.id }
            calls += call
        }
        override fun delete(callId: String) { calls.removeAll { it.id == callId } }
    }
}
