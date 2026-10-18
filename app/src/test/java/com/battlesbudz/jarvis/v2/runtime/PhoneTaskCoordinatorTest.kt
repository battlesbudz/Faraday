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
    // Synchronous fixtures run the coordinator dispatcher as Unconfined:
    // the default Main dispatcher leaves onStorageFailure launches queued
    // on Robolectric's main looper, so error assertions would see null.
    private fun coordinator(ledger: ToolTaskLedger) = PhoneTaskCoordinator(
        CoroutineScope(Dispatchers.Unconfined), androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
        { MobileActionExecutor { ExecutionResult(true, "effect") } }, { true }, { _, _, _, _ -> }, com.battlesbudz.jarvis.v2.voice.SilentWorkController(), {},
        dispatcher = Dispatchers.Unconfined)

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
            // Resume executes only READY attempts, and admit leaves them
            // QUEUED, so prepare the realistic post-restart state first:
            // recovery moves the conversation task to READY and leaves
            // the pending question WAITING_APPROVAL with its approval
            // unconsumed. Ledger-level, before the SilentWork fixture
            // is created, so no recovery refresh pollutes its records.
            ledger.recoverAfterRestart()
            val recoveredConversation = ledger.get(ledger.journal().groups.single { it.conversationId == "conversation" }.attemptIds.single())!!
            assertEquals("recovery must leave the resumed task READY for resume",
                ToolTaskState.READY, recoveredConversation.state)
            val recoveredQuestion = ledger.get(questionAttempt.id)!!
            assertEquals("recovery must preserve the pending question",
                ToolTaskState.WAITING_APPROVAL, recoveredQuestion.state)
            assertFalse("recovery must not consume the pending question's approval",
                ledger.journal().approvals.single { it.id == recoveredQuestion.approvalId }.consumed)
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
                // Gate the fake executor: it parks after entry so the test
                // can observe the pre-effect callback's publication while
                // execution is still blocked. The trailing refresh in
                // resumePhoneTasksAfterUnlock cannot have run yet (the
                // resume job is suspended waiting for this executor), so
                // a RUNNING publication seen here must come from the
                // pipeline's onJournalChanged callback hop, not the
                // trailing refresh.
                val executionRelease = java.util.concurrent.CountDownLatch(1)
                val execThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
                val projected = java.util.concurrent.CountDownLatch(1)
                val scope = CoroutineScope(Dispatchers.Unconfined)
                val baseName = { name: String -> name.substringBefore(" @coroutine") }
                val coordinator = PhoneTaskCoordinator(scope,
                    androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                    { MobileActionExecutor {
                        execThreads += Thread.currentThread().name
                        effectRan.countDown()
                        // Bounded park: if the test thread dies before
                        // releasing, the executor still proceeds after
                        // 10s instead of hanging CI.
                        executionRelease.await(10, TimeUnit.SECONDS)
                        ExecutionResult(true, "effect")
                    } },
                    { true }, { _, _, _, _ -> projected.countDown() },
                    silentWork, {},
                    dispatcher = mainDispatcher, executionDispatcher = executionDispatcher)
                coordinator.resumePhoneTasksAfterUnlock()
                assertTrue("the resumed task must execute", effectRan.await(10, TimeUnit.SECONDS))
                // While execution is blocked, the pre-effect callback must
                // already have published RUNNING via the coordinator
                // dispatcher. Poll for both the state and the test-main
                // refresh-thread record together: refreshPhoneTasks
                // assigns tasks.value before the silent-work clock
                // records the refresh thread, so either alone could be
                // observed first. Release in finally so a failure here
                // cannot strand the executor or hang teardown.
                var sawRunning = false
                var runningOnMain = false
                val runningDeadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < runningDeadline) {
                    val published = coordinator.tasks.value?.attempts?.singleOrNull { it.id == recoveredConversation.id }?.state
                    if (published == ToolTaskState.RUNNING) sawRunning = true
                    val threadsSnapshot: List<String> = synchronized(refreshThreads) { refreshThreads.toList() }
                    if (sawRunning && threadsSnapshot.any { baseName(it) == "test-main" }) { runningOnMain = true; break }
                    Thread.sleep(10)
                }
                try {
                    val threadsSnapshot: List<String> = synchronized(refreshThreads) { refreshThreads.toList() }
                    assertTrue("the pipeline's pre-effect callback must publish RUNNING on the coordinator dispatcher before execution is released (saw RUNNING=$sawRunning, refreshThreads=$threadsSnapshot)",
                        runningOnMain)
                    assertTrue("no publication while blocked may run on the execution dispatcher (refreshThreads=$threadsSnapshot)",
                        threadsSnapshot.none { baseName(it) == "test-exec" })
                } finally {
                    executionRelease.countDown()
                }
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

    @Test fun staleRefreshVersusDenialLeavesAnswerWindowClosed() {
        // Jerry review of f0aad54d: the previous fixture could not fail
        // on the pre-fix code. It started the refresh first, holding
        // publishLock through its slowed read, and phoneTaskAction's
        // first step (operationStatus(null)) also takes that lock, so
        // the denial was serialized behind the refresh's whole
        // decision: refresh decided (opening the window), then the
        // denial closed it, and the test passed with or without the
        // fix. The real race needs the refresh's DECISION to land
        // after the denial while its SNAPSHOT predates it, so this
        // fixture reverses the order: the denial passes its initial
        // lock first and parks at a store seam before the deny+close
        // critical section; the refresh then parks mid-read holding
        // publishLock with a pre-denial snapshot; the test releases
        // the denial and waits for its store write plus a grace
        // window for the close to land, and only then releases the
        // refresh to decide. Pre-fix the denial's close lands first
        // and the stale refresh reopens the window, failing the
        // final assertion; with f0aad54d the denial blocks on the
        // lock until the refresh has decided, then closes the window
        // the refresh opened. Production code untouched: fixture only.
        val directory = Files.createTempDirectory("phone-stale-deny").toFile()
        try {
            val file = File(directory, "journal.json")
            val delegate = FileToolTaskStore(file)
            val readStarted = java.util.concurrent.CountDownLatch(1)
            val slowNextRead = java.util.concurrent.atomic.AtomicBoolean(false)
            val slowReadGate = java.util.concurrent.CountDownLatch(1)
            val denyAtSeam = java.util.concurrent.CountDownLatch(1)
            val denyGate = java.util.concurrent.CountDownLatch(1)
            val denySeamArmed = java.util.concurrent.atomic.AtomicBoolean(false)
            val denyWriteDone = java.util.concurrent.CountDownLatch(1)
            val denyWrites = java.util.concurrent.atomic.AtomicInteger(0)
            // The denial runs inside scope.launch, so coroutine debugging
            // may decorate its thread name ("deny-thread @coroutine#N").
            // Identify the denial thread by Thread identity, never by name.
            val denyThreadRef = java.util.concurrent.atomic.AtomicReference<Thread>()
            val slowStore = object : ToolTaskStore {
                override fun read() = readJournal().attempts
                override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>) =
                    updateJournal { it.copy(attempts = change(it.attempts)) }.attempts
                override fun readJournal(): ToolTaskJournal {
                    val snapshot = delegate.readJournal()
                    if (slowNextRead.getAndSet(false)) {
                        readStarted.countDown()
                        // Park the refresh mid-decision: it holds
                        // publishLock with a pre-denial snapshot until
                        // the test releases it, so its decision cannot
                        // slip ahead of the denial.
                        slowReadGate.await(10, TimeUnit.SECONDS)
                    }
                    return snapshot
                }
                override fun updateJournal(change: (ToolTaskJournal) -> ToolTaskJournal): ToolTaskJournal {
                    if (Thread.currentThread() === denyThreadRef.get()) {
                        val write = denyWrites.incrementAndGet()
                        if (write == 1 && denySeamArmed.getAndSet(false)) {
                            // The denial's first write is
                            // pauseExpiredGroups, before the deny+close
                            // critical section: park here, NOT holding
                            // publishLock, so the refresh can take its
                            // pre-denial snapshot first.
                            denyAtSeam.countDown()
                            denyGate.await(10, TimeUnit.SECONDS)
                        }
                    }
                    val out = delegate.updateJournal(change)
                    if (Thread.currentThread() === denyThreadRef.get() && denyWrites.get() == 2) {
                        // The denial's second write is the deny itself.
                        // It fires only when the denial could run while
                        // the refresh was parked (pre-fix); with the fix
                        // the denial blocks on publishLock and this
                        // never fires before the refresh is released,
                        // which the await timeout below tolerates.
                        denyWriteDone.countDown()
                    }
                    return out
                }
            }
            val ledger = ToolTaskLedger(slowStore)
            val group = ledger.admit(listOf(ActionRequest("read_battery")), "conversation")
            val admitted = ledger.get(group.attemptIds.single())!!
            ledger.requestApproval(admitted.id, admitted.generation, "native", MobileToolCatalog.VERSION)
            val pending = ledger.get(admitted.id)!!
            assertEquals(ToolTaskState.WAITING_APPROVAL, pending.state)
            val silentWork = com.battlesbudz.jarvis.v2.voice.SilentWorkController()
            assertTrue(silentWork.enterSilentWork())
            assertFalse(silentWork.isAnswerWindowOpen())
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val coordinator = PhoneTaskCoordinator(scope,
                androidx.test.core.app.ApplicationProvider.getApplicationContext(), { ledger }, { false },
                { MobileActionExecutor { ExecutionResult(true, "effect") } }, { true },
                { _, _, _, _ -> }, silentWork, {},
                dispatcher = Dispatchers.Unconfined, executionDispatcher = Dispatchers.Unconfined)
            // Start the denial first: it passes its initial
            // operationStatus(null) lock, then parks at the
            // pauseExpiredGroups seam, NOT holding publishLock.
            denySeamArmed.set(true)
            val denyThread = Thread({ denyThreadRef.set(Thread.currentThread()); coordinator.phoneTaskAction(pending.id, pending.generation, "deny") }, "deny-thread")
            denyThread.start()
            assertTrue("the denial must reach its store seam",
                denyAtSeam.await(10, TimeUnit.SECONDS))
            // Now start the refresh: it snapshots the pre-denial
            // journal and parks holding publishLock.
            slowNextRead.set(true)
            val refreshThread = Thread({ coordinator.refreshPhoneTasks() }, "refresh-thread")
            refreshThread.start()
            assertTrue("the refresh must reach its slowed read",
                readStarted.await(10, TimeUnit.SECONDS))
            // Release the denial. Pre-fix it denies and closes while
            // the refresh is still parked; with the fix it blocks on
            // publishLock at the critical section.
            denyGate.countDown()
            // Wait for the pre-fix denial write (a timeout here only
            // means the denial is parked on the lock, i.e. the fixed
            // code), then a grace window for its close to land before
            // the refresh is allowed to decide. A stalled denial here
            // can only weaken the test (pass pre-fix), never fail the
            // fixed code.
            denyWriteDone.await(500, TimeUnit.MILLISECONDS)
            Thread.sleep(200)
            slowReadGate.countDown()
            refreshThread.join(10_000)
            assertFalse("the refresh thread must finish", refreshThread.isAlive)
            denyThread.join(10_000)
            assertFalse("the denial thread must finish", denyThread.isAlive)
            awaitCoordinatorIdle(scope)
            assertTrue("the denial must consume the approval",
                ledger.journal().approvals.single().consumed)
            assertEquals(ApprovalDecision.DENIED, ledger.journal().approvals.single().decision)
            assertFalse("a stale-snapshot refresh must not leave the denied approval's answer window open",
                silentWork.isAnswerWindowOpen())
            assertNull(silentWork.pendingQuestionId())
        } finally { directory.deleteRecursively() }
    }

}

