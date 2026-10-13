package com.battlesbudz.jarvis.v2.voice

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VoiceSessionUiTest {
    @Test fun waitingAndMicHandoffCannotAppearAsThinking() {
        VoiceSessionUi.report("Waiting for Hey Jarvis — microphone active")
        assertEquals(VoicePhase.WAKE, VoiceSessionUi.phase.value)
        VoiceSessionUi.level.value = 0.8f
        VoiceSessionUi.report("Paused — microphone in use by another app")
        assertEquals(VoicePhase.PAUSED, VoiceSessionUi.phase.value)
        assertEquals(0f, VoiceSessionUi.level.value)
        VoiceSessionUi.report("Voice Call is listening — speak now.")
        assertEquals(VoicePhase.LISTENING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Processing your Voice Call turn locally…")
        assertEquals(VoicePhase.THINKING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Jarvis is speaking…")
        assertEquals(VoicePhase.SPEAKING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        assertEquals(VoicePhase.IDLE, VoiceSessionUi.phase.value)
    }

    @After fun reset() {
        VoiceSessionUi.failure.value = null
        VoiceSessionUi.status.value = ""
        VoiceSessionUi.phase.value = VoicePhase.IDLE
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
    }

    @Test fun terminalErrorSurvivesTeardownStatusAndIsDismissible() {
        VoiceSessionUi.reportFailure("chat", "Voice Call turn failed: missing audio asset")
        val failure = requireNotNull(VoiceSessionUi.failure.value)
        VoiceSessionUi.armed.value = false
        VoiceSessionUi.sessionAlive.value = false
        VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        assertEquals(failure, VoiceSessionUi.failure.value)
        VoiceSessionUi.dismissFailure(failure)
        assertNull(VoiceSessionUi.failure.value)
    }

    @Test fun newCallClearsOnlyItsConversationsFailure() {
        VoiceSessionUi.reportFailure("chat", "Voice Call turn failed: missing audio asset")
        VoiceSessionUi.clearFailure("another-chat")
        assertNotNull(VoiceSessionUi.failure.value)
        VoiceSessionUi.clearFailure("chat")
        assertNull(VoiceSessionUi.failure.value)
    }

    @Test fun staleDismissCannotEraseANewerOccurrenceOfTheSameError() {
        val message = "Voice Call turn failed: missing audio asset"
        VoiceSessionUi.reportFailure("chat", message)
        val first = requireNotNull(VoiceSessionUi.failure.value)
        VoiceSessionUi.reportFailure("chat", message)
        val second = requireNotNull(VoiceSessionUi.failure.value)
        assertNotEquals(first, second)
        VoiceSessionUi.dismissFailure(first)
        assertEquals(second, VoiceSessionUi.failure.value)
        VoiceSessionUi.dismissFailure(second)
        assertNull(VoiceSessionUi.failure.value)
    }

    @Test fun normalStopPauseAndWakeStatusNeverInventATerminalError() {
        VoiceSessionUi.failure.value = null
        for (status in listOf("Jarvis session stopped — microphone off.", "Paused — microphone off.",
            "Waiting for Hey Jarvis — microphone active", "Hey Jarvis detected — getting ready to listen…")) {
            VoiceSessionUi.report(status)
            assertNull(VoiceSessionUi.failure.value)
        }
    }
}
