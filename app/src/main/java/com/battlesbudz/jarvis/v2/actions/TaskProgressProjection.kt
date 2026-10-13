package com.battlesbudz.jarvis.v2.actions

/**
 * M1d chat/notification progress (D35, T04, T15).
 *
 * One addressable task-status projection per task group, derived from the
 * durable journal. Chat bubbles, the task panel and notifications all read
 * this projection, so the visible status can never disagree with the ledger.
 * Ordered per-step receipts stay grouped under the logical task/turn.
 */

enum class TaskProjectionState { WORKING, WAITING_APPROVAL, WAITING_INPUT, PAUSED, FINISHED, CANCELLED, FAILED }

data class TaskStatusProjection(
    val groupId: String,
    val label: String,
    val state: TaskProjectionState,
    val completedSteps: Int,
    val totalSteps: Int,
    /** Short human-readable status line for chat bubbles and notifications. */
    val statusLine: String,
    /** Ordered per-step receipts, newest last. */
    val stepReceipts: List<String>
) {
    val isTerminal: Boolean get() = state == TaskProjectionState.FINISHED ||
        state == TaskProjectionState.CANCELLED || state == TaskProjectionState.FAILED
}

class TaskProgressProjector {
    fun project(journal: ToolTaskJournal, groupId: String): TaskStatusProjection? {
        val group = journal.groups.find { it.id == groupId } ?: return null
        val attempts = group.attemptIds.mapNotNull { id -> journal.attempts.find { it.id == id } }
        if (attempts.isEmpty()) return null
        val completed = attempts.count { it.state.isTerminal() }
        val receipts = attempts.mapNotNull { it.result?.takeIf { r -> r.isNotBlank() } }
        // An unconfirmed step is terminal for the journal but must never
        // read as "done": the status line says what was confirmed and what
        // was not, instead of promoting submission to completion.
        val unconfirmed = attempts.count { it.state == ToolTaskState.UNKNOWN_OUTCOME }
        val state = when {
            group.cancelled || attempts.any { it.state == ToolTaskState.CANCELLED } -> TaskProjectionState.CANCELLED
            attempts.any { it.state == ToolTaskState.FAILED } -> TaskProjectionState.FAILED
            attempts.all { it.state.isTerminal() } -> TaskProjectionState.FINISHED
            attempts.any { it.state == ToolTaskState.WAITING_APPROVAL } -> TaskProjectionState.WAITING_APPROVAL
            attempts.any { it.state == ToolTaskState.WAITING_INPUT } -> TaskProjectionState.WAITING_INPUT
            attempts.any { it.state == ToolTaskState.PAUSED } -> TaskProjectionState.PAUSED
            else -> TaskProjectionState.WORKING
        }
        val label = group.summary.ifBlank { "Phone task" }
        val statusLine = when (state) {
            TaskProjectionState.FINISHED -> if (unconfirmed == 0) "$label: done ($completed of ${attempts.size} steps)."
            else "$label: finished, but $unconfirmed of ${attempts.size} step(s) could not be confirmed."
            TaskProjectionState.CANCELLED -> "$label: cancelled ($completed of ${attempts.size} steps completed)."
            TaskProjectionState.FAILED -> "$label: could not complete ($completed of ${attempts.size} steps completed)."
            TaskProjectionState.WAITING_APPROVAL -> "$label: waiting for your approval ($completed of ${attempts.size} steps done)."
            TaskProjectionState.WAITING_INPUT -> "$label: waiting for your answer ($completed of ${attempts.size} steps done)."
            TaskProjectionState.PAUSED -> "$label: paused ($completed of ${attempts.size} steps done)."
            TaskProjectionState.WORKING -> "$label: working ($completed of ${attempts.size} steps done)."
        }
        return TaskStatusProjection(groupId, label, state, completed, attempts.size, statusLine, receipts)
    }
}
