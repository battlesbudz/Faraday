package com.battlesbudz.jarvis.v2.actions

/** Only normalized native requests and short receipts belong here, never raw tool payloads. */
data class ToolTaskJournal(
    val attempts: List<ToolTaskAttempt> = emptyList(),
    val groups: List<ToolTaskGroup> = emptyList(),
    val approvals: List<ActionApprovalRequest> = emptyList(),
    val grants: List<ToolActionGrant> = emptyList(),
    val events: List<ToolTaskEvent> = emptyList(),
    val activeQuestionId: String? = null,
    /** M1e: remembered first-source access per tool family (D09, T08). */
    val sourceAccess: List<ToolSourceAccessRecord> = emptyList(),
    /** M2: versioned workflow definitions (all versions; running occurrences pin theirs). */
    val workflows: List<WorkflowDefinition> = emptyList(),
    /** M2: workflow occurrences with idempotent dedup keys. */
    val occurrences: List<WorkflowOccurrence> = emptyList(),
    /** M2: occurrence/decision receipts. */
    val workflowReceipts: List<WorkflowReceipt> = emptyList()
)
data class ToolTaskGroup(
    val id: String, val conversationId: String, val summary: String, val attemptIds: List<String>,
    val createdAtMs: Long, val expiresAtMs: Long, val cancelled: Boolean = false,
    val resumeAfterRestart: Boolean = true
)
enum class ToolAuthority { USER_REQUEST, EXACT_APPROVAL, ROUTINE }
data class ToolActionGrant(
    val id: String, val routineId: String, val provider: String, val schemaVersion: Int,
    val requests: List<ActionRequest>, val createdAtMs: Long, val expiresAtMs: Long, val revoked: Boolean = false
)
enum class ToolTaskEventKind { ADMITTED, DISPATCHED, RECEIPT, APPROVAL_REQUESTED, RECOVERED, CANCELLED, GRANT_REVOKED, RECONCILED }
data class ToolTaskEvent(val attemptId: String, val generation: Long, val kind: ToolTaskEventKind, val atMs: Long)

internal fun ToolTaskState.isTerminal() = this in setOf(
    ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
internal fun ActionRequest.frozen() = copy(arguments = arguments.toMap())
/** Routine execution belongs to its occurrence owner, never ordinary chat resumption. */
internal fun ToolTaskJournal.isWorkflowOwned(group: ToolTaskGroup): Boolean =
    group.conversationId.startsWith("workflow:") || attempts.any { attempt ->
        attempt.groupId == group.id && grants.any { it.id == attempt.grantId && it.routineId.startsWith("workflow:") }
    }
internal fun ToolTaskJournal.isWorkflowOwned(attempt: ToolTaskAttempt): Boolean =
    groups.any { it.id == attempt.groupId && isWorkflowOwned(it) } ||
        grants.any { it.id == attempt.grantId && it.routineId.startsWith("workflow:") }

internal fun ToolTaskJournal.frozen() = copy(
    attempts = attempts.map { it.copy(request = it.request.frozen()) },
    groups = groups.map { it.copy(attemptIds = it.attemptIds.toList()) },
    approvals = approvals.map { it.copy(action = it.action.frozen()) },
    grants = grants.map { it.copy(requests = it.requests.map { request -> request.frozen() }) }, events = events.toList(),
    sourceAccess = sourceAccess.map { it.copy(scopes = it.scopes.toSet()) },
    occurrences = occurrences.map { it.copy(resumePath = it.resumePath.toList(),
        completedStepIds = it.completedStepIds.toList(),
        stepResults = it.stepResults.mapValues { (_, v) -> v.toMap() }) },
    workflowReceipts = workflowReceipts.toList())

/** Screen mutations are never routine-eligible and never auto-dispatched (D23). */
internal val SCREEN_MUTATION_TOOLS = setOf("screen_tap", "screen_scroll", "screen_type")

/** No grant can expand these tools into arbitrary or consequential operations. */
internal fun ActionRequest.isRoutineEligible() = when (name) {
    "read_battery" -> arguments.isEmpty()
    "set_volume" -> arguments.keys == setOf("level") && arguments["level"]?.toIntOrNull()?.let { it in 0..100 } == true
    "open_app" -> arguments.keys == setOf("app") && !arguments["app"].isNullOrBlank() && arguments.getValue("app").length <= 512
    "media_control" -> arguments.keys == setOf("action") && MediaControlAction.fromVerb(arguments.getValue("action")) != null
    // Scheduling a user-requested one-shot reminder is not a D11
    // confirmation category; it is routine-eligible so reminder occurrences
    // and the deterministic turn path can run it.
    "create_reminder" -> arguments.keys == setOf("message", "at_ms") &&
        !arguments["message"].isNullOrBlank() && arguments.getValue("message").length <= MAX_REMINDER_MESSAGE &&
        arguments["at_ms"]?.toLongOrNull()?.let { it > 0 } == true
    "show_schedule" -> arguments.isEmpty()
    "post_notification" -> arguments.keys == setOf("title", "text") &&
        !arguments["title"].isNullOrBlank() && arguments.getValue("title").length <= 64 &&
        !arguments["text"].isNullOrBlank() && arguments.getValue("text").length <= MAX_REMINDER_MESSAGE
    else -> false
}

/**
 * M1d dispatch eligibility for the approval path (D11/D23, T07).
 *
 * Screen mutations are never routine-eligible and never claimable on bare
 * user-request authority: they become dispatch-eligible only under an exact
 * approval, whose consumption commits atomically with the dispatch claim in
 * [ToolTaskLedger.claim]. A changed target invalidates the prior approval via
 * [ToolTaskLedger.revise], so a stale approval can never authorize a dispatch.
 */
internal fun ActionRequest.isDispatchEligible(authority: ToolAuthority): Boolean =
    isRoutineEligible() || (authority == ToolAuthority.EXACT_APPROVAL && name in SCREEN_MUTATION_TOOLS)

/** Human-readable label for the screen Stop overlay and approval prompts (M1d). */
internal fun ActionRequest.describeForOverlay(): String = when (name) {
    "screen_tap" -> "Tap ${arguments["target"] ?: "screen element"}"
    "screen_scroll" -> "Scroll ${arguments["direction"] ?: ""}".trim()
    "screen_type" -> "Type into ${arguments["target"] ?: "field"}"
    "screen_observe" -> "Read the screen"
    "read_battery" -> "Check battery"
    "set_volume" -> "Set media volume to ${arguments["level"]}%"
    "open_app" -> "Open ${arguments["app"] ?: arguments["package"]}"
    "media_control" -> "Control media"
    "create_reminder" -> "Set a reminder"
    "show_schedule" -> "Show the schedule"
    "post_notification" -> "Post a notification"
    else -> name
}
