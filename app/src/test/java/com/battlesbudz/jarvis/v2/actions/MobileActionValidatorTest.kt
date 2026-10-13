package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileActionValidatorTest {
    private val validator = MobileActionValidator()

    @Test
    fun validatesBatteryAction() {
        assertEquals(
            ActionValidation.Valid(MobileAction.ReadBattery),
            validator.validate(ActionRequest("read_battery"))
        )
    }

    @Test
    fun rejectsUnknownActions() {
        val result = validator.validate(ActionRequest("delete_everything"))
        assertTrue(result is ActionValidation.Rejected)
    }

    @Test
    fun validatesBoundedVolume() {
        assertEquals(
            ActionValidation.Valid(MobileAction.SetVolume(50)),
            validator.validate(ActionRequest("set_volume", mapOf("level" to "50")))
        )
    }

    @Test
    fun validatesHumanReadableAppName() {
        assertEquals(
            ActionValidation.Valid(MobileAction.OpenApp("YouTube")),
            validator.validate(ActionRequest("open_app", mapOf("app" to "YouTube")))
        )
    }

    @Test
    fun rejectsPackageSegmentsStartingWithDigits() {
        val result = validator.validate(
            ActionRequest("open_app", mapOf("package" to "com.1example.app"))
        )
        assertTrue(result is ActionValidation.Rejected)
    }

    @Test
    fun validatesEveryMediaControlVerb() {
        assertEquals(
            ActionValidation.Valid(MobileAction.MediaControl(MediaControlAction.PLAY)),
            validator.validate(ActionRequest("media_control", mapOf("action" to "play")))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.MediaControl(MediaControlAction.PREVIOUS)),
            validator.validate(ActionRequest("media_control", mapOf("action" to " previous ")))
        )
    }

    @Test
    fun rejectsUnknownMediaControlVerbs() {
        listOf("rewind", "PLAY", "", "   ").forEach { verb ->
            val result = validator.validate(ActionRequest("media_control", mapOf("action" to verb)))
            assertTrue("verb '$verb' must be rejected", result is ActionValidation.Rejected)
        }
        assertTrue(validator.validate(ActionRequest("media_control")) is ActionValidation.Rejected)
    }

    @Test
    fun mediaControlReachesExecutorWithTypedAction() {
        val executed = mutableListOf<MobileAction>()
        val pipeline = MobileActionPipeline { action ->
            executed += action
            ExecutionResult(true, "Sent ${MediaControlAction.NEXT.label} command to the active media session.")
        }
        val result = pipeline.execute(ActionRequest("media_control", mapOf("action" to "next")))
        assertTrue(result.succeeded)
        assertEquals(listOf(MobileAction.MediaControl(MediaControlAction.NEXT)), executed)
    }

    @Test
    fun invalidMediaControlNeverReachesExecutor() {
        var executorCalled = false
        val pipeline = MobileActionPipeline {
            executorCalled = true
            ExecutionResult(true, "Unexpected")
        }
        val result = pipeline.execute(ActionRequest("media_control", mapOf("action" to "rewind")))
        assertTrue(!result.succeeded)
        assertTrue(!executorCalled)
    }
}