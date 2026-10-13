package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * M2 settings surface (D36): saved workflows with enable/disable and the
 * connected tools list. Chat remains the operating surface — creation and
 * revision happen in conversation; this section only flips the explicit
 * enablement that drafts require before they can run.
 *
 * M3 adds the connected-providers section (D36, T16, T17): every known
 * ecosystem provider with an honest availability state and a plain-language
 * explanation. Unavailable providers explain why instead of implying they
 * work. The guided MCP setup (D07) accepts a custom server URL here; only
 * free tools turn on by default and an unknown price is never called free.
 */
@Composable
internal fun WorkflowSettingsSection(
    projection: WorkflowSettingsProjection?,
    onSetEnabled: (String, Boolean) -> Unit,
    onConnectMcpServer: (name: String, url: String, token: String, done: (String) -> Unit) -> Unit =
        { _, _, _, done -> done("MCP setup is unavailable right now.") },
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Tools & workflows", style = MaterialTheme.typography.titleMedium)
        Text("Routines you saved. A routine never runs until you enable it here or in chat.",
            style = MaterialTheme.typography.bodySmall)
        if (projection == null) {
            Text("Workflow settings are unavailable right now.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        if (projection.workflows.isEmpty()) {
            Text("No saved routines yet. Describe one in chat — for example, “remind me to charge my phone at 9 PM every day” — and I will draft it for your approval.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        for (row in projection.workflows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(row.summary, style = MaterialTheme.typography.bodySmall)
                    Text("${row.triggersSummary} · ${row.nextRunSummary ?: ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { onSetEnabled(row.id, !row.enabled) },
                    modifier = Modifier.testTag("workflow_toggle_${row.id.take(8)}")
                ) { Text(if (row.enabled) "Disable" else "Enable") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Connected tools", style = MaterialTheme.typography.titleSmall)
        for (tool in projection.tools) {
            Text("${tool.description}: ${tool.state}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Connected providers", style = MaterialTheme.typography.titleSmall)
        Text("App integrations. Provider tools stay off the assistant's surface until a later milestone; the states below are honest availability, not promises.",
            style = MaterialTheme.typography.bodySmall)
        if (projection.providers.isEmpty()) {
            Text("No providers known yet.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        for (provider in projection.providers) {
            Column(Modifier.padding(top = 8.dp)) {
                Text("${provider.displayName} (${provider.kind}): ${provider.state}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(provider.explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
        var showMcpDialog by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showMcpDialog = true },
            modifier = Modifier.testTag("mcp_connect").padding(top = 4.dp)
        ) { Text("Connect an MCP server") }
        if (showMcpDialog) {
            McpConnectDialog(
                onDismiss = { showMcpDialog = false },
                onConnect = onConnectMcpServer
            )
        }
    }
}

/** Guided MCP setup dialog (D07): custom server URL, optional token, staged honest results. */
@Composable
private fun McpConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (name: String, url: String, token: String, done: (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text("Connect an MCP server") },
        text = {
            Column {
                Text("Enter the server URL. Only free tools turn on by default; " +
                    "paid tools and unknown prices need your explicit review.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .testTag("mcp_name"))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Server URL (https)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .testTag("mcp_url"))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .testTag("mcp_token"))
                result?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("mcp_result"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    connecting = true
                    result = null
                    scope.launch {
                        val deferred = CompletableDeferred<String>()
                        onConnect(name, url, token) { deferred.complete(it) }
                        result = deferred.await()
                        connecting = false
                    }
                },
                enabled = !connecting,
                modifier = Modifier.testTag("mcp_confirm")
            ) { Text(if (connecting) "Connecting…" else "Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text("Close") }
        }
    )
}
