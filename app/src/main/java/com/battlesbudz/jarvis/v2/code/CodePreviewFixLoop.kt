package com.battlesbudz.jarvis.v2.code

import kotlinx.coroutines.delay

/** The outcome of one pass through the console-error loop ([CodePreviewFixLoop]). */
sealed interface CodeFixLoopResult {

    /** A settle-and-check found no errors. [fixRounds] counts the fix rounds this pass ran first. */
    data class Clean(val fixRounds: Int) : CodeFixLoopResult

    /**
     * Errors remain but the automatic budget is spent (section 4.5's
     * 2-round cap since Battles's last own message). Only the
     * automatic path returns this. [remainingErrors] are the errors
     * the last check read — the ones a manual Fix errors, or Battles's
     * next message resetting the budget, would take on.
     */
    data class CapReached(
        val fixRounds: Int,
        val remainingErrors: List<CodeConsoleMessage>
    ) : CodeFixLoopResult

    /**
     * A fix round ended without applying (failed, refused, or a plain
     * reply): the loop stops rather than feeding the same errors to a
     * model that just declined or failed to fix them. [outcome] is the
     * round's own outcome, for the screen to show.
     */
    data class Stopped(val outcome: CodeRoundOutcome, val fixRounds: Int) : CodeFixLoopResult

    /**
     * Manual path only: an applied fix round left the error set
     * byte-for-byte the same as the check before it. The manual flow
     * is uncapped, so this no-progress stop is what keeps a model that
     * keeps "fixing" without fixing from spinning forever on the
     * phone. [errors] are the unchanged errors.
     */
    data class NoProgress(
        val fixRounds: Int,
        val errors: List<CodeConsoleMessage>
    ) : CodeFixLoopResult
}

/**
 * Composes the fix-round prompt from captured console errors
 * (section 4.5: the prompt is the error text itself, up to
 * [MAX_ERRORS] distinct errors, clipped). Distinct is by text, source,
 * and line — the same throw reported twice is one error. Each error's
 * text is flattened to one line and clipped at [MAX_ERROR_CHARS]; the
 * source is shown as the file it names with its line, not the whole
 * appassets URL.
 */
object CodeConsolePrompt {

    /** Distinct errors one fix prompt carries (section 4.5). */
    const val MAX_ERRORS: Int = 4

    /** One error's text is clipped to this in the prompt. */
    const val MAX_ERROR_CHARS: Int = 300

    fun compose(errors: List<CodeConsoleMessage>): String {
        val distinct = errors
            .distinctBy { Triple(it.message, it.sourceId, it.lineNumber) }
            .take(MAX_ERRORS)
        return buildString {
            append(
                "The preview of the app reported console errors after the last " +
                    "change. Fix what is throwing, and return the corrected files " +
                    "with write_file or edit_file. The errors were:"
            )
            distinct.forEachIndexed { index, error ->
                append("\n").append(index + 1).append(". ")
                append(error.message.replace(Regex("\\s+"), " ").trim().take(MAX_ERROR_CHARS))
                val source = error.sourceId
                if (!source.isNullOrBlank()) {
                    append("  (")
                    append(source.substringAfterLast('/').ifBlank { source })
                    append(":").append(error.lineNumber).append(")")
                }
            }
        }
    }
}

/**
 * The console-error loop (code-and-export scope section 4.5), as a
 * controller Unit 6's Code screen drives. After an accepted round the
 * screen reloads the preview and calls [afterRound]; the loop waits
 * for the page to settle ([SETTLE_MILLIS], Mochu's number), reads the
 * errors captured since the last check, and — when there are errors —
 * runs a fix round whose prompt is [CodeConsolePrompt]'s composition
 * of them. A fix round is a full round through [CodeRoundRunner]:
 * snapshot, contract, gate, version, write. After a fix round lands,
 * [reloadPreview] runs (the screen reloads the preview there) and the
 * loop settles and checks again.
 *
 * **The cap.** Automatic passes run at most [MAX_AUTO_FIX_ROUNDS] fix
 * rounds since Battles's last own message; the screen calls
 * [onUserMessage] when he sends one, which resets the budget. The
 * manual [fixNow] (the Console pane's Fix errors button, Unit 6) runs
 * the same flow without the cap and does not spend the automatic
 * budget; because uncapped cannot mean unbounded, it stops when a
 * round changes nothing observable — the error set comes back
 * identical — as well as on the shared stops below.
 *
 * **Stops, both paths.** A check that finds no errors ends
 * [CodeFixLoopResult.Clean]. A fix round that does not apply (the
 * model failed the gate four times, the budget refused the round, or
 * it answered with a plain reply) ends
 * [CodeFixLoopResult.Stopped] with that outcome: the errors were just
 * put to the model and it could not or would not fix them, so looping
 * would only repeat the attempt.
 *
 * The settle wait and the preview reload are injected seams: in the
 * app they are a real delay and the screen's reload; in tests they
 * are instant and scripted, so the policy here — bounds, filtering,
 * cap, reset, stops — is JVM-proven while the rendering is Unit 6's
 * device proof.
 */
class CodePreviewFixLoop(
    private val runner: CodeRoundRunner,
    private val consoleErrors: CodeConsoleErrors,
    private val reloadPreview: suspend () -> Unit = {},
    private val settle: suspend () -> Unit = { delay(SETTLE_MILLIS) }
) {

    /** Automatic fix rounds run since Battles's last own message. */
    var autoFixRoundsUsed: Int = 0
        private set

    /** Battles sent his own message: the automatic budget resets (section 4.5). */
    fun onUserMessage() {
        autoFixRoundsUsed = 0
    }

    /** The automatic pass after an accepted round, under the 2-round cap. */
    suspend fun afterRound(
        project: CodeProjectStore.CodeProject,
        onProgress: (CodeRoundProgress) -> Unit = {}
    ): CodeFixLoopResult = runLoop(project, automatic = true, onProgress = onProgress)

    /** The manual Fix errors flow: the same cycle as [afterRound], without the cap. */
    suspend fun fixNow(
        project: CodeProjectStore.CodeProject,
        onProgress: (CodeRoundProgress) -> Unit = {}
    ): CodeFixLoopResult = runLoop(project, automatic = false, onProgress = onProgress)

    private suspend fun runLoop(
        project: CodeProjectStore.CodeProject,
        automatic: Boolean,
        onProgress: (CodeRoundProgress) -> Unit
    ): CodeFixLoopResult {
        var fixRounds = 0
        var previousErrorKey: Set<String>? = null
        while (true) {
            settle()
            val errors = consoleErrors.takeNewErrors()
            if (errors.isEmpty()) return CodeFixLoopResult.Clean(fixRounds)
            if (automatic && autoFixRoundsUsed >= MAX_AUTO_FIX_ROUNDS) {
                return CodeFixLoopResult.CapReached(fixRounds, errors)
            }
            if (!automatic) {
                val key = errors.map { errorKey(it) }.toSet()
                if (key == previousErrorKey) {
                    return CodeFixLoopResult.NoProgress(fixRounds, errors)
                }
                previousErrorKey = key
            }
            val outcome = runner.runRound(project, CodeConsolePrompt.compose(errors), onProgress)
            fixRounds++
            if (automatic) autoFixRoundsUsed++
            if (outcome !is CodeRoundOutcome.Applied) {
                return CodeFixLoopResult.Stopped(outcome, fixRounds)
            }
            reloadPreview()
        }
    }

    private fun errorKey(error: CodeConsoleMessage): String =
        error.message + SEP + (error.sourceId ?: "") + SEP + error.lineNumber

    companion object {
        /** Automatic fix rounds per Battles message (section 4.5). */
        const val MAX_AUTO_FIX_ROUNDS: Int = 2

        /** The post-round settle wait before the console is read (Mochu's 4 seconds). */
        const val SETTLE_MILLIS: Long = 4_000L

        /** Field separator for [errorKey]: a control char console text never carries. */
        private const val SEP: String = "\u0001"
    }
}
