package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.battlesbudz.jarvis.v2.sandbox.SandboxRunner
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
    val runner = remember { SandboxRunner(context.applicationContext, manager) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var proofRunning by remember { mutableStateOf(false) }
    var proofResults by remember { mutableStateOf("") }
    var commandText by remember { mutableStateOf("") }

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
        if (state == SandboxManager.InstallState.READY || state == SandboxManager.InstallState.BUSY) {
            // The free-form console (scope §12): type a command, run it,
            // read the raw output. The §14 proof list is executed through
            // this field; the fixed sequence below is a one-tap subset.
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                enabled = enabled && !busy && !proofRunning,
                label = { Text("Command to run in the sandbox") },
                modifier = Modifier.testTag("sandbox_command_field").fillMaxWidth()
            )
            TextButton(
                enabled = enabled && !busy && !proofRunning && commandText.isNotBlank(),
                modifier = Modifier.testTag("sandbox_command_run"),
                onClick = {
                    val script = commandText
                    proofRunning = true
                    proofResults = "Running…"
                    scope.launch(Dispatchers.IO) {
                        val r = runner.runShellCommand(script)
                        proofResults = buildString {
                            appendLine("— $script")
                            appendLine(when {
                                r.error != null -> "start failed: ${r.error}"
                                r.timedOut -> "TIMED OUT after ${r.durationMs} ms (watchdog)"
                                else -> "exit ${r.exitCode} in ${r.durationMs} ms"
                            })
                            append(r.output.trim().ifEmpty { "(no output)" })
                            if (r.truncated) append("\n(output truncated)")
                        }
                        proofRunning = false
                        refresh++
                    }
                }
            ) { Text("Run command") }
            TextButton(
                enabled = enabled && !busy && !proofRunning,
                modifier = Modifier.testTag("sandbox_proof_run"),
                onClick = {
                    proofRunning = true
                    proofResults = "Running proof checks…"
                    scope.launch(Dispatchers.IO) {
                        proofResults = runSandboxProofs(runner)
                        proofRunning = false
                        refresh++
                    }
                }
            ) { Text(if (proofRunning) "Running proof checks…" else "Run proof checks") }
            if (proofResults.isNotBlank()) {
                Text(
                    proofResults,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("sandbox_proof_results")
                )
            }
        }
    }
}

/**
 * The Phase 1 proof console (scope §14): a fixed sequence of runs whose
 * results are shown verbatim — the checks a human verifies on the phone
 * (echo, workspace persistence, exit codes, the timeout watchdog, the
 * inherited-fd hygiene from review M-4, multi-exec pipelines from review
 * M-6, and the network posture under the current toggle). Nothing here
 * interprets beyond labeling: the output is the evidence.
 */
private fun runSandboxProofs(runner: SandboxRunner): String {
    fun line(label: String, script: String, timeoutMs: Long = SandboxRunner.DEFAULT_TIMEOUT_MS): String {
        val r = runner.runShellCommand(script, timeoutMs)
        val outcome = when {
            r.error != null -> "start failed: ${r.error}"
            r.timedOut -> "TIMED OUT after ${r.durationMs} ms (watchdog)"
            else -> "exit ${r.exitCode} in ${r.durationMs} ms"
        }
        val body = r.output.trim().ifEmpty { "(no output)" }
        return "— $label: $outcome\n$body"
    }
    return listOf(
        line("echo", "echo sandbox-ok"),
        line("workspace write + read", "echo proof-line > /workspace/proof.txt && cat /workspace/proof.txt"),
        line("exit code 3", "exit 3"),
        line("timeout watchdog (2 s limit on a 30 s sleep)", "sleep 30", timeoutMs = 2_000),
        line("open fds inside the guest (M-4: only the PTY/stdio set)", "ls /proc/self/fd"),
        line("multi-exec pipeline (M-6)", "echo a b c | tr ' ' '\\n' | wc -l && ls /bin | wc -l"),
        line("network posture", "ping -c 1 -W 2 1.1.1.1 2>&1 | head -2 || echo ping-failed")
    ).joinToString("\n")
}
