package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Settings surface for the guided MCP setup (D07): a custom server URL is
 * accepted here; only free tools turn on by default and an unknown price is
 * never called free.
 *
 * The saved-workflows list, the connected-tools list, and the provider
 * availability lists that used to render here were removed in the
 * 2026-10-22 Settings simplification — they only reported state and did
 * nothing in Settings. Chat remains the operating surface for routines.
 * The [projection] and [onSetEnabled] parameters are retained so existing
 * call sites keep compiling unchanged; the section no longer reads them.
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
        Text("Connected providers", style = MaterialTheme.typography.titleSmall)
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
