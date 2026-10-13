package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M2 workflow ledger: versioned definitions, enablement, occurrences and
 * decision receipts over the shared durable [ToolTaskStore] (D31–D36,
 * T11–T14). Extends the M1 ledger/approval/grant model — never duplicates
 * it.
 *
 * - Drafts are saved disabled and never run until an explicit [enable]
 *   (T12). [preview] renders the plain-language summary first (D31).
 * - [revise] adds a new version; running occurrences keep the version they
 *   started with — revisions never mutate a running version (T12).
 * - Routine steps run under reusable routine grants whose requests must
 *   exactly match the occurrence's steps (T11); [disable] pauses affected
 *   unfinished work without touching unrelated tasks (D17, T11).
 * - Occurrence dedup keys make trigger firing idempotent: a restart never
 *   replays a trigger (T13).
 */
enum class WorkflowOccurrenceState {
    SCHEDULED, WAITING_EVENT, RUNNING, WAITING_APPROVAL, WAITING_USER,
    SUCCEEDED, FAILED, MISSED, SKIPPED, CANCELLED
}

data class WorkflowOccurrence(
    val id: String,
    val workflowId: String,
    val definitionVersion: Long,
    val triggerIndex: Int,
    val dedupKey: String,
    val scheduledForMs: Long,
    val windowEndMs: Long,
    val state: WorkflowOccurrenceState,
    /** Engine resume position after a wait/approval/user question: index path from the step-graph root. */
    val resumePath: List<Int> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val resultSummary: String? = null,
    /**
     * Finding 4 (timer continuation resume): fire time of a timer/until wait
     * armed on this occurrence; null for event waits and non-waiting runs.
     * A timer resume is only claimable from WAITING_EVENT while this is set.
     */
    val resumeAtMs: Long? = null,
    /** Engine progress persisted at suspend so a resume continues without re-running steps. */
    val completedStepIds: List<String> = emptyList(),
    val stepResults: Map<String, Map<String, String>> = emptyMap()
)

enum class WorkflowReceiptKind {
    DRAFT_SAVED, ENABLED, DISABLED_PAUSED, REVISED, CAPTURED,
    SCHEDULED, FIRED, COMPLETED, SKIPPED,
    MISSED_RELEVANT, MISSED_IRRELEVANT, MISSED_UNCERTAIN,
    NO_PROGRESS_ASKED, NEEDS_APPROVAL_ASKED, GRANT_CREATED, RESCHEDULED
}

data class WorkflowReceipt(
    val id: String,
    val workflowId: String,
    val occurrenceId: String?,
    val kind: WorkflowReceiptKind,
    val message: String,
    val atMs: Long
)

data class DisableResult(val pausedOccurrenceIds: List<String>, val revokedGrantIds: List<String>)

class WorkflowLedger(
    private val store: ToolTaskStore = InMemoryToolTaskStore(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private fun journal() = store.readJournal()

    /** Save a conversation-created or captured draft. Drafts are disabled and never run. */
    fun saveDraft(definition: WorkflowDefinition): WorkflowDefinition {
        val at = now()
        val draft = definition.copy(enabled = false, createdAtMs = at, updatedAtMs = at, version = 1)
        store.updateJournal { j ->
            require(j.workflows.none { it.id == draft.id }) { "A workflow with this id already exists." }
            j.copy(workflows = j.workflows + draft,
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), draft.id, null,
                    WorkflowReceiptKind.DRAFT_SAVED, "Saved “${draft.name}” as a draft. It will not run until you enable it.", at))
        }
        return draft
    }

    /** The plain-language preview shown before enabling (D31, T12). */
    fun preview(id: String): String {
        val current = current(id) ?: throw IllegalArgumentException("Unknown workflow.")
        return current.previewText()
    }

    fun current(id: String): WorkflowDefinition? =
        journal().workflows.filter { it.id == id }.maxByOrNull { it.version }

    fun list(): List<WorkflowDefinition> {
        val seen = hashSetOf<String>()
        return journal().workflows.sortedByDescending { it.version }.filter { seen.add(it.id) }
    }

    /** Explicit enablement (T12). Schedules the first occurrences for time-based triggers. */
    fun enable(id: String): List<WorkflowOccurrence> {
        val at = now()
        val definition = current(id) ?: throw IllegalArgumentException("Unknown workflow.")
        var scheduled = emptyList<WorkflowOccurrence>()
        store.updateJournal { j ->
            val updated = j.workflows.map { if (it.id == id && it.version == definition.version)
                it.copy(enabled = true, updatedAtMs = at) else it }
            var next = j.copy(workflows = updated,
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), id, null,
                    WorkflowReceiptKind.ENABLED, "Enabled “${definition.name}”.", at))
            scheduled = definition.triggers.mapIndexedNotNull { index, trigger ->
                scheduleForTrigger(next, definition, index, trigger, at)?.also { occurrence ->
                    next = next.copy(occurrences = next.occurrences + occurrence,
                        workflowReceipts = next.workflowReceipts + WorkflowReceipt(
                            UUID.randomUUID().toString(), id, occurrence.id,
                            WorkflowReceiptKind.SCHEDULED,
                            "Scheduled “${definition.name}” (${describeTriggerShort(trigger)}).", at))
                }
            }
            next
        }
        return scheduled
    }

    /** Disabling pauses affected unfinished work immediately (D17, T11). */
    fun disable(id: String): DisableResult {
        val at = now()
        var result = DisableResult(emptyList(), emptyList())
        store.updateJournal { j ->
            val definition = j.workflows.filter { it.id == id }.maxByOrNull { it.version }
                ?: return@updateJournal j
            val affected = j.occurrences.filter { it.workflowId == id && it.state in UNFINISHED_OCCURRENCE_STATES }
            val paused = affected.map { it.copy(state = WorkflowOccurrenceState.CANCELLED, updatedAtMs = at,
                resultSummary = "Paused because “${definition.name}” was disabled.") }
            val grantIds = j.grants.filter { it.routineId == routineIdFor(id) && !it.revoked }.map { it.id }.toSet()
            val grants = j.grants.map { if (it.id in grantIds) it.copy(revoked = true) else it }
            // Pausing routine attempts that relied on the revoked grants mirrors
            // ToolTaskLedger.revokeGrant: running attempts keep their receipts.
            val attempts = j.attempts.map { a ->
                if (a.grantId in grantIds && !a.state.isTerminal() && a.state != ToolTaskState.RUNNING)
                    a.copy(state = ToolTaskState.PAUSED, updatedAtMs = maxOf(a.updatedAtMs, at),
                        result = "Paused because “${definition.name}” was disabled.") else a
            }
            result = DisableResult(paused.map { it.id }, grantIds.toList())
            j.copy(workflows = j.workflows.map { if (it.id == id) it.copy(enabled = false, updatedAtMs = at) else it },
                occurrences = j.occurrences.map { o -> paused.find { it.id == o.id } ?: o },
                grants = grants, attempts = attempts,
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), id, null, WorkflowReceiptKind.DISABLED_PAUSED,
                    "Disabled “${definition.name}”. Paused ${paused.size} unfinished occurrence(s); unrelated tasks untouched.", at))
        }
        return result
    }

    /**
     * Revise a workflow: adds a new version, preserving the old ones.
     * Running occurrences keep their pinned version — the revision never
     * mutates them (T12).
     */
    fun revise(id: String, updated: WorkflowDefinition): WorkflowDefinition {
        val at = now()
        val current = current(id) ?: throw IllegalArgumentException("Unknown workflow.")
        require(updated.id == id) { "A revision cannot change the workflow id." }
        val revised = updated.copy(version = current.version + 1, enabled = current.enabled,
            createdAtMs = current.createdAtMs, updatedAtMs = at)
        // init{} runs validation on copy().
        store.updateJournal { j ->
            j.copy(workflows = j.workflows + revised,
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), id, null, WorkflowReceiptKind.REVISED,
                    "Revised “${revised.name}” to version ${revised.version}. Running occurrences keep version ${current.version}.", at))
        }
        return revised
    }

    /**
     * Schedule one occurrence. Returns null when the workflow is disabled,
     * or when an unfinished occurrence already holds [dedupKey] — a restart
     * never replays a trigger (T13).
     */
    fun scheduleOccurrence(
        workflowId: String,
        triggerIndex: Int,
        scheduledForMs: Long,
        windowEndMs: Long,
        dedupKey: String
    ): WorkflowOccurrence? {
        val at = now()
        val definition = current(workflowId) ?: return null
        if (!definition.enabled) return null
        require(triggerIndex in definition.triggers.indices) { "Trigger index out of range." }
        require(scheduledForMs > 0 && windowEndMs >= scheduledForMs) { "Invalid schedule window." }
        require(dedupKey.isNotBlank() && dedupKey.length <= 256) { "Invalid dedup key." }
        var created: WorkflowOccurrence? = null
        store.updateJournal { j ->
            if (j.occurrences.any { it.dedupKey == dedupKey && it.state in UNFINISHED_OCCURRENCE_STATES }) return@updateJournal j
            created = WorkflowOccurrence(UUID.randomUUID().toString(), workflowId, definition.version,
                triggerIndex, dedupKey, scheduledForMs, windowEndMs, WorkflowOccurrenceState.SCHEDULED,
                createdAtMs = at, updatedAtMs = at)
            j.copy(occurrences = j.occurrences + checkNotNull(created),
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), workflowId, checkNotNull(created).id,
                    WorkflowReceiptKind.SCHEDULED, "Scheduled “${definition.name}”.", at))
        }
        return created
    }

    /**
     * Atomically claim a due occurrence for running. Idempotent: only a
     * SCHEDULED occurrence whose time has come and whose workflow is still
     * enabled can be claimed — a second claim finds it RUNNING, never
     * re-fires it (T13).
     */
    fun claimDueOccurrence(id: String): WorkflowOccurrence? {
        val at = now()
        var claimed: WorkflowOccurrence? = null
        store.updateJournal { j ->
            val occurrence = j.occurrences.find { it.id == id } ?: return@updateJournal j
            val definition = j.workflows.filter { it.id == occurrence.workflowId }
                .maxByOrNull { it.version } ?: return@updateJournal j
            if (!definition.enabled || occurrence.state != WorkflowOccurrenceState.SCHEDULED ||
                occurrence.scheduledForMs > at) return@updateJournal j
            claimed = occurrence.copy(state = WorkflowOccurrenceState.RUNNING, updatedAtMs = at)
            j.copy(occurrences = j.occurrences.map { if (it.id == id) checkNotNull(claimed) else it },
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), occurrence.workflowId, id,
                    WorkflowReceiptKind.FIRED, "“${definition.name}” started.", at))
        }
        return claimed
    }

    /**
     * Finding 4 (timer continuation resume): atomically claim a timer-armed
     * resume. Only a WAITING_EVENT occurrence with a due resumeAtMs is
     * claimable here — event waits (resumeAtMs == null) resume through their
     * listeners, never through an alarm. Idempotent: a second claim finds
     * the occurrence RUNNING and does nothing.
     */
    fun claimResumeOccurrence(id: String): WorkflowOccurrence? {
        val at = now()
        var claimed: WorkflowOccurrence? = null
        store.updateJournal { j ->
            val occurrence = j.occurrences.find { it.id == id } ?: return@updateJournal j
            val resumeAt = occurrence.resumeAtMs ?: return@updateJournal j
            if (occurrence.state != WorkflowOccurrenceState.WAITING_EVENT || resumeAt > at)
                return@updateJournal j
            val definition = j.workflows.filter { it.id == occurrence.workflowId }
                .maxByOrNull { it.version }
            claimed = occurrence.copy(state = WorkflowOccurrenceState.RUNNING, updatedAtMs = at)
            j.copy(occurrences = j.occurrences.map { if (it.id == id) checkNotNull(claimed) else it },
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), occurrence.workflowId, id,
                    WorkflowReceiptKind.FIRED, "“${definition?.name ?: "routine"}” resumed after its timer.", at))
        }
        return claimed
    }

    /**
     * Timer resumes currently waiting on their alarm: the schedule receiver
     * re-arms future ones after a restart and reports past-due ones as
     * missed instead of auto-running them.
     */
    fun timerResumes(): List<WorkflowOccurrence> =
        journal().occurrences.filter {
            it.state == WorkflowOccurrenceState.WAITING_EVENT && it.resumeAtMs != null
        }

    fun markWaiting(
        id: String,
        state: WorkflowOccurrenceState,
        resumePath: List<Int>,
        note: String? = null,
        resumeAtMs: Long? = null,
        completedStepIds: List<String> = emptyList(),
        stepResults: Map<String, Map<String, String>> = emptyMap()
    ): Boolean {
        require(state in setOf(WorkflowOccurrenceState.WAITING_EVENT, WorkflowOccurrenceState.WAITING_APPROVAL,
            WorkflowOccurrenceState.WAITING_USER)) { "markWaiting needs a waiting state." }
        val at = now()
        var changed = false
        store.updateJournal { j ->
            val occurrence = j.occurrences.find { it.id == id && it.state == WorkflowOccurrenceState.RUNNING }
                ?: return@updateJournal j
            changed = true
            j.copy(occurrences = j.occurrences.map {
                if (it.id == id) it.copy(state = state, resumePath = resumePath.toList(), updatedAtMs = at,
                    resultSummary = note ?: it.resultSummary, resumeAtMs = resumeAtMs,
                    completedStepIds = completedStepIds.toList(),
                    stepResults = stepResults.mapValues { (_, v) -> v.toMap() }) else it
            })
        }
        return changed
    }

    fun completeOccurrence(id: String, succeeded: Boolean, summary: String): Boolean {
        val at = now()
        var changed = false
        store.updateJournal { j ->
            val occurrence = j.occurrences.find { it.id == id && it.state !in TERMINAL_OCCURRENCE_STATES }
                ?: return@updateJournal j
            changed = true
            val definition = j.workflows.filter { it.id == occurrence.workflowId }.maxByOrNull { it.version }
            j.copy(occurrences = j.occurrences.map {
                if (it.id == id) it.copy(
                    state = if (succeeded) WorkflowOccurrenceState.SUCCEEDED else WorkflowOccurrenceState.FAILED,
                    updatedAtMs = at, resultSummary = summary.take(512)) else it
            }, workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                UUID.randomUUID().toString(), occurrence.workflowId, id, WorkflowReceiptKind.COMPLETED,
                "“${definition?.name ?: "routine"}” ${if (succeeded) "finished" else "stopped"}: ${summary.take(256)}", at))
        }
        return changed
    }

    /**
     * Record a missed-run evaluation (T14). The local planner's decision —
     * relevant, irrelevant or uncertain — is persisted with its receipt; a
     * missed run never silently re-fires.
     */
    fun recordMissedEvaluation(id: String, decision: MissedRunDecision): Boolean {
        val at = now()
        var changed = false
        store.updateJournal { j ->
            // SCHEDULED occurrences, plus WAITING_EVENT timer resumes whose
            // fire time passed (finding 4): a missed resume is reported with
            // an honest receipt, never auto-run.
            val occurrence = j.occurrences.find { it.id == id &&
                (it.state == WorkflowOccurrenceState.SCHEDULED ||
                    (it.state == WorkflowOccurrenceState.WAITING_EVENT && it.resumeAtMs != null)) }
                ?: return@updateJournal j
            changed = true
            val definition = j.workflows.filter { it.id == occurrence.workflowId }.maxByOrNull { it.version }
            val kind = when (decision) {
                is MissedRunDecision.Relevant -> WorkflowReceiptKind.MISSED_RELEVANT
                is MissedRunDecision.Irrelevant -> WorkflowReceiptKind.MISSED_IRRELEVANT
                is MissedRunDecision.Uncertain -> WorkflowReceiptKind.MISSED_UNCERTAIN
            }
            j.copy(occurrences = j.occurrences.map {
                if (it.id == id) it.copy(state = WorkflowOccurrenceState.MISSED, updatedAtMs = at,
                    resultSummary = decision.receipt.take(512)) else it
            }, workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                UUID.randomUUID().toString(), occurrence.workflowId, id, kind,
                "“${definition?.name ?: "routine"}” missed its time — ${decision.receipt.take(256)}", at))
        }
        return changed
    }

    fun occurrence(id: String): WorkflowOccurrence? = journal().occurrences.find { it.id == id }

    fun occurrencesFor(workflowId: String): List<WorkflowOccurrence> =
        journal().occurrences.filter { it.workflowId == workflowId }

    fun receiptsFor(workflowId: String): List<WorkflowReceipt> =
        journal().workflowReceipts.filter { it.workflowId == workflowId }

    fun definitionFor(occurrence: WorkflowOccurrence): WorkflowDefinition? =
        journal().workflows.find { it.id == occurrence.workflowId && it.version == occurrence.definitionVersion }

    /**
     * Capture a successful task as a reusable workflow draft (D31/D41).
     * Only completed groups whose every step succeeded and is
     * routine-eligible can be captured; the draft is disabled until the
     * user enables it.
     */
    fun captureFromTask(groupId: String, name: String): WorkflowDefinition? {
        val at = now()
        val journal = journal()
        val group = journal.groups.find { it.id == groupId } ?: return null
        val attempts = group.attemptIds.mapNotNull { id -> journal.attempts.find { it.id == id } }
        if (attempts.size != group.attemptIds.size || attempts.isEmpty() || attempts.size > 8) return null
        if (attempts.any { it.state != ToolTaskState.SUCCEEDED || !it.request.isRoutineEligible() }) return null
        require(name.isNotBlank() && name.length <= 128) { "Capture needs a name." }
        val steps = attempts.map { attempt ->
            WorkflowStep.Tool(UUID.randomUUID().toString(), attempt.request,
                outputs = standardToolOutputs(attempt.request))
        }
        val draft = WorkflowDefinition(UUID.randomUUID().toString(), name,
            "Saved from a completed task on ${java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(at))}.",
            steps, listOf(WorkflowTrigger.Manual), WorkflowOrigin.CAPTURED, createdAtMs = at, updatedAtMs = at)
        return saveDraft(draft).also {
            store.updateJournal { j -> j.copy(workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                UUID.randomUUID().toString(), it.id, null, WorkflowReceiptKind.CAPTURED,
                "Saved “$name” from a completed task. It will not run until you enable it.", at)) }
        }
    }

    // -- Routine grants (T11) -------------------------------------------------

    /** Stable routine id for a workflow's grants; independent of occurrences. */
    fun routineIdFor(workflowId: String) = "workflow:$workflowId"

    /**
     * Find an existing unexpired, unrevoked grant whose requests exactly
     * cover [requests] — routine grant reuse only when the limits match
     * (T11). A new tool can never broaden the grant: exact request equality
     * is required.
     */
    fun reusableGrant(workflowId: String, requests: List<ActionRequest>): ToolActionGrant? {
        val at = now()
        return journal().grants.find { grant ->
            grant.routineId == routineIdFor(workflowId) && !grant.revoked && at < grant.expiresAtMs &&
                grant.provider == "native" && grant.schemaVersion == MobileToolCatalog.VERSION &&
                grant.requests.size == requests.size &&
                grant.requests.zip(requests).all { (granted, wanted) -> granted == wanted } &&
                requests.all { it.isRoutineEligible() }
        }
    }

    /** Create a fresh routine grant for exactly these requests (1–3, routine-eligible). */
    fun createGrant(workflowId: String, requests: List<ActionRequest>, validForMs: Long = 3_600_000): ToolActionGrant {
        val at = now()
        require(requests.size in 1..3 && requests.all { it.isRoutineEligible() }) {
            "Routine grants cover 1-3 routine-eligible requests."
        }
        require(validForMs in 60_000..86_400_000) { "Grant validity must be 1m-24h." }
        val grant = ToolActionGrant(UUID.randomUUID().toString(), routineIdFor(workflowId), "native",
            MobileToolCatalog.VERSION, requests.map { it.frozen() }, at, Math.addExact(at, validForMs))
        store.updateJournal { j ->
            j.copy(grants = j.grants + grant,
                workflowReceipts = j.workflowReceipts + WorkflowReceipt(
                    UUID.randomUUID().toString(), workflowId, null, WorkflowReceiptKind.GRANT_CREATED,
                    "Routine permission granted for ${requests.size} action(s), exact limits.", at))
        }
        return grant
    }

    /**
     * Recover workflow occurrences after a restart (D27/D63, T13): future
     * scheduled occurrences are kept — never duplicated; running ones
     * become unknown-outcome instead of silently re-firing; past-due
     * scheduled occurrences are left for the missed-run policy, not
     * auto-run (no catch-up duplicate storm).
     */
    fun recoverAfterRestart(): List<WorkflowOccurrence> {
        val at = now()
        var recovered = emptyList<WorkflowOccurrence>()
        store.updateJournal { j ->
            val next = j.occurrences.map { occurrence ->
                when {
                    occurrence.state.isWorkflowTerminal() || occurrence.state == WorkflowOccurrenceState.MISSED -> occurrence
                    occurrence.state == WorkflowOccurrenceState.RUNNING -> occurrence.copy(
                        state = WorkflowOccurrenceState.FAILED, updatedAtMs = maxOf(occurrence.updatedAtMs, at),
                        resultSummary = "Jarvis restarted while this run was active. It was not repeated.")
                    else -> occurrence.copy(updatedAtMs = maxOf(occurrence.updatedAtMs, at))
                }
            }
            recovered = next
            val changed = next.filter { n -> j.occurrences.find { it.id == n.id } != n }
            j.copy(occurrences = next,
                workflowReceipts = j.workflowReceipts + changed.map {
                    WorkflowReceipt(UUID.randomUUID().toString(), it.workflowId, it.id,
                        WorkflowReceiptKind.RESCHEDULED,
                        "Recovered after restart: run marked ${it.state.name.lowercase()}, never re-fired.", at)
                })
        }
        return recovered
    }

    private fun scheduleForTrigger(
        journal: ToolTaskJournal,
        definition: WorkflowDefinition,
        triggerIndex: Int,
        trigger: WorkflowTrigger,
        at: Long
    ): WorkflowOccurrence? {
        val (fireAt, windowEnd) = when (trigger) {
            is WorkflowTrigger.Reminder -> trigger.atMs to trigger.atMs
            is WorkflowTrigger.Deadline -> trigger.atMs to trigger.atMs
            is WorkflowTrigger.Daily -> {
                val next = WorkflowScheduling.nextDailyFire(trigger, at, WorkflowScheduling.systemZone())
                next to next
            }
            is WorkflowTrigger.Window -> {
                // Schedule at the window start; the runtime may defer inside the window.
                trigger.earliestMs to trigger.latestMs
            }
            else -> return null // Manual and event triggers have no clock schedule.
        }
        if (fireAt <= at) return null
        val dedupKey = WorkflowScheduling.dedupKey(definition.id, triggerIndex, trigger, fireAt)
        if (journal.occurrences.any { it.dedupKey == dedupKey && it.state in UNFINISHED_OCCURRENCE_STATES }) return null
        return WorkflowOccurrence(UUID.randomUUID().toString(), definition.id, definition.version,
            triggerIndex, dedupKey, fireAt, windowEnd, WorkflowOccurrenceState.SCHEDULED,
            createdAtMs = at, updatedAtMs = at)
    }

    private fun describeTriggerShort(trigger: WorkflowTrigger): String = when (trigger) {
        is WorkflowTrigger.Manual -> "manual"
        is WorkflowTrigger.Reminder -> "reminder"
        is WorkflowTrigger.Daily -> "daily ${"%02d:%02d".format(trigger.hour, trigger.minute)}"
        is WorkflowTrigger.Window -> "flexible window"
        is WorkflowTrigger.OnNotification -> "on ${trigger.appKey} notification"
        is WorkflowTrigger.OnLocation -> "on location"
        is WorkflowTrigger.Deadline -> "deadline: ${trigger.title}"
    }

    companion object {
        val UNFINISHED_OCCURRENCE_STATES = setOf(
            WorkflowOccurrenceState.SCHEDULED, WorkflowOccurrenceState.WAITING_EVENT,
            WorkflowOccurrenceState.RUNNING, WorkflowOccurrenceState.WAITING_APPROVAL,
            WorkflowOccurrenceState.WAITING_USER)
        val TERMINAL_OCCURRENCE_STATES = setOf(
            WorkflowOccurrenceState.SUCCEEDED, WorkflowOccurrenceState.FAILED,
            WorkflowOccurrenceState.MISSED, WorkflowOccurrenceState.SKIPPED,
            WorkflowOccurrenceState.CANCELLED)
    }
}

internal fun WorkflowOccurrenceState.isWorkflowTerminal() =
    this in WorkflowLedger.TERMINAL_OCCURRENCE_STATES

/** The local planner's verdict on a missed run (D33, T14). */
sealed interface MissedRunDecision {
    val receipt: String
    /** Still relevant: run it now (confirmations still apply). */
    data class Relevant(override val receipt: String) : MissedRunDecision
    /** No longer relevant: report missed, do not run. */
    data class Irrelevant(override val receipt: String) : MissedRunDecision
    /** Cannot tell: ask the user, do not assume. */
    data class Uncertain(override val receipt: String, val question: String) : MissedRunDecision
}
