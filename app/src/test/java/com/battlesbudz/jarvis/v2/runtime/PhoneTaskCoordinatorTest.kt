package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.actions.*
import java.nio.file.Files
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PhoneTaskCoordinatorTest {
    private fun coordinator(ledger: ToolTaskLedger) = PhoneTaskCoordinator(
        CoroutineScope(Dispatchers.Unconfined), androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
        { MobileActionExecutor { ExecutionResult(true, "effect") } }, { true }, { _, _, _, _ -> }, com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {})

    /**
     * The coordinator runs resume/approve asynchronously as children of
     * its scope, and the pipeline's onJournalChanged/onStorageFailure
     * hops are separate children of that same scope. An executor latch
     * fires inside the fake executor — before the pipeline saves the
     * receipt, before the projection hops, and before the resume job's
     * trailing refresh — so a latch alone must never gate teardown:
     * closing dispatchers or deleting the journal directory while those
     * children are still in flight is a flake. Joining the scope's
     * children until none are active is the authoritative wait; a stuck
     * child fails the test on timeout instead of hanging CI.
     */
    private fun awaitCoordinatorIdle(scope: CoroutineScope, timeoutMs: Long = 10_000) {
        runBlocking {
            withTimeout(timeoutMs) {
                while (true) {
                    val active = scope.coroutineContext[Job]?.children?.filter { it.isActive }?.toList() ?: emptyList()
                    if (active.isEmpty()) return@withTimeout
                    active.forEach { it.join() }
                }
            }
        }
    }

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

    @Test fun resumedTaskExecutionRunsOffTheMainBoundary() {
        // Resume-after-unlock and panel-approve dispatch share the same
        // boundary: approval/journal/UI stay on the coordinator dispatcher
        // (Main in production) while the blocking executor runs on the
        // execution dispatcher. A launch-verification dispatch on Main
        // would stall UI/Stop/lifecycle and can ANR.
        val directory = Files.createTempDirectory("phone-resume-thread").toFile()
        try {
            val file = File(directory, "journal.json")
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            ledger.admit(listOf(ActionRequest("read_battery")), "conversation")
            val mainDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main") }
                .asCoroutineDispatcher()
            val executionDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-exec") }
                .asCoroutineDispatcher()
            try {
                val execThreads = mutableListOf<String>()
                val executed = java.util.concurrent.CountDownLatch(1)
                val scope = CoroutineScope(Dispatchers.Unconfined)
                val coordinator = PhoneTaskCoordinator(scope,
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                    { MobileActionExecutor { execThreads += Thread.currentThread().name; executed.countDown(); ExecutionResult(true, "effect") } },
                    { true }, { _, _, _, _ -> },
                    com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {},
                    dispatcher = mainDispatcher, executionDispatcher = executionDispatcher)
                assertTrue(coordinator.recoverAfterRestart())
                assertEquals("recovery must leave the admitted task READY for resume",
                    ToolTaskState.READY, ledger.get(ledger.journal().groups.single().attemptIds.single())?.state)
                coordinator.resumePhoneTasksAfterUnlock()
                assertTrue("the resumed task must execute", executed.await(10, TimeUnit.SECONDS))
                // The latch fires inside the fake executor, before the
                // receipt save and before the coordinator job's
                // projection/refresh finish. Wait for the whole
                // coordinator scope to idle, then verify the terminal
                // receipt, before teardown closes the dispatchers and
                // deletes the journal directory.
                awaitCoordinatorIdle(scope)
                val terminal = ledger.get(ledger.journal().groups.single().attemptIds.single())
                assertEquals("the resumed task must record its terminal receipt before teardown",
                    ToolTaskState.SUCCEEDED, terminal?.state)
                assertEquals("effect", terminal?.result)
                assertEquals("the trailing refresh must publish the terminal attempt before teardown",
                    ToolTaskState.SUCCEEDED, coordinator.tasks.value?.attempts?.single()?.state)
                val baseName = { name: String -> name.substringBefore(" @coroutine") }
                assertEquals("resumed execution must run on the execution dispatcher",
                    listOf("test-exec"), execThreads.map(baseName))
            } finally {
                mainDispatcher.close()
                executionDispatcher.close()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun browseSubmitIsParkedForApprovalNamingTheDestination() {
        // M4 (D11): a model-proposed browse_submit parks as WAITING_APPROVAL
        // with a receipt naming the destination host the user will review.
        val directory = Files.createTempDirectory("phone-browse-park").toFile()
        try {
            val ledger = ToolTaskLedger(FileToolTaskStore(File(directory, "journal.json")))
            val session = BrowserSession()
            val page = session.openPage(
                url = "https://example.com/login", title = "Example login", textExcerpt = "Sign in",
                links = emptyList(),
                forms = listOf(BrowserForm(id = "form0", actionUrl = "https://example.com/session",
                    method = "POST", fields = listOf(BrowserField("f0", "Email", FieldKind.EMAIL)),
                    submitLabel = "Sign in")))
            session.noteFingerprint("fp-login")
            val coordinator = PhoneTaskCoordinator(CoroutineScope(Dispatchers.Unconfined),
                androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                { MobileActionExecutor { ExecutionResult(true, "effect") } }, { true },
                { _, _, _, _ -> }, com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {},
                browserSession = session)
            val result = coordinator.parkBrowseSubmitForApproval(
                ActionRequest("browse_submit", mapOf("token" to page.pageToken)), "conversation-1")
            assertFalse(result.succeeded)
            assertTrue(result.message, result.message.contains("to example.com"))
            assertTrue(result.message, result.message.contains("Submit the form"))
            val attempt = ledger.snapshot().single()
            assertEquals(ToolTaskState.WAITING_APPROVAL, attempt.state)
            assertEquals("browse_submit", attempt.request.name)
        } finally { directory.deleteRecursively() }
    }

    @Test fun pipelineProjectionCallbacksReturnToCoordinatorDispatcher() {
        // Jerry review of 2bb66122: executeAttempt runs on the execution
        // dispatcher, but its onJournalChanged/onStorageFailure callbacks
        // must publish on the coordinator dispatcher (Main in production).
        // A refresh published from IO would move UI/error/silent-work
        // projection off Main and could publish a stale snapshot over a
        // newer Main-thread cancellation. The silent-work clock is
        // invoked on the refresh thread, so it records exactly which
        // threads published.
        val directory = Files.createTempDirectory("phone-callback-thread").toFile()
        try {
            val file = File(directory, "journal.json")
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val question = ledger.admit(listOf(ActionRequest("read_battery")), "question-chat")
            val questionAttempt = ledger.get(question.attemptIds.single())!!
            ledger.requestApproval(questionAttempt.id, questionAttempt.generation, "native", MobileToolCatalog.VERSION)
            ledger.admit(listOf(ActionRequest("read_battery")), "conversation")
            val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-main") }
            val execExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-exec") }
            val mainDispatcher = mainExecutor.asCoroutineDispatcher()
            val executionDispatcher = execExecutor.asCoroutineDispatcher()
            try {
                val refreshThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
                val silentWork = com.battlesbudz.jarvis.v2.voice.SilentWorkController(clock = {
                    refreshThreads += Thread.currentThread().name
                    System.currentTimeMillis()
                })
                assertTrue(silentWork.enterSilentWork())
                val effectRan = java.util.concurrent.CountDownLatch(1)
                val execThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
                val projected = java.util.concurrent.CountDownLatch(1)
                val scope = CoroutineScope(Dispatchers.Unconfined)
                val coordinator = PhoneTaskCoordinator(scope,
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                    { MobileActionExecutor { execThreads += Thread.currentThread().name; effectRan.countDown(); ExecutionResult(true, "effect") } },
                    { true }, { _, _, _, _ -> projected.countDown() },
                    silentWork, {},
                    dispatcher = mainDispatcher, executionDispatcher = executionDispatcher)
                coordinator.resumePhoneTasksAfterUnlock()
                assertTrue("the resumed task must execute", effectRan.await(10, TimeUnit.SECONDS))
                assertTrue("the resumed task must project", projected.await(10, TimeUnit.SECONDS))
                // Drain every refresh posted to the coordinator dispatcher.
                mainExecutor.submit(Runnable {}).get(10, TimeUnit.SECONDS)
                // The barrier above only orders executor-queue work; the
                // authoritative wait is joining the coordinator scope's
                // children (resume job + projection hops) before teardown.
                awaitCoordinatorIdle(scope)
                val terminal = ledger.get(ledger.journal().groups.single { it.conversationId == "conversation" }.attemptIds.single())
                assertEquals("the resumed task must record its terminal receipt before teardown",
                    ToolTaskState.SUCCEEDED, terminal?.state)
                assertEquals("effect", terminal?.result)
                val baseName = { name: String -> name.substringBefore(" @coroutine") }
                assertEquals("resumed execution must run on the execution dispatcher",
                    listOf("test-exec"), execThreads.map(baseName))
                assertTrue("projection refreshes must run on the coordinator dispatcher, never the execution dispatcher",
                    refreshThreads.none { baseName(it) == "test-exec" })
                assertTrue("at least one projection refresh must have run on the coordinator dispatcher",
                    refreshThreads.any { baseName(it) == "test-main" })
            } finally {
                mainDispatcher.close()
                executionDispatcher.close()
                mainExecutor.shutdownNow()
                execExecutor.shutdownNow()
            }
        } finally { directory.deleteRecursively() }
    }

}
