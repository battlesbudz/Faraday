package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import java.util.concurrent.CancellationException
import org.junit.Test

class MobileActionPipelineTest {
    @Test
    fun naturalLanguageShapedRequestIsValidatedBeforeExecution() {
        val executed = mutableListOf<MobileAction>()
        val pipeline = MobileActionPipeline { action ->
            executed += action
            ExecutionResult(true, "Action executed")
        }

        // This represents the structured request emitted by Gemma's native tool call.
        val result = pipeline.execute(
            ActionRequest("set_volume", mapOf("level" to "50"))
        )

        assertTrue(result.succeeded)
        assertEquals(listOf(MobileAction.SetVolume(50)), executed)
    }

    @Test
    fun invalidModelOutputNeverReachesExecutor() {
        var executorCalled = false
        val pipeline = MobileActionPipeline { 
            executorCalled = true
            ExecutionResult(true, "Unexpected")
        }

        val result = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "500")))

        assertTrue(!result.succeeded)
        assertTrue(!executorCalled)
    }
    @Test
    fun permissionDenialReturnsFailureWithoutLeakingPlatformDetails() {
        val pipeline = MobileActionPipeline { throw SecurityException("private platform details") }
        val result = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40")))
        assertFalse(result.succeeded)
        assertEquals("Android denied permission to perform this action.", result.message)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, result.outcome)
    }

    @Test
    fun unavailableServiceReturnsFailureAndDoesNotRetryTheSideEffect() {
        var attempts = 0
        val pipeline = MobileActionPipeline {
            attempts++
            throw IllegalStateException("service disconnected after request")
        }
        val result = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40")))
        assertFalse(result.succeeded)
        assertEquals("Android could not confirm that this action completed.", result.message)
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(1, attempts)
    }

    @Test
    fun failedActionDoesNotPreventTheNextIndependentAction() {
        val attempted = mutableListOf<MobileAction>()
        val pipeline = MobileActionPipeline { action ->
            attempted += action
            if (action is MobileAction.SetVolume) throw SecurityException("denied")
            ExecutionResult(true, "Battery is at 73 percent.")
        }
        assertFalse(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40"))).succeeded)
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertEquals(listOf(MobileAction.SetVolume(40), MobileAction.ReadBattery), attempted)
    }

    @Test
    fun rejectedValidationHasItsOwnTypedOutcomeAndNoEffect() {
        var calls = 0
        val result = MobileActionPipeline { calls++; ExecutionResult(true, "unexpected") }
            .execute(ActionRequest("set_volume", mapOf("level" to "101")))
        assertFalse(result.succeeded)
        assertEquals(ExecutionResult.Outcome.REJECTED_VALIDATION, result.outcome)
        assertEquals(0, calls)
    }

    @Test
    fun legacyConstructorKeepsSucceededAndOutcomeConsistent() {
        assertEquals(ExecutionResult.Outcome.SUCCEEDED, ExecutionResult(true, "done").outcome)
        assertEquals(ExecutionResult.Outcome.FAILED, ExecutionResult(false, "failed").outcome)
    }

    @Test
    fun cancellationStillStopsTheCaller() {
        val cancellation = CancellationException("cancelled")
        val pipeline = MobileActionPipeline { throw cancellation }
        try {
            pipeline.execute(ActionRequest("read_battery"))
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun unexpectedProgrammingErrorsAreNotConvertedToToolFailures() {
        val error = IllegalArgumentException("executor programming error")
        val pipeline = MobileActionPipeline { throw error }
        try {
            pipeline.execute(ActionRequest("read_battery"))
            fail("Unexpected programming errors must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(error, actual)
        }
    }
}
