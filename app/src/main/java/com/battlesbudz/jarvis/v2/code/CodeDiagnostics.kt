package com.battlesbudz.jarvis.v2.code

import android.content.Context
import com.battlesbudz.jarvis.v2.sandbox.SandboxStore

/**
 * The code-mode block of the Copy Diagnostics paste (scope Unit 6),
 * following the sandbox block's pattern: a title line, then indented
 * counters — projects (with each project's short fingerprint, so a
 * paste can be tied to the exact project state it describes),
 * rounds, retries, fix rounds, exports, and the last outcome and
 * last error the [CodeStatsStore] recorded.
 *
 * Everything here reads persisted state: the project store's own
 * listing and the counters file. No engine is touched and nothing
 * is created, so the block is safe to build on the diagnostics path
 * at any time. Fingerprints are computed live from the project
 * files; a project whose files cannot be read reports `unknown`
 * rather than failing the whole paste.
 */
internal object CodeDiagnostics {

    fun snapshot(context: Context): String {
        val workspaceDir = SandboxStore(context.applicationContext).workspaceDir
        val store = CodeProjectStore(workspaceDir)
        val stats = CodeStatsStore(workspaceDir).snapshot()
        val projects = runCatching { store.listProjects() }.getOrDefault(emptyList())
        val fingerprints = projects.associate { project ->
            project.id to runCatching {
                ProjectFingerprint.compute(store.projectDir(project.id)).take(FINGERPRINT_CHARS)
            }.getOrDefault("unknown")
        }
        return format(projects, fingerprints, stats)
    }

    /** The block's exact layout, pinned by tests apart from any Android state. */
    fun format(
        projects: List<CodeProjectStore.CodeProject>,
        fingerprints: Map<String, String>,
        stats: CodeStatsStore.Stats,
    ): String {
        val projectList = if (projects.isEmpty()) {
            "0"
        } else {
            projects.size.toString() + " (" + projects.joinToString(", ") { project ->
                "${project.id} ${fingerprints[project.id] ?: "unknown"}"
            } + ")"
        }
        return listOf(
            "code:",
            "  projects: $projectList",
            "  rounds: ${stats.rounds}",
            "  retries: ${stats.retries}",
            "  fix rounds: ${stats.fixRounds}",
            "  exports: ${stats.exports}",
            "  last outcome: ${stats.lastOutcome ?: "none"}",
            "  last error: ${stats.lastError ?: "none"}",
        ).joinToString("\n")
    }

    private const val FINGERPRINT_CHARS: Int = 12
}
