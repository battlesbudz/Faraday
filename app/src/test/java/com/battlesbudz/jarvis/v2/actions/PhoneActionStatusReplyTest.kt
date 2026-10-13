package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class PhoneActionStatusReplyTest {
    private val request = "set volume to 100% then open Facebook then tell me my battery"
    private val failure = "Media volume set to 100 percent. Could not open Facebook. Not attempted: tell me my battery."
    private val status = PhoneActionStatus("thread", request, failure)

    @Test fun complaintUsesExactFailedReceiptWithoutInventingCompletion() {
        assertEquals(failure, PhoneActionStatusReply.resolve("Well.", "thread", listOf("You" to request), status))
    }

    @Test fun alreadyAppendedCurrentMessageCannotHidePreviousUser() {
        assertEquals(failure, PhoneActionStatusReply.resolve("Well.", "thread",
            listOf("You" to request, "Jarvis" to failure, "You" to "Well."), status))
        assertNull(PhoneActionStatusReply.resolve("Well.", "thread",
            listOf("You" to request, "You" to "Tell me a story", "You" to "Well."), status))
    }

    @Test fun statusQuestionPreservesFailureAndUnattemptedStep() {
        assertEquals(failure, PhoneActionStatusReply.resolve("Did you complete the sequence?", "thread",
            listOf("You" to request), status))
    }

    @Test fun successfulAndFalseConditionalResultsAreNotRewrittenAsGenericCompletion() {
        val skipped = status.copy(message = "Battery is at 78 percent. The condition was not met. Skipped: open Facebook.")
        assertEquals(skipped.message, PhoneActionStatusReply.resolve("Did it work?", "thread", listOf("You" to request), skipped))
        val succeeded = status.copy(message = "Media volume set to 100 percent. Requested opening Facebook. Battery is at 78 percent.")
        assertEquals(succeeded.message, PhoneActionStatusReply.resolve("Is it done?", "thread", listOf("You" to request), succeeded))
    }

    @Test fun unrelatedMessageOrConversationCannotReuseOldReceipt() {
        assertNull(PhoneActionStatusReply.resolve("Well", "other", listOf("You" to request), status))
        assertNull(PhoneActionStatusReply.resolve("Open Instagram", "thread", listOf("You" to request), status))
        assertNull(PhoneActionStatusReply.resolve("Well", "thread", listOf("You" to "tell me about fruit"), status))
        assertNull(PhoneActionStatusReply.resolve("Well", "thread", listOf("You" to request), null))
    }
}
