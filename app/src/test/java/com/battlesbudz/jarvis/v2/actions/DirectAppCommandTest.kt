package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class DirectAppCommandTest {
    @Test fun explicitFacebookCommandNeedsNoModelToolCall() {
        assertEquals(ActionRequest("open_app", mapOf("app" to "Facebook")), DirectAppCommand.parse("Open Facebook."))
        assertEquals("Facebook", DirectAppCommand.parse("Jarvis, could you please open Facebook for me?")?.arguments?.get("app"))
    }
    @Test fun doesNotExecuteQuestionsNegationsOrUnresolvedPronouns() {
        listOf("How do I open Facebook?", "Don't open Facebook", "Did you open Facebook?", "open it", "open Facebook actually cancel that").forEach {
            assertNull(it, DirectAppCommand.parse(it))
        }
    }
    @Test fun executorFailureRemainsAuthoritative() {
        val result = MobileActionPipeline(executor = MobileActionExecutor {
            ExecutionResult(false, "Tap the Open Facebook notification.")
        }).execute(DirectAppCommand.parse("open Facebook")!!)
        assertFalse(result.succeeded)
        assertEquals("Tap the Open Facebook notification.", result.message)
    }
}
