package com.battlesbudz.jarvis.v2.code

import java.io.File

/**
 * The code-mode counters the Copy Diagnostics block reports (scope
 * Unit 6): how many rounds Battles has run, how many generation
 * retries those rounds spent, how many console-fix rounds ran, how
 * many APK exports finished, and the last outcome and last error.
 *
 * The counts live in one small key=value file in the workspace root,
 * beside the projects folder, so the sandbox's Clear workspace —
 * which deletes every file in the workspace — clears the counters
 * with the projects they describe, and Reset, which keeps the
 * workspace, keeps them. Writes are atomic (temp file, then rename)
 * and every method is synchronized; reads tolerate a torn or foreign
 * file by falling back to zeroes rather than failing diagnostics.
 *
 * What counts as a retry is exact, not estimated: the driver passes
 * the generation-attempt count it measured from the project's round
 * log (one entry per attempt, written by the session controller),
 * and a round's retries are its attempts beyond the first.
 */
class CodeStatsStore(workspaceDir: File) {

    private val file = File(workspaceDir, STATS_FILE_NAME)

    /** An immutable view of the counters, as the diagnostics block prints them. */
    data class Stats(
        val rounds: Int,
        val retries: Int,
        val fixRounds: Int,
        val exports: Int,
        val lastOutcome: String?,
        val lastError: String?,
    )

    /**
     * A user round finished, spending [attempts] generation attempts.
     * [outcome] becomes the last outcome; a non-null [error] becomes
     * the last error (a later success never erases the last error —
     * it is the most recent error that happened, not a status flag).
     */
    @Synchronized
    fun recordUserRound(attempts: Int, outcome: String, error: String?) {
        val current = read()
        write(
            current.copy(
                rounds = current.rounds + 1,
                retries = current.retries + (attempts - 1).coerceAtLeast(0),
                lastOutcome = outcome,
                lastError = error ?: current.lastError,
            )
        )
    }

    /**
     * Console-fix rounds finished — the automatic pass after a user
     * round, or a manual Fix errors pass — [fixRounds] rounds
     * spending [attempts] generation attempts between them. Counters
     * only: the caller folds the fix result into the operation's own
     * outcome line.
     */
    @Synchronized
    fun recordFixRounds(fixRounds: Int, attempts: Int) {
        if (fixRounds <= 0 && attempts <= 0) return
        val current = read()
        write(
            current.copy(
                fixRounds = current.fixRounds + fixRounds,
                retries = current.retries + (attempts - fixRounds).coerceAtLeast(0),
            )
        )
    }

    /** Names the last outcome (and optionally the last error) for events that are not rounds: a manual fix pass, a rollback. */
    @Synchronized
    fun recordOutcome(outcome: String, error: String? = null) {
        val current = read()
        write(current.copy(lastOutcome = outcome, lastError = error ?: current.lastError))
    }

    /** An export attempt finished; only a finished APK counts toward [Stats.exports]. */
    @Synchronized
    fun recordExport(success: Boolean, outcome: String, error: String?) {
        val current = read()
        write(
            current.copy(
                exports = current.exports + if (success) 1 else 0,
                lastOutcome = outcome,
                lastError = error ?: current.lastError,
            )
        )
    }

    @Synchronized
    fun snapshot(): Stats = read()

    private fun read(): Stats {
        val values = runCatching {
            if (!file.isFile) return Stats(0, 0, 0, 0, null, null)
            file.readLines().mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
        return Stats(
            rounds = values["rounds"]?.toIntOrNull() ?: 0,
            retries = values["retries"]?.toIntOrNull() ?: 0,
            fixRounds = values["fixRounds"]?.toIntOrNull() ?: 0,
            exports = values["exports"]?.toIntOrNull() ?: 0,
            lastOutcome = values["lastOutcome"]?.takeIf { it.isNotEmpty() },
            lastError = values["lastError"]?.takeIf { it.isNotEmpty() },
        )
    }

    private fun write(stats: Stats) {
        val text = buildString {
            append("rounds=").append(stats.rounds).append('\n')
            append("retries=").append(stats.retries).append('\n')
            append("fixRounds=").append(stats.fixRounds).append('\n')
            append("exports=").append(stats.exports).append('\n')
            append("lastOutcome=").append(oneLine(stats.lastOutcome)).append('\n')
            append("lastError=").append(oneLine(stats.lastError)).append('\n')
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$STATS_FILE_NAME.tmp")
        temp.writeText(text)
        check(temp.renameTo(file)) { "Could not store the code-mode counters" }
    }

    /** Outcomes and errors are single diagnostics lines: flatten and bound them. */
    private fun oneLine(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').trim().take(MAX_VALUE_CHARS)

    companion object {
        const val STATS_FILE_NAME: String = "code-stats.txt"
        private const val MAX_VALUE_CHARS: Int = 200
    }
}
