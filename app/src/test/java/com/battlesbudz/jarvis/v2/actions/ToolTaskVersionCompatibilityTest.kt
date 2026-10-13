package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixtures come from genuine versioned writers, independently of the current codec. */
class ToolTaskVersionCompatibilityTest {
    private val fixtures = listOf("schema1-original.json", "schema2-legacy.json", "schema2-muse-native-compatible.json",
        "schema2-muse-source-access.json", "schema3-muse-native-compatible.json", "schema3-muse-build1067.json")
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/action-journal/$name"))
        .bufferedReader().use { it.readText() }
    private fun withFile(body: (File) -> Unit) {
        val directory = Files.createTempDirectory("journal-version").toFile()
        try { body(File(directory, "journal.json")) } finally { directory.deleteRecursively() }
    }
    private fun assertProtected(file: File, root: JSONObject, reason: ToolTaskStorageFailure) {
        file.writeText(root.toString())
        val before = file.readText()
        val store = FileToolTaskStore(file)
        assertEquals(reason, assertThrows(ToolTaskStorageException::class.java) { store.readJournal() }.failure)
        assertThrows(ToolTaskStorageException::class.java) { ToolTaskLedger(store).create(ActionRequest("read_battery")) }
        assertEquals(before, file.readText())
    }

    @Test fun allOriginalWritersReadAndMigrateWithoutLosingExistingData() {
        fixtures.forEach { name -> withFile { file ->
            file.writeText(fixture(name))
            val store = FileToolTaskStore(file)
            val before = store.readJournal()
            val event = ToolTaskEvent(before.attempts.first().id, 99, ToolTaskEventKind.RECONCILED, 2000L)
            val expected = before.copy(events = before.events + event)
            assertEquals(expected, store.updateJournal { expected })
            assertEquals(expected, FileToolTaskStore(file).readJournal())
            assertEquals(3, JSONObject(file.readText()).getInt("schemaVersion"))
        } }
    }

    @Test fun recoveryNeverReplaysPreviouslyRunningEffectsOrErasesWorkflowProgress() {
        fixtures.forEach { name -> withFile { file ->
            file.writeText(fixture(name))
            val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 2000L }
            val before = ledger.journal()
            val running = before.attempts.filter { it.state == ToolTaskState.RUNNING }
            ledger.recoverAfterRestart()
            val after = ledger.journal()
            running.forEach { original ->
                assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(original.id)?.state)
                assertNull(ledger.claim(original.id, checkNotNull(ledger.get(original.id)).generation))
            }
            assertEquals(before.sourceAccess, after.sourceAccess)
            assertEquals(before.workflows, after.workflows)
            assertEquals(before.occurrences, after.occurrences)
            assertEquals(before.workflowReceipts, after.workflowReceipts)
            assertEquals(after, FileToolTaskStore(file).readJournal())
        } }
    }

    @Test fun futureFieldsAtEveryDurableBoundaryAreNeverDiscarded() = withFile { file ->
        val original = fixture("schema3-muse-build1067.json")
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("futureAuthority", JSONObject()) },
            { it.getJSONArray("attempts").getJSONObject(0).put("futureGuard", true) },
            { it.getJSONArray("groups").getJSONObject(0).put("futureDependency", "wait") },
            { it.getJSONArray("approvals").getJSONObject(0).put("futureApprovalBinding", "keep") },
            { it.getJSONArray("sourceAccess").getJSONObject(0).put("futureScopeLimit", "keep") },
            { it.getJSONArray("workflows").getJSONObject(0).put("futurePolicy", JSONObject()) },
            { it.getJSONArray("occurrences").getJSONObject(0).put("futureProgress", JSONObject()) },
            { it.getJSONArray("workflowReceipts").getJSONObject(0).put("futureEvidence", "keep") }
        )
        mutations.forEach { mutate ->
            val root = JSONObject(original); mutate(root)
            assertProtected(file, root, ToolTaskStorageFailure.UNSUPPORTED_CONTENT)
        }
    }

    @Test fun missingAuthorityAndCoercedSecurityValuesCannotCreatePermission() = withFile { file ->
        val original = fixture("schema3-muse-native-compatible.json")
        for (key in listOf("authority", "provider", "toolSchemaVersion", "groupId", "approvalId", "grantId", "actionRevision", "reconciled")) {
            val root = JSONObject(original)
            root.getJSONArray("attempts").getJSONObject(2).remove(key)
            assertProtected(file, root, ToolTaskStorageFailure.INVALID_CONTENT)
        }
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("attempts").getJSONObject(2).put("toolSchemaVersion", "1") },
            { it.getJSONArray("attempts").getJSONObject(2).put("reconciled", "false") },
            { it.getJSONArray("groups").getJSONObject(0).put("resumeAfterRestart", "true") },
            { it.remove("sourceAccess") }
        )
        mutations.forEach { mutate -> val root = JSONObject(original); mutate(root); assertProtected(file, root, ToolTaskStorageFailure.INVALID_CONTENT) }
    }

    @Test fun futureVersionHasSpecificNonSensitiveDiagnostic() = withFile { file ->
        assertProtected(file, JSONObject(fixture("schema3-muse-build1067.json")).put("schemaVersion", 99),
            ToolTaskStorageFailure.UNSUPPORTED_SCHEMA)
        val failure = assertThrows(ToolTaskStorageException::class.java) { FileToolTaskStore(file).readJournal() }
        assertTrue(failure.userMessage().contains("format 99"))
        assertFalse(failure.userMessage().contains(file.path))
    }

    @Test fun failedCommitIsDistinctFromSuccessfulReloadAndRetainsEverySection() = withFile { file ->
        file.writeText(fixture("schema3-muse-build1067.json"))
        val before = file.readText()
        val failing = ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("private path or payload") })
        val failure = assertThrows(ToolTaskStorageException::class.java) { failing.create(ActionRequest("read_battery")) }
        assertEquals(ToolTaskStorageFailure.WRITE_FAILED, failure.failure)
        assertFalse(failure.userMessage().contains("private"))
        assertEquals(before, file.readText())
        assertNotNull(FileToolTaskStore(file).readJournal())
    }

    @Test fun standaloneAndGroupedClaimsHonorStoredPhoneDenialAfterMigration() {
        for (name in listOf("schema2-muse-native-compatible.json", "schema3-muse-native-compatible.json")) withFile { file ->
            val root = JSONObject(fixture(name))
            root.getJSONArray("sourceAccess").getJSONObject(0).put("state", "DENIED")
            file.writeText(root.toString())
            val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 2000L }
            val standalone = ledger.create(ActionRequest("read_battery"))
            assertNull(ledger.transition(standalone.id, 0, ToolTaskState.RUNNING))
            val group = ledger.admit(listOf(ActionRequest("read_battery")), "ordinary")
            assertNull(ledger.claim(group.attemptIds.single(), 0))
            assertEquals(SourceAccessState.DENIED, ledger.journal().sourceAccess.first().state)
        }
    }
    @Test fun recoveryKeepsRoutineOwnershipAndQuarantinesWithoutChatReplay() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 2000L }
        val request = ActionRequest("read_battery")
        val ordinary = ledger.admit(listOf(request), "ordinary")
        val grant = ledger.grant("workflow:definition", listOf(request), 9999L)
        val waiting = ledger.admit(listOf(request), "workflow:waiting", ToolAuthority.ROUTINE, grant.id)
        val running = ledger.admit(listOf(request), "workflow:running", ToolAuthority.ROUTINE, grant.id)
        assertNotNull(ledger.claim(running.attemptIds.single(), 0))
        ledger.recoverAfterRestart()
        val journal = ledger.journal()
        assertEquals(ToolTaskState.READY, ledger.get(ordinary.attemptIds.single())?.state)
        assertEquals(ToolTaskState.PAUSED, ledger.get(waiting.attemptIds.single())?.state)
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(running.attemptIds.single())?.state)
        assertTrue(journal.isWorkflowOwned(waiting))
        assertTrue(journal.isWorkflowOwned(running))
        assertFalse(journal.isWorkflowOwned(ordinary))
        assertFalse(journal.groups.any { it.cancelled })
        assertNull(ledger.claim(waiting.attemptIds.single(), checkNotNull(ledger.get(waiting.attemptIds.single())).generation))
        assertNull(ledger.claim(running.attemptIds.single(), checkNotNull(ledger.get(running.attemptIds.single())).generation))
    }

    @Test fun previousApkTerminalOnlySchema2ReceiptRemainsReadableAndNeverReplays() = withFile { file ->
        val raw = fixture("schema2-upgrade-terminal-receipt.json")
        file.writeText(raw)
        val ledger = ToolTaskLedger(FileToolTaskStore(file)) { 1700000000100L }
        val receipt = ledger.snapshot().single()
        assertEquals(ToolTaskState.SUCCEEDED, receipt.state)
        assertEquals(2L, receipt.generation)
        assertEquals("Battery level is 73%.", receipt.result)
        assertEquals(raw, file.readText())
        assertEquals(listOf(receipt), ledger.recoverAfterRestart())
        assertNull(ledger.claim(receipt.id, receipt.generation))
        assertNull(ledger.transition(receipt.id, receipt.generation, ToolTaskState.RUNNING))
        assertNull(ledger.revise(receipt.id, receipt.generation, ActionRequest("read_battery")))
        var effects = 0
        assertFalse(JournaledActionPipeline(ledger) { effects++; ExecutionResult(true, "must not replay") }
            .executeAttempt(receipt).succeeded)
        assertEquals(0, effects)
        ledger.create(ActionRequest("read_battery"))
        assertEquals(3, JSONObject(file.readText()).getInt("schemaVersion"))
        assertEquals(receipt, FileToolTaskStore(file).read().first())
    }

    @Test fun legacyReceiptExceptionCannotDefaultActiveOrPartiallyAnnotatedAuthority() = withFile { file ->
        val raw = fixture("schema2-upgrade-terminal-receipt.json")
        for (state in ToolTaskState.entries.map { it.name }.filterNot { it in setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN_OUTCOME") }) {
            val root = JSONObject(raw)
            root.getJSONArray("attempts").getJSONObject(0).put("state", state)
            assertProtected(file, root, ToolTaskStorageFailure.INVALID_CONTENT)
        }
        for (field in listOf("authority", "provider", "toolSchemaVersion", "stepId", "groupId", "grantId", "approvalId", "actionRevision", "reconciled")) {
            val partial = JSONObject(raw)
            partial.getJSONArray("attempts").getJSONObject(0).put(field, JSONObject.NULL)
            assertProtected(file, partial, ToolTaskStorageFailure.INVALID_CONTENT)
        }
        for (collection in listOf("groups", "approvals", "grants", "events")) {
            val linked = JSONObject(raw)
            linked.getJSONArray(collection).put(JSONObject())
            assertProtected(file, linked, ToolTaskStorageFailure.INVALID_CONTENT)
        }
        val future = JSONObject(raw)
        future.getJSONArray("attempts").getJSONObject(0).put("futureApprovalContext", JSONObject())
        assertProtected(file, future, ToolTaskStorageFailure.UNSUPPORTED_CONTENT)
        val newer = JSONObject(raw).put("schemaVersion", 3)
        assertProtected(file, newer, ToolTaskStorageFailure.INVALID_CONTENT)
        val linked = JSONObject(raw).put("activeQuestionId", "unresolved-choice")
        assertProtected(file, linked, ToolTaskStorageFailure.INVALID_CONTENT)
    }

}
