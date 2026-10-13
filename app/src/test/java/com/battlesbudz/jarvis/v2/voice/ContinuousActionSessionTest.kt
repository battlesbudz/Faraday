package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue
import com.battlesbudz.jarvis.v2.actions.AcceptedActionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousActionSessionTest {
    @Test fun newSessionOwnsOnlyNewCommandsAndDoesNotRestoreDeliveredOldReports() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val first = ContinuousActionSession(queue)
        assertTrue(first.reserveActionAdmission("facebook"))
        queue.admit("facebook", "u-facebook", "Facebook")
        queue.start { true }.join()
        first.onTaskEvent("facebook", "Opening Facebook.")
        val report = checkNotNull(first.nextDelivery())
        assertTrue(first.markDelivered(report.attemptId, setOf("facebook")))
        assertTrue(first.ownsActionTask("facebook"))

        val second = ContinuousActionSession(queue)
        assertFalse(second.ownsActionTask("facebook"))
        assertTrue(second.reserveActionAdmission("instagram"))
        queue.admit("instagram", "u-instagram", "Instagram")
        queue.start { true }.join()
        queue.tasks.value.filter { second.ownsActionTask(it.id) }.forEach {
            second.onTaskEvent(it.id, "Requested opening ${it.value}.")
        }
        assertEquals(listOf("instagram"), checkNotNull(second.nextDelivery()).reports.map { it.taskId })
        queue.close()
    }

    @Test fun interruptedReportRemainsOwnedButRejectedAdmissionDoesNot() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        session.reserveActionAdmission("accepted")
        queue.admit("accepted", "u", "Accepted")
        queue.start { true }.join()
        session.onTaskEvent("accepted", "Accepted result.")
        val report = checkNotNull(session.nextDelivery())
        session.interruptDelivery(report.attemptId)
        assertTrue(session.ownsActionTask("accepted"))
        assertEquals("accepted", checkNotNull(session.nextDelivery()).reports.single().taskId)
        session.reserveActionAdmission("rejected")
        assertTrue(session.abandonActionAdmission("rejected"))
        assertFalse(session.ownsActionTask("rejected"))
        queue.close()
    }

    @Test fun captureRemainsActiveAndFollowupIsAdmittedWhileFirstTaskBlocks() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val invoked = mutableListOf<String>()
        val worker = queue.start { task ->
            invoked += task.value
            if (task.value == "A") {
                enteredA.complete(Unit)
                releaseA.await()
            }
            true
        }
        enteredA.await()

        session.onCaptureStarted()
        assertTrue(session.isCaptureInProgress())
        val followup = SessionCapture("utterance-b", "open B")
        assertEquals(CaptureOutcome.AcceptedAction(followup), session.onCaptured(followup, CapturedKind.AcceptedAction))
        assertNotNull(queue.admit("b", "utterance-b", "B"))
        assertEquals("a", queue.activeTask()?.id)
        assertEquals(1, queue.pendingCount())

        releaseA.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertEquals(listOf("A", "B"), invoked)
        queue.close()
    }

    @Test fun completionDuringCaptureWaitsForFinalFloorThenSignalsDeliveryWithoutAnotherUtterance() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = queue.start { task ->
            entered.complete(Unit)
            release.await()
            session.onTaskEvent(task.id, "A completed")
            true
        }
        entered.await()
        session.onCaptureStarted()
        release.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertNull(session.nextDelivery()) // A real utterance is still being finalized.

        val ready = CompletableDeferred<Unit>()
        val wait = async { session.awaitDeliveryReady(); ready.complete(Unit) }
        assertFalse(ready.isCompleted)
        assertTrue(session.onCaptured(
            SessionCapture("utterance-followup", "what else?", recognitionIssue = "uncertain"),
            CapturedKind.Ordinary
        ) is CaptureOutcome.RecognitionIssue)
        withTimeout(1_000) { ready.await() }
        val delivery = requireNotNull(session.nextDelivery())
        assertEquals(listOf(PendingReport("a", "A completed")), delivery.reports)
        wait.cancel()
        queue.close()
    }

    @Test fun completionWhileIdleStartsDeliveryAndOnlyPlaybackCompletionMarksItDelivered() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        queue.drain { task ->
            assertTrue(session.onTaskEvent(task.id, "A done"))
            true
        }
        val delivery = requireNotNull(session.nextDelivery())
        assertFalse(session.markDelivered(delivery.attemptId + 1, setOf("a")))
        assertEquals(1, session.pendingReportCount())
        assertTrue(session.markDelivered(delivery.attemptId, setOf("a")))
        assertEquals(0, session.pendingReportCount())
        assertNull(session.nextDelivery())
        queue.close()
    }

    @Test fun admissionReservationSurvivesDelayedTerminalEventUntilPlaybackAcknowledgement() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue, reportCapacity = 1)
        assertTrue(session.reserveActionAdmission("a"))
        assertNotNull(queue.admit("a", "utterance-a", "A"))
        queue.drain { true } // Queue is terminal before its runtime callback relays a report.
        assertFalse(session.canAdmitAcceptedAction())
        assertFalse(session.reserveActionAdmission("b"))

        assertTrue(session.onTaskEvent("a", "A result"))
        val delivery = requireNotNull(session.nextDelivery())
        assertTrue(session.markDelivered(delivery.attemptId, setOf("a")))
        assertTrue(session.reserveActionAdmission("b"))
        assertTrue(session.abandonActionAdmission("b")) // Queue admission did not occur.
        queue.close()
    }

    @Test fun admissionBackpressureReservesReportCapacityBeforeNewTasksCanBeAccepted() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue, reportCapacity = 3)
        repeat(3) { index ->
            val id = "task-$index"
            assertTrue(session.reserveActionAdmission(id))
            assertNotNull(queue.admit(id, "utterance-$index", id))
            queue.drain { task ->
                assertTrue(session.onTaskEvent(task.id, "result ${task.id}"))
                true
            }
        }
        assertEquals(3, session.pendingReportCount())
        assertFalse(session.reserveActionAdmission("overflow"))
        // The runtime must return a bounded busy/retry reply here instead of admitting task four.
        assertFalse(session.onTaskEvent("overflow", "durably retained outside this session"))
        val delivery = requireNotNull(session.nextDelivery())
        assertTrue(session.markDelivered(delivery.attemptId, setOf("task-0", "task-1", "task-2")))
        assertTrue(session.reserveActionAdmission("overflow"))
        assertTrue(session.onTaskEvent("overflow", "refilled from durable receipt"))
        queue.close()
    }

    @Test fun interruptedReportThenNewTaskPreservesBothPendingOutcomesInFifoOrder() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val executions = mutableListOf<String>()
        queue.admit("a", "utterance-a", "A")
        queue.drain { task ->
            executions += task.value
            session.onTaskEvent(task.id, "A result")
            true
        }
        val firstAttempt = requireNotNull(session.nextDelivery())
        assertTrue(session.interruptDelivery(firstAttempt.attemptId))

        queue.admit("b", "utterance-b", "B")
        queue.drain { task ->
            executions += task.value
            session.onTaskEvent(task.id, "B result")
            true
        }
        val resumed = requireNotNull(session.nextDelivery())
        assertEquals(listOf("a", "b"), resumed.reports.map { it.taskId })
        assertEquals(listOf("A", "B"), executions)
        assertTrue(session.markDelivered(resumed.attemptId, setOf("a", "b")))
        assertEquals(0, session.pendingReportCount())
        queue.close()
    }

    @Test fun ordinaryTurnIsRetainedUntilWorkerIdleAndStableCaptureIdsAreDeduplicated() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = queue.start {
            entered.complete(Unit)
            release.await()
            true
        }
        entered.await()
        session.onCaptureStarted()
        val ordinary = SessionCapture("ordinary-1", "tell me a joke")
        assertEquals(CaptureOutcome.DeferredConversation(ordinary), session.onCaptured(ordinary, CapturedKind.Ordinary))
        assertEquals(CaptureOutcome.Duplicate, session.onCaptured(ordinary, CapturedKind.Ordinary))
        assertNull(session.takeDeferredConversation())
        release.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertEquals(ordinary, session.takeDeferredConversation())
        assertNull(session.takeDeferredConversation())
        queue.close()
    }

    @Test fun explicitControlIsDelegatedButSpeechOnlyLeavesTasksAndReportsAlive() = runBlocking {
        val queue = AcceptedActionQueue<Int>()
        val session = ContinuousActionSession(queue)
        queue.admit("one", "utterance-one", 1)
        queue.admit("two", "utterance-two", 2)
        assertEquals(VoiceActionControl.SpeechOnly, session.control("stop speaking", hasUnfinished = true))
        assertEquals(2, queue.pendingCount())
        val explicit = session.control("cancel all actions", hasUnfinished = true)
        assertEquals(VoiceActionControl.CancelAll, explicit)
        assertEquals(2, queue.pendingCount()) // Runtime applies the call-scoped queue cancellation.
        queue.cancel(explicit)
        assertEquals(listOf(AcceptedActionState.CANCELLED, AcceptedActionState.CANCELLED), queue.tasks.value.map { it.state })
        queue.close()
    }

    @Test fun duplicateTerminalEventsNeverRepeatReportsAndDetachDoesNotCancelQueueWork() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        assertTrue(session.onTaskEvent("a", "first result"))
        assertTrue(session.onTaskEvent("a", "replayed result"))
        assertEquals(1, session.pendingReportCount())
        session.detach()
        assertNull(session.nextDelivery())
        val executed = mutableListOf<String>()
        queue.drain { task -> executed += task.value; true }
        assertEquals(listOf("A"), executed)
        queue.close()
    }
    @Test fun laterOrdinaryCaptureDoesNotReplaceDeferredAudioAfterWorkerBecomesIdle() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("a", "utterance-a", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = queue.start {
            entered.complete(Unit)
            release.await()
            true
        }
        entered.await()
        session.onCaptureStarted()
        val deferred = SessionCapture("ordinary-first", "first question", wav = byteArrayOf(1, 2), audioIsComplete = false)
        assertEquals(CaptureOutcome.DeferredConversation(deferred), session.onCaptured(deferred, CapturedKind.Ordinary))
        release.complete(Unit)
        withTimeout(1_000) { worker.join() }

        session.onCaptureStarted()
        val later = SessionCapture("ordinary-later", "second question", wav = byteArrayOf(3, 4), audioIsComplete = true)
        assertEquals(CaptureOutcome.ConversationBusy(later), session.onCaptured(later, CapturedKind.Ordinary))
        val handedOff = requireNotNull(session.takeDeferredConversation())
        assertEquals("ordinary-first", handedOff.utteranceId)
        assertEquals("first question", handedOff.text)
        assertFalse(handedOff.audioIsComplete)
        assertTrue(handedOff.wav.contentEquals(byteArrayOf(1, 2)))
        queue.close()
    }

    @Test fun oneLocalRejectionIsRetainedForAudibleDeliveryWithoutTakingAnActionReservation() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue, reportCapacity = 1)
        assertTrue(session.reserveActionAdmission("action"))
        assertNotNull(queue.admit("action", "utterance-action", "A"))
        queue.drain { task ->
            assertTrue(session.onTaskEvent(task.id, "A completed"))
            true
        }
        assertTrue(session.offerLocalFeedback("I couldn't accept that follow-up."))
        assertTrue(session.offerLocalFeedback("The second follow-up was also rejected."))
        val report = requireNotNull(session.nextDelivery())
        assertEquals(listOf("action", "local-feedback-1"), report.reports.map { it.taskId })
        assertTrue(report.reports.last().text.contains("second follow-up"))
        assertTrue(session.markDelivered(report.attemptId, report.reports.mapTo(linkedSetOf()) { it.taskId }))
        assertEquals(0, session.pendingReportCount())
        queue.close()
    }

    @Test fun ordinaryFinalAtQueueIdleKeepsExecutorReportUntilPlaybackAcknowledgesIt() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        assertTrue(session.reserveActionAdmission("action"))
        assertNotNull(queue.admit("action", "utterance-action", "A"))
        queue.drain { task ->
            assertTrue(session.onTaskEvent(task.id, "A completed"))
            true
        }
        session.onCaptureStarted()
        val ordinary = SessionCapture("ordinary", "tell me a joke", wav = byteArrayOf(7), audioIsComplete = true)
        assertEquals(CaptureOutcome.DeferredConversation(ordinary), session.onCaptured(ordinary, CapturedKind.Ordinary))
        // The runtime must defer this ordinary handoff until this delivery completes.
        session.onCaptureStarted()
        val later = SessionCapture("ordinary-later", "second question")
        assertEquals(CaptureOutcome.ConversationBusy(later), session.onCaptured(later, CapturedKind.Ordinary))
        val report = requireNotNull(session.nextDelivery())
        assertEquals(listOf("action"), report.reports.map { it.taskId })
        assertTrue(session.markDelivered(report.attemptId, setOf("action")))
        assertEquals(0, session.pendingReportCount())
        assertEquals("ordinary", ordinary.utteranceId)
        queue.close()
    }

    @Test fun pausedCaptureBoundaryDoesNotCancelBlockedAcceptedWorker() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("action", "utterance-action", "A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = queue.start {
            entered.complete(Unit)
            release.await()
            true
        }
        entered.await()
        session.onCaptureStarted()
        session.onCaptureStopped() // runtime's microphone-paused final boundary
        assertTrue(queue.hasUnfinished())
        release.complete(Unit)
        withTimeout(1_000) { worker.join() }
        assertEquals(AcceptedActionState.COMPLETED, queue.tasks.value.single().state)
        queue.close()
    }

    @Test fun queuedCancellationKeepsOneTerminalReportForEveryReservedAction() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        listOf("a", "b").forEach { id ->
            assertTrue(session.reserveActionAdmission(id))
            assertNotNull(queue.admit(id, "utterance-$id", id))
        }
        queue.cancel(VoiceActionControl.CancelAll)
        queue.drain { true } // drains the cancelled records to its observable idle transition
        assertEquals(listOf(AcceptedActionState.CANCELLED, AcceptedActionState.CANCELLED), queue.tasks.value.map { it.state })
        // This mirrors the runtime's idle terminal relay, including actions that never entered execute.
        queue.tasks.value.forEach { task -> assertTrue(session.onTaskEvent(task.id, "Cancelled; unattempted: ${task.value}.")) }
        val report = requireNotNull(session.nextDelivery())
        assertEquals(listOf("a", "b"), report.reports.map { it.taskId })
        assertTrue(session.markDelivered(report.attemptId, setOf("a", "b")))
        queue.close()
    }

    @Test fun unrelayedTerminalReservationDefersFirstOrdinaryCaptureAndRejectsSecond() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        assertTrue(session.reserveActionAdmission("action"))
        assertNotNull(queue.admit("action", "utterance-action", "A"))
        queue.drain { true } // terminal, but runtime relay has not yet published its receipt
        session.onCaptureStarted()
        val first = SessionCapture("ordinary-first", "first ordinary", wav = byteArrayOf(9))
        assertEquals(CaptureOutcome.DeferredConversation(first), session.onCaptured(first, CapturedKind.Ordinary))
        session.onCaptureStarted()
        val second = SessionCapture("ordinary-second", "second ordinary")
        assertEquals(CaptureOutcome.ConversationBusy(second), session.onCaptured(second, CapturedKind.Ordinary))
        assertEquals(first, session.takeDeferredConversation())
        queue.close()
    }

    @Test fun typedControlUsesTheAcceptedSessionWithoutClearingABegunSpokenFloor() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        queue.admit("blocked", "spoken-action", "A")
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val worker = queue.start { entered.complete(Unit); hold.await(); true }
        entered.await()
        session.onCaptureStarted()
        val typed = SessionCapture("typed-cancel", "cancel that action", origin = TranscriptOrigin.TYPED)
        assertEquals(CaptureOutcome.Control(typed, VoiceActionControl.CancelCurrent),
            session.onTyped(typed, CapturedKind.Control(VoiceActionControl.CancelCurrent)))
        assertTrue(session.isCaptureInProgress())
        // The runtime applies this call-scoped control to the same durable queue.
        queue.cancel(VoiceActionControl.CancelCurrent)
        assertEquals(AcceptedActionState.CANCELLED, queue.tasks.value.single().state)
        worker.join()
        queue.close()
    }

    @Test fun idleHandoffNeverStealsConfirmedSpokenFloor() = runBlocking {
        val queue = AcceptedActionQueue<String>(); val session = ContinuousActionSession(queue)
        queue.admit("a", "a", "A")
        val release = CompletableDeferred<Unit>()
        val worker = queue.start { release.await(); true }
        session.onCaptureStarted()
        val deferred = SessionCapture("ordinary", "question")
        assertTrue(session.onCaptured(deferred, CapturedKind.Ordinary) is CaptureOutcome.DeferredConversation)
        // Simulate the next confirmed capture before idle handoff; it must keep its floor.
        session.onCaptureStarted()
        release.complete(Unit); worker.join()
        assertNull(session.takeDeferredConversationIfNoCapture())
        session.onCaptureStopped()
        assertEquals(deferred, session.takeDeferredConversationIfNoCapture())
        queue.close()
    }

    @Test fun idleCaptureDetachKeepsDeferredInputRetrievable() = runBlocking {
        val queue = AcceptedActionQueue<String>(); val session = ContinuousActionSession(queue)
        val deferred = SessionCapture("typed-b", "B", origin = TranscriptOrigin.TYPED)
        assertTrue(session.onCaptured(deferred, CapturedKind.Ordinary) is CaptureOutcome.ConversationReady)
        // A real deferred slot is retained while work exists; detach only capture ownership.
        queue.admit("a", "a", "A")
        assertTrue(session.onCaptured(deferred.copy(utteranceId = "deferred"), CapturedKind.Ordinary) is CaptureOutcome.DeferredConversation)
        queue.drain { true }
        assertTrue(session.tryDetachIdleCapture())
        assertEquals("deferred", session.takeDeferredConversationIfNoCapture()?.utteranceId)
        queue.close()
    }

    @Test fun idleObservationRemainsArmedWhenExecutorFinishedBeforeThePumpListener() = runBlocking {
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        assertTrue(session.reserveActionAdmission("fast"))
        assertNotNull(queue.admit("fast", "utterance-fast", "fast"))
        // The executor completed before the host had attached its first idle listener.
        queue.drain { true }
        assertTrue(session.needsIdleObservation(terminalReportsObserved = false))
        assertFalse(session.needsIdleObservation(terminalReportsObserved = true))
        // Relaying the durable terminal record makes a report visible without rerunning work.
        assertTrue(session.onTaskEvent("fast", "fast complete"))
        assertNotNull(session.nextDelivery())
        queue.close()
    }

    @Test fun productionPumpSelectorObservesFastIdleThenStillAcceptsTypedControl() = runBlocking {
        val followup = CompletableDeferred<CapturedVoiceTurn>()
        val delivery = CompletableDeferred<Unit>()
        val idle = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val typed = CompletableDeferred<Unit>()
        var capture: CapturedVoiceTurn? = null
        assertEquals(ActionPumpEvent.WORKER_IDLE,
            awaitActionPumpEvent(followup, delivery, idle, typed) { capture = it })
        assertNull(capture)
        // The first selection did not cancel the persistent typed waiter or the ASR floor.
        typed.complete(Unit)
        assertEquals(ActionPumpEvent.TYPED_AVAILABLE,
            awaitActionPumpEvent(followup, delivery, null, typed) { capture = it })
        assertNull(capture)
    }

}
