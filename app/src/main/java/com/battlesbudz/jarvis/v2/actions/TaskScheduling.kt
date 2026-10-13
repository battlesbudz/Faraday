package com.battlesbudz.jarvis.v2.actions

/**
 * M1d task/conversation scheduling policy (D18, T02).
 *
 * Decides whether a follow-up task may run independently of already-running
 * work or must queue behind a conflicting resource. JVM-pure: the runtime
 * supplies the running set and enforces the decision.
 */

/** The shared resource a task needs while it runs. */
enum class TaskResourceKind { SCREEN_LEASE, APP, NONE }

data class TaskResource(val kind: TaskResourceKind, val appKey: String? = null) {
    init {
        if (kind == TaskResourceKind.APP) require(!appKey.isNullOrBlank()) { "APP resources need an app key" }
    }
}

sealed interface ScheduleDecision {
    /** The task does not conflict: admit and run it independently. */
    data object RunNow : ScheduleDecision

    /** The task conflicts: hold it until the conflicting work finishes. */
    data class Queue(val reason: String) : ScheduleDecision
}

/**
 * Schedules follow-up tasks around running work (D18: run independent tasks
 * concurrently, queue conflicting steps needing the same app or screen).
 *
 * - Two tasks needing the screen lease conflict: the second queues.
 * - Two tasks targeting the same app conflict: the second queues.
 * - Anything else runs independently.
 */
class TaskScheduler {
    companion object {
        val SCREEN_MUTATIONS: Set<String> = SCREEN_MUTATION_TOOLS
    }

    /** The resource a native request needs. Screen mutations need the lease; app launches target their app. */
    fun resourceFor(request: ActionRequest): TaskResource = when (request.name) {
        in SCREEN_MUTATIONS -> TaskResource(TaskResourceKind.SCREEN_LEASE)
        "open_app" -> {
            val app = request.arguments["app"]?.trim()?.lowercase().orEmpty()
            if (app.isBlank()) TaskResource(TaskResourceKind.NONE) else TaskResource(TaskResourceKind.APP, app)
        }
        "navigate" -> {
            val destination = request.arguments["destination"]?.trim()?.lowercase().orEmpty()
            if (destination.isBlank()) TaskResource(TaskResourceKind.NONE) else TaskResource(TaskResourceKind.APP, "maps:$destination")
        }
        else -> TaskResource(TaskResourceKind.NONE)
    }

    fun resourceForPlan(requests: List<ActionRequest>): TaskResource {
        val resources = requests.map(::resourceFor)
        // A plan's resource is its most exclusive one: a screen lease beats an app target.
        return resources.firstOrNull { it.kind == TaskResourceKind.SCREEN_LEASE }
            ?: resources.firstOrNull { it.kind == TaskResourceKind.APP }
            ?: TaskResource(TaskResourceKind.NONE)
    }

    fun schedule(new: TaskResource, running: List<TaskResource>): ScheduleDecision {
        if (new.kind == TaskResourceKind.SCREEN_LEASE &&
            running.any { it.kind == TaskResourceKind.SCREEN_LEASE }) {
            return ScheduleDecision.Queue("Screen control is busy with another task. This task waits for its turn.")
        }
        if (new.kind == TaskResourceKind.APP &&
            running.any { it.kind == TaskResourceKind.APP && it.appKey == new.appKey }) {
            return ScheduleDecision.Queue("Jarvis is already working in ${new.appKey}. This task waits for its turn.")
        }
        return ScheduleDecision.RunNow
    }
}

/**
 * M1d task-targeted cancellation scope (D19, D24, T03).
 *
 * Speech-only stop preserves work; task stop cancels exactly the addressed
 * task by identity; stop-all cancels remaining work. Completed effects are
 * never replayed: cancellation only touches unfinished attempts.
 */
sealed interface TaskStopScope {
    /** Silence speech only; work continues untouched. */
    data object SpeechOnly : TaskStopScope

    /** Cancel exactly one task, addressed by its task/attempt identity. */
    data class SingleTask(val taskId: String) : TaskStopScope

    /** Cancel the currently running task. */
    data object CurrentTask : TaskStopScope

    /** Cancel all remaining unfinished work. */
    data object AllTasks : TaskStopScope

    /** Cancel queued work but leave the running task alone. */
    data object QueuedOnly : TaskStopScope
}

class TaskStopRouter {
    /**
     * Maps a parsed voice control plus the addressed task identities to an
     * exact cancellation scope. [newestTaskId] is the most recently admitted
     * unfinished task; [currentTaskId] the running one. Null when absent.
     */
    fun route(
        control: com.battlesbudz.jarvis.v2.voice.VoiceActionControl,
        newestTaskId: String?,
        currentTaskId: String?
    ): TaskStopScope? = when (control) {
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly ->
            TaskStopScope.SpeechOnly
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.CancelNewest ->
            newestTaskId?.let { TaskStopScope.SingleTask(it) }
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.CancelCurrent ->
            currentTaskId?.let { TaskStopScope.CurrentTask }
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.CancelAll ->
            TaskStopScope.AllTasks
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.CancelQueued ->
            TaskStopScope.QueuedOnly
        com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None -> null
    }
}
