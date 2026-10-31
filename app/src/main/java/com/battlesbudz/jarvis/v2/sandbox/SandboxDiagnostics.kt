package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context
import java.io.File

/**
 * The sandbox block of the diagnostics paste (Phase 1 scope §12): enough to
 * answer "what state is the sandbox in" from a pasted report — engine,
 * rootfs version as stamped at install, the networking setting, storage,
 * run bookkeeping, sessions lost to process death, and the engine's own
 * capability probe output captured at install (review M-6: the probe is
 * the ground truth for what the mediation layer can do on this device).
 *
 * Everything reads persisted state (store + state dir), so the block is
 * accurate even in a fresh process that never opened the sandbox.
 */
internal object SandboxDiagnostics {

    fun snapshot(context: Context): String {
        val store = SandboxStore(context)
        val installed = store.isInstalled()
        val usable = store.isUsable()
        val version = store.installedVersion() ?: "unknown"
        val storageMb = store.storageBytes() / (1024.0 * 1024.0)
        val network = if (SandboxSettings.networkEnabled(context)) "on" else "off"
        return buildString {
            appendLine("Sandbox (chroot-ng, Alpine 3.24.3):")
            appendLine("  rootfs: ${when {
                usable -> "installed ($version)"
                installed -> "present but unusable (no shell)"
                else -> "not installed"
            }}")
            appendLine("  networking: $network (default off; settings-only toggle)")
            appendLine("  storage: ${"%.1f".format(storageMb)} MB under files/sandbox")
            appendLine("  runs: ${store.runCount()}, last: ${store.lastRunSummary() ?: "none"}")
            appendLine("  resets: ${store.resetsCount()}")
            appendLine("  sessions lost to process death: ${sessionsLost(store)}")
            val probe = probeOutput(store)
            if (probe != null) {
                appendLine("  engine probe (captured at install):")
                for (line in probe.lines()) appendLine("    $line")
            } else {
                append("  engine probe: not captured")
            }
        }.trimEnd()
    }

    private fun sessionsLost(store: SandboxStore): Int = try {
        File(store.stateDir, "sessions-lost.txt").readText().trim().toInt()
    } catch (e: Exception) {
        0
    }

    private fun probeOutput(store: SandboxStore): String? = try {
        File(store.stateDir, "probe.txt").readText().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
