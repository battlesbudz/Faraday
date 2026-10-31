package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.battlesbudz.jarvis.v2.sandbox.SandboxManager
import com.battlesbudz.jarvis.v2.sandbox.SandboxSettings
import io.github.sylirre.terminal.term.UserlandRootfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings section for the on-device Linux sandbox (Phase 1 scope §11).
 * Lives in the Settings dialog in [VoiceCallScreen], next to the other
 * sections. Everything here is user-operated: installing, the networking
 * toggle (decision D3 — settings-only, default off, never per-command and
 * never model-controlled), Reset (rootfs only; the workspace survives),
 * and the separate Clear workspace action (decision D2).
 */
@Composable
internal fun SandboxSettingsSection(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { SandboxManager(context.applicationContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Read on every recomposition; `refresh` bumps after each action.
    val state = manager.installState
    val networkOn = SandboxSettings.networkEnabled(context)
    val storageMb = remember(refresh) { manager.store.storageBytes() / (1024.0 * 1024.0) }

    Column {
        Text("Linux sandbox", style = MaterialTheme.typography.titleMedium)
        Text(
            "An Alpine Linux environment that runs on this phone (chroot-ng engine). " +
                "Commands run locally; files you keep live in the workspace and survive a reset.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Status: ${when (state) {
                SandboxManager.InstallState.NOT_INSTALLED -> "not installed"
                SandboxManager.InstallState.INSTALLING -> "installing…"
                SandboxManager.InstallState.READY -> "installed and ready"
                SandboxManager.InstallState.BUSY -> "session running"
                SandboxManager.InstallState.FAILED -> "failed: ${manager.lastInstallError ?: "unknown error"}"
            }}",
            modifier = Modifier.testTag("sandbox_status")
        )
        Text("Storage: ${"%.1f".format(storageMb)} MB")
        Text("Networking: ${if (networkOn) "on" else "off"}")
        if (state == SandboxManager.InstallState.NOT_INSTALLED ||
            state == SandboxManager.InstallState.FAILED
        ) {
            TextButton(
                enabled = enabled && !busy,
                modifier = Modifier.testTag("sandbox_install"),
                onClick = {
                    busy = true
                    message = "Installing the sandbox…"
                    scope.launch(Dispatchers.IO) {
                        manager.ensureInstalled()
                        busy = false
                        refresh++
                        message = if (manager.installState == SandboxManager.InstallState.READY) {
                            "Sandbox installed."
                        } else {
                            "Install failed: ${manager.lastInstallError ?: "unknown error"}"
                        }
                    }
                }
            ) { Text("Install sandbox") }
        }
        TextButton(
            enabled = enabled && !busy,
            modifier = Modifier.testTag("sandbox_network_toggle"),
            onClick = {
                val next = !networkOn
                SandboxSettings.setNetworkEnabled(context, next)
                scope.launch(Dispatchers.IO) {
                    // Apply the guest-side default (DNS config) to match.
                    UserlandRootfs.applyNetworkingDefaults(context, next)
                }
                refresh++
                message = if (next) {
                    "Networking on: new sandbox sessions can reach the network."
                } else {
                    "Networking off: new sandbox sessions are blocked from the network at the engine."
                }
            }
        ) { Text(if (networkOn) "Turn networking off" else "Turn networking on") }
        if (!confirmReset) {
            TextButton(
                enabled = enabled && !busy,
                modifier = Modifier.testTag("sandbox_reset"),
                onClick = { confirmReset = true }
            ) { Text("Reset sandbox") }
        } else {
            Text(
                "Reset removes the Linux system and starts fresh. Your workspace files are kept.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                enabled = enabled && !busy,
                modifier = Modifier.testTag("sandbox_reset_confirm"),
                onClick = {
                    confirmReset = false
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        manager.reset()
                        busy = false
                        refresh++
                        message = "Sandbox reset. The workspace was kept."
                    }
                }
            ) { Text("Reset — keep workspace") }
            TextButton(
                enabled = enabled && !busy,
                onClick = { confirmReset = false }
            ) { Text("Cancel") }
        }
        if (!confirmClear) {
            TextButton(
                enabled = enabled && !busy,
                modifier = Modifier.testTag("sandbox_clear_workspace"),
                onClick = { confirmClear = true }
            ) { Text("Clear workspace") }
        } else {
            Text(
                "Clear workspace deletes every file in the workspace. The Linux system is kept.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(
                enabled = enabled && !busy,
                modifier = Modifier.testTag("sandbox_clear_workspace_confirm"),
                onClick = {
                    confirmClear = false
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        manager.clearWorkspace()
                        busy = false
                        refresh++
                        message = "Workspace cleared."
                    }
                }
            ) { Text("Clear — delete workspace files") }
            TextButton(
                enabled = enabled && !busy,
                onClick = { confirmClear = false }
            ) { Text("Cancel") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
