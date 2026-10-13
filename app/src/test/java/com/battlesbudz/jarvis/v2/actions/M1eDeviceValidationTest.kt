package com.battlesbudz.jarvis.v2.actions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * M1e device validation (JVM contract): permission/source-access admission
 * (T08), lock gating with gated owner recognition (T09) and crash/outcome
 * reconciliation with stale-callback rejection (T10).
 */
class M1eDeviceValidationTest {

    private fun pipeline(
        ledger: ToolTaskLedger,
        dispatched: MutableList<String> = mutableListOf(),
        probe: ToolCapabilityProbe? = null,
        locked: Boolean = false
    ): JournaledActionPipeline {
        val executor = MobileActionExecutor { action ->
            dispatched += action.toString()
            ExecutionResult(true, "ok")
        }
        return JournaledActionPipeline(
            ledger, executor,
            sourceAccess = ToolSourceAccess(ledger),
            capabilityProbe = probe,
            lockGate = DeviceLockGate(isLocked = { locked })
        )
    }

    // ---- T08: permission / source-access handling ----

    @Test fun firstSourceAccessIsRememberedAfterSuccessfulDispatch() {
        val ledger = ToolTaskLedger()
        val pipeline = pipeline(ledger)
        val result = pipeline.execute(ActionRequest("read_battery"))
        assertTrue(result.succeeded)
        val record = ledger.journal().sourceAccess.single { it.family == "phone" }
        assertEquals(SourceAccessState.GRANTED, record.state)
        // Family-grained grant (D10): the whole family scope set is remembered.
        assertEquals(ToolSourcePolicy.familyScopes("phone"), record.scopes)
    }

    @Test fun withinFamilyToolsAreAutoExposedUnderApprovedAccess() {
        // D10: a new tool within the family's approved access needs no re-ask.
        val ledger = ToolTaskLedger()
        val dispatched = mutableListOf<String>()
        val pipeline = pipeline(ledger, dispatched)
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertTrue(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25"))).succeeded)
        assertEquals(2, dispatched.size)
        val record = ledger.journal().sourceAccess.single { it.family == "phone" }
        assertTrue(record.scopes.containsAll(setOf("battery.read", "audio.modify")))
    }

    @Test fun denialBlocksDispatchOnEveryAdapterWithHonestReceipt() {
        val ledger = ToolTaskLedger()
        ledger.recordSourceDenial("phone")
        val dispatched = mutableListOf<String>()
        val pipeline = pipeline(ledger, dispatched)
        val denied = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
        assertFalse(denied.succeeded)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        assertTrue("receipt must say access was denied, was: ${denied.message}",
            denied.message.contains("denied", ignoreCase = true))
        assertTrue("no adapter may be touched", dispatched.isEmpty())
        assertTrue("denied attempt must not be journaled", ledger.journal().attempts.isEmpty())
    }

    @Test fun revocationBlocksDispatchAndApprovalClaim() {
        val ledger = ToolTaskLedger()
        val dispatched = mutableListOf<String>()
        val pipeline = pipeline(ledger, dispatched)
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertTrue(ledger.revokeSourceAccess("phone"))
        // Direct path blocked.
        val denied = pipeline.execute(ActionRequest("read_battery"))
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        // Approval path blocked: the claim cannot authorize dispatch.
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        assertNull("revoked family must not claim", ledger.claim(pending.task.id, pending.task.generation,
            approval = pending.approval))
        assertEquals("exactly one real dispatch happened", 1, dispatched.size)
    }

    @Test fun dispatchNeverOverwritesDenialOrRevocation() {
        val ledger = ToolTaskLedger()
        ledger.recordSourceDenial("media")
        // Even a successful dispatch elsewhere cannot resurrect the denial.
        ledger.recordSourceGrant(ActionRequest("media_control", mapOf("action" to "pause")))
        val record = ledger.journal().sourceAccess.single { it.family == "media" }
        assertEquals(SourceAccessState.DENIED, record.state)
    }

    @Test fun newToolCannotBroadenScopeBeyondFamily() {
        // A tampered record claiming another family's scope is rejected at
        // admission; the file store also refuses to persist it.
        val store = InMemoryToolTaskStore()
        store.updateJournal { j ->
            j.copy(sourceAccess = listOf(
                ToolSourceAccessRecord("phone", setOf("screen.control"), SourceAccessState.GRANTED, 0)))
        }
        val ledger = ToolTaskLedger(store)
        val denial = ToolSourceAccess(ledger).denial(ActionRequest("read_battery"))
        assertNotNull("out-of-family scope must not admit", denial)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denial!!.outcome)
    }

    @Test fun fileStoreRefusesBroadenedSourceAccess() {
        val dir = Files.createTempDirectory("m1e-source").toFile()
        try {
            val file = File(dir, "journal.json")
            val store = FileToolTaskStore(file) { f, s -> f.writeText(s) }
            val ledger = ToolTaskLedger(store)
            ledger.recordSourceGrant(ActionRequest("read_battery"))
            // Tamper the persisted JSON: phone claims a screen scope.
            val root = JSONObject(file.readText())
            val records = root.getJSONArray("sourceAccess")
            records.getJSONObject(0).put("scopes", JSONArray(listOf("screen.control")))
            file.writeText(root.toString())
            try {
                store.readJournal()
                fail("broadened source access must fail validation")
            } catch (_: ToolTaskStorageException) { /* expected */ }
        } finally { dir.deleteRecursively() }
    }

    @Test fun sourceAccessSurvivesFileRoundTrip() {
        val dir = Files.createTempDirectory("m1e-source").toFile()
        try {
            val file = File(dir, "journal.json")
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            ledger.recordSourceGrant(ActionRequest("read_battery"))
            ledger.recordSourceDenial("screen")
            val reopened = ToolTaskLedger(FileToolTaskStore(file)).journal().sourceAccess
            assertEquals(SourceAccessState.GRANTED,
                reopened.single { it.family == "phone" }.state)
            assertEquals(SourceAccessState.DENIED,
                reopened.single { it.family == "screen" }.state)
        } finally { dir.deleteRecursively() }
    }

    @Test fun capabilityDenialBlocksDispatchWithoutTouchingAdapter() {
        val ledger = ToolTaskLedger()
        val dispatched = mutableListOf<String>()
        val pipeline = pipeline(ledger, dispatched, probe = ToolCapabilityProbe { _ ->
            "Screen control is not available."
        })
        val denied = pipeline.execute(ActionRequest("screen_observe"))
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun capabilityRevokedBetweenAdmissionAndDispatchStillBlocks() {
        val ledger = ToolTaskLedger()
        val dispatched = mutableListOf<String>()
        var calls = 0
        val pipeline = pipeline(ledger, dispatched, probe = ToolCapabilityProbe { _ ->
            if (++calls == 1) null else "Capability was revoked."
        })
        val denied = pipeline.execute(ActionRequest("read_battery"))
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        assertTrue("adapter must not run", dispatched.isEmpty())
        val attempt = ledger.journal().attempts.single()
        assertEquals("blocked attempt must be terminal", ToolTaskState.FAILED, attempt.state)
    }

    // ---- T09: lock handling ----

    @Test fun unlockedDeviceAllowsEveryFamily() {
        val gate = DeviceLockGate(isLocked = { false })
        listOf("read_battery", "set_volume", "open_app", "media_control", "open_website",
            "open_settings", "navigate", "screen_observe", "screen_tap").forEach { tool ->
            assertEquals("$tool must be allowed when unlocked", LockVerdict.ALLOWED,
                gate.check(ActionRequest(tool)))
        }
    }

    @Test fun lockedDeviceAllowsOnlyNonSensitiveReads() {
        val gate = DeviceLockGate(isLocked = { true })
        assertEquals(LockVerdict.ALLOWED, gate.check(ActionRequest("read_battery")))
        listOf("set_volume", "open_app", "media_control", "open_website", "open_settings",
            "navigate", "screen_observe", "screen_tap", "screen_scroll", "screen_type").forEach { tool ->
            assertEquals("$tool must hand off to unlock", LockVerdict.NEEDS_UNLOCK,
                gate.check(ActionRequest(tool)))
        }
    }

    @Test fun ownerRecognitionIsGatedAndNeverAuthorizes() {
        // T09 plan note: until verified, gate this mode and use unlock
        // handoff; an untested voice match is never secure authorization.
        val gate = DeviceLockGate(isLocked = { true })
        assertEquals(OwnerRecognitionMode.GATED, gate.ownerRecognition)
        // Even with a claimed voice match there is no authorization path:
        // the gate has no input for it and still hands off to unlock.
        assertEquals(LockVerdict.NEEDS_UNLOCK, gate.check(ActionRequest("open_app", mapOf("app" to "Maps"))))
        assertFalse("unlock handoff must never describe a voice match as authorization",
            gate.unlockMessage(ActionRequest("open_app")).contains("voice", ignoreCase = true))
    }

    @Test fun lockedSensitiveDispatchReturnsNeedsUnlockWithoutEffect() {
        val ledger = ToolTaskLedger()
        val dispatched = mutableListOf<String>()
        val pipeline = pipeline(ledger, dispatched, locked = true)
        val battery = pipeline.execute(ActionRequest("read_battery"))
        assertTrue("non-sensitive read continues while locked", battery.succeeded)
        val tap = pipeline.execute(
            ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "0123456789abcdef")))
        assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, tap.outcome)
        assertTrue(tap.message.contains("locked", ignoreCase = true))
        val volume = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
        assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, volume.outcome)
        assertEquals("only the battery read dispatched", 1, dispatched.size)
        val states = ledger.journal().attempts.map { it.state }.toSet()
        assertTrue("needs-unlock attempts are terminal", states.all { it.isTerminal() })
    }

    // ---- T10: crash / outcome reconciliation ----

    @Test fun staleCallbackCannotPublishCompletion() {
        val ledger = ToolTaskLedger()
        val task = ledger.create(ActionRequest("read_battery"))
        val running = checkNotNull(ledger.transition(task.id, task.generation, ToolTaskState.RUNNING))
        // A newer generation already terminal: the stale callback is rejected.
        checkNotNull(ledger.transition(running.id, running.generation, ToolTaskState.SUCCEEDED, "done",
            ExecutionResult.Outcome.SUCCEEDED))
        assertNull("stale callback must be rejected",
            ledger.finish(running, ExecutionResult(true, "late arrival")))
        assertEquals(ToolTaskState.SUCCEEDED, ledger.get(task.id)?.state)
        // A callback for a non-running attempt is rejected too.
        assertNull(ledger.finish(checkNotNull(ledger.get(task.id)), ExecutionResult(true, "again")))
    }

    @Test fun crashBeforeDispatchReconcilesWithoutRepeat() {
        val dir = Files.createTempDirectory("m1e-crash").toFile()
        try {
            val file = File(dir, "journal.json")
            var ledger = ToolTaskLedger(FileToolTaskStore(file))
            val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread")
            val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
            val claimed = checkNotNull(ledger.claim(attempt.id, attempt.generation))
            assertEquals(ToolTaskState.RUNNING, claimed.state)
            // Process dies before dispatch. A new ledger over the same file recovers.
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recovered = ledger.recoverAfterRestart().single { it.id == claimed.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recovered.state)
            // The pre-crash callback is stale and rejected.
            assertNull(ledger.finish(claimed, ExecutionResult(true, "late")))
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(claimed.id)?.state)
            // Reconcile the unknown outcome; the mutation is never repeated.
            assertTrue(ledger.reconcileUnknown(recovered.id, recovered.generation))
            val dispatched = mutableListOf<String>()
            val again = pipeline(ledger, dispatched).executeAttempt(checkNotNull(ledger.get(claimed.id)))
            assertFalse(again.succeeded)
            assertTrue("no blind repeat of the unknown mutation", dispatched.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun crashAfterDispatchReconcilesWithoutRepeat() {
        val dir = Files.createTempDirectory("m1e-crash").toFile()
        try {
            val file = File(dir, "journal.json")
            var ledger = ToolTaskLedger(FileToolTaskStore(file))
            val dispatched = mutableListOf<String>()
            val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread")
            val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
            val claimed = checkNotNull(ledger.claim(attempt.id, attempt.generation))
            // The real effect happens; the process dies before the receipt saves.
            val action = (MobileActionValidator().validate(claimed.request) as ActionValidation.Valid).action
            MobileActionExecutor { mobileAction ->
                dispatched += mobileAction.toString()
                ExecutionResult(true, "Battery 80%")
            }.execute(action)
            assertEquals(1, dispatched.size)
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recovered = ledger.recoverAfterRestart().single { it.id == claimed.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recovered.state)
            assertTrue(ledger.reconcileUnknown(recovered.id, recovered.generation))
            // The reconciled attempt can never dispatch again.
            val again = pipeline(ledger, dispatched).executeAttempt(checkNotNull(ledger.get(claimed.id)))
            assertFalse(again.succeeded)
            assertEquals("the effect happened exactly once", 1, dispatched.size)
        } finally { dir.deleteRecursively() }
    }
}
