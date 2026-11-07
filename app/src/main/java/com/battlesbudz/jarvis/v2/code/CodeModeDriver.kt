package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.CodeModeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What one Code screen operation did, in the shape the screen renders. */
sealed interface CodeOperationReport {

    /**
     * A user round and, when it applied, its automatic console-fix
     * pass. [fixResult] is null when the round wrote nothing, so no
     * preview check ran. [attempts] counts every generation attempt
     * the operation spent, measured from the project's round log.
     */
    data class Round(
        val outcome: CodeRoundOutcome,
        val fixResult: CodeFixLoopResult?,
        val attempts: Int,
    ) : CodeOperationReport

    /** A manual Fix errors pass. [attempts] counts its generation attempts. */
    data class Fix(val result: CodeFixLoopResult, val attempts: Int) : CodeOperationReport

    /** The engine borrow was refused — the lease is held, or no engine could be prepared. Nothing ran. */
    data class Unavailable(val reason: String) : CodeOperationReport

    /** The operation itself broke (engine or storage failure). Nothing is claimed about the project. */
    data class FailedToRun(val reason: String) : CodeOperationReport
}

/**
 * The Code screen's wiring over the landed code-mode machinery
 * (scope Unit 6): it owns one [CodeSessionController] /
 * [CodeRoundRunner] / [CodePreviewFixLoop] session at a time, bound
 * to the borrowed engine, the open project, and that project's
 * preview console, and drives the scope's round flow — Battles's
 * message to a gated round, an applied round to a preview reload and
 * the bounded automatic fix pass, the Fix errors button to the same
 * loop uncapped — recording every operation in [CodeStatsStore] for
 * the diagnostics block. Decision A holds: no approval step exists
 * anywhere in this flow.
 *
 * A session is rebuilt whenever its binding changes. Switching
 * projects on the same engine releases the old session first
 * (leaving a project leaves its conversation); a different engine
 * means the old engine is gone or closing, so the stale session is
 * dropped and the fresh controller re-prepares from the on-disk
 * snapshot — the cohesion rule doing its work. A skipped release is
 * always safe: the engine's own reset discipline exits code mode
 * before any ordinary generation.
 *
 * Generation attempts are counted from the project's round log,
 * which the session controller appends exactly once per attempt;
 * the runner appends none of its own. Retries in the diagnostics
 * are therefore measured, never estimated.
 */
class CodeModeDriver(
    private val store: CodeProjectStore,
    private val stats: CodeStatsStore,
    private val engineAccess: CodeEngineAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val loopSettle: suspend () -> Unit = { delay(CodePreviewFixLoop.SETTLE_MILLIS) },
) {

    private class Session(
        val engine: CodeModeEngine,
        val projectId: String,
        val consoleErrors: CodeConsoleErrors,
        val controller: CodeSessionController,
        val runner: CodeRoundRunner,
        val loop: CodePreviewFixLoop,
    )

    private var session: Session? = null

    /**
     * Runs one of Battles's rounds: the message goes to the round
     * runner, an applied round reloads the preview and runs the
     * automatic fix pass, and the whole operation is recorded.
     * [reloadPreview] must surface the project's current files in
     * its WebView; it is called on the operation's own thread.
     */
    suspend fun sendUserRound(
        project: CodeProjectStore.CodeProject,
        request: String,
        consoleErrors: CodeConsoleErrors,
        reloadPreview: suspend () -> Unit,
        onProgress: (CodeRoundProgress) -> Unit = {},
    ): CodeOperationReport {
        val attemptsBefore = roundLogEntries(project)
        val borrowed = try {
            engineAccess.withEngine { engine, contextTokens ->
                val active = sessionFor(engine, project, consoleErrors, reloadPreview, contextTokens)
                active.loop.onUserMessage()
                val outcome = active.runner.runRound(project, request, onProgress)
                val roundAttempts = roundLogEntries(project) - attemptsBefore
                var fixResult: CodeFixLoopResult? = null
                if (outcome is CodeRoundOutcome.Applied) {
                    // The new files reach the preview before the loop's
                    // first check, so the check reads the page Battles
                    // is looking at, not the one the round replaced.
                    reloadPreview()
                    fixResult = active.loop.afterRound(project, onProgress)
                }
                val fixAttempts = roundLogEntries(project) - attemptsBefore - roundAttempts
                recordRound(project, outcome, fixResult, roundAttempts, fixAttempts)
                CodeOperationReport.Round(outcome, fixResult, roundLogEntries(project) - attemptsBefore)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return CodeOperationReport.FailedToRun(failure.message ?: failure.javaClass.simpleName)
        }
        return borrowed ?: CodeOperationReport.Unavailable(UNAVAILABLE_REASON)
    }

    /** The Fix errors button: the console loop's manual pass over the preview's current errors. */
    suspend fun fixErrors(
        project: CodeProjectStore.CodeProject,
        consoleErrors: CodeConsoleErrors,
        reloadPreview: suspend () -> Unit,
        onProgress: (CodeRoundProgress) -> Unit = {},
    ): CodeOperationReport {
        val attemptsBefore = roundLogEntries(project)
        val borrowed = try {
            engineAccess.withEngine { engine, contextTokens ->
                val active = sessionFor(engine, project, consoleErrors, reloadPreview, contextTokens)
                val result = active.loop.fixNow(project, onProgress)
                val attempts = roundLogEntries(project) - attemptsBefore
                if (result.roundsRun > 0 || attempts > 0) stats.recordFixRounds(result.roundsRun, attempts)
                stats.recordOutcome(fixOutcomeLine(project, result), fixErrorLine(result))
                CodeOperationReport.Fix(result, attempts)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return CodeOperationReport.FailedToRun(failure.message ?: failure.javaClass.simpleName)
        }
        return borrowed ?: CodeOperationReport.Unavailable(UNAVAILABLE_REASON)
    }

    /**
     * Releases the live session's code conversation, when the
     * session belongs to [engine] (the engine a successful borrow
     * just handed back). Callers invoke this inside a borrow; when
     * the borrow fails the release is skipped, which the engine's
     * reset discipline makes safe.
     */
    suspend fun releaseSession(engine: CodeModeEngine) {
        val current = session ?: return
        session = null
        if (current.engine === engine) runCatching { current.controller.release() }
    }

    private suspend fun sessionFor(
        engine: CodeModeEngine,
        project: CodeProjectStore.CodeProject,
        consoleErrors: CodeConsoleErrors,
        reloadPreview: suspend () -> Unit,
        contextTokens: Int?,
    ): Session {
        val current = session
        if (current != null && current.engine === engine &&
            current.projectId == project.id && current.consoleErrors === consoleErrors
        ) {
            return current
        }
        if (current != null && current.engine === engine) {
            // Leaving the old binding on the same engine: end its code
            // conversation before the new session prepares its own.
            runCatching { current.controller.release() }
        }
        val controller = CodeSessionController(store, engine, contextTokens, nowMillis)
        val runner = CodeRoundRunner(store, controller, contextTokens, nowMillis)
        val loop = CodePreviewFixLoop(runner, consoleErrors, reloadPreview, loopSettle)
        return Session(engine, project.id, consoleErrors, controller, runner, loop).also { session = it }
    }

    /**
     * Generation attempts so far, counted from the round log: the
     * session controller appends exactly one entry per round it runs
     * (parsed, failed, or generation error), and the runner's every
     * attempt is one controller round — so an operation's entry-count
     * delta is its attempt count, with continuations counted inside
     * the attempt that made them.
     */
    private fun roundLogEntries(project: CodeProjectStore.CodeProject): Int =
        store.readRoundLog(project).size

    private fun recordRound(
        project: CodeProjectStore.CodeProject,
        outcome: CodeRoundOutcome,
        fixResult: CodeFixLoopResult?,
        roundAttempts: Int,
        fixAttempts: Int,
    ) {
        val fixRounds = fixResult?.roundsRun ?: 0
        if (fixRounds > 0 || fixAttempts > 0) stats.recordFixRounds(fixRounds, fixAttempts)
        stats.recordUserRound(roundAttempts, roundOutcomeLine(project, outcome, fixResult), roundErrorLine(outcome, fixResult))
    }

    private fun roundOutcomeLine(
        project: CodeProjectStore.CodeProject,
        outcome: CodeRoundOutcome,
        fixResult: CodeFixLoopResult?,
    ): String = when (outcome) {
        is CodeRoundOutcome.Applied ->
            "round applied on ${project.name}: ${outcome.writtenPaths.joinToString(", ")} (rollback point v${outcome.version})" + fixSuffix(fixResult)
        is CodeRoundOutcome.Replied -> "model replied on ${project.name}; no files written"
        is CodeRoundOutcome.Failed -> "round failed on ${project.name}"
        is CodeRoundOutcome.Refused -> "round refused on ${project.name}"
    }

    private fun roundErrorLine(outcome: CodeRoundOutcome, fixResult: CodeFixLoopResult?): String? = when (outcome) {
        is CodeRoundOutcome.Failed -> outcome.reason
        is CodeRoundOutcome.Refused -> outcome.reason
        else -> fixErrorLine(fixResult)
    }

    private fun fixOutcomeLine(project: CodeProjectStore.CodeProject, result: CodeFixLoopResult): String =
        "manual fix on ${project.name}:" + when (result) {
            is CodeFixLoopResult.Clean -> if (result.fixRounds == 0) " console already clean" else " console clean after ${result.fixRounds} fix round(s)"
            is CodeFixLoopResult.CapReached -> " ${result.remainingErrors.size} console error(s) remain after ${result.fixRounds} fix round(s)"
            is CodeFixLoopResult.Stopped -> " stopped after ${result.fixRounds} fix round(s)"
            is CodeFixLoopResult.NoProgress -> " console errors unchanged after ${result.fixRounds} fix round(s)"
        }

    private fun fixSuffix(fixResult: CodeFixLoopResult?): String = when (fixResult) {
        null -> ""
        is CodeFixLoopResult.Clean -> if (fixResult.fixRounds == 0) "; console clean" else "; console clean after ${fixResult.fixRounds} fix round(s)"
        is CodeFixLoopResult.CapReached -> "; ${fixResult.remainingErrors.size} console error(s) remain after ${fixResult.fixRounds} fix round(s)"
        is CodeFixLoopResult.Stopped -> "; fix pass stopped after ${fixResult.fixRounds} fix round(s)"
        is CodeFixLoopResult.NoProgress -> "; console errors unchanged after ${fixResult.fixRounds} fix round(s)"
    }

    private fun fixErrorLine(fixResult: CodeFixLoopResult?): String? = when (fixResult) {
        null, is CodeFixLoopResult.Clean -> null
        is CodeFixLoopResult.CapReached -> fixResult.remainingErrors.firstOrNull()?.message
        is CodeFixLoopResult.Stopped -> (fixResult.outcome as? CodeRoundOutcome.Failed)?.reason
            ?: (fixResult.outcome as? CodeRoundOutcome.Refused)?.reason
            ?: "a fix round did not land"
        is CodeFixLoopResult.NoProgress -> fixResult.errors.firstOrNull()?.message
    }

    private val CodeFixLoopResult.roundsRun: Int
        get() = when (this) {
            is CodeFixLoopResult.Clean -> fixRounds
            is CodeFixLoopResult.CapReached -> fixRounds
            is CodeFixLoopResult.Stopped -> fixRounds
            is CodeFixLoopResult.NoProgress -> fixRounds
        }

    companion object {
        const val UNAVAILABLE_REASON: String =
            "the model is busy with a chat reply, a voice call, or another round, or no downloaded model could be prepared"
    }
}
