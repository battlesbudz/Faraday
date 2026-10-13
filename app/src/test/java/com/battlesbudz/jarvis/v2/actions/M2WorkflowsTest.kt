package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.UUID

/**
 * M2 reusable workflows and triggers (D31–D36, T11–T14).
 *
 * Covers versioned step graphs with typed result bindings, deterministic
 * conditions, waits and bounded adaptive steps; draft/enable/revise
 * lifecycle; routine-grant reuse limits and disable semantics; scheduling
 * policy (reminders, windows, DST, reboot dedup); missed-run evaluation;
 * the engine's suspension/approval/user-question outcomes; schema-3
 * persistence; and the settings projection.
 */
class M2WorkflowsTest {

    private var nowMs = 1_700_000_000_000L
    private val now: () -> Long = { nowMs }

    private fun uid() = UUID.randomUUID().toString()

    private fun batteryStep(id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("read_battery"))

    private fun volumeStep(level: String, id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("set_volume", mapOf("level" to level)))

    private fun definition(
        name: String = "Evening wind-down",
        steps: List<WorkflowStep> = listOf(batteryStep(), volumeStep("20")),
        triggers: List<WorkflowTrigger> = listOf(WorkflowTrigger.Manual),
        origin: WorkflowOrigin = WorkflowOrigin.CONVERSATION
    ) = WorkflowDefinition(uid(), name, "A test routine.", steps, triggers, origin,
        createdAtMs = nowMs, updatedAtMs = nowMs)

    // -- Definition validation -------------------------------------------------

    @Test fun validDefinitionPasses() {
        definition() // init{} validates; must not throw
    }

    @Test fun unknownToolIsRejected() {
        try {
            definition(steps = listOf(WorkflowStep.Tool(uid(), ActionRequest("send_text"))))
            fail("unknown tool must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wrongArgumentsAreRejected() {
        try {
            definition(steps = listOf(WorkflowStep.Tool(uid(), ActionRequest("set_volume"))))
            fail("missing arguments must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun nonRoutineWebToolCannotBeAWorkflowStep() {
        // M1e family discipline carried forward: only routine-eligible tools
        // run under a routine grant; screen mutations get an approval branch.
        try {
            definition(steps = listOf(
                WorkflowStep.Tool(uid(), ActionRequest("open_website", mapOf("url" to "https://example.com")))))
            fail("open_website must be rejected as a workflow step")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun screenMutationStepsAreAllowedWithApprovalBranch() {
        definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("screen_tap",
                mapOf("target" to "n1", "token" to "abcdef0123456789")))))
    }

    @Test fun duplicateStepIdsAreRejected() {
        val id = uid()
        try {
            definition(steps = listOf(batteryStep(id), volumeStep("20", id)))
            fail("duplicate step ids must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun bindingToUnknownStepIsRejected() {
        val step = WorkflowStep.Tool(uid(), ActionRequest("set_volume", mapOf("level" to "20")),
            bindings = mapOf("level" to WorkflowBinding(uid(), "battery_percent")))
        try { definition(steps = listOf(step)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun bindingToUndeclaredOutputIsRejected() {
        val first = batteryStep()
        val step = WorkflowStep.Tool(uid(), ActionRequest("set_volume", mapOf("level" to "20")),
            bindings = mapOf("level" to WorkflowBinding(first.id, "nope")))
        try { definition(steps = listOf(first, step)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun conditionOnWrongTypeIsRejected() {
        val first = batteryStep()
        val branch = WorkflowStep.Branch(uid(),
            WorkflowCondition.GreaterThan(WorkflowBinding(first.id, "message"), 20.0),
            listOf(volumeStep("10")))
        try { definition(steps = listOf(first, branch)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidConditionRegexIsRejected() {
        val first = batteryStep()
        val branch = WorkflowStep.Branch(uid(),
            WorkflowCondition.Matches(WorkflowBinding(first.id, "message"), "(["),
            listOf(volumeStep("10")))
        try { definition(steps = listOf(first, branch)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun placeholderToUnknownStepIsRejected() {
        val step = WorkflowStep.Tool(uid(),
            ActionRequest("open_app", mapOf("app" to "\${${uid()}.message}")))
        try { definition(steps = listOf(step)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun waitBoundsAreEnforced() {
        try {
            definition(steps = listOf(WorkflowStep.Wait(uid(), WorkflowWait.Timer(500))))
            fail("sub-second waits must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            definition(steps = listOf(WorkflowStep.Wait(uid(), WorkflowWait.Timer(8 * 86_400_000L))))
            fail("over-long waits must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun adaptiveBudgetBoundsAreEnforced() {
        try {
            definition(steps = listOf(WorkflowStep.Adaptive(uid(), "goal",
                listOf(ActionRequest("read_battery")), EffortBudget(0, 60_000, 3))))
            fail("zero attempts must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun previewIsPlainLanguage() {
        val text = definition().previewText()
        assertTrue(text.contains("Evening wind-down"))
        assertTrue("preview names the battery check" , text.contains("battery"))
        assertTrue("preview names the volume step", text.contains("20%"))
        assertTrue("preview names the trigger", text.contains("when you ask"))
        assertTrue("preview names permissions", text.contains("phone controls"))
        assertTrue("preview states the draft needs enabling", text.contains("draft"))
        assertFalse("preview must not leak step ids", text.contains("steps:"))
    }

    // -- Draft / enable / revise lifecycle (T12) --------------------------------

    private fun ledger() = WorkflowLedger(InMemoryToolTaskStore(), now)

    @Test fun draftIsSavedDisabledAndShowsPreview() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        assertFalse(saved.enabled)
        val preview = l.preview(saved.id)
        assertTrue(preview.contains("Evening wind-down"))
    }

    @Test fun enableSchedulesTimeBasedTriggers() {
        val l = ledger()
        val at = 1_700_000_000_000L
        val reminderAt = at + 3_600_000L
        val saved = l.saveDraft(definition(triggers = listOf(
            WorkflowTrigger.Reminder(reminderAt),
            WorkflowTrigger.Daily(21, 0),
            WorkflowTrigger.Manual)))
        val scheduled = l.enable(saved.id)
        assertEquals("reminder + daily get occurrences; manual does not", 2, scheduled.size)
        val reminder = scheduled.find { it.triggerIndex == 0 }!!
        assertEquals(reminderAt, reminder.scheduledForMs)
        assertEquals(WorkflowOccurrenceState.SCHEDULED, reminder.state)
        assertTrue(l.current(saved.id)!!.enabled)
    }

    @Test fun pastReminderIsNotScheduledOnEnable() {
        val l = ledger()
        val saved = l.saveDraft(definition(triggers = listOf(WorkflowTrigger.Reminder(nowMs - 1_000))))
        assertTrue(l.enable(saved.id).isEmpty())
    }

    @Test fun occurrenceCannotBeScheduledWhileDisabled() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        assertNull(l.scheduleOccurrence(saved.id, 0, nowMs + 60_000, nowMs + 60_000, "k1"))
    }

    @Test fun claimDueIsIdempotent() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "due-1")!!
        val claimed = l.claimDueOccurrence(occurrence.id)
        assertNotNull(claimed)
        assertEquals(WorkflowOccurrenceState.RUNNING, claimed!!.state)
        assertNull("a second claim must not re-fire", l.claimDueOccurrence(occurrence.id))
    }

    @Test fun futureOccurrenceCannotBeClaimedEarly() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs + 600_000, nowMs + 600_000, "future-1")!!
        assertNull(l.claimDueOccurrence(occurrence.id))
    }

    @Test fun dedupKeyPreventsDoubleScheduling() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val first = l.scheduleOccurrence(saved.id, 0, nowMs + 60_000, nowMs + 60_000, "same-key")
        assertNotNull(first)
        assertNull("same dedup key must not schedule twice",
            l.scheduleOccurrence(saved.id, 0, nowMs + 60_000, nowMs + 60_000, "same-key"))
    }

    @Test fun reviseCreatesANewVersionAndRunningOccurrenceKeepsItsPin() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "rev-1")!!
        l.claimDueOccurrence(occurrence.id)
        val revised = l.revise(saved.id, definition(name = "Evening wind-down v2",
            steps = listOf(batteryStep())).copy(id = saved.id))
        assertEquals(2, revised.version)
        assertEquals(1, l.definitionFor(occurrence)!!.version)
        assertEquals(2, l.current(saved.id)!!.version)
        assertTrue("enabled state survives a revision", l.current(saved.id)!!.enabled)
    }

    // -- Routine grants and disable (T11) ----------------------------------------

    @Test fun routineGrantReusedOnlyWhenLimitsMatchExactly() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        val requests = listOf(ActionRequest("read_battery"),
            ActionRequest("set_volume", mapOf("level" to "20")))
        val grant = l.createGrant(saved.id, requests)
        assertEquals(grant.id, l.reusableGrant(saved.id, requests)!!.id)
        val different = listOf(ActionRequest("read_battery"),
            ActionRequest("set_volume", mapOf("level" to "30")))
        assertNull("changed limits must not reuse the grant", l.reusableGrant(saved.id, different))
        val broader = requests + ActionRequest("open_app", mapOf("app" to "Maps"))
        assertNull("a broader request set must not reuse the grant", l.reusableGrant(saved.id, broader))
    }

    @Test fun grantCannotCoverNonRoutineTools() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        try {
            l.createGrant(saved.id, listOf(ActionRequest("open_website", mapOf("url" to "https://example.com"))))
            fail("non-routine tools must not get routine grants")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun disablePausesAffectedWorkWithoutTouchingUnrelatedTasks() {
        val store = InMemoryToolTaskStore()
        val tasks = ToolTaskLedger(store, now)
        val l = WorkflowLedger(store, now)
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs + 60_000, nowMs + 60_000, "dis-1")!!
        // An unrelated user-requested task.
        val unrelated = tasks.create(ActionRequest("read_battery"))
        // A routine attempt under this workflow's grant.
        val grant = l.createGrant(saved.id, listOf(ActionRequest("read_battery")))
        val group = tasks.admit(listOf(ActionRequest("read_battery")), "chat-1",
            authority = ToolAuthority.ROUTINE, grantId = grant.id)
        val result = l.disable(saved.id)
        assertEquals(listOf(occurrence.id), result.pausedOccurrenceIds)
        assertEquals(listOf(grant.id), result.revokedGrantIds)
        assertEquals(WorkflowOccurrenceState.CANCELLED, l.occurrence(occurrence.id)!!.state)
        val paused = tasks.get(group.attemptIds.single())!!
        assertEquals(ToolTaskState.PAUSED, paused.state)
        assertEquals("unrelated task untouched", ToolTaskState.QUEUED, tasks.get(unrelated.id)!!.state)
        assertFalse("disabled workflow cannot schedule", l.current(saved.id)!!.enabled)
        assertNull(l.claimDueOccurrence(occurrence.id))
    }

    // -- Capture (D31/D41) ---------------------------------------------------------

    @Test fun successfulTaskCanBeCapturedAsDraft() {
        val store = InMemoryToolTaskStore()
        val tasks = ToolTaskLedger(store, now)
        val l = WorkflowLedger(store, now)
        val group = tasks.admit(listOf(ActionRequest("read_battery")), "chat-1")
        val attempt = tasks.get(group.attemptIds.single())!!
        val running = tasks.claim(attempt.id, attempt.generation)!!
        tasks.finish(running, ExecutionResult(true, "Battery is at 80 percent."))
        val captured = l.captureFromTask(group.id, "Check battery")
        assertNotNull(captured)
        assertEquals(WorkflowOrigin.CAPTURED, captured!!.origin)
        assertFalse("captured drafts start disabled", captured.enabled)
        assertEquals(1, captured.steps.size)
    }

    @Test fun failedTaskCannotBeCaptured() {
        val store = InMemoryToolTaskStore()
        val tasks = ToolTaskLedger(store, now)
        val l = WorkflowLedger(store, now)
        val group = tasks.admit(listOf(ActionRequest("read_battery")), "chat-1")
        val attempt = tasks.get(group.attemptIds.single())!!
        val running = tasks.claim(attempt.id, attempt.generation)!!
        tasks.finish(running, ExecutionResult(false, "boom"))
        assertNull(l.captureFromTask(group.id, "Check battery"))
    }

    // -- Restart recovery (T13) -----------------------------------------------------

    @Test fun restartNeverReplaysTriggers() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val future = l.scheduleOccurrence(saved.id, 0, nowMs + 600_000, nowMs + 600_000, "boot-1")!!
        val running = l.scheduleOccurrence(saved.id, 0, nowMs - 5_000, nowMs - 5_000, "boot-2")!!
        l.claimDueOccurrence(running.id)
        val recovered = l.recoverAfterRestart()
        assertEquals(WorkflowOccurrenceState.SCHEDULED,
            recovered.find { it.id == future.id }!!.state)
        assertEquals("interrupted runs fail, never re-fire",
            WorkflowOccurrenceState.FAILED, recovered.find { it.id == running.id }!!.state)
        assertNull("dedup survives the restart",
            l.scheduleOccurrence(saved.id, 0, nowMs + 600_000, nowMs + 600_000, "boot-1"))
    }

    // -- Scheduling policy (T13) ------------------------------------------------------

    @Test fun dailyTriggerFiresTomorrowAfterItsTime() {
        val zone = ZoneId.of("America/New_York")
        // 2026-10-04 22:00 EDT; daily 21:00 already passed → next is 2026-10-05 21:00 EDT.
        val at = java.time.ZonedDateTime.of(2026, 10, 4, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val next = WorkflowScheduling.nextDailyFire(WorkflowTrigger.Daily(21, 0), at, zone)
        val zoned = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(5, zoned.dayOfMonth)
        assertEquals(21, zoned.hour)
    }

    @Test fun dailyTriggerSurvivesSpringForward() {
        val zone = ZoneId.of("America/New_York")
        // 2026-03-08 02:00→03:00: 02:30 does not exist; the wall-clock
        // intent resolves forward instead of firing twice or never.
        val before = java.time.ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val next = WorkflowScheduling.nextDailyFire(WorkflowTrigger.Daily(2, 30), before, zone)
        val zoned = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(8, zoned.dayOfMonth)
        assertTrue("nonexistent 02:30 resolves forward", zoned.hour >= 3)
        // And the following day is a normal 02:30 again.
        val nextDay = WorkflowScheduling.nextDailyFire(WorkflowTrigger.Daily(2, 30),
            zoned.toInstant().toEpochMilli() + 1, zone)
        val zoned2 = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextDay), zone)
        assertEquals(9, zoned2.dayOfMonth)
        assertEquals(2, zoned2.hour)
        assertEquals(30, zoned2.minute)
    }

    @Test fun exactAlarmHonesty() {
        val exact = WorkflowScheduling.alarmSchedule(true)
        assertEquals(WorkflowScheduling.AlarmMode.EXACT, exact.mode)
        assertNull(exact.honestNote)
        val fallback = WorkflowScheduling.alarmSchedule(false)
        assertEquals(WorkflowScheduling.AlarmMode.INEXACT_FALLBACK, fallback.mode)
        assertNotNull("fallback must say so honestly", fallback.honestNote)
    }

    @Test fun dedupKeysAreStablePerSlot() {
        val trigger = WorkflowTrigger.Daily(21, 0)
        val a = WorkflowScheduling.dedupKey("w", 0, trigger, 1_700_000_000_000L)
        val b = WorkflowScheduling.dedupKey("w", 0, trigger, 1_700_000_000_000L)
        assertEquals(a, b)
        assertNotEquals(a, WorkflowScheduling.dedupKey("w", 1, trigger, 1_700_000_000_000L))
    }

    @Test fun coalesceMissedKeepsOnlyTheLatest() {
        fun occurrence(id: String, at: Long) = WorkflowOccurrence(id, "w", 1, 0, "k-$id",
            at, at, WorkflowOccurrenceState.SCHEDULED, emptyList(), at, at)
        val (keep, skipped) = WorkflowScheduling.coalesceMissed(listOf(
            occurrence("a", 1000), occurrence("b", 2000), occurrence("c", 3000)))
        assertEquals(listOf("c"), keep.map { it.id })
        assertEquals(setOf("a", "b"), skipped.map { it.id }.toSet())
    }

    @Test fun timezoneChangeRecomputesDailyOccurrences() {
        val zone = ZoneId.of("America/New_York")
        val definition = definition(triggers = listOf(WorkflowTrigger.Daily(21, 0)))
        val at = java.time.ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val occurrence = WorkflowOccurrence(uid(), definition.id, 1, 0, "k",
            WorkflowScheduling.nextDailyFire(WorkflowTrigger.Daily(21, 0), at, zone),
            0, WorkflowOccurrenceState.SCHEDULED, emptyList(), at, at)
        val fixed = occurrence.copy(windowEndMs = occurrence.scheduledForMs)
        val recomputed = WorkflowScheduling.rescheduleForZoneChange(
            listOf(fixed), listOf(definition), at, zone)
        assertEquals(1, recomputed.size)
        val zoned = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(recomputed.single().scheduledForMs), zone)
        assertEquals(21, zoned.hour)
        assertEquals(0, zoned.minute)
    }

    // -- Missed-run evaluation (T14) ---------------------------------------------------

    private fun missedDefinition() = definition(triggers = listOf(WorkflowTrigger.Reminder(nowMs - 3_600_000)))

    private fun missedOccurrence(workflowId: String) = WorkflowOccurrence(uid(), workflowId, 1, 0, "k",
        nowMs - 3_600_000, nowMs - 3_600_000, WorkflowOccurrenceState.SCHEDULED, emptyList(),
        nowMs - 3_600_000, nowMs - 3_600_000)

    @Test fun missedIrrelevantWhenTriggerNoLongerValid() {
        val definition = missedDefinition()
        val decision = WorkflowScheduling.evaluateMissedRun(missedOccurrence(definition.id), definition,
            WorkflowScheduling.MissedRunCircumstances(false, true, 3_600_000, "the deadline passed"))
        assertTrue(decision is MissedRunDecision.Irrelevant)
        assertTrue(decision.receipt.contains("no longer relevant"))
    }

    @Test fun missedRelevantWhenStillFresh() {
        val definition = missedDefinition()
        val occurrence = missedOccurrence(definition.id).copy(scheduledForMs = nowMs - 5 * 60_000,
            windowEndMs = nowMs - 5 * 60_000)
        val decision = WorkflowScheduling.evaluateMissedRun(occurrence, definition,
            WorkflowScheduling.MissedRunCircumstances(true, true, 5 * 60_000))
        assertTrue(decision is MissedRunDecision.Relevant)
    }

    @Test fun missedUncertainWhenStaleAndNobodyAround() {
        val definition = missedDefinition()
        val decision = WorkflowScheduling.evaluateMissedRun(missedOccurrence(definition.id), definition,
            WorkflowScheduling.MissedRunCircumstances(true, false, 2 * 3_600_000))
        assertTrue(decision is MissedRunDecision.Uncertain)
        assertTrue((decision as MissedRunDecision.Uncertain).question.isNotBlank())
    }

    @Test fun missedRecordedWithReceipt() {
        val l = ledger()
        val saved = l.saveDraft(missedDefinition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 3_600_000, nowMs - 3_600_000, "miss-1")
        // scheduleOccurrence allows past times; the policy evaluates them.
        assertNotNull(occurrence)
        assertTrue(l.recordMissedEvaluation(occurrence!!.id,
            MissedRunDecision.Irrelevant("the moment has passed")))
        assertEquals(WorkflowOccurrenceState.MISSED, l.occurrence(occurrence.id)!!.state)
        val receipts = l.receiptsFor(saved.id)
        assertTrue(receipts.any { it.kind == WorkflowReceiptKind.MISSED_IRRELEVANT })
    }

    // -- Binding placeholder scanner ------------------------------------------

    @Test fun placeholderScannerFindsValidPlaceholdersOnly() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val found = findBindingPlaceholders("vol=\${$id.message} and \${$id.battery_percent}!")
        assertEquals(2, found.size)
        assertEquals(id, found[0].stepId)
        assertEquals("message", found[0].outputName)
        assertEquals("\${$id.message}", found[0].text)
        assertEquals("battery_percent", found[1].outputName)
        // Too short an id, a bad output name, and a missing brace never match.
        assertTrue(findBindingPlaceholders("x=\${abc.message}").isEmpty())
        assertTrue(findBindingPlaceholders("x=\${$id.9bad}").isEmpty())
        assertTrue(findBindingPlaceholders("x=\${$id.message").isEmpty())
        assertTrue(findBindingPlaceholders("no placeholders here").isEmpty())
        // Substitution replaces every occurrence.
        val subbed = substituteBindingPlaceholders("a=\${$id.message},b=\${$id.message}") { _, _, _ -> "Q" }
        assertEquals("a=Q,b=Q", subbed)
    }

    // -- Engine --------------------------------------------------------------------------

    private fun scripted(vararg results: ExecutionResult): (ActionRequest) -> ExecutionResult {
        val queue = ArrayDeque(results.toList())
        return { queue.removeFirst() }
    }

    private fun ok(message: String = "ok") = ExecutionResult(true, message)

    @Test fun engineRunsToolStepsAndCompletes() {
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine(now).run(definition(),
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertTrue((outcome as WorkflowRunOutcome.Completed).succeeded)
        assertEquals(listOf("read_battery", "set_volume"), dispatched)
    }

    @Test fun engineEvaluatesConditionsInCode() {
        val batteryId = uid()
        val branchId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(branchId,
                WorkflowCondition.LessThan(WorkflowBinding(batteryId, "battery_percent"), 20.0),
                listOf(volumeStep("10")),
                listOf(volumeStep("50")))))
        val low = WorkflowEngine(now).run(def,
            dispatch = scripted(ExecutionResult.battery(10), ok()))
        assertTrue(low is WorkflowRunOutcome.Completed)
        val high = WorkflowEngine(now).run(def,
            dispatch = scripted(ExecutionResult.battery(90), ok()))
        assertTrue(high is WorkflowRunOutcome.Completed)
        // Battery percent flowed through the typed binding into the branch.
    }

    @Test fun engineBindsTypedOutputsIntoLaterArguments() {
        val batteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Tool(uid(), ActionRequest("open_app", mapOf("app" to "Battery \${$batteryId.battery_percent}%")))))
        val seen = mutableListOf<ActionRequest>()
        WorkflowEngine(now).run(def, dispatch = { request ->
            seen += request; if (request.name == "read_battery") ExecutionResult.battery(42) else ok()
        })
        assertEquals("Battery 42%", seen.last().arguments["app"])
    }

    @Test fun engineStopsOnFirstFailureWithoutRetry() {
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine(now).run(definition(steps = listOf(
            batteryStep(), volumeStep("20"), volumeStep("30"))),
            dispatch = { request ->
                dispatched += request.name
                if (request.name == "set_volume" && request.arguments["level"] == "20") ExecutionResult(false, "denied")
                else ok()
            })
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        assertEquals("failed step is not retried and later steps never run",
            listOf("read_battery", "set_volume"), dispatched)
        assertTrue((outcome as WorkflowRunOutcome.Failed).reason.contains("denied"))
    }

    @Test fun engineNeverRepeatsUnknownOutcomes() {
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine(now).run(definition(),
            dispatch = { request ->
                dispatched += request.name
                ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION, "maybe happened")
            })
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        assertEquals(1, dispatched.size)
        assertTrue((outcome as WorkflowRunOutcome.Failed).reason.contains("not repeated"))
    }

    @Test fun unconfirmedLaunchNeverAdvancesDependentWorkflowSteps() {
        // A launch submitted without foreground evidence is unknown
        // completion: the run stops, the launch is dispatched exactly once
        // (no retry of the uncertain launch), and the step that depends on
        // the destination having opened never executes.
        val dispatched = mutableListOf<String>()
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_app", mapOf("app" to "Settings"))),
            batteryStep()))
        val outcome = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request.name
                if (request.name == "open_app") verifiedLaunchReceipt(
                    "Settings", BackgroundLaunchRoute.DIRECT,
                    platformError = null, foregroundObserved = false
                ) else ok()
            })
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        assertEquals(
            "the uncertain launch is not repeated and the dependent step never runs",
            listOf("open_app"), dispatched
        )
        assertTrue((outcome as WorkflowRunOutcome.Failed).reason.contains("not repeated"))
    }

    @Test fun engineSuspendsOnWaitAndResumesWithoutRerun() {
        val waitId = uid()
        val def = definition(steps = listOf(batteryStep(), WorkflowStep.Wait(waitId, WorkflowWait.Timer(60_000)),
            volumeStep("20")))
        val dispatched = mutableListOf<String>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val path = (suspended as WorkflowRunOutcome.Suspended).resumePath
        assertEquals(listOf(1), path)
        assertEquals(listOf("read_battery"), dispatched)
        // Resume: the satisfied wait is skipped, earlier steps are not re-run.
        val resumed = WorkflowEngine(now).run(def, startPath = path,
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(resumed is WorkflowRunOutcome.Completed)
        assertEquals(listOf("read_battery", "set_volume"), dispatched)
    }

    // -- Timer continuation resume (finding 4) ---------------------------------------

    @Test fun timerResumeClaimIsArmedIdempotentAndDueOnly() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "resume-1")!!
        l.claimDueOccurrence(occurrence.id)
        val path = listOf(1)
        // Armed one minute out: not claimable early.
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, path,
            "Waiting: a timer", resumeAtMs = nowMs + 60_000))
        assertNull("a timer resume must not be claimable early",
            l.claimResumeOccurrence(occurrence.id))
        nowMs += 61_000
        val resumed = l.claimResumeOccurrence(occurrence.id)
        assertNotNull(resumed)
        assertEquals(WorkflowOccurrenceState.RUNNING, resumed!!.state)
        assertEquals(path, resumed.resumePath)
        assertNull("a second resume claim must not re-fire", l.claimResumeOccurrence(occurrence.id))
    }

    @Test fun eventWaitsAreNeverClaimedByATimerResume() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "resume-2")!!
        l.claimDueOccurrence(occurrence.id)
        // Event wait: no resume time armed.
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, listOf(1),
            "Waiting: a notification"))
        nowMs += 3_600_000
        assertNull("an event wait must never be alarm-claimed",
            l.claimResumeOccurrence(occurrence.id))
    }

    @Test fun suspendedRunResumesFromSavedProgressWithoutRerun() {
        // Production-path regression test for finding 4: a suspend persists
        // the engine's progress, and the resume continues from the saved
        // position without re-running completed steps.
        val waitId = uid()
        val batteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Wait(waitId, WorkflowWait.Timer(60_000)),
            volumeStep("20")))
        val l = ledger()
        val saved = l.saveDraft(def)
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "resume-3")!!
        val claimed = l.claimDueOccurrence(occurrence.id)!!
        val dispatched = mutableListOf<String>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request.name
                if (request.name == "read_battery") ExecutionResult.battery(42) else ok()
            })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val progress = suspended as WorkflowRunOutcome.Suspended
        assertEquals(listOf(batteryId), progress.completedStepIds)
        assertEquals("42", progress.results[batteryId]?.get("battery_percent"))
        // Persist the suspend exactly like the coordinator does.
        assertTrue(l.markWaiting(claimed.id, WorkflowOccurrenceState.WAITING_EVENT,
            progress.resumePath, "Waiting: a timer",
            resumeAtMs = nowMs + 60_000,
            completedStepIds = progress.completedStepIds,
            stepResults = progress.results))
        nowMs += 61_000
        val resumed = l.claimResumeOccurrence(claimed.id)!!
        // Resume from the saved position with the saved progress.
        val outcome = WorkflowEngine(now).run(def,
            startPath = resumed.resumePath,
            skipStepIds = resumed.completedStepIds.toSet(),
            initialResults = resumed.stepResults,
            initialCompleted = resumed.completedStepIds.toSet(),
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals("completed steps must not re-run",
            listOf("read_battery", "set_volume"), dispatched)
    }

    @Test fun timerResumeProgressSurvivesFileRoundTrip() = withFile { file ->
        val l = WorkflowLedger(FileToolTaskStore(file), now)
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "rt-resume")!!
        l.claimDueOccurrence(occurrence.id)
        val stepId = uid()
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, listOf(2),
            "Waiting: a timer", resumeAtMs = nowMs + 60_000,
            completedStepIds = listOf(stepId),
            stepResults = mapOf(stepId to mapOf("message" to "done"))))
        val reopened = WorkflowLedger(FileToolTaskStore(file), now)
        val loaded = reopened.occurrence(occurrence.id)!!
        assertEquals(WorkflowOccurrenceState.WAITING_EVENT, loaded.state)
        assertEquals(nowMs + 60_000, loaded.resumeAtMs)
        assertEquals(listOf(stepId), loaded.completedStepIds)
        assertEquals("done", loaded.stepResults[stepId]?.get("message"))
        // And the resume is still claimable after the round trip.
        nowMs += 61_000
        assertNotNull(reopened.claimResumeOccurrence(occurrence.id))
    }

    @Test fun pastDueTimerResumeIsReportedMissed() {
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "resume-4")!!
        l.claimDueOccurrence(occurrence.id)
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, listOf(1),
            "Waiting: a timer", resumeAtMs = nowMs + 60_000))
        nowMs += 61_000
        assertTrue(l.recordMissedEvaluation(occurrence.id,
            MissedRunDecision.Irrelevant("The timer resume passed while the phone was off.")))
        assertEquals(WorkflowOccurrenceState.MISSED, l.occurrence(occurrence.id)!!.state)
        assertNull("a missed resume is terminal and never claimable",
            l.claimResumeOccurrence(occurrence.id))
    }

    @Test fun engineAsksForApprovalOnScreenStepsAndNeverDispatchesThem() {
        val def = definition(steps = listOf(batteryStep(),
            WorkflowStep.Tool(uid(), ActionRequest("screen_tap",
                mapOf("target" to "n1", "token" to "abcdef0123456789")))))
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine(now).run(def,
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(outcome is WorkflowRunOutcome.NeedsApproval)
        assertEquals(listOf("read_battery"), dispatched)
    }

    @Test fun adaptiveStepRetriesWithinBudgetThenAsks() {
        val def = definition(steps = listOf(WorkflowStep.Adaptive(uid(), "lower the volume",
            listOf(ActionRequest("set_volume", mapOf("level" to "10")),
                ActionRequest("set_volume", mapOf("level" to "0"))),
            EffortBudget(maxAttempts = 4, maxWallMs = 60_000, noProgressLimit = 2))))
        val dispatched = mutableListOf<String>()
        val outcome = WorkflowEngine(now).run(def,
            dispatch = { request -> dispatched += request.name; ExecutionResult(false, "denied") })
        assertTrue("budget exhaustion asks the user" , outcome is WorkflowRunOutcome.NeedsUser)
        val asked = outcome as WorkflowRunOutcome.NeedsUser
        assertTrue(asked.question.contains("lower the volume"))
        assertTrue(dispatched.size <= 4)
    }

    @Test fun adaptiveStepSucceedsOnAlternative() {
        val def = definition(steps = listOf(WorkflowStep.Adaptive(uid(), "lower the volume",
            listOf(ActionRequest("set_volume", mapOf("level" to "10")),
                ActionRequest("set_volume", mapOf("level" to "0"))),
            EffortBudget(maxAttempts = 4, maxWallMs = 60_000, noProgressLimit = 3))))
        val outcome = WorkflowEngine(now).run(def,
            dispatch = scripted(ExecutionResult(false, "denied"), ok()))
        assertTrue(outcome is WorkflowRunOutcome.Completed)
    }

    @Test fun engineEnforcesMaxStepsPerRun() {
        val steps = (1..10).map { volumeStep(it.toString()) }
        val outcome = WorkflowEngine(now).run(
            definition(steps = steps).copy(maxStepsPerRun = 5),
            dispatch = { ok() })
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        assertTrue((outcome as WorkflowRunOutcome.Failed).reason.contains("effort budget"))
    }

    // -- Settings projection ---------------------------------------------------------------

    @Test fun settingsProjectionListsWorkflowsAndTools() {
        val store = InMemoryToolTaskStore()
        val tasks = ToolTaskLedger(store, now)
        val l = WorkflowLedger(store, now)
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        tasks.recordSourceGrant(ActionRequest("read_battery"))
        tasks.recordSourceDenial("screen")
        val projection = WorkflowSettingsProjection.from(store.readJournal(), nowMs)
        assertEquals(1, projection.workflows.size)
        val row = projection.workflows.single()
        assertEquals("Evening wind-down", row.name)
        assertTrue(row.enabled)
        assertTrue(row.triggersSummary.contains("Manual"))
        val phone = projection.tools.single { it.family == "phone" }
        assertEquals("granted", phone.state)
        val screen = projection.tools.single { it.family == "screen" }
        assertEquals("denied", screen.state)
        val web = projection.tools.single { it.family == "web" }
        assertEquals("not yet asked", web.state)
    }

    // -- Schema-3 file persistence ------------------------------------------------------------

    private fun withFile(test: (java.io.File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("m2-workflows").toFile()
        try { test(java.io.File(directory, "workflows.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun workflowFileRoundTrip() = withFile { file ->
            val store = FileToolTaskStore(file)
            val l = WorkflowLedger(store, now)
            val def = definition(triggers = listOf(WorkflowTrigger.Daily(21, 30),
                WorkflowTrigger.Deadline(nowMs + 86_400_000, "Dentist")))
            val saved = l.saveDraft(def)
            l.enable(saved.id)
            val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs + 3_600_000, nowMs + 3_600_000, "rt-1")!!
            val grant = l.createGrant(saved.id, listOf(ActionRequest("read_battery")))
            // Reopen: everything survives.
            val reopened = WorkflowLedger(FileToolTaskStore(file), now)
            assertEquals(1, reopened.current(saved.id)!!.version)
            assertEquals(2, reopened.current(saved.id)!!.triggers.size)
            assertEquals(WorkflowOccurrenceState.SCHEDULED, reopened.occurrence(occurrence.id)!!.state)
            assertEquals(grant.id, reopened.reusableGrant(saved.id, listOf(ActionRequest("read_battery")))!!.id)
            assertTrue(reopened.receiptsFor(saved.id).isNotEmpty())
    }

    @Test fun tamperedWorkflowIsRefusedByTheFileStore() = withFile { file ->
            val l = WorkflowLedger(FileToolTaskStore(file), now)
            l.saveDraft(definition())
            // Tamper: rewrite the file with an invalid step tool.
            val raw = file.readText()
            val tampered = raw.replace("read_battery", "send_nukes")
            file.writeText(tampered)
            try {
                FileToolTaskStore(file).readJournal()
                fail("tampered workflow must be refused")
            } catch (_: ToolTaskStorageException) { }
    }

    // -- Nested timer waits (repair round 3, finding F4) ---------------------------

    @Test fun nestedTimerInThenBranchResumesToCompletion() {
        // Jerry's minimal repro: a Wait nested inside a Branch's then-list
        // must resume to completion. The resume path tail ([0] of [1,0])
        // must survive the descent through the branch instead of being
        // dropped, which previously re-suspended the nested wait forever.
        val batteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 0.0),
                thenSteps = listOf(
                    WorkflowStep.Wait(uid(), WorkflowWait.Timer(60_000)),
                    volumeStep("20")))))
        val l = ledger()
        val saved = l.saveDraft(def)
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "nest-1")!!
        val claimed = l.claimDueOccurrence(occurrence.id)!!
        val dispatched = mutableListOf<String>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request.name
                if (request.name == "read_battery") ExecutionResult.battery(42) else ok()
            })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val progress = suspended as WorkflowRunOutcome.Suspended
        assertEquals(listOf(1, 0), progress.resumePath)
        assertEquals(listOf("read_battery"), dispatched)
        // Persist the suspend exactly like the coordinator does.
        assertTrue(l.markWaiting(claimed.id, WorkflowOccurrenceState.WAITING_EVENT,
            progress.resumePath, "Waiting: a timer",
            resumeAtMs = nowMs + 60_000,
            completedStepIds = progress.completedStepIds,
            stepResults = progress.results))
        nowMs += 61_000
        val resumed = l.claimResumeOccurrence(claimed.id)!!
        // The nested wait is the leaf target, so it is already satisfied and
        // the run continues past it without re-running completed steps.
        val outcome = WorkflowEngine(now).run(def,
            startPath = resumed.resumePath,
            skipStepIds = resumed.completedStepIds.toSet(),
            initialResults = resumed.stepResults,
            initialCompleted = resumed.completedStepIds.toSet(),
            dispatch = { request -> dispatched += request.name; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals("battery must not re-dispatch after a nested resume",
            listOf("read_battery", "set_volume"), dispatched)
    }

    @Test fun nestedTimerInElseBranchTakesElsePath() {
        // The nested wait keeps its position in the else-list, and the
        // resume takes the same else path the original run chose.
        val batteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 0.0),
                thenSteps = listOf(volumeStep("20")),
                elseSteps = listOf(
                    WorkflowStep.Wait(uid(), WorkflowWait.Timer(60_000)),
                    volumeStep("10")))))
        val dispatched = mutableListOf<ActionRequest>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request
                if (request.name == "read_battery") ExecutionResult.battery(0) else ok()
            })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val progress = suspended as WorkflowRunOutcome.Suspended
        assertEquals("the else-branch wait sits at nested index 0",
            listOf(1, 0), progress.resumePath)
        val outcome = WorkflowEngine(now).run(def,
            startPath = progress.resumePath,
            skipStepIds = progress.completedStepIds.toSet(),
            initialResults = progress.results,
            initialCompleted = progress.completedStepIds.toSet(),
            dispatch = { request -> dispatched += request; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals(listOf("read_battery", "set_volume"), dispatched.map { it.name })
        assertEquals("the else branch ran, not the then branch",
            "10", dispatched.last().arguments["level"])
    }

    @Test fun deeplyNestedUntilTimeResumes() {
        // A wait two branches deep: the resume path tail must survive two
        // descents. The leading battery step exists because branch
        // conditions must bind an earlier step (definition validation), so
        // the deep wait sits at [1,1,0]; both battery steps complete before
        // the wait and neither re-runs on resume.
        val batteryId = uid()
        val innerBatteryId = uid()
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 0.0),
                thenSteps = listOf(
                    batteryStep(innerBatteryId),
                    WorkflowStep.Branch(uid(),
                        WorkflowCondition.GreaterThan(
                            // Branch conditions bind steps visible to the
                            // validator (outer scope); the inner battery step
                            // still runs, but only earlier-scope steps bind.
                            WorkflowBinding(batteryId, "battery_percent"), 0.0),
                        thenSteps = listOf(
                            WorkflowStep.Wait(uid(), WorkflowWait.UntilTime(nowMs + 60_000)),
                            volumeStep("7")))))))
        val l = ledger()
        val saved = l.saveDraft(def)
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "nest-deep")!!
        val claimed = l.claimDueOccurrence(occurrence.id)!!
        val dispatched = mutableListOf<ActionRequest>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request
                if (request.name == "read_battery") ExecutionResult.battery(42) else ok()
            })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val progress = suspended as WorkflowRunOutcome.Suspended
        assertEquals(listOf(1, 1, 0), progress.resumePath)
        assertEquals(listOf("read_battery", "read_battery"), dispatched.map { it.name })
        assertTrue(l.markWaiting(claimed.id, WorkflowOccurrenceState.WAITING_EVENT,
            progress.resumePath, "Waiting: a scheduled time",
            resumeAtMs = nowMs + 60_000,
            completedStepIds = progress.completedStepIds,
            stepResults = progress.results))
        nowMs += 61_000
        val resumed = l.claimResumeOccurrence(claimed.id)!!
        val outcome = WorkflowEngine(now).run(def,
            startPath = resumed.resumePath,
            skipStepIds = resumed.completedStepIds.toSet(),
            initialResults = resumed.stepResults,
            initialCompleted = resumed.completedStepIds.toSet(),
            dispatch = { request -> dispatched += request; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals(listOf("read_battery", "read_battery", "set_volume"),
            dispatched.map { it.name })
        assertEquals("7", dispatched.last().arguments["level"])
    }

    @Test fun nestedResumePreservesPreWaitBindings() {
        // A post-wait step bound to a pre-wait output must resolve from the
        // saved results, not from a re-dispatch of the earlier step.
        val batteryId = uid()
        val boundVolume = WorkflowStep.Tool(uid(),
            ActionRequest("set_volume", mapOf("level" to "0")),
            bindings = mapOf("level" to WorkflowBinding(batteryId, "battery_percent")))
        val def = definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 0.0),
                thenSteps = listOf(
                    WorkflowStep.Wait(uid(), WorkflowWait.Timer(60_000)),
                    boundVolume))))
        val dispatched = mutableListOf<ActionRequest>()
        val suspended = WorkflowEngine(now).run(def,
            dispatch = { request ->
                dispatched += request
                if (request.name == "read_battery") ExecutionResult.battery(42) else ok()
            })
        assertTrue(suspended is WorkflowRunOutcome.Suspended)
        val progress = suspended as WorkflowRunOutcome.Suspended
        assertEquals(listOf(1, 0), progress.resumePath)
        val outcome = WorkflowEngine(now).run(def,
            startPath = progress.resumePath,
            skipStepIds = progress.completedStepIds.toSet(),
            initialResults = progress.results,
            initialCompleted = progress.completedStepIds.toSet(),
            dispatch = { request -> dispatched += request; ok() })
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals("battery must not re-dispatch after a nested resume",
            listOf("read_battery", "set_volume"), dispatched.map { it.name })
        assertEquals("the level came from the saved pre-wait results",
            "42", dispatched.last().arguments["level"])
    }

    @Test fun duplicateAlarmAfterNestedResumeDoesNothing() {
        // A redelivered alarm for an already-claimed nested resume finds
        // the occurrence RUNNING and does nothing: no double run.
        val l = ledger()
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "nest-dup")!!
        l.claimDueOccurrence(occurrence.id)
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, listOf(1, 0),
            "Waiting: a timer", resumeAtMs = nowMs + 60_000))
        nowMs += 61_000
        val resumed = l.claimResumeOccurrence(occurrence.id)
        assertNotNull(resumed)
        assertEquals(WorkflowOccurrenceState.RUNNING, resumed!!.state)
        assertNull("a redelivered alarm must not re-fire a claimed resume",
            l.claimResumeOccurrence(occurrence.id))
    }

    @Test fun alarmRunnerCompletionFollowsTerminalCheckpoint() {
        // Wiring regression test: the runner returns only after the
        // terminal checkpoint completes, so done() - the receiver's
        // PendingResult finish - can never fire while the run is detached.
        val occurrence = WorkflowOccurrence(uid(), "w", 1, 0, "k-alarm",
            scheduledForMs = nowMs, windowEndMs = nowMs,
            state = WorkflowOccurrenceState.RUNNING,
            createdAtMs = nowMs, updatedAtMs = nowMs)
        val gate = java.util.concurrent.CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val runner = WorkflowAlarmRunner(
            claimDue = { occurrence },
            claimResume = { null },
            runOccurrence = { _, _ ->
                gate.await() // block the terminal checkpoint until released
                events += "checkpoint"
            })
        val thread = Thread { try { runner.onAlarm(occurrence.id) } finally { events += "done" } }
        thread.start()
        Thread.sleep(300)
        assertFalse("done() must not fire while the terminal checkpoint is blocked",
            events.contains("done"))
        gate.countDown()
        thread.join(5_000)
        assertEquals(listOf("checkpoint", "done"), events)
    }

    @Test fun alarmRunnerCoversResumeAndDuplicateAlarm() {
        // A resume alarm runs with resume = true; an already-claimed alarm
        // runs nothing and never throws.
        val occurrence = WorkflowOccurrence(uid(), "w", 1, 0, "k-alarm-resume",
            scheduledForMs = nowMs, windowEndMs = nowMs,
            state = WorkflowOccurrenceState.WAITING_EVENT,
            createdAtMs = nowMs, updatedAtMs = nowMs)
        val runs = mutableListOf<Pair<String, Boolean>>()
        val runner = WorkflowAlarmRunner(
            claimDue = { null },
            claimResume = { occurrence },
            runOccurrence = { claimed, resume -> runs += claimed.id to resume })
        runner.onAlarm(occurrence.id)
        assertEquals(listOf(occurrence.id to true), runs)
        val duplicate = WorkflowAlarmRunner(
            claimDue = { null },
            claimResume = { null },
            runOccurrence = { _, _ -> fail("a duplicate alarm must not run") })
        duplicate.onAlarm(occurrence.id) // must not throw
        assertEquals("only the resume ran", 1, runs.size)
    }

    @Test fun processRecreationRearmsFutureTimerResume() = withFile { file ->
        // A reboot wipes the process but not the file: the schedule
        // receiver's rescheduleFromLedger re-arms exactly this timerResumes()
        // list (the Android-only re-arm loop) and reports past-due ones
        // missed via recordMissedEvaluation - the existing
        // pastDueTimerResumeIsReportedMissed covers the missed policy.
        val l = WorkflowLedger(FileToolTaskStore(file), now)
        val saved = l.saveDraft(definition())
        l.enable(saved.id)
        val occurrence = l.scheduleOccurrence(saved.id, 0, nowMs - 1_000, nowMs - 1_000, "rt-nest")!!
        l.claimDueOccurrence(occurrence.id)
        assertTrue(l.markWaiting(occurrence.id, WorkflowOccurrenceState.WAITING_EVENT, listOf(1, 0),
            "Waiting: a timer", resumeAtMs = nowMs + 60_000,
            completedStepIds = listOf(uid())))
        // "Process death": reopen the ledger from the same file.
        val reopened = WorkflowLedger(FileToolTaskStore(file), now)
        val resumes = reopened.timerResumes()
        assertEquals(listOf(occurrence.id), resumes.map { it.id })
        assertEquals(nowMs + 60_000, resumes.single().resumeAtMs)
        assertNull("not yet due: still not claimable",
            reopened.claimResumeOccurrence(occurrence.id))
        nowMs += 61_000
        assertNotNull("due after the advance: claimable again",
            reopened.claimResumeOccurrence(occurrence.id))
    }
}
