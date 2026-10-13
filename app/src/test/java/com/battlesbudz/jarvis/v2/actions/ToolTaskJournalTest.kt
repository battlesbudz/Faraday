package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTaskJournalTest {
    @Test
    fun mediaControlIsRoutineEligibleWithExactVerb() {
        assertTrue(ActionRequest("media_control", mapOf("action" to "pause")).isRoutineEligible())
        assertTrue(ActionRequest("media_control", mapOf("action" to "next")).isRoutineEligible())
    }

    @Test
    fun malformedMediaControlIsNotRoutineEligible() {
        assertFalse(ActionRequest("media_control", mapOf("action" to "rewind")).isRoutineEligible())
        assertFalse(ActionRequest("media_control").isRoutineEligible())
        assertFalse(
            ActionRequest("media_control", mapOf("action" to "pause", "extra" to "1")).isRoutineEligible()
        )
    }

    @Test
    fun existingToolsKeepRoutineEligibility() {
        assertTrue(ActionRequest("read_battery").isRoutineEligible())
        assertTrue(ActionRequest("set_volume", mapOf("level" to "50")).isRoutineEligible())
        assertTrue(ActionRequest("open_app", mapOf("app" to "Settings")).isRoutineEligible())
        assertFalse(ActionRequest("set_volume", mapOf("level" to "500")).isRoutineEligible())
        assertFalse(ActionRequest("delete_everything").isRoutineEligible())
    }
}
