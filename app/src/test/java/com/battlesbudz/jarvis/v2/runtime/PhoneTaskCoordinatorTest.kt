package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.actions.*
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PhoneTaskCoordinatorTest {
    private fun coordinator(ledger: ToolTaskLedger) = PhoneTaskCoordinator(
        CoroutineScope(Dispatchers.Unconfined), androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
        { MobileActionExecutor { ExecutionResult(true, "effect") } }, { true }, { _, _, _, _ -> }, com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {})

    @Test fun successfulRefreshClearsOnlyTheOldLoadFailure() {
        val directory = Files.createTempDirectory("phone-coordinator").toFile()
        try {
            val file = File(directory, "journal.json").apply { writeText("corrupt") }
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val coordinator = coordinator(ledger)
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("couldn't be validated"))
            assertNull(coordinator.tasks.value)
            // Simulate repair/replacement by another compatible writer, not an app reset path.
            file.writeText("""{"schemaVersion":2,"attempts":[],"groups":[],"approvals":[],"grants":[],"events":[],"activeQuestionId":null}""")
            coordinator.refreshPhoneTasks()
            assertNotNull(coordinator.tasks.value)
            assertNull(coordinator.error.value)
        } finally { directory.deleteRecursively() }
    }

    @Test fun readableOldStateDoesNotEraseAFailedCancellationWarning() {
        val directory = Files.createTempDirectory("phone-coordinator").toFile()
        try {
            val file = File(directory, "journal.json")
            val seed = ToolTaskLedger(FileToolTaskStore(file))
            val group = seed.admit(listOf(ActionRequest("read_battery")), "conversation")
            val before = file.readText()
            var fail = true
            val ledger = ToolTaskLedger(FileToolTaskStore(file) { target, value -> if (fail) error("disk full") else target.writeText(value) })
            val coordinator = coordinator(ledger)
            coordinator.cancelPhoneTask(group.id)
            assertEquals(before, file.readText())
            assertFalse(checkNotNull(coordinator.tasks.value).groups.single().cancelled)
            assertTrue(checkNotNull(coordinator.error.value).contains("not confirmed cancelled"))
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("not confirmed cancelled"))
            // A later read failure and successful read must not hide the failed write either.
            file.writeText("bad")
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("couldn't be validated"))
            file.writeText(before)
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("not confirmed cancelled"))
            assertFalse(checkNotNull(coordinator.error.value).contains("couldn't be validated"))
            fail = false
            coordinator.cancelPhoneTask(group.id)
            assertTrue(checkNotNull(coordinator.tasks.value).groups.single().cancelled)
            assertNull(coordinator.error.value)
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedStandaloneReceiptIsReportedHonestlyAfterSuccessfulRead() {
        val directory = Files.createTempDirectory("phone-coordinator").toFile()
        try {
            val file = File(directory, "journal.json")
            var writes = 0
            val ledger = ToolTaskLedger(FileToolTaskStore(file) { target, value ->
                writes++
                if (writes == 3) error("receipt write failed") else target.writeText(value)
            })
            val coordinator = coordinator(ledger)
            var effects = 0
            val result = coordinator.executePhoneAction(ActionRequest("read_battery"),
                MobileActionExecutor { effects++; ExecutionResult(true, "battery receipt") })
            assertEquals(1, effects)
            assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
            assertTrue(checkNotNull(coordinator.error.value).contains("The action ran"))
            assertEquals(ToolTaskState.RUNNING, checkNotNull(coordinator.tasks.value).attempts.single().state)
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("The action ran"))
            assertFalse(checkNotNull(coordinator.error.value).contains("didn't start"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedStandaloneAdmissionKeepsSaveWarningAfterSuccessfulRead() {
        val directory = Files.createTempDirectory("phone-coordinator").toFile()
        try {
            val file = File(directory, "journal.json")
            val coordinator = coordinator(ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("disk full") }))
            var effects = 0
            assertFalse(coordinator.executePhoneAction(ActionRequest("read_battery"),
                MobileActionExecutor { effects++; ExecutionResult(true, "effect") }).succeeded)
            assertEquals(0, effects)
            assertTrue(checkNotNull(coordinator.error.value).contains("couldn't be saved"))
            coordinator.refreshPhoneTasks()
            assertTrue(checkNotNull(coordinator.error.value).contains("couldn't be saved"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun ordinaryRecoveryCancelsMissingChatButNeverWorkflowOwnedGroups() {
        val directory = Files.createTempDirectory("phone-ownership").toFile()
        try {
            val ledger = ToolTaskLedger(FileToolTaskStore(File(directory, "journal.json")))
            val request = ActionRequest("read_battery")
            val chat = ledger.admit(listOf(request), "deleted-chat")
            val grant = ledger.grant("workflow:definition", listOf(request), System.currentTimeMillis() + 60_000)
            val workflow = ledger.admit(listOf(request), "workflow:occurrence", ToolAuthority.ROUTINE, grant.id)
            var effects = 0
            val projected = mutableListOf<String>()
            val coordinator = PhoneTaskCoordinator(CoroutineScope(Dispatchers.Unconfined),
                androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                { MobileActionExecutor { effects++; ExecutionResult(true, "effect") } }, { false },
                { conversation, _, _, _ -> projected += conversation },
                com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {}, Dispatchers.Unconfined)
            assertTrue(coordinator.recoverAfterRestart())
            coordinator.resumePhoneTasksAfterUnlock()
            assertTrue(ledger.journal().groups.single { it.id == chat.id }.cancelled)
            assertFalse(ledger.journal().groups.single { it.id == workflow.id }.cancelled)
            assertEquals(ToolTaskState.PAUSED, ledger.get(workflow.attemptIds.single())?.state)
            assertEquals(0, effects)
            assertFalse(projected.any { it.startsWith("workflow:") })
        } finally { directory.deleteRecursively() }
    }

}
