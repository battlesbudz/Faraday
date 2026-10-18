package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.*

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun PhoneTaskPanel(journal: ToolTaskJournal?, conversationId: String, error: String?,
    onAction: (String, Long, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val groups = journal?.groups.orEmpty().filter { it.conversationId == conversationId }.mapTo(hashSetOf()) { it.id }
    val attempts = journal?.attempts.orEmpty().filter { it.groupId in groups || it.groupId == null &&
        it.state !in setOf(ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED) && !it.reconciled }
    val unfinished = attempts.filter { it.state !in setOf(ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED) && !it.reconciled }
    if (attempts.isNotEmpty() || error != null) TextButton(onClick = { open = true }, modifier = Modifier.testTag("phone_tasks_open")) {
        Text(if (error != null) "Phone tasks need attention" else "Phone tasks · ${unfinished.size} waiting")
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Phone tasks") },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } }, text = {
            // Dialog content is a separate semantics root from the activity.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).semantics { testTagsAsResourceId = true },
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                items((unfinished + attempts.filterNot { it in unfinished }.takeLast(3)).distinctBy { it.id }, key = { it.id }) { a ->
                    Column(Modifier.fillMaxWidth().testTag("phone_task_${a.id}")) {
                        Text(a.request.description(), style = MaterialTheme.typography.titleSmall)
                        Text(a.result ?: a.state.description(), style = MaterialTheme.typography.bodySmall)
                        if (a.state == ToolTaskState.UNKNOWN_OUTCOME && !a.reconciled) {
                            Text("Check what happened before requesting this action again.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onAction(a.id, a.generation, "checked") }, modifier = Modifier.testTag("task_checked_${a.id}")) { Text("I've checked this") }
                        }
                        val approval = journal?.approvals?.find { it.id == a.approvalId && !it.consumed && it.action == a.request }
                        if (a.state == ToolTaskState.WAITING_APPROVAL && approval != null) {
                            Row {
                                TextButton(onClick = { onAction(a.id, a.generation, "approve") }, modifier = Modifier.testTag("task_approve_${a.id}")) { Text("Approve") }
                                TextButton(onClick = { onAction(a.id, a.generation, "deny") }, modifier = Modifier.testTag("task_deny_${a.id}")) { Text("Decline") }
                            }
                        } else if (a.state in setOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.PAUSED)) {
                            TextButton(onClick = { onAction(a.id, a.generation, "cancel") }, modifier = Modifier.testTag("task_cancel_${a.id}")) { Text("Cancel task") }
                        }
                    }
                }
            }
        })
}

private fun ActionRequest.description() = when (name) {
    "read_battery" -> "Check battery"
    "set_volume" -> "Set media volume to ${arguments["level"]}%"
    "open_app" -> "Open ${arguments["app"] ?: arguments["package"]}"
    "media_control" -> (MediaControlAction.fromVerb(arguments["action"].orEmpty())?.label
        ?.replaceFirstChar { it.uppercase() } ?: "Control") + " media"
    "browse_open" -> "Open ${arguments["url"]?.let { BrowserNavigationPolicy.hostOf(it) ?: it } ?: "the page"} in the browser"
    "browse_read" -> "Read the current page"
    "browse_click" -> "Follow link ${arguments["target"]}"
    "browse_back" -> "Go back"
    "browse_forward" -> "Go forward"
    "browse_fill" -> "Fill in the form field"
    "browse_submit" -> "Submit the form"
    "browse_handoff" -> "Open the page in your browser"
    "browse_login" -> "Fill the login with your password manager"
    else -> name
}
private fun ToolTaskState.description() = when (this) {
    ToolTaskState.QUEUED, ToolTaskState.READY -> "Waiting for its turn"
    ToolTaskState.RUNNING -> "Working"
    ToolTaskState.WAITING_APPROVAL -> "Waiting for your approval"
    ToolTaskState.PAUSED -> "Paused. Please make a fresh request if still needed."
    ToolTaskState.SUCCEEDED -> "Completed"
    ToolTaskState.FAILED -> "Could not complete"
    ToolTaskState.CANCELLED -> "Cancelled"
    ToolTaskState.UNKNOWN_OUTCOME -> "Completion could not be confirmed"
    else -> "Waiting"
}
