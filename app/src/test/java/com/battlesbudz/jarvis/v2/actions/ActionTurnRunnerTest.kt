package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class ActionTurnRunnerTest {
    private class World(private val failAt: Int? = null) : MobileActionExecutor {
        val actions = mutableListOf<MobileAction>()
        override fun execute(action: MobileAction): ExecutionResult {
            actions += action
            return if (actions.size == failAt) ExecutionResult(false, "failed") else ExecutionResult(true, "did $action")
        }
    }
    private fun call(name: String, json: String) = ToolCall(name, json)

    @Test fun batchAndSequentialCallsFollowTheAuthorizedOrder() {
        val mediaPlan = ActionTurnPlan.parse("Set my media volume to 30 percent, then tell me my battery level")
        assertEquals(ActionTurnPlan.Ready(listOf(
            ActionTurnPlan.Step(ActionRequest("set_volume", mapOf("level" to "30")), "Set my media volume to 30 percent"),
            ActionTurnPlan.Step(ActionRequest("read_battery"), "tell me my battery level"))), mediaPlan)
        val plan = ActionTurnPlan.parse("Read battery, then set volume to 20 percent, and then open Settings")
        val world = World()
        val outcome = ActionTurnRunner(world).run(plan, listOf(
            listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}")),
            listOf(call("open_app", "{\"app\":\"Settings\"}"))))
        assertTrue(outcome.completed)
        assertEquals(listOf(MobileAction.ReadBattery, MobileAction.SetVolume(20), MobileAction.OpenApp("Settings")), world.actions)
    }

    @Test fun discoursePrefixesAndAfterThatRemainDirectedActions() {
        assertEquals("Facebook", (ActionTurnPlan.parse("And then open up Facebook after that.") as ActionTurnPlan.Ready).steps.single().request.arguments["app"])
        assertEquals("Settings", (ActionTurnPlan.parse("Then open Settings") as ActionTurnPlan.Ready).steps.single().request.arguments["app"])
        assertEquals("read_battery", (ActionTurnPlan.parse("Actually read battery") as ActionTurnPlan.Ready).steps.single().request.name)
        assertTrue(ActionTurnPlan.parse("Open Facebook after the download finishes") is ActionTurnPlan.Rejected)
    }

    @Test fun malformedWrongOrOutOfOrderResponseHasNoEffects() {
        val plan = ActionTurnPlan.parse("Read battery then set volume to 20 percent")
        for (calls in listOf(
            listOf(call("set_volume", "{\"level\":20}")),
            listOf(call("read_battery", "{\"extra\":true}")),
            listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":100}")))) {
            val world = World()
            assertFalse(ActionTurnRunner(world).run(plan, listOf(calls)).completed)
            assertTrue(world.actions.isEmpty())
        }
    }

    @Test fun explicitRepeatsExecuteButModelReplaysDoNot() {
        val repeated = ActionTurnPlan.parse("Set volume to 20 percent then set volume to 20 percent")
        val world = World()
        assertTrue(ActionTurnRunner(world).run(repeated, listOf(listOf(
            call("set_volume", "{\"level\":20}"), call("set_volume", "{\"level\":20}")))).completed)
        assertEquals(2, world.actions.size)
        val once = ActionTurnPlan.parse("Set volume to 20 percent then read battery")
        val dedup = World()
        val result = ActionTurnRunner(dedup).run(once, listOf(
            listOf(call("set_volume", "{\"level\":20}")),
            listOf(call("set_volume", "{\"level\":20}"), call("read_battery", "{}"))))
        assertTrue(result.completed)
        assertEquals(listOf(MobileAction.SetVolume(20), MobileAction.ReadBattery), dedup.actions)
    }

    @Test fun invalidPlansAndFailureStopWithoutLaterEffects() {
        assertTrue(ActionTurnPlan.parse("Set volume to 20 percent and explain batteries") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 5000 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set media volume to -30 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 30.5 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 20 percent and explain batteries") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("What battery technology is used?") is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Tell me about battery technology") is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Set volume to 30 percent delete files") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Read battery then set volume to 20 percent then open Settings then open YouTube") is ActionTurnPlan.Rejected)
        val world = World(failAt = 2)
        val result = ActionTurnRunner(world).run(ActionTurnPlan.parse("Read battery then set volume to 20 percent then open Settings"),
            listOf(listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}"), call("open_app", "{\"app\":\"Settings\"}"))))
        assertFalse(result.completed)
        assertEquals(2, world.actions.size)
    }
    @Test fun cancellationAndRetryCapNeverReplayCompletedEffects() {
        val cancelled = World()
        try {
            ActionTurnRunner(cancelled).run(ActionTurnPlan.parse("Read battery then set volume to 20 percent"), listOf(
                listOf(call("read_battery", "{}")), emptyList()))
        } catch (_: java.util.concurrent.CancellationException) { }
        // Duplicate-only model passes terminate at the bounded coordinator limit without replaying volume.
        val world = World()
        val result = ActionTurnRunner(world).run(ActionTurnPlan.parse("Set volume to 20 percent then read battery"),
            List(7) { listOf(call("set_volume", "{\"level\":20}")) })
        assertFalse(result.completed)
        assertEquals(listOf(MobileAction.SetVolume(20)), world.actions)
    }
    @Test fun exactNumberWordsAndOrdinaryConversationCannotAuthorizeEffects() {
        for (text in listOf("Set volume to two hundred percent", "Set volume to negative thirty percent",
            "Set volume to thirty banana percent", "Set volume to 30banana percent")) {
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.Rejected)
        }
        for (text in listOf("What happens when water freezes?", "I like cats and dogs and birds and fish", "Make a poem about the sea"))
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
        assertEquals(ActionTurnPlan.Ready(listOf(ActionTurnPlan.Step(ActionRequest("set_volume", mapOf("level" to "31")), "Set volume to thirty one percent"))),
            ActionTurnPlan.parse("Set volume to thirty one percent"))
        for (word in listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine"))
            assertTrue(word, ActionTurnPlan.parse("Set volume to $word percent") is ActionTurnPlan.Ready)
    }

    @Test fun actualCoroutineCancellationAfterReceiptSkipsLaterAction() = kotlinx.coroutines.runBlocking {
        val executed = mutableListOf<MobileAction>()
        val runner = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") })
        val job = kotlinx.coroutines.Job()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.CoroutineScope(job).launch {
            try {
                runner.runNative(ActionTurnPlan.parse("Read battery then set volume to 20 percent"),
                    listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}")),
                    dispatch = { request ->
                        executed += MobileActionValidator().validate(request).let { (it as ActionValidation.Valid).action }
                        if (executed.size == 1) coroutineContext.cancel()
                        ExecutionResult(true, "completed ${request.name}")
                    }, nextCalls = { emptyList() })
            } catch (_: java.util.concurrent.CancellationException) { } finally { done.complete(Unit) }
        }
        done.await()
        assertEquals(listOf(MobileAction.ReadBattery), executed)
    }

    @Test fun authoritativePlanKeepsImmediateOffersButRejectsStaleHistory() {
        val offer = listOf(com.battlesbudz.jarvis.v2.ChatEntry("Jarvis", "Shall I open YouTube?"))
        val yesPlan = ActionTurnPlan.parse("Yes", offer) as ActionTurnPlan.Ready
        assertEquals(ActionRequest("open_app", mapOf("app" to "YouTube")), yesPlan.steps.single().request)
        assertTrue(com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(yesPlan.steps.single().sourceClause,
            "open_app", yesPlan.steps.single().request.arguments))
        assertEquals(ActionTurnPlan.Ready(listOf(ActionTurnPlan.Step(ActionRequest("open_app", mapOf("app" to "YouTube")), "Open YouTube"))),
            ActionTurnPlan.parse("Open it", offer))
        assertTrue(ActionTurnPlan.parse("Yes", listOf(com.battlesbudz.jarvis.v2.ChatEntry("You", "Open YouTube"),
            com.battlesbudz.jarvis.v2.ChatEntry("Jarvis", "What would you like to discuss?"))) is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Read battery; set volume to 30 percent please; open Settings") is ActionTurnPlan.Ready)
    }

    @Test fun actualCancellationBeforeFirstEffectHasNoReceipt() = kotlinx.coroutines.runBlocking {
        val executed = mutableListOf<MobileAction>()
        val runner = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") })
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Job().apply { cancel() }) {
                runner.runNative(ActionTurnPlan.parse("Read battery"), listOf(call("read_battery", "{}")),
                    dispatch = { executed += MobileAction.ReadBattery; ExecutionResult(true, "done") }, nextCalls = { emptyList() })
            }
        } catch (_: java.util.concurrent.CancellationException) { }
        assertTrue(executed.isEmpty())
    }

    @Test fun configuredModelPassBudgetCountsInitialGenerationAndNeverRequestsAnotherPastLimit() = kotlinx.coroutines.runBlocking {
        val plan = ActionTurnPlan.parse("Read battery then set volume to 20 percent then open Settings")
        val executed = mutableListOf<ActionRequest>()
        var nextCalls = 0
        val onePass = ActionTurnRunner(World(), maxModelPasses = 1).runNative(
            plan, listOf(call("read_battery", "{}")),
            dispatch = { request -> executed += request; ExecutionResult(true, "done") },
            nextCalls = { nextCalls++; listOf(call("set_volume", "{\"level\":20}")) }
        )
        assertFalse(onePass.completed)
        assertEquals(listOf(ActionRequest("read_battery")), executed)
        assertEquals(0, nextCalls)

        executed.clear()
        val twoPass = ActionTurnRunner(World(), maxModelPasses = 2).runNative(
            plan, listOf(call("read_battery", "{}")),
            dispatch = { request -> executed += request; ExecutionResult(true, "done") },
            nextCalls = {
                nextCalls++
                listOf(call("set_volume", "{\"level\":20}"))
            }
        )
        assertFalse(twoPass.completed)
        assertEquals(listOf(ActionRequest("read_battery"), ActionRequest("set_volume", mapOf("level" to "20"))), executed)
        assertEquals(1, nextCalls)
    }

    @Test fun batchCanCompleteWithinConfiguredPassAndInvalidPassLimitsAreRejected() = kotlinx.coroutines.runBlocking {
        val executed = mutableListOf<ActionRequest>()
        var nextCalls = 0
        val completed = ActionTurnRunner(World(), maxModelPasses = 1).runNative(
            ActionTurnPlan.parse("Read battery then set volume to 20 percent"),
            listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}")),
            dispatch = { request -> executed += request; ExecutionResult(true, "done") },
            nextCalls = { nextCalls++; emptyList() }
        )
        assertTrue(completed.completed)
        assertEquals(2, executed.size)
        assertEquals(0, nextCalls)
        for (limit in listOf(0, -1)) try {
            ActionTurnRunner(World(), maxModelPasses = limit)
            fail("limit $limit should fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun naturalBatteryPhrasesAndSafeRetryPrefixUseLiteralAppNames() {
        for (battery in listOf("tell me what my battery percentage is", "tell me what the battery level is", "what is my battery percentage", "how much battery do I have", "how much my battery", "how much battery is left", "how much battery does my phone have", "tell me how much battery I have left"))
            assertTrue(battery, ActionTurnPlan.parse(battery) is ActionTurnPlan.Ready)
        val plan = ActionTurnPlan.parse("Can you open up Facebook and tell me what my battery percentage is?") as ActionTurnPlan.Ready
        assertEquals(listOf(ActionRequest("open_app", mapOf("app" to "Facebook")), ActionRequest("read_battery")), plan.steps.map { it.request })
        val retry = ActionTurnPlan.parse("I said, can you open up the fistbook and tell me what my battery percentage is?") as ActionTurnPlan.Ready
        assertEquals("fistbook", retry.steps.first().request.arguments["app"])
        for (text in listOf("Tell me about battery technology", "I said that Facebook was useful", "I said, don't open Facebook", "For example, \"tell me what my battery percentage is\""))
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
    }

    @Test fun productionRoutePlanDrivesSimulatedCallsWithoutAppSubstitution() {
        val turns = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient())
        val plan = turns.plan("Can you open up Facebook and tell me what my battery percentage is?").actionPlan
        val world = World()
        assertTrue(ActionTurnRunner(world).run(plan, listOf(listOf(call("open_app", "{\"app\":\"Facebook\"}"), call("read_battery", "{}")))).completed)
        assertEquals(listOf(MobileAction.OpenApp("Facebook"), MobileAction.ReadBattery), world.actions)
        val retry = turns.plan("I said, can you open up the fistbook and tell me what my battery percentage is?").actionPlan
        val rejected = World()
        assertFalse(ActionTurnRunner(rejected).run(retry, listOf(listOf(call("open_app", "{\"app\":\"Facebook\"}")))).completed)
        assertTrue(rejected.actions.isEmpty())
        val failing = World(failAt = 1)
        val outcome = ActionTurnRunner(failing).run(retry, listOf(listOf(call("open_app", "{\"app\":\"fistbook\"}"), call("read_battery", "{}"))))
        assertFalse(outcome.completed); assertEquals(listOf(MobileAction.OpenApp("fistbook")), failing.actions)
        val source = (retry as ActionTurnPlan.Ready).steps
        assertTrue(source.all { com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(it.sourceClause, it.request.name, it.request.arguments) })
    }

    @Test fun unconfirmedLaunchStopsTurnBeforeDependentStep() = kotlinx.coroutines.runBlocking {
        // A launch submitted without foreground evidence is unknown
        // completion: the turn stops instead of treating the dependent step
        // as success-backed, and the uncertain launch is never repeated.
        val plan = ActionTurnPlan.parse("Open Settings then read battery") as ActionTurnPlan.Ready
        val dispatched = mutableListOf<String>()
        val outcome = ActionTurnRunner(object : MobileActionExecutor {
            override fun execute(action: MobileAction) = ExecutionResult(true, "unused")
        }).runValidated(
            plan,
            dispatch = { request ->
                dispatched += request.name
                if (request.name == "open_app") verifiedLaunchReceipt(
                    "Settings", BackgroundLaunchRoute.DIRECT,
                    platformError = null, foregroundObserved = false
                )
                else ExecutionResult(true, "Battery is at 73 percent.")
            },
            checkBattery = { ExecutionResult(true, "Battery is at 73 percent.") }
        )
        assertFalse("the turn must not complete on an unconfirmed launch", outcome.completed)
        assertTrue("the turn must stop: ${outcome.message}", outcome.stopped)
        assertEquals(
            "the dependent step must not inherit the unestablished launch",
            listOf("open_app"), dispatched
        )
        assertEquals(1, outcome.receipts.size)
        assertEquals(
            ExecutionResult.Outcome.UNKNOWN_COMPLETION,
            outcome.receipts.single().result.outcome
        )
        assertTrue(
            "the user report must stay honest: ${outcome.message}",
            outcome.message.contains("could not be confirmed")
        )
    }

}
