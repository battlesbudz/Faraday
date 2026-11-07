package com.battlesbudz.jarvis.v2.code

import java.io.File

/** The outcome of one full code round, as [CodeRoundRunner.runRound] returns it. */
sealed interface CodeRoundOutcome {

    /**
     * The round's action set passed the gate and was committed:
     * [writtenPaths] are the project files written (project-relative,
     * sorted), [briefUpdated] whether an `update_brief` landed (its
     * Files section was then regenerated, as it is after any file
     * change), and [version] is the version snapshot taken of the
     * pre-round state before anything wrote — the rollback point for
     * this round. [attempts] counts the generations the round took.
     */
    data class Applied(
        val writtenPaths: List<String>,
        val briefUpdated: Boolean,
        val version: Int,
        val attempts: Int,
        val source: CodeParseSource
    ) : CodeRoundOutcome

    /** The round was a plain reply: nothing was written, no version taken. */
    data class Replied(val text: String, val attempts: Int) : CodeRoundOutcome

    /**
     * Every attempt failed. [reason] is the last attempt's specific
     * feedback, fit to show Battles. The project on disk is untouched
     * (see the runner's doc for the one bookkeeping exception).
     */
    data class Failed(val reason: String, val attempts: Int) : CodeRoundOutcome

    /** The round was refused before anything was sent (the budget floor rule). */
    data class Refused(val reason: String) : CodeRoundOutcome
}

/**
 * Runs a whole code round to a committed end (code-and-export scope
 * sections 4.3 and 4.4): generation attempts through
 * [CodeSessionController], the normalizers, the in-memory edit
 * applier, the completeness gate, and — only when all of that passes —
 * the atomic apply wired to Unit 1's store. This is the layer Unit 6's
 * code screen drives; Decision A stands here in code: there is no
 * approval step anywhere in a round.
 *
 * **The retry loop.** Up to [MAX_ATTEMPTS] attempts per round. A
 * failed attempt retries with specific feedback as the next attempt's
 * prompt — what was incomplete, which find text was not found (with
 * Mochu's instruction to return a full write instead), why a brief was
 * refused — and each retry runs on a reset conversation with a fresh
 * snapshot, per section 4.4: the runner releases the controller's
 * session before every retry, so the next attempt re-prepares and
 * re-snapshots from the untouched disk state. After the last attempt
 * the round ends [CodeRoundOutcome.Failed].
 *
 * **All-or-nothing, by construction.** An attempt's actions are
 * normalized and applied to an in-memory copy of the project, then
 * gated. The commit — version snapshot, then file writes, then the
 * brief, then the round counter — is the only code path in the round
 * that touches the project, and it runs only after the full action
 * set has passed. A failed attempt therefore writes nothing, takes no
 * version, and bumps no counter, so after a fully failed round every
 * project file is byte-identical to before the round. The single
 * exception is the round log, which gains one entry per attempt from
 * the controller: the log is the scope's designated carrier of round
 * outcomes — section 3.2 names "failed" as one of its outcomes — so
 * its growth is the record of the failure, not a change to the
 * project the gate protects. Per Unit 2's stated design the apply
 * outcomes ride the controller's entry; this runner appends no
 * round-log entries of its own.
 *
 * **Machine-owned paths.** Model writes to `BRIEF.md`, `project.json`,
 * `rounds.log`, or anything under `versions/` fail the attempt with
 * feedback naming the right channel (`update_brief` for the brief;
 * the rest are the app's bookkeeping and have no model channel).
 * Section 3.3 makes `update_brief` the brief's one door, and a direct
 * write would bypass its validation and the Files regeneration.
 *
 * The commit's per-file writes are temp-then-rename like the store's
 * own, and the version snapshot precedes them, so even a process death
 * mid-commit leaves the pre-round state one rollback away. The
 * all-or-nothing guarantee above is stated for action sets and gate
 * outcomes, which is what the scope's test pins.
 */
class CodeRoundRunner(
    private val store: CodeProjectStore,
    private val controller: CodeSessionController,
    private val contextTokens: Int?,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    /** One attempt's refusal: [feedback] is the exact retry prompt for it. */
    private class AttemptFailure(val feedback: String) : Exception(feedback)

    private fun fail(feedback: String): Nothing = throw AttemptFailure(feedback)

    /**
     * Runs one round for [project] from Battles's [request]. Generation
     * progress (the file being written) is reported through
     * [onProgress] on every attempt.
     */
    suspend fun runRound(
        project: CodeProjectStore.CodeProject,
        request: String,
        onProgress: (CodeRoundProgress) -> Unit = {}
    ): CodeRoundOutcome {
        var attempts = 0
        var prompt = request
        while (true) {
            attempts++
            when (val result = controller.runRound(project, prompt, onProgress)) {
                is CodeRoundResult.Refused -> return CodeRoundOutcome.Refused(result.reason)
                is CodeRoundResult.Failed -> {
                    if (attempts >= MAX_ATTEMPTS) {
                        return CodeRoundOutcome.Failed(result.reason, attempts)
                    }
                    prompt = parseFeedback(result.reason)
                }
                is CodeRoundResult.Parsed -> {
                    val replies = result.actions.filterIsInstance<CodeAction.Reply>()
                    if (result.actions.isNotEmpty() && replies.size == result.actions.size) {
                        return CodeRoundOutcome.Replied(
                            replies.joinToString("\n\n") { it.text }, attempts
                        )
                    }
                    try {
                        return applyAttempt(project, request, result, attempts)
                    } catch (failure: AttemptFailure) {
                        if (attempts >= MAX_ATTEMPTS) {
                            return CodeRoundOutcome.Failed(failure.feedback, attempts)
                        }
                        prompt = failure.feedback
                    }
                }
            }
            // A retry runs on a reset conversation with a fresh
            // snapshot (section 4.4): releasing makes the controller
            // re-prepare and re-snapshot on the next attempt.
            controller.release()
        }
    }

    // -- One attempt: normalize, apply in memory, gate, commit --------------

    private fun applyAttempt(
        project: CodeProjectStore.CodeProject,
        originalRequest: String,
        parsed: CodeRoundResult.Parsed,
        attempts: Int
    ): CodeRoundOutcome.Applied {
        val dir = store.projectDir(project.id)
        val actions = parsed.actions.map { normalizeAction(dir, it) }
        val work = RoundWork(dir)

        // Sequential in-memory application, in the round's own order.
        for (action in actions) {
            when (action) {
                is CodeAction.WriteFile -> {
                    work.contents[action.path] = action.content
                    work.written += action.path
                    work.dirty += action.path
                }
                is CodeAction.EditFile -> {
                    val base = work.contentOf(action.path)
                        ?: fail(
                            "Your edit_file targets ${action.path}, which does not exist " +
                                "in the project. Return write_file with the complete " +
                                "${action.path} instead."
                        )
                    when (val applied = CodeEditApplier.apply(base, action.edits)) {
                        is CodeEditApplier.Result.Applied -> {
                            work.contents[action.path] = applied.content
                            work.dirty += action.path
                        }
                        is CodeEditApplier.Result.Unmatched -> fail(
                            unmatchedFindFeedback(action.path, applied.find)
                        )
                    }
                }
                is CodeAction.UpdateBrief -> {
                    work.pendingBrief = validateBrief(action.content)
                }
                is CodeAction.Reply -> {
                    // The parser drops a riding reply when writes are
                    // present; a reply reaching here beside writes is
                    // ignored — the actions speak for themselves.
                }
            }
        }

        unmixCssWrites(work)
        consolidateSplit(work, originalRequest)

        // The completeness gate, over everything this round writes or
        // the repair layer rewrote. Edited-only files are exempt.
        val gatePaths = (work.written + work.repaired).sorted()
        for (path in gatePaths) {
            val content = work.contents[path] ?: continue
            val problems = CodeCompletenessGate.checkWritten(path, content)
            if (problems.isNotEmpty()) fail(gateFeedback(path, problems))
        }

        if (work.dirty.isEmpty() && work.pendingBrief == null) {
            // Unreachable by construction (every write verb dirties a
            // path and a brief update sets the pending brief); stated
            // honestly rather than committed as a phantom round.
            fail("The round produced actions but nothing to apply.")
        }

        // Commit: the only disk writes of the round, in the scope's
        // order — version snapshot, then writes, then the brief. The
        // write targets are all resolved before the snapshot is
        // taken, so nothing in the commit can fail an attempt after
        // the snapshot exists (the paths were already validated in
        // checkedPath; this is the same check, done while failing is
        // still free).
        val targets = work.dirty.sorted().map { path ->
            path to (store.resolvePath(dir, path)
                ?: fail("The path \"$path\" could not be resolved inside the project."))
        }
        val version = store.snapshotVersion(project)
        for ((path, target) in targets) {
            target.parentFile?.mkdirs()
            writeAtomically(target, work.contents.getValue(path))
        }
        if (work.pendingBrief != null) {
            store.writeBrief(project, work.pendingBrief!!)
            store.regenerateBriefFilesSection(project)
        } else if (work.dirty.isNotEmpty()) {
            // The brief's Files section is machine-owned: refresh it
            // from the folder the writes just changed.
            store.regenerateBriefFilesSection(project)
        }
        val fresh = store.loadProject(project.id) ?: project
        store.saveProject(
            fresh.copy(rounds = fresh.rounds + 1, updatedAtMillis = nowMillis())
        )
        return CodeRoundOutcome.Applied(
            writtenPaths = work.dirty.sorted(),
            briefUpdated = work.pendingBrief != null,
            version = version,
            attempts = attempts,
            source = parsed.source
        )
    }

    /** The mutable in-memory state of one attempt. */
    private inner class RoundWork(private val dir: File) {
        val contents: MutableMap<String, String> = mutableMapOf()
        val written: MutableSet<String> = mutableSetOf()
        val repaired: MutableSet<String> = mutableSetOf()
        val dirty: MutableSet<String> = mutableSetOf()
        var pendingBrief: ProjectBrief? = null

        /** A path's current content: the round's version if set, else disk, else null. */
        fun contentOf(path: String): String? {
            contents[path]?.let { return it }
            val file = File(dir, path)
            if (!file.isFile) return null
            val text = try {
                file.readText()
            } catch (e: Exception) {
                return null
            }
            contents[path] = text
            return text
        }
    }

    // -- Normalization --------------------------------------------------------

    /**
     * Cleans one parsed action: its path (cleaned, machine-owned and
     * escape-checked) and its body (whole-body wrappers stripped).
     * Fails the attempt when the path must be refused.
     */
    private fun normalizeAction(dir: File, action: CodeAction): CodeAction = when (action) {
        is CodeAction.WriteFile -> {
            val path = checkedPath(dir, action.path)
            CodeAction.WriteFile(path, CodeNormalizer.cleanBody(action.content))
        }
        is CodeAction.EditFile -> {
            val path = checkedPath(dir, action.path)
            CodeAction.EditFile(path, action.edits)
        }
        is CodeAction.UpdateBrief ->
            CodeAction.UpdateBrief(CodeNormalizer.cleanBody(action.content))
        is CodeAction.Reply -> action
    }

    private fun checkedPath(dir: File, rawPath: String): String {
        val path = CodeNormalizer.cleanPath(rawPath)
        if (path.isEmpty()) {
            fail("A file action named no usable path (\"$rawPath\"). Name a file inside the project.")
        }
        when {
            path == CodeProjectStore.BRIEF_FILE_NAME -> fail(
                "The path \"BRIEF.md\" is managed by the app: use update_brief to " +
                    "change the brief, not write_file."
            )
            path == CodeProjectStore.PROJECT_JSON_FILE_NAME -> fail(
                "The path \"project.json\" is the app's own project state and cannot be written."
            )
            path == CodeProjectStore.ROUND_LOG_FILE_NAME -> fail(
                "The path \"rounds.log\" is the app's own round log and cannot be written."
            )
            path.substringBefore('/') == CodeProjectStore.VERSIONS_DIR_NAME -> fail(
                "Paths under \"versions/\" are the app's rollback snapshots and cannot be written."
            )
        }
        if (store.resolvePath(dir, path) == null) {
            fail(
                "The path \"$rawPath\" is not allowed: file paths must stay inside " +
                    "the project folder (no absolute paths, no \"..\")."
            )
        }
        return path
    }

    /**
     * The CSS/JS un-mix over the round's writes: JavaScript inside a
     * written css file moves to `app.js` (created, or appended to the
     * round's own write of it), and `index.html` — the round's version
     * when it has one, else the one on disk — gains the script
     * reference if it does not already carry it, joining the round's
     * writes when the injection changes it.
     */
    private fun unmixCssWrites(work: RoundWork) {
        val cssPaths = work.written.filter { it.lowercase().endsWith(".css") }
        var extractedAny = false
        for (path in cssPaths) {
            val content = work.contents[path] ?: continue
            val split = CodeNormalizer.splitCssJs(content)
            if (split.js.isBlank()) continue
            extractedAny = true
            work.contents[path] = split.css
            val script = CodeNormalizer.SCRIPT_FILE_NAME
            val existing = work.contentOf(script)
            work.contents[script] = when {
                existing == null -> split.js
                existing.isBlank() -> split.js
                else -> existing.trimEnd('\n') + "\n" + split.js
            }
            work.written += script
            work.dirty += script
        }
        if (!extractedAny) return
        val index = CodeProjectStore.INDEX_FILE_NAME
        val html = work.contentOf(index) ?: return
        val injected = CodeNormalizer.injectScriptReference(html, CodeNormalizer.SCRIPT_FILE_NAME)
        if (injected != html) {
            work.contents[index] = injected
            work.repaired += index
            work.dirty += index
        }
    }

    /**
     * Single-file consolidation: when Battles did not ask for a split,
     * css/js files this round wrote that `index.html` references are
     * inlined into it and their separate writes are dropped.
     */
    private fun consolidateSplit(work: RoundWork, originalRequest: String) {
        val index = CodeProjectStore.INDEX_FILE_NAME
        if (index !in work.written) return
        if (CodeNormalizer.splitWasRequested(originalRequest)) return
        val cssByPath = work.written
            .filter { it.lowercase().endsWith(".css") }
            .associateWith { work.contents.getValue(it) }
        val jsByPath = work.written
            .filter { it.lowercase().endsWith(".js") }
            .associateWith { work.contents.getValue(it) }
        if (cssByPath.isEmpty() && jsByPath.isEmpty()) return
        val (html, inlined) = CodeNormalizer.consolidateSplit(
            work.contents.getValue(index), cssByPath, jsByPath
        )
        if (inlined.isEmpty()) return
        work.contents[index] = html
        work.repaired += index
        for (path in inlined) {
            work.contents.remove(path)
            work.written.remove(path)
            work.dirty.remove(path)
        }
    }

    // -- The brief --------------------------------------------------------------

    /** Parses and validates an `update_brief` body, or fails the attempt with the reason. */
    private fun validateBrief(content: String): ProjectBrief {
        val brief = ProjectBrief.parse(content)
        if (brief == null) {
            val missing = missingBriefSections(content)
            fail(
                "Your update_brief is missing sections: ${missing.joinToString(", ")}. " +
                    "The brief must keep all five sections — Goal, Files, Decisions, " +
                    "Known problems, Next steps. Return the complete brief."
            )
        }
        val budget = CodeBudget.compute(contextTokens, CodeSystemPrompt.TEXT.length)
        if (budget != null) {
            val problems = brief.validate(budget)
            if (problems.isNotEmpty()) {
                fail(
                    "Your update_brief was not applied: ${problems.joinToString(" ")} " +
                        "Return a shorter complete brief."
                )
            }
        }
        return brief
    }

    private fun missingBriefSections(text: String): List<String> {
        val present = mutableSetOf<String>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val title = line.trim().removePrefix("## ").trim()
            if (line.trim().startsWith("## ")) {
                ProjectBrief.SECTION_TITLES.firstOrNull { it.equals(title, ignoreCase = true) }
                    ?.let { present += it }
            }
        }
        return ProjectBrief.SECTION_TITLES.filter { it !in present }
    }

    // -- Feedback strings (section 4.4: specific, or the retry is blind) -------

    private fun parseFeedback(reason: String): String =
        "Your previous response could not be applied: $reason " +
            "Respond with complete XML actions only — write_file, edit_file, " +
            "update_brief, or reply — and close every tag you open."

    private fun unmatchedFindFeedback(path: String, find: String): String {
        val snippet = find.replace(Regex("\\s+"), " ").trim().take(MAX_SNIPPET_CHARS)
        return "Your edit_file <find> text was not found in $path: \"$snippet\". " +
            "Return write_file with the complete updated $path instead."
    }

    private fun gateFeedback(path: String, problems: List<String>): String {
        val isHtml = path.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }
        val closing = if (isHtml) ", closing every tag you open" else ""
        return "Your previous response was incomplete: $path ${problems.joinToString("; ")}. " +
            "Return the complete $path with write_file$closing."
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Same-directory rename is atomic on Android's filesystems;
            // this fallback mirrors the store's and only fires on
            // exotic storage.
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        /** Attempts per round, retries included (section 4.4). */
        const val MAX_ATTEMPTS: Int = 4

        /** The missing-find snippet is clipped to this in retry feedback. */
        private const val MAX_SNIPPET_CHARS: Int = 120
    }
}
