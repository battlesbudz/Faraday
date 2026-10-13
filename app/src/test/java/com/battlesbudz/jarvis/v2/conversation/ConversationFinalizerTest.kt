package com.battlesbudz.jarvis.v2.conversation

import org.junit.Assert.*
import org.junit.Test

/** Receipt honesty and short-fragment recovery must remain stable across turn extraction. */
class ConversationFinalizerTest {
    private fun resolve(
        response: String,
        previous: String? = null,
        receipt: String? = null,
        actionIntent: Boolean = false,
        actionName: String? = null
    ) = ConversationFinalizer.resolve(response, previous, receipt, actionIntent, actionName)

    @Test fun verifiedActionReceiptOverridesModelDraftAndRecoveryMessages() {
        val result = resolve("Done.", previous = "Done.", receipt = "The volume change failed.",
            actionIntent = true, actionName = "set_volume")
        assertEquals("The volume change failed.", result.text)
        // Context still needs resetting after the repeated short draft, even though the receipt wins.
        assertTrue(result.repeatedFragment)
    }

    @Test fun presentButEmptyReceiptIsStillAuthoritative() {
        assertEquals("", resolve("I changed it.", receipt = "", actionIntent = true).text)
    }

    @Test fun unexecutedActionIntentCannotPublishClaimedSuccessOrRepeatedFallback() {
        val result = resolve("Done.", previous = "Done.", actionIntent = true)
        assertEquals("I couldn't execute that phone action. Please ask again with the app name or exact setting.", result.text)
        assertTrue(result.repeatedFragment)
    }

    @Test fun repeatedShortAssistantFragmentBecomesLostThreadMessage() {
        val result = resolve("Yes.", previous = "Yes.")
        assertTrue(result.repeatedFragment)
        assertEquals("I lost the thread of the conversation. Please ask that again.", result.text)
    }

    @Test fun shortRepetitionBoundaryIsInclusiveAndDoesNotSuppressLongEquality() {
        for (length in listOf(1, 32)) {
            val fragment = "a".repeat(length)
            assertTrue("length=$length", resolve(fragment, previous = fragment).repeatedFragment)
        }
        val longReply = "a".repeat(33)
        val result = resolve(longReply, previous = longReply)
        assertFalse(result.repeatedFragment)
        assertEquals(longReply, result.text)
    }

    @Test fun equalityRemainsExactAndDoesNotNormalizeCurrentDraft() {
        assertFalse(resolve("yes", previous = "Yes").repeatedFragment)
        assertFalse(resolve(" Yes ", previous = "Yes").repeatedFragment)
    }

    @Test fun blankDraftUsesActionSpecificFailureOnlyWhenActionNameIsPresent() {
        assertEquals("I couldn't complete that phone action.", resolve(" \n", actionName = "open_app").text)
        assertEquals("I couldn't generate a response. Please try that again.", resolve(" \n").text)
    }

    @Test fun ordinaryReplyPreservesTextAndFormatting() {
        val response = "First paragraph.\n\nSecond paragraph."
        val result = resolve(response)
        assertEquals(response, result.text)
        assertFalse(result.repeatedFragment)
    }
}
