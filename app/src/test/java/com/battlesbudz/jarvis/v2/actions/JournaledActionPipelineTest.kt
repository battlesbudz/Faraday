package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class JournaledActionPipelineTest {
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("journal-dispatch").toFile()
        try { test(File(directory, "attempts.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun executorObservesDurableIntentThenTypedReceiptReloads() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor  {
            calls++
            assertEquals(ToolTaskState.RUNNING, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state)
            ExecutionResult(true, "85%")
        })
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertEquals(1, calls)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(ToolTaskState.SUCCEEDED, saved.state)
        assertEquals(ExecutionResult.Outcome.SUCCEEDED, saved.resultOutcome)
        assertEquals("85%", saved.result)
    }

    @Test fun validationOrPreDispatchStorageFailureHasZeroEffects() = withFile { file ->
        var calls = 0
        val executor = MobileActionExecutor { calls++; ExecutionResult(true, "effect") }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        assertEquals(ExecutionResult.Outcome.REJECTED_VALIDATION,
            JournaledActionPipeline(ledger, executor).execute(ActionRequest("set_volume", mapOf("level" to "999"))).outcome)
        assertFalse(file.exists())
        val failing = ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("disk full") })
        assertFalse(JournaledActionPipeline(failing, executor).execute(ActionRequest("read_battery")).succeeded)
        file.writeText("corrupt")
        assertFalse(JournaledActionPipeline(ledger, executor).execute(ActionRequest("read_battery")).succeeded)
        assertEquals(0, calls)
        assertEquals("corrupt", file.readText())
    }

    @Test fun receiptWriteFailureReturnsUnknownWithoutRepeatingEffect() = withFile { file ->
        val goodStore = FileToolTaskStore(file)
        var writes = 0
        val store = object : ToolTaskStore {
            override fun read() = goodStore.read()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
                if (++writes == 3) throw ToolTaskStorageException()
                return goodStore.update(change)
            }
        }
        var calls = 0
        val result = JournaledActionPipeline(ToolTaskLedger(store), MobileActionExecutor  { calls++; ExecutionResult(true, "effect") })
            .execute(ActionRequest("read_battery"))
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(ToolTaskState.RUNNING, goodStore.read().single().state)
        val recovered = ToolTaskLedger(goodStore).recoverAfterRestart().single()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recovered.state)
        assertEquals(1, calls)
    }

    @Test fun cancellationAndProgrammingErrorsPropagateAndFencePossibleEffects() = withFile { file ->
        for (error in listOf(CancellationException("cancel"), IllegalArgumentException("bug"))) {
            file.delete()
            val pipeline = JournaledActionPipeline(ToolTaskLedger(FileToolTaskStore(file)), MobileActionExecutor  { throw error })
            try { pipeline.execute(ActionRequest("read_battery")); fail("Expected original exception") }
            catch (observed: Exception) { assertSame(error, observed) }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state)
        }
    }

    @Test fun permissionAndServiceFailuresRetainTheirDistinctOutcomes() = withFile { file ->
        for ((error, outcome) in listOf(SecurityException() to ExecutionResult.Outcome.DENIED_PERMISSION,
            IllegalStateException() to ExecutionResult.Outcome.UNKNOWN_COMPLETION)) {
            file.delete()
            val result = JournaledActionPipeline(ToolTaskLedger(FileToolTaskStore(file)), MobileActionExecutor  { throw error })
                .execute(ActionRequest("read_battery"))
            assertEquals(outcome, result.outcome)
            assertEquals(outcome, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().resultOutcome)
        }
    }

    @Test fun observerSeesDurableRunningBeforeExecutorAndReceiptAfterward() = withFile { file ->
        for (bound in listOf(false, true)) {
            file.delete()
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val observed = mutableListOf<ToolTaskState>()
            var calls = 0
            val request = ActionRequest("read_battery")
            val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
                calls++
                assertEquals(listOf(ToolTaskState.RUNNING), observed)
                ExecutionResult(true, "85%")
            }, onJournalChanged = {
                observed += ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state
            })
            val result = if (bound) {
                val group = ledger.admit(listOf(request), "conversation-a")
                pipeline.executeBound(requireNotNull(ledger.get(group.attemptIds.single())), request)
            } else pipeline.execute(request)

            assertTrue(result.succeeded)
            assertEquals(1, calls)
            assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.SUCCEEDED), observed)
        }
    }

    @Test fun observerSeesFailureAndCancellationWithoutChangingTheOriginalOutcome() = withFile { file ->
        for (error in listOf(CancellationException("cancel"), IllegalArgumentException("bug"))) {
            file.delete()
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val observed = mutableListOf<ToolTaskState>()
            var calls = 0
            val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor { calls++; throw error },
                onJournalChanged = { observed += ledger.snapshot().single().state })

            try { pipeline.execute(ActionRequest("read_battery")); fail("Expected original exception") }
            catch (caught: Exception) { assertSame(error, caught) }
            assertEquals(1, calls)
            assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.UNKNOWN_OUTCOME), observed)
        }
    }

    @Test fun observerFailureCannotBlockRetryOrReplaceDispatch() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        var observations = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "85%")
        }, onJournalChanged = {
            observations++
            throw IllegalStateException("fixture presentation failure")
        })

        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertEquals(1, calls)
        assertEquals(2, observations)
        assertEquals(ToolTaskState.SUCCEEDED, ledger.snapshot().single().state)
    }

    @Test fun rejectedRequestsNeverPublishRunningOrCallExecutor() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        var observations = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "effect")
        }, onJournalChanged = { observations++ })

        assertEquals(ExecutionResult.Outcome.REJECTED_VALIDATION,
            pipeline.execute(ActionRequest("set_volume", mapOf("level" to "999"))).outcome)
        assertEquals(0, calls)
        assertEquals(0, observations)
        assertFalse(file.exists())
    }

    @Test fun failedReceiptWritePublishesActualRunningStateWithoutInventingSuccess() = withFile { file ->
        val goodStore = FileToolTaskStore(file)
        var writes = 0
        val store = object : ToolTaskStore {
            override fun read() = goodStore.read()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
                if (++writes == 3) throw ToolTaskStorageException()
                return goodStore.update(change)
            }
        }
        val ledger = ToolTaskLedger(store)
        val observed = mutableListOf<ToolTaskState>()
        var calls = 0
        val result = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "effect")
        }, onJournalChanged = { observed += ledger.snapshot().single().state }).execute(ActionRequest("read_battery"))

        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.RUNNING), observed)
        assertEquals(1, calls)
    }

    @Test fun secretFillTextRedactedPreJournalButDispatchedVerbatim() = withFile { file ->
        // Pre-journal credential boundary: the journaled attempt redacts the
        // secret argument, while the live dispatch uses the original.
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var dispatched: MobileAction? = null
        val observed = mutableListOf<ToolTaskState>()
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction): ExecutionResult {
                dispatched = action
                assertEquals(listOf(ToolTaskState.RUNNING), observed)
                assertFalse(file.readText().contains("s3cr3t-pw"))
                return ExecutionResult(true, "filled")
            }
            override fun secretArgumentKeys(request: ActionRequest): Set<String> =
                if (request.name == "browse_fill") setOf("text") else emptySet()
        }
        val pipeline = JournaledActionPipeline(ledger, executor, onJournalChanged = {
            val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
            assertEquals(CredentialBoundary.REDACTED, saved.request.arguments["text"])
            assertFalse("observation must only see redacted durable bytes", file.readText().contains("s3cr3t-pw"))
            observed += saved.state
        })
        val request = ActionRequest(
            "browse_fill",
            mapOf("field" to "f1", "text" to "s3cr3t-pw", "token" to "0123456789abcdef")
        )
        assertTrue(pipeline.execute(request).succeeded)
        assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.SUCCEEDED), observed)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(CredentialBoundary.REDACTED, saved.request.arguments["text"])
        assertEquals("f1", saved.request.arguments["field"])
        assertEquals("the live dispatch must use the original secret",
            MobileAction.BrowseFill("f1", "s3cr3t-pw", "0123456789abcdef"), dispatched)
        assertFalse("the secret must never reach the bytes on disk",
            file.readText().contains("s3cr3t-pw"))
    }

    @Test fun nonSecretRequestsJournalVerbatim() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction) = ExecutionResult(true, "ok")
            override fun secretArgumentKeys(request: ActionRequest): Set<String> = emptySet()
        }
        val pipeline = JournaledActionPipeline(ledger, executor)
        val request = ActionRequest("set_volume", mapOf("level" to "20"))
        assertTrue(pipeline.execute(request).succeeded)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(mapOf("level" to "20"), saved.request.arguments)
    }

    @Test fun sourceAdmissionStorageFailureIsReportedBeforeSecretInspectionOrDispatch() = withFile { file ->
        val raw = "corrupt journal with private contents"
        file.writeText(raw)
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        var inspections = 0
        var observations = 0
        val failures = mutableListOf<ToolTaskStorageFailure>()
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction): ExecutionResult {
                calls++
                return ExecutionResult(true, "effect")
            }
            override fun secretArgumentKeys(request: ActionRequest): Set<String> {
                inspections++
                return setOf("text")
            }
        }
        val result = JournaledActionPipeline(ledger, executor,
            sourceAccess = ToolSourceAccess(ledger),
            onJournalChanged = { observations++ },
            onStorageFailure = { failure, _ ->
                failures += failure.failure
                throw IllegalStateException("presentation failure")
            }).execute(ActionRequest("browse_fill",
                mapOf("field" to "f1", "text" to "s3cr3t-pw", "token" to "0123456789abcdef")))

        assertFalse(result.succeeded)
        assertTrue(result.message.contains("I didn't start this action."))
        assertFalse(result.message.contains(raw))
        assertFalse(result.message.contains("s3cr3t-pw"))
        assertEquals(listOf(ToolTaskStorageFailure.INVALID_CONTENT), failures)
        assertEquals(0, calls)
        assertEquals(0, inspections)
        assertEquals(0, observations)
        assertEquals(raw, file.readText())
    }

    @Test fun redactedIntentSurvivesReceiptFailureAndRecoveryWithoutRepeatingTheSecretEffect() = withFile { file ->
        val goodStore = FileToolTaskStore(file)
        var writes = 0
        val store = object : ToolTaskStore {
            override fun read() = goodStore.read()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
                if (++writes == 3) throw ToolTaskStorageException(ToolTaskStorageFailure.WRITE_FAILED)
                return goodStore.update(change)
            }
        }
        val ledger = ToolTaskLedger(store)
        val observed = mutableListOf<ToolTaskState>()
        var calls = 0
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction): ExecutionResult {
                calls++
                assertEquals("s3cr3t-pw", (action as MobileAction.BrowseFill).text)
                return ExecutionResult(true, "filled")
            }
            override fun secretArgumentKeys(request: ActionRequest): Set<String> = setOf("text")
        }
        val result = JournaledActionPipeline(ledger, executor,
            onJournalChanged = { observed += ledger.snapshot().single().state })
            .execute(ActionRequest("browse_fill",
                mapOf("field" to "f1", "text" to "s3cr3t-pw", "token" to "0123456789abcdef")))

        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.RUNNING), observed)
        assertFalse(file.readText().contains("s3cr3t-pw"))
        val recovered = ToolTaskLedger(goodStore).recoverAfterRestart().single()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recovered.state)
        assertEquals(CredentialBoundary.REDACTED, recovered.request.arguments["text"])
        assertFalse(file.readText().contains("s3cr3t-pw"))
        assertEquals(1, calls)
    }

}
