package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import org.junit.Assert.*
import org.junit.Test

class WispPresentationTest {

    @Test fun streamingAudioAndApprovalRemainTruthfulWhilePublicWorkIsActive() {
        val work = WispPresentation(WispActivity.THINKING, "Writing your reply")
        val speaking = present(armed = true, phase = VoicePhase.SPEAKING, observed = work)
        assertEquals(WispActivity.SPEAKING, speaking.activity)
        assertEquals("Speaking · Writing your reply", speaking.label)
        assertEquals(.8f, WispPresenter.audioLevel(speaking.activity, VoicePhase.SPEAKING, true, false, .2f, .8f), 0f)
        val listening = present(armed = true, phase = VoicePhase.LISTENING, observed = work)
        assertEquals(WispActivity.LISTENING, listening.activity)
        assertEquals("Listening · Writing your reply", listening.label)
        assertEquals("Microphone paused · Writing your reply", present(armed = true,
            phase = VoicePhase.LISTENING, paused = true, observed = work).label)
        assertEquals(WispActivity.APPROVAL, present(armed = true, phase = VoicePhase.SPEAKING,
            callState = VoiceSessionState.WAITING_FOR_CONFIRMATION, observed = work).activity)
    }

    @Test fun idleAndPersistentJournalWarningHaveNoActivityTextButCharacterRemains() {
        assertNull(WispPresenter.statusText(present()))
        assertEquals(WispActivity.READY, present().activity)
        assertNull(WispPresenter.statusText(present(taskError = journalError)))
        assertEquals("Listening", WispPresenter.statusText(present(armed = true, phase = VoicePhase.LISTENING)))
        assertNull(WispPresenter.statusText(present(armed = true, phase = VoicePhase.LISTENING), detailsAllowed = false))
    }

    @Test fun actualToolArgumentsProduceSpecificPublicBlurbsWithoutRawResults() {
        fun task(name: String, args: Map<String, String>, result: String? = null) = WispPresenter.task(
            attempt(ToolTaskState.RUNNING, name).copy(request = ActionRequest(name, args), result = result))
        assertEquals("Adjusting media volume to 25%", task("set_volume", mapOf("level" to "25")).label)
        assertEquals("Opening YouTube", task("open_app", mapOf("app" to "YouTube")).label)
        for (privateValue in listOf("https://example.org/private", "a@private.org", "secret=abcd", "123456789", "App\u202eName")) {
            assertEquals("Opening app", task("open_app", mapOf("app" to privateValue)).label)
        }
        assertEquals("Adjusting volume", task("set_volume", mapOf("level" to "999")).label)
        assertEquals("Working on a task", task("future_tool", mapOf("body" to "private message")).label)
        assertEquals("Opening Wi-Fi settings", task("open_settings", mapOf("screen" to "wifi")).label)
        assertEquals("Scrolling the screen down", task("screen_scroll", mapOf("direction" to "down", "token" to "secret")).label)
        assertEquals("Typing into the selected field", task("screen_type", mapOf("text" to "private message", "token" to "secret")).label)
        assertEquals("Scheduling your reminder", task("create_reminder", mapOf("message" to "private medical detail")).label)
        assertEquals("Opening directions", task("navigate", mapOf("destination" to "private home address")).label)
        assertEquals("Opening a website", task("open_website", mapOf("url" to "https://private/?token=secret")).label)
        val result = task("read_battery", emptyMap(), "private receipt payload")
        assertFalse(result.toString().contains("private receipt"))
    }

    @Test fun concurrentTasksUseStablePriorityAndAnHonestCount() {
        val running = attempt(ToolTaskState.RUNNING).copy(id = "running", updatedAtMs = 4)
        val older = running.copy(id = "older", updatedAtMs = 3)
        val approval = attempt(ToolTaskState.WAITING_APPROVAL).copy(id = "approval", updatedAtMs = 2)
        val result = present(ToolTaskJournal(attempts = listOf(running, approval, older)))
        assertEquals(WispActivity.APPROVAL, result.activity)
        assertEquals(2, result.otherTaskCount)
        assertEquals("Waiting for your approval · 2 other tasks", WispPresenter.statusText(result))
        val mostRecent = present(ToolTaskJournal(attempts = listOf(running, older)))
        assertEquals("running:0:RUNNING", mostRecent.taskKey)
        assertEquals(1, mostRecent.otherTaskCount)
    }

    @Test fun openEndedStatusIsBoundedAndLockGatedWithoutChangingOperationState() {
        val work = WispPresentation(WispActivity.THINKING, "Comparing the selected routes for your trip")
        assertEquals(work.label, WispPresenter.statusText(work))
        assertNull(WispPresenter.statusText(work, false))
        assertEquals("Working on your request", WispPresenter.statusText(work.copy(label = "secret=private")))
        assertEquals(WispActivity.THINKING, work.activity)
    }

    private val journalError = "The action journal is unavailable. Phone actions are paused."
    private fun attempt(state: ToolTaskState, name: String = "read_battery", generation: Long = 0,
        outcome: ExecutionResult.Outcome? = null, group: String? = null) = ToolTaskAttempt(
        "task", generation, state, ActionRequest(name, emptyMap()), 1, 2,
        resultOutcome = outcome, groupId = group)
    private fun present(journal: ToolTaskJournal? = null, armed: Boolean = false,
        phase: VoicePhase = VoicePhase.IDLE, busy: Boolean = false, paused: Boolean = false,
        receipt: WispPresentation? = null, observed: WispPresentation? = null,
        callState: VoiceSessionState = VoiceSessionState.PASSIVE_LISTENING,
        taskError: String? = null) = WispPresenter.present(
        "chat", journal, taskError, busy, armed, phase, callState, paused, observed, receipt)

    @Test fun callViewportHasModestTargetsAndPreservesShortWindowSpace() {
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(false, VoiceSessionState.ENDED, false))
        assertEquals(WispViewport(204, 128), WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, false))
        assertEquals(WispViewport(116, 66), WispPresenter.viewport(false, VoiceSessionState.ENDED, true))
        assertEquals(WispViewport(138, 80), WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, true))
    }

    @Test fun everyActiveCallStateKeepsItsSpaceUntilEndOrPassiveWakeListening() {
        val activeStates = listOf(VoiceSessionState.ACTIVELY_LISTENING, VoiceSessionState.PROCESSING,
            VoiceSessionState.EXECUTING_ACTION, VoiceSessionState.SPEAKING,
            VoiceSessionState.WAITING_FOR_CONFIRMATION, VoiceSessionState.INTERRUPTED)
        for (compact in listOf(false, true)) {
            val expanded = if (compact) WispViewport(138, 80) else WispViewport(204, 128)
            val resting = if (compact) WispViewport(116, 66) else WispViewport(168, 104)
            for (state in activeStates) {
                assertEquals(state.name, expanded, WispPresenter.viewport(true, state, compact))
                // A stale DTO cannot enlarge the character after explicit disarm/end.
                assertEquals(state.name, resting, WispPresenter.viewport(false, state, compact))
            }
            assertEquals(resting, WispPresenter.viewport(true, VoiceSessionState.ENDED, compact))
            assertEquals(resting, WispPresenter.viewport(true, VoiceSessionState.PASSIVE_LISTENING, compact))
            // A later call can expand again after the previous one ended.
            assertEquals(expanded, WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, compact))
        }
    }

    @Test fun microphonePauseInterruptionAndTaskPosesDoNotCollapseAnActiveCall() {
        val expected = WispViewport(204, 128)
        assertEquals(WispActivity.PAUSED, present(armed = true, phase = VoicePhase.PAUSED,
            paused = true, callState = VoiceSessionState.ACTIVELY_LISTENING).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, false))
        assertEquals(WispActivity.PAUSED, present(armed = true,
            callState = VoiceSessionState.INTERRUPTED).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.INTERRUPTED, false))
        val running = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING)))
        assertEquals(WispActivity.CHECKING, present(running, armed = true,
            callState = VoiceSessionState.EXECUTING_ACTION).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.EXECUTING_ACTION, false))
        val approval = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.WAITING_APPROVAL)))
        assertEquals(WispActivity.APPROVAL, present(approval, armed = true,
            callState = VoiceSessionState.WAITING_FOR_CONFIRMATION).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.WAITING_FOR_CONFIRMATION, false))
        // The same task/wake pose outside an active call retains the resting viewport.
        assertEquals(WispActivity.CHECKING, present(running).activity)
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(false, VoiceSessionState.ENDED, false))
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.WAKE).activity)
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(true, VoiceSessionState.PASSIVE_LISTENING, false))
    }

    @Test fun idleIsPresentAndStaleCallPhaseCannotAnimateAfterEnd() {
        assertEquals(WispActivity.READY, present().activity)
        assertEquals(WispActivity.READY, present(phase = VoicePhase.SPEAKING).activity)
        assertEquals(WispActivity.THINKING, present(busy = true).activity)
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.LISTENING).activity)
        assertEquals(WispActivity.SPEAKING, present(armed = true, phase = VoicePhase.SPEAKING).activity)
        assertEquals(WispActivity.PAUSED, present(armed = true, phase = VoicePhase.LISTENING, paused = true).activity)
    }

    @Test fun audioComesFromTheActualOwnerAndNonFiniteLevelsAreSilent() {
        fun level(activity: WispActivity, phase: VoicePhase, armed: Boolean = true, paused: Boolean = false,
            mic: Float = .2f, output: Float = .8f) = WispPresenter.audioLevel(activity, phase, armed, paused, mic, output)
        assertEquals(.2f, level(WispActivity.LISTENING, VoicePhase.LISTENING), 0f)
        assertEquals(.8f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING), 0f)
        assertEquals(.8f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, paused = true), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, paused = true), 0f)
        assertEquals(0f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, armed = false), 0f)
        assertEquals(0f, level(WispActivity.THINKING, VoicePhase.SPEAKING), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, mic = Float.NaN), 0f)
        assertEquals(0f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, output = Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, output = 3f), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, mic = -1f), 0f)
    }

    @Test fun persistentJournalErrorAllowsRepeatedCallTransitionsAndActualAudioOwners() {
        fun check(phase: VoicePhase, state: VoiceSessionState, expected: WispActivity,
            paused: Boolean = false, expectedLevel: Float = 0f) {
            val result = present(armed = true, phase = phase, callState = state,
                paused = paused, taskError = journalError)
            assertEquals("$phase / $state / paused=$paused", expected, result.activity)
            assertEquals(expectedLevel, WispPresenter.audioLevel(result.activity, phase, true,
                paused, microphone = .25f, playback = .8f), 0f)
        }
        repeat(2) {
            check(VoicePhase.PREPARING, VoiceSessionState.PROCESSING, WispActivity.THINKING)
            check(VoicePhase.WAKE, VoiceSessionState.PASSIVE_LISTENING, WispActivity.LISTENING, expectedLevel = .25f)
            check(VoicePhase.WAKING, VoiceSessionState.ACTIVELY_LISTENING, WispActivity.THINKING)
            check(VoicePhase.LISTENING, VoiceSessionState.ACTIVELY_LISTENING, WispActivity.LISTENING, expectedLevel = .25f)
            check(VoicePhase.THINKING, VoiceSessionState.PROCESSING, WispActivity.THINKING)
            check(VoicePhase.SPEAKING, VoiceSessionState.SPEAKING, WispActivity.SPEAKING, expectedLevel = .8f)
            check(VoicePhase.SPEAKING, VoiceSessionState.SPEAKING, WispActivity.SPEAKING, paused = true, expectedLevel = .8f)
            check(VoicePhase.LISTENING, VoiceSessionState.ACTIVELY_LISTENING, WispActivity.PAUSED, paused = true)
            check(VoicePhase.PAUSED, VoiceSessionState.ACTIVELY_LISTENING, WispActivity.PAUSED)
            check(VoicePhase.IDLE, VoiceSessionState.INTERRUPTED, WispActivity.PAUSED)
            check(VoicePhase.IDLE, VoiceSessionState.EXECUTING_ACTION, WispActivity.THINKING)
            check(VoicePhase.LISTENING, VoiceSessionState.ACTIVELY_LISTENING, WispActivity.LISTENING, expectedLevel = .25f)
            assertEquals("Task needs attention", present(callState = VoiceSessionState.ENDED,
                taskError = journalError).label)
        }
    }

    @Test fun journalErrorRemainsAttentionAtIdleAndAfterEndEvenWithStaleAudioPhase() {
        val attention = WispPresentation(WispActivity.ERROR, "Task needs attention", journalError)
        for (phase in VoicePhase.entries) {
            val result = present(phase = phase, callState = VoiceSessionState.ENDED, taskError = journalError)
            assertEquals(phase.name, attention, result)
            assertEquals(0f, WispPresenter.audioLevel(result.activity, phase, false, false, .7f, .9f), 0f)
        }
        assertEquals(attention, present(armed = true, taskError = journalError))
        assertEquals(attention, present(taskError = journalError,
            observed = WispPresentation(WispActivity.ERROR, "Something went wrong")))
        assertEquals(WispActivity.READY, present().activity)
    }

    @Test fun journalErrorDoesNotMaskCurrentTextOrReferenceWork() {
        assertEquals(WispActivity.THINKING, present(busy = true, taskError = journalError).activity)
        val reference = WispPresentation(WispActivity.CHECKING, "Checking references")
        assertEquals(reference, present(busy = true, observed = reference, taskError = journalError))
        assertEquals("Task needs attention", present(taskError = journalError).label)
    }

    @Test fun journalErrorPreservesUrgentTaskAndCallApprovalPriorityWithoutChangingTasks() {
        val reference = WispPresentation(WispActivity.CHECKING, "Checking references")
        val success = WispPresentation(WispActivity.SUCCESS, "Task complete")
        for (state in listOf(ToolTaskState.WAITING_APPROVAL, ToolTaskState.UNKNOWN_OUTCOME, ToolTaskState.RUNNING)) {
            val task = attempt(state)
            val journal = ToolTaskJournal(attempts = listOf(task))
            assertEquals(state.name, WispPresenter.task(task), present(journal, armed = true,
                phase = VoicePhase.SPEAKING, busy = true, observed = reference, receipt = success, taskError = journalError))
            assertEquals(task, journal.attempts.single())
        }
        assertEquals("Waiting for you", present(armed = true, phase = VoicePhase.SPEAKING,
            callState = VoiceSessionState.WAITING_FOR_CONFIRMATION, taskError = journalError).label)
        for (state in listOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.WAITING_INPUT,
            ToolTaskState.WAITING_RESOURCE, ToolTaskState.PAUSED)) {
            val task = attempt(state)
            assertEquals(state.name, WispPresenter.task(task), present(
                ToolTaskJournal(attempts = listOf(task)), taskError = journalError))
        }
    }

    @Test fun freshReceiptsKeepTheirMeaningWithJournalErrorAndThenReturnToAttention() {
        for ((state, outcome, expected) in listOf(
            Triple(ToolTaskState.SUCCEEDED, ExecutionResult.Outcome.SUCCEEDED, WispActivity.SUCCESS),
            Triple(ToolTaskState.FAILED, ExecutionResult.Outcome.FAILED, WispActivity.ERROR),
            Triple(ToolTaskState.CANCELLED, null, WispActivity.PAUSED))) {
            val tracker = WispReceiptTracker()
            val running = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING)))
            val completed = ToolTaskJournal(attempts = listOf(attempt(state, generation = 1, outcome = outcome)))
            assertNull(tracker.update(running, "chat"))
            val receipt = tracker.update(completed, "chat")
            assertEquals(expected, present(completed, receipt = receipt, taskError = journalError).activity)
            assertEquals("Task needs attention", present(completed, taskError = journalError).label)
            assertNull(tracker.update(completed, "chat"))
            assertNull(WispReceiptTracker().update(completed, "chat"))
        }
    }

    @Test fun everyRunningPropRequiresItsActualTool() {
        for ((tool, expected) in mapOf("read_battery" to WispActivity.CHECKING,
            "set_volume" to WispActivity.EDITING, "open_app" to WispActivity.CONNECTING,
            "unknown_tool" to WispActivity.THINKING)) {
            val journal = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING, tool)))
            assertEquals(tool, expected, present(journal).activity)
        }
        assertEquals(WispActivity.THINKING, present(ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.QUEUED)))).activity)
    }

    @Test fun approvalAndUnknownOutcomesTakePriorityWithoutStartingEffects() {
        val approval = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.WAITING_APPROVAL)))
        assertEquals(WispActivity.APPROVAL, present(approval, armed = true, phase = VoicePhase.SPEAKING).activity)
        assertEquals(ToolTaskState.WAITING_APPROVAL, approval.attempts.single().state)
        val unknown = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.UNKNOWN_OUTCOME)))
        assertEquals(WispActivity.ERROR, present(unknown, busy = true).activity)
        assertEquals(WispActivity.READY, present(unknown.copy(attempts = listOf(unknown.attempts.single().copy(reconciled = true)))).activity)
    }

    @Test fun acceptedRoutineActivityIsAppWideButOtherConversationsRemainPrivate() {
        val routine = attempt(ToolTaskState.RUNNING, group = "routine").copy(authority = ToolAuthority.ROUTINE)
        val journal = ToolTaskJournal(attempts = listOf(routine),
            groups = listOf(ToolTaskGroup("routine", "workflow:occurrence", "Private routine title", listOf("task"), 1, 10)))
        assertEquals(WispActivity.CHECKING, present(journal).activity)
        assertEquals("Checking battery", WispPresenter.statusText(present(journal)))
        assertEquals(WispActivity.READY, present(journal.copy(attempts = listOf(routine.copy(authority = ToolAuthority.USER_REQUEST)))).activity)
        assertEquals(WispActivity.READY, present(journal.copy(groups = listOf(journal.groups.single().copy(conversationId = "other-chat")))).activity)
    }

    @Test fun anotherConversationCannotDriveTheCharacter() {
        val journal = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING, group = "foreign")),
            groups = listOf(ToolTaskGroup("foreign", "other-chat", "Battery", listOf("task"), 1, 10)))
        assertEquals(WispActivity.READY, present(journal).activity)
    }

    @Test fun currentReferenceObservationOverridesThinkingButNeverCreatesSuccess() {
        val reference = WispPresentation(WispActivity.CHECKING, "Checking references")
        assertEquals(reference, present(busy = true, observed = reference))
        assertEquals(WispActivity.THINKING, present(busy = true, observed = null).activity)
        assertEquals(WispActivity.READY, present(observed = null).activity)
    }

    @Test fun recentTerminalFailureNeverOverridesFreshConversationOrAudioWork() {
        val error = WispPresentation(WispActivity.ERROR, "Something went wrong")
        assertEquals(WispActivity.ERROR, present(observed = error).activity)
        assertEquals(WispActivity.THINKING, present(busy = true, observed = error).activity)
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.LISTENING, observed = error).activity)
        assertEquals(WispActivity.SPEAKING, present(armed = true, phase = VoicePhase.SPEAKING, observed = error).activity)
    }

    @Test fun successRequiresAnExecutorReceiptAndNeverPlaysOnHistoricalLoad() {
        val tracker = WispReceiptTracker()
        val completed = attempt(ToolTaskState.SUCCEEDED, generation = 1, outcome = ExecutionResult.Outcome.SUCCEEDED)
        assertNull(tracker.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        assertNull(tracker.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        val fresh = WispReceiptTracker()
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING))), "chat"))
        assertEquals(WispActivity.SUCCESS, fresh.update(ToolTaskJournal(attempts = listOf(completed)), "chat")?.activity)
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(completed)), "different-chat"))
        assertEquals(WispActivity.ERROR, WispPresenter.task(completed.copy(resultOutcome = null)).activity)
        assertEquals(WispActivity.ERROR, WispPresenter.task(completed.copy(resultOutcome = ExecutionResult.Outcome.UNKNOWN_COMPLETION)).activity)
    }

    @Test fun quickTasksBetweenFramesAreObservedAndFailureCancellationNeverCelebrate() {
        for ((state, expected) in mapOf(ToolTaskState.FAILED to WispActivity.ERROR,
            ToolTaskState.UNKNOWN_OUTCOME to WispActivity.ERROR, ToolTaskState.CANCELLED to WispActivity.PAUSED)) {
            val tracker = WispReceiptTracker()
            assertNull(tracker.update(ToolTaskJournal(), "chat"))
            assertEquals(expected, tracker.update(ToolTaskJournal(attempts = listOf(attempt(state))), "chat")?.activity)
        }
    }
    @Test fun speakingFaceSurvivesEveryBodyWithoutChangingTaskPriorityOrAuthority() {
        for (state in listOf(ToolTaskState.RUNNING, ToolTaskState.WAITING_APPROVAL,
            ToolTaskState.UNKNOWN_OUTCOME, ToolTaskState.WAITING_RESOURCE)) {
            val task = attempt(state, "screen_scroll")
            val journal = ToolTaskJournal(attempts = listOf(task))
            val body = present(journal, armed = true, phase = VoicePhase.SPEAKING)
            val audio = WispPresenter.audioActivity(VoicePhase.SPEAKING, true, false, VoiceSessionState.SPEAKING)
            assertEquals(WispActivity.SPEAKING, audio)
            assertEquals(.8f, WispPresenter.audioLevel(audio!!, VoicePhase.SPEAKING, true, false, .2f, .8f), 0f)
            if (state == ToolTaskState.RUNNING) assertEquals(WispGesture.SCROLL, body.gesture)
            if (state == ToolTaskState.WAITING_APPROVAL) assertEquals(WispActivity.APPROVAL, body.activity)
            if (state == ToolTaskState.UNKNOWN_OUTCOME) assertEquals(WispActivity.ERROR, body.activity)
            assertEquals(task, journal.attempts.single())
        }
    }

    @Test fun publicReferenceBodyAndListeningFaceStayIndependent() {
        val reference = WispPresentation(WispActivity.CHECKING, "Checking references", gesture = WispGesture.RESEARCH)
        for (phase in listOf(VoicePhase.SPEAKING, VoicePhase.LISTENING)) {
            val result = present(armed = true, phase = phase, observed = reference)
            assertEquals(WispActivity.CHECKING, result.bodyActivity)
            assertEquals(WispGesture.RESEARCH, result.gesture)
            assertEquals(if (phase == VoicePhase.SPEAKING) WispActivity.SPEAKING else WispActivity.LISTENING,
                WispPresenter.audioActivity(phase, true, false, VoiceSessionState.ACTIVELY_LISTENING))
        }
    }

    @Test fun toolGesturesComeOnlyFromActualRunningRequestKinds() {
        val gestures = mapOf("screen_observe" to WispGesture.READING, "screen_tap" to WispGesture.TAP,
            "screen_scroll" to WispGesture.SCROLL, "set_volume" to WispGesture.VOLUME)
        for ((name, gesture) in gestures) {
            assertEquals(gesture, WispPresenter.task(attempt(ToolTaskState.RUNNING, name)).gesture)
            for (state in listOf(ToolTaskState.WAITING_APPROVAL, ToolTaskState.UNKNOWN_OUTCOME, ToolTaskState.FAILED))
                assertEquals(WispGesture.NONE, WispPresenter.task(attempt(state, name)).gesture)
        }
        for (name in listOf("media_control", "screen_type", "future_tool", "create_reminder"))
            assertEquals(WispGesture.NONE, WispPresenter.task(attempt(ToolTaskState.RUNNING, name)).gesture)
        for (state in listOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.WAITING_RESOURCE, ToolTaskState.PAUSED))
            assertEquals(WispGesture.WAITING, WispPresenter.task(attempt(state, "screen_tap")).gesture)
    }

    @Test fun interruptionEndAndPauseRejectStaleAudioButRealRestartRearmsIt() {
        repeat(2) {
            assertEquals(WispActivity.SPEAKING, WispPresenter.audioActivity(VoicePhase.SPEAKING, true, false,
                VoiceSessionState.SPEAKING))
            for (state in listOf(VoiceSessionState.INTERRUPTED, VoiceSessionState.ENDED)) {
                assertNull(WispPresenter.audioActivity(VoicePhase.SPEAKING, true, false, state))
                assertNotEquals(WispActivity.SPEAKING, present(armed = true, phase = VoicePhase.SPEAKING,
                    callState = state).activity)
            }
            assertNull(WispPresenter.audioActivity(VoicePhase.LISTENING, true, true, VoiceSessionState.ACTIVELY_LISTENING))
            assertNull(WispPresenter.audioActivity(VoicePhase.SPEAKING, false, false, VoiceSessionState.SPEAKING))
            assertEquals(WispActivity.LISTENING, WispPresenter.audioActivity(VoicePhase.LISTENING, true, false,
                VoiceSessionState.ACTIVELY_LISTENING))
        }
    }

}
