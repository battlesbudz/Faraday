package com.battlesbudz.jarvis.v2.actions

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConditionalActionPlanTest {
    private fun plan(text: String) = ActionTurnPlan.parse(text) as ActionTurnPlan.Ready

    @Test fun exactPhoneReportAndCommaSequencesParseAsWholePlans() {
        val p = plan("if my battery is less than 60 open Facebook")
        assertEquals(BatteryCondition(BatteryCondition.Comparison.BELOW, 60), p.batteryCondition)
        assertEquals(ActionRequest("open_app", mapOf("app" to "Facebook")), p.steps.single().request)
        for (text in listOf(
            "Set volume to 40%, open Chrome, and tell me my battery percentage.",
            "Set volume to 40%, open Chrome, tell me my battery percentage",
            "Set volume to 40% then open Chrome then tell me my battery percentage"))
            assertEquals(text, listOf("set_volume", "open_app", "read_battery"), plan(text).steps.map { it.request.name })
        assertEquals(p.batteryCondition, plan("Open Facebook if my battery is below sixty percent").batteryCondition)
        assertTrue(plan("Unless my battery is at least 60%, then open Settings").batteryCondition!!.unless)
        assertTrue(com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
            p.steps.single().sourceClause, "open_app", mapOf("app" to "Facebook")))
    }

    @Test fun comparisonsRespectEqualityAndUnless() {
        for ((operator, low, equal, high) in listOf(
            listOf("below", true, false, false), listOf("at most", true, true, false),
            listOf("above", false, false, true), listOf("at least", false, true, true),
            listOf("equal to", false, true, false))) {
            val condition = plan("if my battery is $operator 60 open Settings").batteryCondition!!
            assertEquals(operator.toString(), low, condition.matches(59))
            assertEquals(operator.toString(), equal, condition.matches(60))
            assertEquals(operator.toString(), high, condition.matches(61))
            assertEquals(!condition.matches(60), condition.copy(unless = true).matches(60))
        }
    }

    @Test fun unsupportedOrInvalidConditionsNeverAuthorizeUnconditionalEffects() {
        for (text in listOf(
            "if my battery is less than 101 open Facebook", "if my battery is below 30.5 open Facebook",
            "if my battery is below negative thirty open Facebook", "if it is raining open Facebook",
            "when my battery is below 60 open Facebook", "Open Facebook after the download finishes",
            "If my battery is below 60 open Facebook else open Chrome",
            "If my battery is below 60 set volume to 150%", "Open Chrome, set volume to 150%"))
            assertFalse(text, ActionTurnPlan.parse(text) is ActionTurnPlan.Ready)
        for (text in listOf("How do I open Facebook if my battery is low?", "What happens when water freezes?",
            "Don't open Facebook if my battery is below 60", "For example, \"if my battery is below 60 open Facebook\""))
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
    }

    @Test fun trueConditionAndCommaPlanExecuteInOrderWithoutModelCalls() = runBlocking {
        val executed = mutableListOf<ActionRequest>()
        var reads = 0
        val p = plan("If my battery is below 60, set volume to 40%, open Chrome, and tell me my battery percentage")
        val outcome = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(p,
            dispatch = { executed += it; ExecutionResult(true, "did ${it.name}") },
            checkBattery = { reads++; ExecutionResult.battery(41) })
        assertTrue(outcome.completed)
        assertEquals(true, outcome.conditionMatched)
        assertEquals(1, reads)
        assertEquals(p.steps.map { it.request }, executed)
        assertEquals(3, outcome.receipts.size)
    }

    @Test fun falseConditionIsCompletedSkipWithNoRequestedEffects() = runBlocking {
        val outcome = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(
            plan("If my battery is below 60 open Facebook"),
            dispatch = { error("False condition dispatched an action") }, checkBattery = { ExecutionResult.battery(60) })
        assertTrue(outcome.completed)
        assertFalse(outcome.stopped)
        assertEquals(false, outcome.conditionMatched)
        assertTrue(outcome.receipts.isEmpty())
        assertTrue(outcome.message.contains("Skipped"))
    }

    @Test fun proseCannotSubstituteForNumericBatteryResult() = runBlocking {
        for (reading in listOf(ExecutionResult(true, "Battery is at 41 percent."),
            ExecutionResult(false, "Unavailable"), ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION, "41%"))) {
            val result = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(
                plan("If my battery is below 60 open Facebook"), dispatch = { error("Unverified condition dispatched") },
                checkBattery = { reading })
            assertFalse(result.completed)
            assertTrue(result.receipts.isEmpty())
        }
    }

    @Test fun modelBatchCannotBypassBatteryCondition() {
        val result = ActionTurnRunner(MobileActionExecutor { error("Condition bypassed") }).run(
            plan("If my battery is below 60 open Facebook"),
            listOf(listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", """{"app":"Facebook"}"""))))
        assertFalse(result.completed)
        assertTrue(result.receipts.isEmpty())
    }

    @Test fun failuresStopLaterStepsAndExplicitRepeatsRemainExplicit() = runBlocking {
        val calls = mutableListOf<ActionRequest>()
        val result = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(
            plan("Set volume to 20%, open missing app, read battery"),
            dispatch = { calls += it; ExecutionResult(it.name != "open_app", if (it.name == "open_app") "Missing app" else "Volume set") },
            checkBattery = { error("Unconditional plan read battery early") })
        assertFalse(result.completed)
        assertEquals(listOf("set_volume", "open_app"), calls.map { it.name })
        calls.clear()
        assertTrue(ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(
            plan("Set volume to 20%, set volume to 20%"),
            dispatch = { calls += it; ExecutionResult(true, "Volume set") }, checkBattery = { error("No condition") }).completed)
        assertEquals(2, calls.size)
    }

    @Test fun cancellationAfterReceiptPreventsNextEffect() = runBlocking {
        val calls = mutableListOf<ActionRequest>()
        try { kotlinx.coroutines.withContext(kotlinx.coroutines.Job()) {
            ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") }).runValidated(plan("Read battery, set volume to 20%"),
                dispatch = { calls += it; coroutineContext.cancel(); ExecutionResult(true, "Recorded") },
                checkBattery = { error("No condition") })
        } } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(listOf("read_battery"), calls.map { it.name })
    }

    @Test fun conditionalGroupSurvivesReloadButCannotResumeWithoutFreshRequest() {
        val directory = java.nio.file.Files.createTempDirectory("conditional-journal").toFile()
        try {
            val file = File(directory, "journal.json")
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val group = ledger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "40")), ActionRequest("read_battery")),
                "thread", resumeAfterRestart = false)
            val running = JournaledActionPipeline(ledger, MobileActionExecutor  { ExecutionResult(true, "40%") })
            assertTrue(running.executeAttempt(ledger.get(group.attemptIds.first())!!).succeeded)
            val recovered = ToolTaskLedger(FileToolTaskStore(file))
            assertFalse(recovered.journal().groups.single().resumeAfterRestart)
            recovered.recoverAfterRestart()
            assertEquals(ToolTaskState.SUCCEEDED, recovered.get(group.attemptIds.first())!!.state)
            assertEquals(ToolTaskState.PAUSED, recovered.get(group.attemptIds.last())!!.state)
            assertFalse(JournaledActionPipeline(recovered, MobileActionExecutor  { error("Replayed stale condition") })
                .executeAttempt(recovered.get(group.attemptIds.last())!!).succeeded)
        } finally { directory.deleteRecursively() }
    }
}
