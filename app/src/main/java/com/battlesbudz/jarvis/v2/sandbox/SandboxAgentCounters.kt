package com.battlesbudz.jarvis.v2.sandbox

import java.io.File

/**
 * Sandbox Phase 2 agent-run bookkeeping for the diagnostics block: how many
 * commands Gemma proposed, how many Battles approved or denied, and how the
 * approved runs ended. Persisted as key=value lines in one small file in
 * the sandbox state dir — the same file-backed, best-effort pattern as the
 * Phase 1 counters ([SandboxStore.recordRun], resets) — so the numbers
 * survive the process that produced them and a diagnostics paste can prove
 * the approval gate from Battles's phone: a denied command must show up as
 * denied with no matching run.
 *
 * Pure JVM (a [File] and nothing Android), so the counting rules are
 * unit-tested directly.
 */
internal class SandboxAgentCounters(private val file: File) {

    data class Snapshot(
        val proposed: Int = 0,
        val approved: Int = 0,
        val denied: Int = 0,
        val runs: Int = 0,
        val failed: Int = 0,
        val timedOut: Int = 0,
        val lastExitCode: Int? = null
    )

    @Synchronized
    fun recordProposal() = update { it.copy(proposed = it.proposed + 1) }

    @Synchronized
    fun recordDecision(approved: Boolean) = update {
        if (approved) it.copy(approved = it.approved + 1) else it.copy(denied = it.denied + 1)
    }

    @Synchronized
    fun recordRun(exitCode: Int?, timedOut: Boolean, failed: Boolean) = update {
        it.copy(
            runs = it.runs + 1,
            failed = it.failed + if (failed) 1 else 0,
            timedOut = it.timedOut + if (timedOut) 1 else 0,
            lastExitCode = exitCode
        )
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val values = try {
            file.readLines().mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
        fun count(key: String): Int = values[key]?.toIntOrNull() ?: 0
        return Snapshot(
            proposed = count("proposed"),
            approved = count("approved"),
            denied = count("denied"),
            runs = count("runs"),
            failed = count("failed"),
            timedOut = count("timedOut"),
            lastExitCode = values["lastExit"]?.toIntOrNull()
        )
    }

    private fun update(change: (Snapshot) -> Snapshot) {
        // Bookkeeping never fails the action it describes (Phase 1 rule).
        try {
            val next = change(snapshot())
            file.parentFile?.mkdirs()
            file.writeText(
                "proposed=${next.proposed}\n" +
                    "approved=${next.approved}\n" +
                    "denied=${next.denied}\n" +
                    "runs=${next.runs}\n" +
                    "failed=${next.failed}\n" +
                    "timedOut=${next.timedOut}\n" +
                    "lastExit=${next.lastExitCode?.toString() ?: "none"}\n"
            )
        } catch (e: Exception) {
            // Diagnostics garnish only.
        }
    }
}
