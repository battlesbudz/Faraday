package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class ToolTaskPersistenceTest {
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("phone-actions").toFile()
        try { test(File(directory, "attempts.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun reloadPreservesTypedReceiptAndRejectsReplay() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val task = ledger.create(ActionRequest("set_volume", mapOf("level" to "25")))
        val running = checkNotNull(ledger.transition(task.id, 0, ToolTaskState.RUNNING))
        val done = ledger.transition(task.id, running.generation, ToolTaskState.SUCCEEDED, "25%", ExecutionResult.Outcome.SUCCEEDED)
        val reopened = ToolTaskLedger(FileToolTaskStore(file))
        assertEquals(done, reopened.get(task.id))
        assertEquals(done, reopened.recoverAfterRestart().single())
        assertNull(reopened.transition(task.id, checkNotNull(done).generation, ToolTaskState.RUNNING))
    }

    @Test fun restartFencesRunningAndPendingApprovalsWithoutChangingCancelledWork() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val running = ledger.create(ActionRequest("open_app", mapOf("app" to "Maps")), ToolTaskState.RUNNING)
        val waiting = ledger.create(ActionRequest("read_battery"), ToolTaskState.WAITING_APPROVAL)
        val cancelled = ledger.create(ActionRequest("read_battery"), ToolTaskState.CANCELLED)
        val reopened = ToolTaskLedger(FileToolTaskStore(file))
        reopened.recoverAfterRestart()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, reopened.get(running.id)?.state)
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, reopened.get(running.id)?.resultOutcome)
        assertEquals(ToolTaskState.PAUSED, reopened.get(waiting.id)?.state)
        assertEquals(cancelled, reopened.get(cancelled.id))
        assertNull(reopened.transition(running.id, running.generation, ToolTaskState.SUCCEEDED, "late"))
        assertNull(reopened.transition(waiting.id, waiting.generation, ToolTaskState.RUNNING))
        val recovered = reopened.snapshot()
        assertEquals(recovered, reopened.recoverAfterRestart())
        assertEquals(recovered, ToolTaskLedger(FileToolTaskStore(file)).snapshot())
    }

    @Test fun independentInstancesCannotLoseCreatesOrBothClaimOneGeneration() = withFile { file ->
        val pool = Executors.newFixedThreadPool(4)
        try {
            val creates = (1..24).map { pool.submit<ToolTaskAttempt> { ToolTaskLedger(FileToolTaskStore(file)).create(ActionRequest("read_battery")) } }
            creates.forEach { it.get() }
            assertEquals(24, ToolTaskLedger(FileToolTaskStore(file)).snapshot().size)
            val task = creates.first().get()
            val start = CountDownLatch(1)
            val claims = (1..4).map { pool.submit<ToolTaskAttempt?> {
                start.await()
                ToolTaskLedger(FileToolTaskStore(file)).transition(task.id, 0, ToolTaskState.RUNNING)
            } }
            start.countDown()
            assertEquals(1, claims.map { it.get() }.count { it != null })
        } finally { pool.shutdownNow() }
    }

    @Test fun failedCommitDoesNotAdvanceDiskOrGeneration() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val queued = ledger.create(ActionRequest("read_battery"))
        val original = file.readText()
        val failing = ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("disk full") })
        assertThrows(ToolTaskStorageException::class.java) { failing.transition(queued.id, 0, ToolTaskState.RUNNING) }
        assertEquals(original, file.readText())
        assertEquals(queued, ledger.get(queued.id))
    }

    @Test fun corruptUnknownSchemaAndOversizedJournalAreNeverOverwritten() = withFile { file ->
        for (raw in listOf("broken", "{\"schemaVersion\":99,\"attempts\":[]}", "x".repeat(FileToolTaskStore.MAX_BYTES + 1))) {
            file.writeText(raw)
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            assertThrows(ToolTaskStorageException::class.java) { ledger.create(ActionRequest("read_battery")) }
            assertEquals(raw, file.readText())
        }
    }

    @Test fun abandonedTempIsDiscardedWithoutPromotingUncommittedState() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val queued = ledger.create(ActionRequest("read_battery"))
        val abandoned = File(file.parentFile, ".${file.name}.abandoned.tmp").apply { writeText("uncommitted") }
        val unrelated = File(file.parentFile, "other.tmp").apply { writeText("keep") }
        assertEquals(queued, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single())
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun capacityFailsClosedAndRetainsExistingReceipts() = withFile { file ->
        val store = FileToolTaskStore(file)
        val template = ToolTaskLedger().create(ActionRequest("read_battery"))
        store.update { (1..FileToolTaskStore.MAX_ATTEMPTS).map { template.copy(id = java.util.UUID.randomUUID().toString()) } }
        assertThrows(ToolTaskStorageException::class.java) { ToolTaskLedger(store).create(ActionRequest("read_battery")) }
        assertEquals(FileToolTaskStore.MAX_ATTEMPTS, store.read().size)
    }
}
