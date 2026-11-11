package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.ActionApprovalRequest
import com.battlesbudz.jarvis.v2.actions.ToolTaskAttempt
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskState

/**
 * Inline approval cards (2026-11-10): an approval requested during a
 * conversation is decided in that conversation's thread, not in the
 * phone-tasks panel. The card shows the exact action the panel used to
 * show — for run_in_sandbox, the exact command text — and carries the
 * same Approve/Deny callback (attempt id, generation, command) the
 * panel used, so the coordinator's decision path (atomic approval
 * consumption with the dispatch claim, session admissions, denial
 * cancelling the attempt) is unchanged. A card exists only while its
 * approval is unconsumed and its attempt is WAITING_APPROVAL in a group
 * owned by this conversation, so a decision removes the card on the
 * next journal snapshot and nothing leaks into another conversation.
 */
internal data class PendingApproval(
    val attempt: ToolTaskAttempt,
    val approval: ActionApprovalRequest
)

internal fun inlineApprovalCards(journal: ToolTaskJournal?, conversationId: String): List<PendingApproval> {
    val j = journal ?: return emptyList()
    val groups = j.groups.filter { it.conversationId == conversationId }.mapTo(hashSetOf()) { it.id }
    if (groups.isEmpty()) return emptyList()
    return j.attempts
        .filter { it.groupId in groups && it.state == ToolTaskState.WAITING_APPROVAL }
        .mapNotNull { attempt ->
            val approval = attempt.approvalId?.let { id ->
                j.approvals.find { it.id == id && !it.consumed && it.action == attempt.request }
            }
            approval?.let { PendingApproval(attempt, it) }
        }
        .sortedWith(compareBy({ it.attempt.createdAtMs }, { it.attempt.id }))
}

@Composable
internal fun InlineApprovalCard(card: PendingApproval, onAction: (String, Long, String) -> Unit) {
    val attempt = card.attempt
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("inline_approval_${attempt.id}")) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Jarvis · Approval needed", style = MaterialTheme.typography.labelMedium)
            Text(attempt.request.description(), modifier = Modifier.padding(top = 6.dp))
            Text("Waiting for your approval", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(attempt.id, attempt.generation, "approve") },
                    modifier = Modifier.testTag("task_approve_${attempt.id}")) { Text("Approve") }
                OutlinedButton(onClick = { onAction(attempt.id, attempt.generation, "deny") },
                    modifier = Modifier.testTag("task_deny_${attempt.id}")) { Text("Deny") }
            }
        }
    }
}

/** Standalone column form for fixtures that compose the cards outside the transcript list. */
@Composable
internal fun InlineApprovalCards(journal: ToolTaskJournal?, conversationId: String,
    onAction: (String, Long, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        inlineApprovalCards(journal, conversationId).forEach { card ->
            InlineApprovalCard(card, onAction)
        }
    }
}
