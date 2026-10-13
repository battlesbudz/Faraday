package com.battlesbudz.jarvis.v2.actions

import java.security.MessageDigest
import java.util.UUID

enum class ApprovalDecision { APPROVED, DENIED, STALE, AMBIGUOUS }
data class ActionApprovalRequest(
    val id: String, val taskId: String, val stepId: String, val provider: String,
    val action: ActionRequest, val schemaVersion: Int, val revision: Long,
    val fingerprint: String, val createdAtMs: Long, val consumed: Boolean = false,
    val decision: ApprovalDecision? = null
)

/** Length framing binds every field without delimiter collisions. */
internal fun approvalFingerprint(taskId: String, stepId: String, provider: String, action: ActionRequest,
    schemaVersion: Int, revision: Long): String {
    val fields = listOf(taskId, stepId, provider, schemaVersion.toString(), revision.toString(), action.name) +
        action.arguments.toSortedMap().flatMap { listOf(it.key, it.value) }
    val canonical = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
internal fun newApproval(j: ToolTaskJournal, taskId: String, stepId: String, provider: String,
    action: ActionRequest, schemaVersion: Int, atMs: Long): ActionApprovalRequest {
    val revision = Math.addExact(j.approvals.filter { it.taskId == taskId && it.stepId == stepId }.maxOfOrNull { it.revision } ?: 0L, 1)
    val frozen = action.frozen()
    return ActionApprovalRequest(UUID.randomUUID().toString(), taskId, stepId, provider, frozen, schemaVersion, revision,
        approvalFingerprint(taskId, stepId, provider, frozen, schemaVersion, revision), atMs)
}
internal fun replaceApprovals(j: ToolTaskJournal, fresh: ActionApprovalRequest) = j.approvals.map {
    if (!it.consumed && it.taskId == fresh.taskId && it.stepId == fresh.stepId)
        it.copy(consumed = true, decision = ApprovalDecision.STALE) else it
} + fresh

class ActionApprovalStore(
    private var store: ToolTaskStore = InMemoryToolTaskStore(),
    private val now: () -> Long = System::currentTimeMillis
) {
    /** Legacy callers may construct stores separately; the gate unifies them before admission. */
    internal fun attach(shared: ToolTaskStore) {
        if (store === shared) return
        val old = store.readJournal()
        if (old.approvals.isNotEmpty()) shared.updateJournal { j ->
            j.copy(approvals = (j.approvals + old.approvals).distinctBy { it.id }, activeQuestionId = null)
        }
        store = shared
    }
    fun request(taskId: String, stepId: String, provider: String, action: ActionRequest, schemaVersion: Int): ActionApprovalRequest {
        var created: ActionApprovalRequest? = null
        store.updateJournal { j ->
            created = newApproval(j, taskId, stepId, provider, action, schemaVersion, now())
            j.copy(approvals = replaceApprovals(j, checkNotNull(created)), activeQuestionId = null)
        }
        return checkNotNull(created)
    }
    fun consume(id: String, fingerprint: String): ApprovalDecision = decide(id, fingerprint, ApprovalDecision.APPROVED)
    fun deny(id: String): ApprovalDecision = decide(id, null, ApprovalDecision.DENIED)
    private fun decide(id: String, fingerprint: String?, decision: ApprovalDecision): ApprovalDecision {
        var result = ApprovalDecision.STALE
        store.updateJournal { j ->
            val a = j.approvals.find { it.id == id && !it.consumed } ?: return@updateJournal j
            if (fingerprint != null && a.fingerprint != fingerprint) return@updateJournal j
            result = decision
            j.copy(approvals = j.approvals.map { if (it.id == id) it.copy(consumed = true, decision = decision) else it },
                activeQuestionId = j.activeQuestionId?.takeUnless { it == id },
                attempts = if (decision != ApprovalDecision.DENIED) j.attempts else j.attempts.map { task ->
                    if (task.approvalId == id && task.state == ToolTaskState.WAITING_APPROVAL)
                        task.copy(generation = Math.addExact(task.generation, 1), state = ToolTaskState.CANCELLED,
                            updatedAtMs = maxOf(task.updatedAtMs, now()), result = "You declined this action.") else task
                })
        }
        return result
    }
    fun activeQuestion(): ActionApprovalRequest? {
        val j = store.readJournal()
        val open = j.approvals.filterNot { it.consumed }
        return open.singleOrNull()?.takeIf { it.id == j.activeQuestionId }
    }
    fun presentQuestion(id: String): Boolean {
        var presented = false
        store.updateJournal { j ->
            if (j.approvals.none { it.id == id && !it.consumed }) j
            else { presented = true; j.copy(activeQuestionId = id) }
        }
        return presented
    }
    fun spokenYes(): ApprovalDecision {
        var result = ApprovalDecision.AMBIGUOUS
        store.updateJournal { j ->
            val a = j.approvals.filterNot { it.consumed }.singleOrNull()?.takeIf { it.id == j.activeQuestionId }
                ?: return@updateJournal j
            result = ApprovalDecision.APPROVED
            j.copy(approvals = j.approvals.map { if (it.id == a.id) it.copy(consumed = true, decision = result) else it },
                activeQuestionId = null)
        }
        return result
    }
    fun get(id: String) = store.readJournal().approvals.find { it.id == id }
    fun pending() = store.readJournal().approvals.filterNot { it.consumed }
}
