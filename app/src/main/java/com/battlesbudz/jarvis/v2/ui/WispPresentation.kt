package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.presentation.ActivityText
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.ToolTaskAttempt
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskState
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState

/** Presentation only: a pose never starts, approves, retries or completes an operation. */
internal enum class WispActivity {
    READY, LISTENING, THINKING, SPEAKING, CONNECTING, CHECKING, EDITING,
    APPROVAL, SUCCESS, ERROR, PAUSED
}

/** Typed observed props; never inferred from free-form labels or private tool payloads. */
internal enum class WispGesture { NONE, RESEARCH, READING, TAP, SCROLL, VOLUME, WAITING }

internal data class WispPresentation(
    val activity: WispActivity,
    val label: String,
    val detail: String? = null,
    val taskKey: String? = null,
    val otherTaskCount: Int = 0,
    val gesture: WispGesture = WispGesture.NONE,
    val bodyActivity: WispActivity? = null
)

internal data class WispViewport(val widthDp: Int, val heightDp: Int)

internal object WispPresenter {
    /** Call ownership controls space, independently of a temporary pose or microphone pause. */
    fun viewport(armed: Boolean, callState: VoiceSessionState, compact: Boolean): WispViewport {
        val activeCall = armed && when (callState) {
            VoiceSessionState.PASSIVE_LISTENING, VoiceSessionState.ENDED -> false
            VoiceSessionState.ACTIVELY_LISTENING, VoiceSessionState.PROCESSING,
            VoiceSessionState.EXECUTING_ACTION, VoiceSessionState.SPEAKING,
            VoiceSessionState.WAITING_FOR_CONFIRMATION, VoiceSessionState.INTERRUPTED -> true
        }
        return when {
            compact && activeCall -> WispViewport(138, 80)
            compact -> WispViewport(116, 66)
            activeCall -> WispViewport(204, 128)
            else -> WispViewport(168, 104)
        }
    }

    fun present(
        conversationId: String,
        journal: ToolTaskJournal?,
        taskError: String?,
        chatBusy: Boolean,
        armed: Boolean,
        phase: VoicePhase,
        callState: VoiceSessionState,
        microphonePaused: Boolean,
        observedActivity: WispPresentation? = null,
        receipt: WispPresentation? = null
    ): WispPresentation {
        // Explicit end/interruption defeats a stale speaking phase until the real call owner rearms.
        val observedPhase = if (callState == VoiceSessionState.ENDED || callState == VoiceSessionState.INTERRUPTED)
            VoicePhase.IDLE else phase
        val attempts = relevantAttempts(journal, conversationId).sortedWith(compareBy({ it.updatedAtMs }, { it.id }))
        fun selected(attempt: ToolTaskAttempt): WispPresentation = task(attempt).copy(
            otherTaskCount = attempts.count { it.id != attempt.id && !it.reconciled && it.state in activeStates })
        // Specific task authority/outcomes take priority. Unknown outcomes must never look successful.
        val urgent = attempts.lastOrNull { it.state in setOf(ToolTaskState.WAITING_APPROVAL, ToolTaskState.WAITING_INPUT) && !it.reconciled }
            ?: attempts.lastOrNull { it.state == ToolTaskState.UNKNOWN_OUTCOME && !it.reconciled }
        if (urgent != null) return selected(urgent)
        attempts.lastOrNull { it.state == ToolTaskState.RUNNING && !it.reconciled }?.let { return selected(it) }
        val recentError = observedActivity?.takeIf { it.activity == WispActivity.ERROR }
        if (armed && callState == VoiceSessionState.WAITING_FOR_CONFIRMATION)
            return WispPresentation(WispActivity.APPROVAL, "Waiting for you")
        if (observedActivity != null && recentError == null) {
            // Streaming speech/listening can overlap real work. Preserve the actual audio
            // owner and name both observations instead of making an active microphone invisible.
            val publicWork = ActivityText.publicBlurb(observedActivity.label)
            return when {
                armed && observedPhase == VoicePhase.SPEAKING -> observedActivity.copy(
                    activity = WispActivity.SPEAKING, bodyActivity = observedActivity.activity, label = "Speaking · $publicWork")
                armed && (microphonePaused || observedPhase == VoicePhase.PAUSED) -> observedActivity.copy(
                    label = "Microphone paused · $publicWork")
                armed && observedPhase == VoicePhase.LISTENING -> observedActivity.copy(
                    activity = WispActivity.LISTENING, bodyActivity = observedActivity.activity, label = "Listening · $publicWork")
                else -> observedActivity
            }
        }
        if (armed && observedPhase == VoicePhase.SPEAKING)
            return WispPresentation(WispActivity.SPEAKING, "Speaking")
        attempts.lastOrNull { it.state in pendingStates && !it.reconciled }?.let { return selected(it) }
        if (chatBusy) return WispPresentation(WispActivity.THINKING, "Thinking")
        if (receipt != null) return receipt
        // A persistent journal failure remains visible in the task panel. It is the idle
        // fallback here, so unrelated live work and its real audio owner remain represented.
        val idle = if (taskError != null) WispPresentation(WispActivity.ERROR, "Task needs attention", taskError)
            else WispPresentation(WispActivity.READY, "Ready")
        if (taskError == null && recentError != null && (!armed || observedPhase == VoicePhase.IDLE)) return recentError
        if (!armed) return idle
        if (microphonePaused || observedPhase == VoicePhase.PAUSED)
            return WispPresentation(WispActivity.PAUSED, "Microphone paused")
        return when (observedPhase) {
            VoicePhase.LISTENING -> WispPresentation(WispActivity.LISTENING, "Listening")
            VoicePhase.WAKE -> WispPresentation(WispActivity.LISTENING, "Say “Hey Jarvis”")
            VoicePhase.THINKING -> WispPresentation(WispActivity.THINKING, "Thinking")
            VoicePhase.PREPARING, VoicePhase.WAKING -> WispPresentation(WispActivity.THINKING, observedPhase.label)
            else -> when (callState) {
                VoiceSessionState.EXECUTING_ACTION -> WispPresentation(WispActivity.THINKING, "Working")
                VoiceSessionState.INTERRUPTED -> WispPresentation(WispActivity.PAUSED, "Reply interrupted")
                else -> idle
            }
        }
    }

    /** Face/audio ownership is independent of the task body and its approval/error priority. */
    fun audioActivity(phase: VoicePhase, armed: Boolean, microphonePaused: Boolean,
        callState: VoiceSessionState): WispActivity? = when {
        !armed || callState == VoiceSessionState.ENDED || callState == VoiceSessionState.INTERRUPTED -> null
        phase == VoicePhase.SPEAKING -> WispActivity.SPEAKING
        !microphonePaused && phase in setOf(VoicePhase.LISTENING, VoicePhase.WAKE) -> WispActivity.LISTENING
        else -> null
    }

    fun audioLevel(activity: WispActivity, phase: VoicePhase, armed: Boolean,
        microphonePaused: Boolean, microphone: Float, playback: Float): Float {
        val level = when {
            !armed -> 0f
            activity == WispActivity.SPEAKING && phase == VoicePhase.SPEAKING -> playback
            activity == WispActivity.LISTENING && !microphonePaused &&
                phase in setOf(VoicePhase.LISTENING, VoicePhase.WAKE) -> microphone
            else -> 0f
        }
        return if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    }

    internal fun relevantAttempts(journal: ToolTaskJournal?, conversationId: String): List<ToolTaskAttempt> {
        val groups = journal?.groups.orEmpty().filter { it.conversationId == conversationId }.mapTo(hashSetOf()) { it.id }
        val workflowGroups = journal?.groups.orEmpty().filter { it.conversationId.startsWith("workflow:") }
            .mapTo(hashSetOf()) { it.id }
        return journal?.attempts.orEmpty().filter {
            it.groupId in groups || it.groupId == null && !it.reconciled ||
                it.authority == com.battlesbudz.jarvis.v2.actions.ToolAuthority.ROUTINE && it.groupId in workflowGroups
        }
    }

    internal fun task(attempt: ToolTaskAttempt): WispPresentation {
        val key = "${attempt.id}:${attempt.generation}:${attempt.state}"
        val description = when (attempt.request.name) {
            "read_battery" -> "Checking battery"
            "set_volume" -> attempt.request.arguments["level"]?.toIntOrNull()?.takeIf { it in 0..100 }
                ?.let { "Adjusting media volume to $it%" } ?: "Adjusting volume"
            "open_app" -> ActivityText.appName(attempt.request.arguments["app"])
                ?.let { "Opening $it" } ?: "Opening app"
            "media_control" -> when (attempt.request.arguments["action"]) {
                "play" -> "Starting media playback"
                "pause" -> "Pausing media playback"
                "toggle" -> "Toggling media playback"
                "next" -> "Skipping to the next track"
                "previous" -> "Returning to the previous track"
                else -> "Controlling media playback"
            }
            "open_settings" -> settingsScreens[attempt.request.arguments["screen"]]
                ?.let { "Opening $it settings" } ?: "Opening settings"
            "open_website" -> "Opening a website"
            "navigate" -> "Opening directions"
            "screen_observe" -> "Reading the current screen"
            "screen_tap" -> "Tapping the selected screen control"
            "screen_scroll" -> when (attempt.request.arguments["direction"]) {
                "up" -> "Scrolling the screen up"
                "down" -> "Scrolling the screen down"
                else -> "Scrolling the screen"
            }
            "screen_type" -> "Typing into the selected field"
            "create_reminder" -> "Scheduling your reminder"
            "show_schedule" -> "Checking your scheduled reminders"
            "post_notification" -> "Posting your reminder notification"
            else -> if (attempt.provider != "native") "Running a connected tool" else "Working on a task"
        }
        val (activity, label) = when (attempt.state) {
            ToolTaskState.WAITING_APPROVAL -> WispActivity.APPROVAL to "Waiting for your approval"
            ToolTaskState.WAITING_INPUT -> WispActivity.APPROVAL to "Waiting for your answer"
            ToolTaskState.UNKNOWN_OUTCOME -> WispActivity.ERROR to "Check the task outcome"
            ToolTaskState.FAILED -> WispActivity.ERROR to "Task could not complete"
            ToolTaskState.CANCELLED -> WispActivity.PAUSED to "Task cancelled"
            ToolTaskState.PAUSED -> WispActivity.PAUSED to "Task paused"
            ToolTaskState.QUEUED, ToolTaskState.READY -> WispActivity.THINKING to "Task queued"
            ToolTaskState.WAITING_RESOURCE -> WispActivity.PAUSED to "Waiting for a resource"
            ToolTaskState.SUCCEEDED -> if (attempt.resultOutcome == ExecutionResult.Outcome.SUCCEEDED)
                WispActivity.SUCCESS to "Task complete" else WispActivity.ERROR to "Check the task outcome"
            ToolTaskState.RUNNING -> when (attempt.request.name) {
                "read_battery", "screen_observe", "show_schedule" -> WispActivity.CHECKING to description
                "set_volume", "media_control", "screen_tap", "screen_scroll", "screen_type", "create_reminder" -> WispActivity.EDITING to description
                "open_app", "open_website", "open_settings", "navigate" -> WispActivity.CONNECTING to description
                else -> WispActivity.THINKING to description
            }
        }
        // Executor receipt bodies can contain private request/result data. The header only uses the
        // authored operation summary; detailed receipts remain in the existing task panel.
        val gesture = when {
            attempt.state in setOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.WAITING_RESOURCE,
                ToolTaskState.PAUSED) -> WispGesture.WAITING
            attempt.state != ToolTaskState.RUNNING -> WispGesture.NONE
            else -> when (attempt.request.name) {
                "screen_observe" -> WispGesture.READING
                "screen_tap" -> WispGesture.TAP
                "screen_scroll" -> WispGesture.SCROLL
                "set_volume" -> WispGesture.VOLUME
                else -> WispGesture.NONE
            }
        }
        return WispPresentation(activity, label, description, key, gesture = gesture)
    }

    /** The character remains visible while idle; the under-character activity node does not. */
    fun statusText(presentation: WispPresentation, detailsAllowed: Boolean = true): String? {
        if (!detailsAllowed || presentation.activity == WispActivity.READY ||
            presentation.taskKey == null && presentation.label == "Task needs attention") return null
        val label = ActivityText.publicBlurb(presentation.label)
        return if (presentation.otherTaskCount > 0) "$label · ${presentation.otherTaskCount} other task${if (presentation.otherTaskCount == 1) "" else "s"}"
            else label
    }

    private val settingsScreens = mapOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "display" to "display",
        "sound" to "sound", "apps" to "app", "battery" to "battery", "location" to "location",
        "storage" to "storage", "network" to "network", "general" to "general")
    private val activeStates = setOf(ToolTaskState.RUNNING, ToolTaskState.QUEUED, ToolTaskState.READY,
        ToolTaskState.WAITING_APPROVAL, ToolTaskState.WAITING_INPUT, ToolTaskState.WAITING_RESOURCE,
        ToolTaskState.PAUSED)
    private val pendingStates = setOf(ToolTaskState.QUEUED, ToolTaskState.READY,
        ToolTaskState.WAITING_INPUT, ToolTaskState.WAITING_RESOURCE, ToolTaskState.PAUSED)
}

/** Only newly observed terminal receipts celebrate; opening history/recreating UI never does. */
internal class WispReceiptTracker {
    private var conversation: String? = null
    private var previous: Map<String, Pair<Long, ToolTaskState>>? = null

    fun update(journal: ToolTaskJournal?, conversationId: String): WispPresentation? {
        if (journal == null) return null
        val attempts = WispPresenter.relevantAttempts(journal, conversationId)
        val baseline = previous.takeIf { conversation == conversationId }
        previous = attempts.associate { it.id to (it.generation to it.state) }
        conversation = conversationId
        if (baseline == null) return null
        return attempts.lastOrNull { attempt ->
            attempt.state in terminalStates && baseline[attempt.id] != (attempt.generation to attempt.state)
        }?.let(WispPresenter::task)
    }

    private val terminalStates = setOf(ToolTaskState.SUCCEEDED, ToolTaskState.FAILED,
        ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
}
