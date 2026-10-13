package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Journal hardening ported from audio: typed failures, authority-field
 * shape validation, and lossless-read verification — with the Script step
 * kind (new in schema 3) covered by the hardened reader.
 *
 * Schema stays 3 with the new Script kind: Script steps are a step kind
 * within schema 3, not a schema bump. Upgrade: older journals read
 * normally. Rollback: a build without the Script codec that opens a
 * journal containing Script steps gets a typed failure and the bytes on
 * disk are preserved.
 */
class ToolTaskJournalHardeningTest {
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("journal-hardening").toFile()
        try { test(File(directory, "attempts.json")) } finally { directory.deleteRecursively() }
    }

    private fun uid() = UUID.randomUUID().toString()

    private fun scriptWorkflow(): WorkflowDefinition = WorkflowDefinition(
        uid(), "Scripted", "A script workflow.", listOf(
            WorkflowStep.Script(uid(), "log(\"hi\"); return 1;", listOf("log"),
                mapOf("result" to WorkflowValueType.TEXT))
        ),
        listOf(WorkflowTrigger.Manual), WorkflowOrigin.CONVERSATION,
        createdAtMs = 1_700_000_000_000L, updatedAtMs = 1_700_000_000_000L
    )

    @Test fun scriptStepRoundTripsThroughHardenedJournal() = withFile { file ->
        val store = FileToolTaskStore(file)
        val definition = scriptWorkflow()
        store.updateJournal { it.copy(workflows = listOf(definition)) }
        val reloaded = FileToolTaskStore(file).readJournal()
        val step = reloaded.workflows.single().steps.single() as WorkflowStep.Script
        assertEquals("log(\"hi\"); return 1;", step.source)
        assertEquals(listOf("log"), step.requiredHostFunctions)
        assertEquals(mapOf("result" to WorkflowValueType.TEXT), step.outputs)
    }

    @Test fun unknownStepKindIsRejectedAndBytesPreserved() = withFile { file ->
        val store = FileToolTaskStore(file)
        store.updateJournal { it.copy(workflows = listOf(scriptWorkflow())) }
        val raw = file.readText()
        // A newer build's unknown step kind: the typed reader rejects it and
        // never rewrites the bytes.
        val tampered = raw.replace("\"kind\":\"script\"", "\"kind\":\"quantum\"")
        assertNotEquals(raw, tampered)
        file.writeText(tampered)
        val failure = assertThrows(ToolTaskStorageException::class.java) {
            FileToolTaskStore(file).readJournal()
        }
        assertEquals(ToolTaskStorageFailure.INVALID_CONTENT, failure.failure)
        assertEquals("bytes on disk must be preserved", tampered, file.readText())
    }

    @Test fun unknownFieldIsRejectedAndBytesPreserved() = withFile { file ->
        val store = FileToolTaskStore(file)
        store.updateJournal { it.copy(workflows = listOf(scriptWorkflow())) }
        val raw = file.readText()
        // A newer build's unknown field on a known step: decode succeeds but
        // the lossless-read check rejects it — nothing is silently dropped.
        val tampered = raw.replace("\"kind\":\"script\"", "\"kind\":\"script\",\"frobnicate\":42")
        file.writeText(tampered)
        val failure = assertThrows(ToolTaskStorageException::class.java) {
            FileToolTaskStore(file).readJournal()
        }
        assertEquals(ToolTaskStorageFailure.UNSUPPORTED_CONTENT, failure.failure)
        assertEquals("bytes on disk must be preserved", tampered, file.readText())
    }

    @Test fun unsupportedSchemaReportsVersion() = withFile { file ->
        file.writeText("{\"schemaVersion\":99,\"attempts\":[]}")
        val failure = assertThrows(ToolTaskStorageException::class.java) {
            FileToolTaskStore(file).readJournal()
        }
        assertEquals(ToolTaskStorageFailure.UNSUPPORTED_SCHEMA, failure.failure)
        assertEquals(99, failure.journalSchemaVersion)
        assertTrue(failure.userMessage().contains("99"))
        assertTrue("the message must not leak paths or payloads",
            !failure.userMessage().contains(file.absolutePath))
    }

    @Test fun writeFailureIsTyped() = withFile { file ->
        val failing = FileToolTaskStore(file) { _, _ -> error("disk full") }
        val failure = assertThrows(ToolTaskStorageException::class.java) {
            ToolTaskLedger(failing).create(ActionRequest("read_battery"))
        }
        assertEquals(ToolTaskStorageFailure.WRITE_FAILED, failure.failure)
    }

    @Test fun attemptMissingAuthorityFieldsIsRejected() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        ledger.create(ActionRequest("read_battery"))
        val raw = JSONObject(file.readText())
        // Simulate a schema-2 journal whose attempt lacks the ownership
        // fields: not the legacy-receipts shape (the attempt is active),
        // so the shape check must reject it.
        raw.put("schemaVersion", 2)
        val attempt = raw.getJSONArray("attempts").getJSONObject(0)
        listOf("authority", "provider", "toolSchemaVersion", "stepId", "groupId",
            "grantId", "approvalId", "actionRevision", "reconciled").forEach { attempt.remove(it) }
        file.writeText(raw.toString())
        val failure = assertThrows(ToolTaskStorageException::class.java) {
            FileToolTaskStore(file).readJournal()
        }
        assertEquals(ToolTaskStorageFailure.INVALID_CONTENT, failure.failure)
    }

    @Test fun legacyTerminalReceiptsWithoutAuthorityFieldsStillRead() = withFile { file ->
        val store = FileToolTaskStore(file)
        // A terminal, unlinked receipt in the legacy schema-2 shape.
        val attempt = ToolTaskAttempt(
            id = uid(), generation = 0, state = ToolTaskState.SUCCEEDED,
            request = ActionRequest("read_battery"),
            createdAtMs = 1_700_000_000_000L, updatedAtMs = 1_700_000_000_000L,
            result = "85%", resultOutcome = ExecutionResult.Outcome.SUCCEEDED
        )
        store.updateJournal { it.copy(attempts = listOf(attempt)) }
        val raw = JSONObject(file.readText())
        raw.put("schemaVersion", 2)
        val json = raw.getJSONArray("attempts").getJSONObject(0)
        listOf("authority", "provider", "toolSchemaVersion", "stepId", "groupId",
            "grantId", "approvalId", "actionRevision", "reconciled").forEach { json.remove(it) }
        file.writeText(raw.toString())
        val reloaded = FileToolTaskStore(file).readJournal()
        assertEquals(1, reloaded.attempts.size)
        assertEquals(ToolTaskState.SUCCEEDED, reloaded.attempts.single().state)
    }
}
