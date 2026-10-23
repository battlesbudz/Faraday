package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CheckedConversationLifecycleTest {
    private class Child(val number: Int)
    private class Native {
        val events = mutableListOf<String>()
        var created = 0
        var creating: () -> Unit = {}
        var drain: () -> Unit = {}
        var cancel: () -> Unit = {}
        var dispose: () -> Unit = {}
        var disposeEngine: () -> Unit = {}
        var workerAllowed = true
        var quarantineNotifications = 0
        val quarantineFailures = mutableListOf<Throwable>()
        val owner = CheckedConversationLifecycle(
            createConversation = { creating(); Child(++created).also { events += "create:${it.number}" } },
            cancelConversation = { events += "cancel:${it.number}"; cancel() },
            awaitIdle = { events += "drain:${it.number}"; drain() },
            closeConversation = { events += "close:${it.number}"; dispose() },
            closeEngine = { events += "engine.close"; disposeEngine() },
            checkWorkerThread = { check(workerAllowed) { "UI or native callback" } },
            onQuarantined = { quarantineNotifications++; quarantineFailures += it }
        ).also { it.initialize { events += "initialize" } }
    }
    private fun failure(block: () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected failure")
    }

    @Test fun closedStateIsReportedOnlyOnceCloseIsRequested() {
        val native = Native()
        assertFalse(native.owner.isClosed)
        val turn = native.owner.beginTurn()
        native.owner.finishTurn(turn, { true })
        assertFalse("A finished turn does not close the engine", native.owner.isClosed)
        native.owner.close()
        assertTrue(native.owner.isClosed)
        // A closed engine still reports safeToRelease; isClosed is the
        // separate signal the post-answer caption guard relies on.
        assertTrue(native.owner.safeToRelease)
    }

    @Test fun failedCloseStillReportsClosedSoOptionalWorkSkips() {
        val native = Native()
        native.disposeEngine = { error("engine close failed") }
        failure { native.owner.close() }
        assertTrue("Close was requested even though the drain failed", native.owner.isClosed)
        assertTrue(native.owner.isQuarantined)
    }

    @Test fun earlyPreparedChildIsIdleAndUsedExactlyOnce() {
        val native = Native()
        assertTrue(native.owner.prepareConversation { true })
        assertTrue(native.owner.hasUnusedPreparedConversation)
        assertTrue(native.owner.safeToRelease)
        assertEquals(listOf("initialize", "create:1", "drain:1"), native.events)
        assertTrue(native.owner.prepareConversation { true })
        assertEquals(1, native.created)
        val turn = native.owner.beginTurn()
        assertFalse(native.owner.hasUnusedPreparedConversation)
        native.owner.finishTurn(turn, { true })
        assertFalse(native.owner.prepareConversation { true })
        assertEquals(1, native.created)
    }
    @Test fun resetDiscardsPreparationBeforeAnyNewTurn() {
        val native = Native()
        native.owner.prepareConversation { true }; native.owner.resetForConfirmedHistory()
        assertFalse(native.owner.hasUnusedPreparedConversation)
        val turn = native.owner.beginTurn()
        assertEquals(2, turn.conversation.number)
        native.owner.finishTurn(turn, { true })
    }
    @Test fun ownerCancelledDuringPreparationCannotPublishItsChild() {
        val native = Native(); val current = AtomicBoolean(true)
        native.drain = { current.set(false) }
        assertFalse(native.owner.prepareConversation(current::get))
        assertFalse(native.owner.hasUnusedPreparedConversation)
        assertTrue(native.owner.safeToRelease)
        assertEquals(listOf("initialize", "create:1", "drain:1", "close:1"), native.events)
    }
    @Test fun concurrentGenerationAndResetAreRejectedDuringEarlyPrefill() {
        val native = Native(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        native.creating = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        val error = AtomicReference<Throwable?>()
        val worker = thread { try { native.owner.prepareConversation { true } } catch (caught: Throwable) { error.set(caught) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(native.owner.safeToRelease)
        failure { native.owner.beginTurn() }; failure { native.owner.resetForConfirmedHistory() }
        release.countDown(); worker.join(2000)
        assertNull(error.get()); assertTrue(native.owner.hasUnusedPreparedConversation)
        native.owner.close()
    }
    @Test fun failedPreparationDrainQuarantinesBeforeReuse() {
        val native = Native(); native.drain = { error("prefill drain failed") }
        failure { native.owner.prepareConversation { true } }
        assertTrue(native.owner.isQuarantined); assertFalse(native.owner.safeToRelease)
        failure { native.owner.beginTurn() }; assertFalse(native.events.contains("close:1"))
    }

    @Test fun successfulTerminalStillNeedsCheckedDrainBeforeReuseOrTelemetry() {
        val native = Native()
        val turn = native.owner.beginTurn()
        assertFalse(native.owner.safeToRelease)
        assertTrue(native.owner.finishTurn(turn, { true }, { native.events += "telemetry:${it.number}" }))
        assertEquals(listOf("initialize", "create:1", "drain:1", "telemetry:1"), native.events)
        assertTrue(native.owner.safeToRelease)
        val next = native.owner.beginTurn()
        assertSame(turn.conversation, next.conversation)
        native.owner.finishTurn(next, { true })
        native.owner.close()
        native.owner.close()
        assertEquals(1, native.events.count { it == "engine.close" })
        assertEquals(1, native.events.count { it == "close:1" })
    }

    @Test fun submissionExceptionCallbackErrorAndCancellationAllInvalidateHistory() {
        // The owner deliberately treats every kind of unsuccessful send identically.
        for (kind in listOf("submission rejection", "callback error", "cancel")) {
            val native = Native()
            val turn = native.owner.beginTurn()
            assertFalse(kind, native.owner.finishTurn(turn, { false }))
            assertEquals(listOf("initialize", "create:1", "cancel:1", "drain:1", "close:1"), native.events)
            assertTrue(kind, native.owner.requiresConfirmedHistoryRebuild)
            assertTrue(kind, native.owner.safeToRelease)
            failure { native.owner.beginTurn() }
            native.owner.resetForConfirmedHistory()
            assertFalse(native.owner.requiresConfirmedHistoryRebuild)
            val replacement = native.owner.beginTurn()
            assertNotSame(turn.conversation, replacement.conversation)
            native.owner.finishTurn(replacement, { true })
        }
    }

    @Test fun cancellationDuringPostIdleTelemetryInvalidatesTheCandidateBeforeReuse() {
        val native = Native()
        val active = AtomicBoolean(true)
        val turn = native.owner.beginTurn()
        assertFalse(native.owner.finishTurn(turn, active::get) { child ->
            assertEquals("drain:1", native.events.last())
            native.events += "telemetry:${child.number}"
            active.set(false)
        })
        assertEquals(listOf("initialize", "create:1", "drain:1", "telemetry:1", "close:1"), native.events)
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        assertTrue(native.owner.safeToRelease)
        failure { native.owner.beginTurn() }
    }

    @Test fun cancellationDuringDrainDiscardsTheCompletedCandidate() {
        val native = Native()
        val active = AtomicBoolean(true)
        val turn = native.owner.beginTurn()
        native.drain = { active.set(false) }
        assertFalse(native.owner.finishTurn(turn, active::get))
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        assertEquals(listOf("initialize", "create:1", "drain:1", "close:1"), native.events)
    }

    @Test fun drainTimeoutRetainsBothHandlesAndCannotImplicitlyRetryOrRebuild() {
        val native = Native()
        val timeout = IllegalStateException("native drain timed out")
        native.drain = { throw timeout }
        val turn = native.owner.beginTurn()
        assertSame(timeout, failure { native.owner.finishTurn(turn, { true }) })
        assertTrue(native.owner.isQuarantined)
        assertEquals(1, native.quarantineNotifications)
        assertFalse(native.owner.safeToRelease)
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        failure { native.owner.close() }
        failure { native.owner.resetForConfirmedHistory() }
        failure { native.owner.beginTurn() }
        assertEquals(listOf("initialize", "create:1", "drain:1"), native.events)
        native.drain = {}
        native.owner.retryQuarantinedClose()
        assertEquals(listOf("initialize", "create:1", "drain:1", "drain:1", "close:1", "engine.close"), native.events)
        assertTrue(native.owner.safeToRelease)
        assertFalse(native.owner.isQuarantined)
        failure { native.owner.beginTurn() }
    }

    @Test fun quarantineObserverReceivesTheExactDrainFailure() {
        val native = Native()
        val drainFailure = IllegalStateException("native drain failed")
        native.drain = { throw drainFailure }
        val turn = native.owner.beginTurn()
        assertSame(drainFailure, failure { native.owner.finishTurn(turn, { true }) })
        assertSame(drainFailure, native.quarantineFailures.single())
        assertTrue(native.owner.isQuarantined)
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        assertFalse(native.owner.safeToRelease)
    }

    @Test fun fixtureResetCannotReplaceABorrowedConversation() {
        // The reliability runner resets before every fixture through ToolCallEngine.
        // Its reset must respect the same active native owner as ordinary generation.
        val native = Native()
        val turn = native.owner.beginTurn()
        val beforeReset = native.events.toList()
        failure { native.owner.resetForConfirmedHistory() }
        assertEquals(beforeReset, native.events)
        assertFalse(native.owner.safeToRelease)
        assertTrue(native.owner.finishTurn(turn, { true }))

        native.owner.resetForConfirmedHistory()
        assertEquals(listOf("initialize", "create:1", "drain:1", "drain:1", "close:1"), native.events)
        val next = native.owner.beginTurn()
        assertNotSame(turn.conversation, next.conversation)
        assertTrue(native.owner.finishTurn(next, { true }))
        native.owner.close()
    }

    @Test fun fixtureResetDrainFailureQuarantinesBeforeAnotherConversationCanStart() {
        val native = Native()
        val turn = native.owner.beginTurn()
        assertTrue(native.owner.finishTurn(turn, { true }))
        val resetFailure = IllegalStateException("reset drain failed")
        native.drain = { throw resetFailure }

        assertSame(resetFailure, failure { native.owner.resetForConfirmedHistory() })
        assertSame(resetFailure, native.quarantineFailures.single())
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        assertFalse(native.owner.safeToRelease)
        failure { native.owner.beginTurn() }
        failure { native.owner.resetForConfirmedHistory() }
        failure { native.owner.close() }
        assertEquals(listOf("initialize", "create:1", "drain:1", "drain:1"), native.events)

        native.drain = {}
        native.owner.retryQuarantinedClose()
        assertEquals(1, native.events.count { it == "create:1" })
        assertEquals(1, native.events.count { it == "close:1" })
        assertEquals(1, native.events.count { it == "engine.close" })
    }

    @Test fun checkedChildCloseFailureNeverFallsThroughToParentEngineClose() {
        val native = Native()
        val turn = native.owner.beginTurn()
        native.owner.finishTurn(turn, { true })
        native.dispose = { error("checked delete timed out") }
        failure { native.owner.close() }
        assertTrue(native.owner.isQuarantined)
        assertFalse(native.owner.safeToRelease)
        assertFalse(native.events.contains("engine.close"))
        native.dispose = {}
        native.owner.retryQuarantinedClose()
        assertEquals(1, native.events.count { it == "engine.close" })
    }

    @Test fun cancellationFailureStillAttemptsCheckedDrainAndDispose() {
        val native = Native()
        val cancellationFailure = IllegalStateException("cancel JNI failed")
        native.cancel = { throw cancellationFailure }
        val turn = native.owner.beginTurn()
        assertSame(cancellationFailure, failure { native.owner.finishTurn(turn, { false }) })
        assertEquals(listOf("initialize", "create:1", "cancel:1", "drain:1", "close:1"), native.events)
        assertTrue(native.owner.safeToRelease)
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
    }

    @Test fun simultaneousCancelAndDrainFailurePreservesBothErrorsAndQuarantines() {
        val native = Native()
        val cancelError = IllegalStateException("cancel failed")
        val drainError = IllegalStateException("drain timed out")
        native.cancel = { throw cancelError }
        native.drain = { throw drainError }
        val turn = native.owner.beginTurn()
        assertSame(drainError, failure { native.owner.finishTurn(turn, { false }) })
        assertArrayEquals(arrayOf(cancelError), drainError.suppressed)
        assertFalse(native.events.contains("close:1"))
        assertFalse(native.owner.safeToRelease)
    }

    @Test fun closeRacingBorrowerDrainCannotDeleteEitherHandleOrDeadlockTheCallback() {
        val native = Native()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val turn = native.owner.beginTurn()
        native.drain = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            // A native callback must be able to inspect state while the owner blocks in drain.
            assertFalse(native.owner.safeToRelease)
        }
        val drainThread = thread {
            try { native.owner.finishTurn(turn, { true }) } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            failure { native.owner.close() }
            assertFalse(native.owner.safeToRelease)
            assertFalse(native.events.any { it.startsWith("close:") || it == "engine.close" })
        } finally { release.countDown(); drainThread.join(3_000) }
        assertFalse(drainThread.isAlive)
        error.get()?.let { throw it }
        native.drain = {}
        native.owner.close()
        assertTrue(native.owner.safeToRelease)
    }

    @Test fun uiAndCallbackLifecycleAttemptsAreRejectedBeforeNativeControl() {
        val native = Native()
        val before = native.events.toList()
        native.workerAllowed = false
        failure { native.owner.beginTurn() }
        failure { native.owner.close() }
        failure { native.owner.resetForConfirmedHistory() }
        failure { native.owner.retryQuarantinedClose() }
        assertEquals(before, native.events)
        assertTrue(native.owner.safeToRelease)
        native.workerAllowed = true
        val turn = native.owner.beginTurn()
        native.owner.finishTurn(turn, { true })
    }

    @Test fun parentCloseFailureRetainsQuarantineWithoutRepeatedChildDestruction() {
        val native = Native()
        val turn = native.owner.beginTurn()
        native.owner.finishTurn(turn, { true })
        native.disposeEngine = { error("engine disposal failed") }
        failure { native.owner.close() }
        assertFalse(native.owner.safeToRelease)
        assertTrue(native.owner.isQuarantined)
        native.disposeEngine = {}
        native.owner.retryQuarantinedClose()
        assertEquals(1, native.events.count { it == "close:1" })
        assertTrue(native.owner.safeToRelease)
    }

    @Test fun staleTurnCannotReleaseCurrentBorrower() {
        val native = Native()
        val old = native.owner.beginTurn()
        native.owner.finishTurn(old, { true })
        val current = native.owner.beginTurn()
        failure { native.owner.finishTurn(old, { false }) }
        assertFalse(native.owner.safeToRelease)
        native.owner.finishTurn(current, { true })
        assertTrue(native.owner.safeToRelease)
    }

    @Test fun metadataFailureAfterDrainDiscardsHistoryBeforeReturningError() {
        val native = Native()
        val turn = native.owner.beginTurn()
        val error = IllegalStateException("metadata failed")
        assertSame(error, failure { native.owner.finishTurn(turn, { true }, { throw error }) })
        assertTrue(native.owner.requiresConfirmedHistoryRebuild)
        assertTrue(native.owner.safeToRelease)
        assertEquals(listOf("initialize", "create:1", "drain:1", "close:1"), native.events)
    }
    @Test fun incrementalSessionLeaseBlocksEngineUntilCheckedChildCloseReturns() {
        val native = Native()
        val child = native.owner.createChild({ Any() }) { native.events += "session.drain+close" }
        assertFalse(native.owner.safeToRelease)
        failure { native.owner.beginTurn() }
        failure { native.owner.resetForConfirmedHistory() }
        native.owner.closeChild(child)
        native.owner.closeChild(child)
        assertTrue(native.owner.safeToRelease)
        native.owner.close()
        assertEquals(listOf("initialize", "session.drain+close", "engine.close"), native.events)
    }

    @Test fun incrementalSessionDrainFailureRetainsTheExactChildAndParentForExplicitRetry() {
        val native = Native()
        val token = Any()
        val child = native.owner.createChild({ token }) {
            assertSame(token, it)
            native.events += "session.drain+close"
        }
        native.owner.quarantineChild(child, IllegalStateException("session timed out"))
        assertTrue(native.owner.isQuarantined)
        assertFalse(native.owner.safeToRelease)
        failure { native.owner.closeChild(child) }
        failure { native.owner.close() }
        assertEquals(listOf("initialize"), native.events)
        native.owner.retryQuarantinedClose()
        native.owner.closeChild(child)
        assertEquals(listOf("initialize", "session.drain+close", "engine.close"), native.events)
        assertTrue(native.owner.safeToRelease)
    }

    @Test fun incrementalCheckedCloseFailureCannotReleaseModelOrDestroyParent() {
        val native = Native()
        var failClose = true
        val child = native.owner.createChild({ Any() }) {
            native.events += "session.close"
            check(!failClose) { "child close timed out" }
        }
        failure { native.owner.closeChild(child) }
        assertTrue(native.owner.isQuarantined)
        assertFalse(native.owner.safeToRelease)
        failure { native.owner.close() }
        assertFalse(native.events.contains("engine.close"))
        failClose = false
        native.owner.retryQuarantinedClose()
        assertEquals(1, native.events.count { it == "engine.close" })
    }

    @Test fun quarantineNotificationIsInjectedAndEmittedOnceAcrossFailedDisposalRetries() {
        val native = Native()
        native.drain = { error("native timeout") }
        val turn = native.owner.beginTurn()
        failure { native.owner.finishTurn(turn, { true }) }
        failure { native.owner.retryQuarantinedClose() }
        failure { native.owner.retryQuarantinedClose() }
        assertEquals(1, native.quarantineNotifications)
        assertFalse(native.owner.safeToRelease)
        native.drain = {}
        native.owner.retryQuarantinedClose()
        assertEquals(1, native.quarantineNotifications)
    }

    @Test fun childHolderAndCleanupArePreparedBeforeNativeAllocation() {
        val native = Native()
        val order = mutableListOf<String>()
        val token = Any()
        val lease = native.owner.createChild(
            create = { order += "create"; token },
            allocateLease = { close -> order += "holder"; CheckedConversationLifecycle.ChildLease(close) },
            checkedClose = { assertSame(token, it); order += "dispose" }
        )
        assertEquals(listOf("holder", "create"), order)
        assertSame(token, lease.child)
        native.owner.closeChild(lease)
        assertEquals(listOf("holder", "create", "dispose"), order)
    }

    @Test fun failedChildHolderAllocationCannotCreateOrStrandNativeHandle() {
        val native = Native()
        val allocationFailure = OutOfMemoryError("injected holder allocation failure")
        var nativeCreates = 0
        assertSame(allocationFailure, failure {
            native.owner.createChild(
                create = { nativeCreates++; Any() },
                allocateLease = { throw allocationFailure },
                checkedClose = { fail("no child may exist") }
            )
        })
        assertEquals(0, nativeCreates)
        assertTrue(native.owner.safeToRelease)
        native.owner.close()
        assertEquals(listOf("initialize", "engine.close"), native.events)
    }

}
