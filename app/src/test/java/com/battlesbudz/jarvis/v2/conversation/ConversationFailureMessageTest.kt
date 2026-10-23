package com.battlesbudz.jarvis.v2.conversation

import org.junit.Assert.*
import org.junit.Test

class ConversationFailureMessageTest {
    /** The exact SDK rejection from Battles's build-1563 diagnostics dump. */
    private val dumpParseError = RuntimeException(
        "Failed to parse tool calls from code block: call:open_app{app:<|\"|>Facebook<|\"|>Facebook<|\"|>}")

    @Test fun toolCallParseFailureNeverSurfacesAsModelLoadFailure() {
        val message = conversationFailureMessage(isNativeQuarantined = false, dumpParseError)
        assertEquals("I couldn't complete that action. Please try again.", message)
        assertFalse(message.contains("load"))
        assertFalse(message.contains("Status Code"))
    }

    @Test fun quarantineKeepsItsRestartMessageEvenForParseFailures() {
        val message = conversationFailureMessage(isNativeQuarantined = true, dumpParseError)
        assertEquals("The local model did not stop safely. Restart Jarvis before starting another request.", message)
    }

    @Test fun otherFailuresKeepTheLoadMessage() {
        val message = conversationFailureMessage(isNativeQuarantined = false,
            RuntimeException("weights missing"))
        assertEquals("I could not load the local model: weights missing", message)
    }
}
