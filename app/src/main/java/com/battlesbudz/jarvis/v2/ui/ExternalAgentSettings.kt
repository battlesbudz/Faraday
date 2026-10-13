package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.ExternalAgentFunctions
import com.battlesbudz.jarvis.v2.actions.ExternalAgentGrant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M7 external-agent settings surface: which outside apps are allowed to call
 * Jarvis functions, what each one may call, and the authorize/revoke controls.
 *
 * Plain-language contract, always visible: external agents can only ever use
 * the five listed functions. Screen control, browser submit/login, app
 * launching, navigation, and reminders are never exposed to them, and every
 * call they make shows up in the same receipt history as everything else.
 */
@Composable
internal fun ExternalAgentSettingsSection(
    grants: List<ExternalAgentGrant>,
    onAuthorize: (callerId: String, displayName: String, functions: Set<String>) -> String,
    onRevoke: (callerId: String) -> Boolean,
    onSetEnabled: (callerId: String, enabled: Boolean) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("External agents", style = MaterialTheme.typography.titleMedium)
        Text(
            "Other apps on this phone can call five Jarvis functions — and only these five: " +
                ExternalAgentFunctions.EXPOSED.sorted().joinToString(", ") + ". " +
                "Screen control, browser actions, app launching, and reminders are never available to them. " +
                "Each agent needs your explicit approval below before it can call anything, and every call " +
                "appears in the receipt history.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (grants.isEmpty()) {
            Text("No external agents authorized.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        for (grant in grants) {
            ExternalAgentGrantRow(grant = grant, onRevoke = onRevoke, onSetEnabled = onSetEnabled)
        }
        var showAuthorize by remember { mutableStateOf(false) }
        var authorizeMessage by remember { mutableStateOf<String?>(null) }
        TextButton(
            onClick = { showAuthorize = true },
            modifier = Modifier.testTag("external_agent_authorize_button").padding(top = 8.dp),
        ) { Text("Authorize an agent") }
        authorizeMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (showAuthorize) {
            AuthorizeAgentDialog(
                onDismiss = { showAuthorize = false },
                onConfirm = { callerId, displayName, functions ->
                    val result = onAuthorize(callerId, displayName, functions)
                    authorizeMessage = result
                    showAuthorize = false
                },
            )
        }
    }
}

@Composable
private fun ExternalAgentGrantRow(
    grant: ExternalAgentGrant,
    onRevoke: (callerId: String) -> Boolean,
    onSetEnabled: (callerId: String, enabled: Boolean) -> Boolean,
) {
    var revoked by remember(grant.id, grant.revoked) { mutableStateOf(grant.revoked) }
    var enabled by remember(grant.id, grant.enabled) { mutableStateOf(grant.enabled) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grant.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(grant.callerId, style = MaterialTheme.typography.bodySmall)
            Text(
                "Can call: ${grant.allowedFunctions.sorted().joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Authorized ${dateFormat.format(Date(grant.authorizedAtMs))}" +
                    if (revoked) " · revoked" else if (!enabled) " · paused" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(
                onClick = {
                    val newEnabled = !enabled
                    if (onSetEnabled(grant.callerId, newEnabled)) enabled = newEnabled
                },
                enabled = !revoked,
                modifier = Modifier.testTag("external_agent_toggle_${grant.callerId}"),
            ) { Text(if (enabled) "Pause" else "Resume") }
            TextButton(
                onClick = { showRevokeConfirm = true },
                enabled = !revoked,
                modifier = Modifier.testTag("external_agent_revoke_${grant.callerId}"),
            ) { Text("Revoke") }
        }
    }
    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Revoke access?") },
            text = { Text("\"${grant.displayName}\" will no longer be able to call any Jarvis functions. This cannot be undone — you would need to authorize it again.") },
            confirmButton = {
                TextButton(onClick = {
                    if (onRevoke(grant.callerId)) revoked = true
                    showRevokeConfirm = false
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AuthorizeAgentDialog(
    onDismiss: () -> Unit,
    onConfirm: (callerId: String, displayName: String, functions: Set<String>) -> Unit,
) {
    var callerId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authorize an external agent") },
        text = {
            Column {
                Text(
                    "The caller ID is usually the app's package name (for example, com.example.myapp). " +
                        "Only check the functions this agent actually needs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = callerId,
                    onValueChange = { callerId = it; error = null },
                    label = { Text("Caller ID (app package)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; error = null },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text("Allowed functions:", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp))
                for (function in ExternalAgentFunctions.EXPOSED.sorted()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = function in selected.value,
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + function
                                else selected.value - function
                            },
                            modifier = Modifier.testTag("external_agent_function_$function"),
                        )
                        Text(function, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!ExternalAgentFunctions.isValidCallerId(callerId.trim().lowercase())) {
                    error = "Caller ID must look like an app package: lowercase letters, numbers, dots, dashes (1–48 characters)."
                    return@TextButton
                }
                if (displayName.isBlank()) {
                    error = "Give this agent a display name."
                    return@TextButton
                }
                if (selected.value.isEmpty()) {
                    error = "Check at least one function."
                    return@TextButton
                }
                onConfirm(callerId.trim().lowercase(), displayName.trim(), selected.value.toSet())
            }) { Text("Authorize") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
