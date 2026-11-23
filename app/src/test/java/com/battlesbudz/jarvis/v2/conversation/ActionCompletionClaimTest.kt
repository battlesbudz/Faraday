package com.battlesbudz.jarvis.v2.conversation

import org.junit.Assert.*
import org.junit.Test

class ActionCompletionClaimTest {
    @Test fun detectsPhoneActionCompletionClaims() {
        listOf(
            "Sending a message to Andrea saying \"testing\" now.",
            "I will send a message to Andrea now.",
            "I sent the message.",
            "I've opened YouTube.",
            "Message sent.",
            "I'm posting the notification now.",
            "Your reminder is set.",
        ).forEach { assertTrue("Expected claim: $it", ActionCompletionClaim.isClaim(it)) }
    }

    @Test fun ignoresOffersQuestionsNegationsAndUserAttribution() {
        listOf(
            "I can send that for you — what should it say?",
            "Would you like me to send it?",
            "I didn't send anything.",
            "I'll send it once you confirm.",
            "You sent a message yesterday.",
            "Messages you sent yesterday are in the thread.",
        ).forEach { assertFalse("Unexpected claim: $it", ActionCompletionClaim.isClaim(it)) }
    }
}
