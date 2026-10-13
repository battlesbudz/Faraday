package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DurableToolTaskTest {
    private val battery = ActionRequest("read_battery")
    private val volume = ActionRequest("set_volume", mapOf("level" to "25"))
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("durable-task").toFile()
        try { test(File(directory, "tasks.json")) } finally { directory.deleteRecursively() }
    }
    private fun ToolTaskLedger.attempt(group: ToolTaskGroup, index: Int = 0) = checkNotNull(get(group.attemptIds[index]))

    @Test fun orderedGroupHasOneOwnerAndDurableReceiptsBeforeAdvancing() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val group = ledger.admit(listOf(volume, battery, volume), "conversation")
        assertNull(ledger.claim(group.attemptIds[1], 0))
        var effects = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
            effects++
            val reopened = ToolTaskLedger(FileToolTaskStore(file))
            assertEquals(3, reopened.journal().groups.single().attemptIds.size)
            assertEquals(ToolTaskState.RUNNING, reopened.get(group.attemptIds[effects - 1])?.state)
            ExecutionResult(true, "receipt $effects")
        })
        for (index in 0..2) assertTrue(pipeline.executeAttempt(ledger.attempt(group, index)).succeeded)
        assertFalse(pipeline.executeAttempt(ledger.attempt(group)).succeeded)
        assertEquals(3, effects)
        assertEquals(3, ToolTaskLedger(FileToolTaskStore(file)).snapshot().count { it.state == ToolTaskState.SUCCEEDED })
        assertEquals(3, ledger.journal().events.count { it.kind == ToolTaskEventKind.DISPATCHED })
    }

    @Test fun exactApprovalCannotAuthorizeAnotherTaskOrChangedSchema() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(ledger.store)
        val gate = ActionDispatchGate(approvals, ledger)
        val first = ledger.admit(listOf(volume), "one", ToolAuthority.EXACT_APPROVAL)
        val second = ledger.admit(listOf(volume), "two", ToolAuthority.EXACT_APPROVAL)
        val pending = gate.prepare(ledger.attempt(first))
        assertNull(gate.authorize(pending.copy(task = ledger.attempt(second))))
        assertNull(gate.authorize(pending, schemaVersion = MobileToolCatalog.VERSION + 1))
        assertFalse(checkNotNull(approvals.get(pending.approval.id)).consumed)
        assertNotNull(gate.authorize(pending))
        assertEquals(ApprovalDecision.APPROVED, ActionApprovalStore(FileToolTaskStore(file)).get(pending.approval.id)?.decision)
        assertNull(gate.authorize(pending))
    }

    @Test fun revisedTargetInvalidatesButtonAndRequiresFreshChoice() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val gate = ActionDispatchGate(ActionApprovalStore(ledger.store), ledger)
        val group = ledger.admit(listOf(volume), "conversation", ToolAuthority.EXACT_APPROVAL)
        val old = gate.prepare(ledger.attempt(group))
        val edited = checkNotNull(ledger.revise(old.task.id, old.task.generation,
            ActionRequest("set_volume", mapOf("level" to "30"))))
        assertNull(gate.authorize(old))
        assertNull(ledger.claim(edited.id, edited.generation))
        val fresh = gate.prepare(edited)
        assertTrue(fresh.approval.revision > old.approval.revision)
        assertNotNull(gate.authorize(fresh))
        assertEquals(ApprovalDecision.STALE, ActionApprovalStore(FileToolTaskStore(file)).get(old.approval.id)?.decision)
    }

    @Test fun fingerprintIsFramedAndArgumentsAreFrozen() {
        val mutable = mutableMapOf("app" to "x|package=y")
        val one = ActionApprovalStore().request("task", "step", "native", ActionRequest("open_app", mutable), 1)
        mutable["app"] = "changed"
        val two = ActionApprovalStore().request("task", "step", "native", ActionRequest("open_app", mapOf("app" to "x", "package" to "y")), 1)
        assertNotEquals(one.fingerprint, two.fingerprint)
        assertEquals("x|package=y", one.action.arguments["app"])
        val otherTask = ActionApprovalStore().request("another", "step", "native", one.action, 1)
        assertNotEquals(one.fingerprint, otherTask.fingerprint)
    }

    @Test fun deniedChoiceSurvivesRestartAndCannotDispatch() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(ledger.store)
        val group = ledger.admit(listOf(volume), "conversation", ToolAuthority.EXACT_APPROVAL)
        val pending = ActionDispatchGate(approvals, ledger).prepare(ledger.attempt(group))
        assertEquals(ApprovalDecision.DENIED, approvals.deny(pending.approval.id))
        val reopened = ToolTaskLedger(FileToolTaskStore(file))
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.CANCELLED, reopened.get(pending.task.id)?.state)
        assertEquals(ApprovalDecision.DENIED, ActionApprovalStore(reopened.store).get(pending.approval.id)?.decision)
        assertNull(ActionDispatchGate(ActionApprovalStore(reopened.store), reopened).authorize(pending))
    }

    @Test fun approvalAndDispatchRollbackTogetherIfCommitFails() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val group = ledger.admit(listOf(battery), "conversation", ToolAuthority.EXACT_APPROVAL)
        val pending = ActionDispatchGate(ActionApprovalStore(ledger.store), ledger).prepare(ledger.attempt(group))
        val before = file.readText()
        val failed = ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("disk full") })
        val gate = ActionDispatchGate(ActionApprovalStore(failed.store), failed)
        assertThrows(ToolTaskStorageException::class.java) { gate.authorize(pending) }
        assertEquals(before, file.readText())
        assertFalse(checkNotNull(ActionApprovalStore(ledger.store).get(pending.approval.id)).consumed)
        assertEquals(ToolTaskState.WAITING_APPROVAL, ledger.get(pending.task.id)?.state)
    }

    @Test fun concurrentApprovalClaimsHaveOnlyOneWinner() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val group = ledger.admit(listOf(battery), "conversation", ToolAuthority.EXACT_APPROVAL)
        val pending = ActionDispatchGate(ActionApprovalStore(ledger.store), ledger).prepare(ledger.attempt(group))
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val claims = (1..4).map { pool.submit<ToolTaskAttempt?> {
                start.await()
                val other = ToolTaskLedger(FileToolTaskStore(file))
                ActionDispatchGate(ActionApprovalStore(other.store), other).authorize(pending)
            } }
            start.countDown()
            assertEquals(1, claims.map { it.get() }.count { it != null })
        } finally { pool.shutdownNow() }
    }

    @Test fun revocationPausesDependentStepsAndPreservesOtherWorkAndReceipts() = withFile { file ->
        var now = 10L
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { now }
        val grant = ledger.grant("morning", listOf(volume, battery), 100L)
        val group = ledger.admit(listOf(volume, battery), "conversation", ToolAuthority.ROUTINE, grant.id)
        val independent = ledger.admit(listOf(battery), "conversation")
        val running = checkNotNull(ledger.claim(group.attemptIds[0], 0))
        ledger.finish(running, ExecutionResult(true, "25%"))
        now = 20L
        assertTrue(ledger.revokeGrant(grant.id))
        assertEquals(ToolTaskState.SUCCEEDED, ledger.attempt(group).state)
        assertEquals(ToolTaskState.PAUSED, ledger.attempt(group, 1).state)
        assertNotNull(ledger.claim(independent.attemptIds.single(), 0))
        val reopened = ToolTaskLedger(FileToolTaskStore(file)) { now }
        reopened.recoverAfterRestart()
        assertTrue(reopened.journal().grants.single().revoked)
        assertNull(reopened.claim(group.attemptIds[1], reopened.attempt(group, 1).generation))
    }

    @Test fun expiredGrantAndOutOfScopeActionHaveNoEffects() = withFile { file ->
        var now = 10L
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { now }
        val grant = ledger.grant("routine", listOf(battery), 20L)
        assertThrows(IllegalArgumentException::class.java) { ledger.admit(listOf(volume), "conversation", ToolAuthority.ROUTINE, grant.id) }
        val group = ledger.admit(listOf(battery), "conversation", ToolAuthority.ROUTINE, grant.id)
        now = 21L
        var effects = 0
        assertFalse(JournaledActionPipeline(ledger, MobileActionExecutor { effects++; ExecutionResult(true, "effect") }).executeAttempt(ledger.attempt(group)).succeeded)
        assertEquals(0, effects)
        assertThrows(IllegalArgumentException::class.java) { ledger.grant("unsafe", listOf(ActionRequest("send_message")), 100L) }
    }

    @Test fun restartResumesRelevantStepsAndNeverReplaysCompletedPrefix() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 10L }
        val group = ledger.admit(listOf(volume, battery), "conversation")
        ledger.finish(checkNotNull(ledger.claim(group.attemptIds[0], 0)), ExecutionResult(true, "25%"))
        val reopened = ToolTaskLedger(FileToolTaskStore(file)) { 20L }
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.SUCCEEDED, reopened.attempt(group).state)
        assertEquals(ToolTaskState.READY, reopened.attempt(group, 1).state)
        var effects = 0
        val pipeline = JournaledActionPipeline(reopened, MobileActionExecutor { effects++; ExecutionResult(true, "80%") })
        assertFalse(pipeline.executeAttempt(reopened.attempt(group)).succeeded)
        assertTrue(pipeline.executeAttempt(reopened.attempt(group, 1)).succeeded)
        assertEquals(1, effects)
        assertEquals(reopened.snapshot(), reopened.recoverAfterRestart())
    }

    @Test fun unknownEffectAndExpiredTaskFenceRecoveryAndStaleCompletion() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 10L }
        val group = ledger.admit(listOf(volume, battery), "conversation")
        val running = checkNotNull(ledger.claim(group.attemptIds[0], 0))
        val expired = ledger.admit(listOf(battery), "conversation", validForMs = 1L)
        val reopened = ToolTaskLedger(FileToolTaskStore(file)) { 20L }
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, reopened.attempt(group).state)
        assertEquals(ToolTaskState.PAUSED, reopened.attempt(group, 1).state)
        assertEquals(ToolTaskState.PAUSED, reopened.attempt(expired).state)
        assertNull(reopened.finish(running, ExecutionResult(true, "late")))
        val unknown = reopened.attempt(group)
        assertTrue(reopened.reconcileUnknown(unknown.id, unknown.generation))
        assertNull(reopened.claim(unknown.id, reopened.attempt(group).generation))
        assertEquals(ToolTaskState.PAUSED, reopened.attempt(group, 1).state)
    }

    @Test fun spokenApprovalRequiresQuestionPresentedAgainAfterRestart() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(ledger.store)
        val group = ledger.admit(listOf(battery), "conversation", ToolAuthority.EXACT_APPROVAL)
        val pending = ActionDispatchGate(approvals, ledger).prepare(ledger.attempt(group))
        ledger.recoverAfterRestart()
        val gate = ActionDispatchGate(approvals, ledger)
        assertNull(gate.authorizeSpoken(pending))
        assertEquals(ApprovalDecision.AMBIGUOUS, approvals.spokenYes())
        assertTrue(approvals.presentQuestion(pending.approval.id))
        assertNotNull(gate.authorizeSpoken(pending))
    }

    @Test fun competingQuestionsCannotConsumeSpokenApproval() {
        val ledger = ToolTaskLedger()
        val approvals = ActionApprovalStore(ledger.store)
        val gate = ActionDispatchGate(approvals, ledger)
        val a = gate.prepare(ledger.create(battery))
        gate.prepare(ledger.create(volume))
        assertNull(gate.authorizeSpoken(a))
        assertFalse(checkNotNull(approvals.get(a.approval.id)).consumed)
    }

    @Test fun cancellationIsDurableAndDoesNotUndoRunningEffect() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val group = ledger.admit(listOf(volume, battery), "conversation")
        val running = checkNotNull(ledger.claim(group.attemptIds[0], 0))
        assertTrue(ledger.cancelGroup(group.id))
        assertNotNull(ledger.finish(running, ExecutionResult(true, "25%")))
        val reopened = ToolTaskLedger(FileToolTaskStore(file))
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.SUCCEEDED, reopened.attempt(group).state)
        assertEquals(ToolTaskState.CANCELLED, reopened.attempt(group, 1).state)
        assertNull(reopened.claim(group.attemptIds[1], reopened.attempt(group, 1).generation))
    }

    @Test fun retentionKeepsUnfinishedPrefixUnknownEffectsAndRecentReceipts() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val group = ledger.admit(listOf(volume, battery), "conversation")
        ledger.finish(checkNotNull(ledger.claim(group.attemptIds[0], 0)), ExecutionResult(true, "25%"))
        val unknown = ledger.create(battery, ToolTaskState.UNKNOWN_OUTCOME)
        val template = ledger.create(battery, ToolTaskState.SUCCEEDED)
        ledger.store.update { attempts -> attempts + (1..600).map { template.copy(id = UUID.randomUUID().toString(), stepId = UUID.randomUUID().toString()) } }
        assertTrue(ledger.snapshot().size <= FileToolTaskStore.RETAIN_RECEIPTS)
        assertNotNull(ledger.get(unknown.id))
        assertNotNull(ledger.get(group.attemptIds[0]))
        assertNotNull(ledger.get(group.attemptIds[1]))
        assertNotNull(ledger.create(battery))
    }

    @Test fun schemaOneMigratesWithoutPromotingLegacyWork() = withFile { file ->
        val id = UUID.randomUUID().toString()
        file.writeText("""{"schemaVersion":1,"attempts":[{"id":"$id","generation":0,"state":"RUNNING","request":{"name":"read_battery","arguments":{}},"createdAtMs":1,"updatedAtMs":1,"result":null,"resultOutcome":null}]}""")
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        ledger.recoverAfterRestart()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(id)?.state)
        assertEquals(3, JSONObject(file.readText()).getInt("schemaVersion"))
        assertTrue(ledger.journal().groups.isEmpty())
    }

    @Test fun reportedAppCasePreservesOriginalLiteralAndSubstitutionHasNoEffect() {
        val ledger = ToolTaskLedger()
        val group = ledger.admit(listOf(ActionRequest("open_app", mapOf("app" to "maps"))), "conversation")
        var observed: MobileAction? = null
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor { observed = it; ExecutionResult(true, "opened") })
        assertFalse(pipeline.executeBound(ledger.attempt(group), ActionRequest("open_app", mapOf("app" to "Facebook"))).succeeded)
        assertNull(observed)
        assertTrue(pipeline.executeBound(ledger.attempt(group), ActionRequest("open_app", mapOf("app" to "Maps"))).succeeded)
        assertEquals("maps", (observed as MobileAction.OpenApp).appName)
    }

    @Test fun legacyPausedWorkCanBeCancelledWithoutDismissingUnknownEffects() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val pending = ledger.create(battery)
        val unknown = ledger.create(volume, ToolTaskState.UNKNOWN_OUTCOME)
        ledger.recoverAfterRestart()
        val paused = checkNotNull(ledger.get(pending.id))
        assertEquals(ToolTaskState.PAUSED, paused.state)
        assertTrue(ledger.cancelLegacyAttempt(paused.id, paused.generation))
        assertFalse(ledger.cancelLegacyAttempt(unknown.id, unknown.generation))
        val reopened = ToolTaskLedger(FileToolTaskStore(file))
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.CANCELLED, reopened.get(paused.id)?.state)
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, reopened.get(unknown.id)?.state)
        assertNull(reopened.claim(paused.id, checkNotNull(reopened.get(paused.id)).generation))
    }

    @Test fun freshSpokenChoiceRequiresActualQuestionPresentation() {
        val ledger = ToolTaskLedger()
        val approvals = ActionApprovalStore(ledger.store)
        val gate = ActionDispatchGate(approvals, ledger)
        val pending = gate.prepare(ledger.create(battery))
        assertNull(gate.authorizeSpoken(pending))
        assertEquals(ApprovalDecision.AMBIGUOUS, approvals.spokenYes())
        assertTrue(approvals.presentQuestion(pending.approval.id))
        assertNotNull(gate.authorizeSpoken(pending))
    }
}
