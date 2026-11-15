package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.ai.CodeModeEngine
import kotlinx.coroutines.CancellationException

/**
 * Progress of a round in flight, for the code screen's live build
 * view: the file being written right now (null between writes), the
 * partial body of that file streamed so far, the response's total
 * streamed characters (the unambiguous working signal — it grows
 * while generation is alive), and, once [CodeRoundRunner] tags it,
 * the attempt in flight out of the round's maximum.
 */
data class CodeRoundProgress(
    val writingFilePath: String?,
    val writingContent: String? = null,
    val streamedChars: Int = 0,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
)

/** The outcome of one code round, as [CodeSessionController.runRound] returns it. */
sealed interface CodeRoundResult {

    /**
     * The round produced a parsed action set for Unit 3's gate and apply
     * step. Nothing has been written to the project: [actions] is the
     * whole deliverable of this unit's round.
     */
    data class Parsed(
        val actions: List<CodeAction>,
        val source: CodeParseSource,
        val rawResponse: String,
        val continuationsUsed: Int,
        val reusedSession: Boolean
    ) : CodeRoundResult

    /**
     * The round produced nothing Unit 3 can apply: the response held no
     * usable actions, or generation itself failed. [reason] is one line,
     * fit for the retry feedback of Unit 3 and for the round log.
     * [rawResponse] is what the model produced (partial, when generation
     * failed mid-stream).
     */
    data class Failed(
        val reason: String,
        val rawResponse: String,
        val continuationsUsed: Int,
        val reusedSession: Boolean
    ) : CodeRoundResult

    /**
     * The round was refused before anything was sent: the code prompt
     * plus even a minimum snapshot cannot fit this model's planning
     * limit (section 3.2's floor rule). Honest instead of silent.
     */
    data class Refused(val reason: String) : CodeRoundResult
}

/**
 * Drives code rounds on the one shared engine conversation
 * (code-and-export scope sections 3.5 and 4.2): the fingerprint reuse
 * rule, the generation loop with truncation continuation, the strict
 * parser and recovery chain, and the round log. It applies nothing —
 * the parsed action set goes to Unit 3's gate and apply step, and the
 * round's single round-log entry is appended here, at the layer that
 * knows how the round ended; Unit 3's apply outcomes ride the same
 * entry rather than adding a second one.
 *
 * The reuse rule, stated once against the real pieces: the controller
 * remembers the open project, the engine's conversation generation, and
 * a **session fingerprint** — the fingerprint of the disk state the
 * live conversation is known to reflect. A round reuses the live
 * conversation, sending only the short turn prompt, when the project is
 * the same, the fingerprint on disk still equals the session
 * fingerprint, the generation is unchanged (no chat turn, voice turn,
 * tool-policy change, model switch, or reset happened in between), the
 * engine is still in code mode, and the estimated remaining room covers
 * the short prompt plus the reply reserve. Otherwise the conversation
 * is re-prepared and a fresh full snapshot is sent.
 *
 * Two bookkeeping details make the fingerprint honest:
 *
 * - The round log is project content to the fingerprint (Unit 1 keys it
 *   that way), and this controller appends one line per round — a line
 *   the conversation has, by definition, already lived. So after a
 *   round that wrote nothing (a reply, a failure), the session
 *   fingerprint is refreshed to the post-append fingerprint rather than
 *   left pointing at a state the app's own bookkeeping moved past.
 * - A round whose actions include any write verb invalidates the
 *   session fingerprint outright: section 3.5's rule is that any write
 *   round changes the fingerprint by definition, so the turn after a
 *   change always re-snapshots — the model next sees the applied result
 *   as ground truth, never its own unverified output.
 *
 * Leaving code mode is [release]: the engine conversation is released
 * and chat rebuilds from confirmed history. Code rounds are not chat
 * history and never enter it.
 */
class CodeSessionController(
    private val store: CodeProjectStore,
    private val engine: CodeModeEngine,
    private val contextTokens: Int?,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    private var openProjectId: String? = null
    private var sessionFingerprint: String? = null
    private var recordedGeneration: Long = -1L
    private var sessionChars: Int = 0

    /**
     * Runs one round: [request] is Battles's words for the open
     * [project]. [onProgress] reports the file being written as the
     * response streams, for the code screen's round status (Unit 6).
     */
    suspend fun runRound(
        project: CodeProjectStore.CodeProject,
        request: String,
        onProgress: (CodeRoundProgress) -> Unit = {}
    ): CodeRoundResult {
        val budget = CodeBudget.compute(contextTokens, CodeSystemPrompt.TEXT.length)
            ?: return CodeRoundResult.Refused(
                "Code mode cannot fit its system prompt plus a minimum project snapshot " +
                    "inside this model's context limit, so the round was refused instead " +
                    "of sending a snapshot that would not fit."
            )
        val dir = store.projectDir(project.id)
        val currentFingerprint = ProjectFingerprint.compute(dir)
        val shortPrompt = shortTurnPrompt(request)
        val reused = openProjectId == project.id &&
            sessionFingerprint != null &&
            currentFingerprint == sessionFingerprint &&
            recordedGeneration == engine.conversationGeneration &&
            engine.codeModeActive &&
            sessionChars + shortPrompt.length + budget.replyReserveChars <= budget.limitChars

        val prompt: String
        if (reused) {
            prompt = shortPrompt
        } else {
            engine.prepareCodeConversation()
            val snapshot = ProjectSnapshotBuilder.build(store, project, budget)
            prompt = snapshotPrompt(snapshot, request)
            sessionChars = CodeSystemPrompt.TEXT.length
            recordedGeneration = engine.conversationGeneration
            openProjectId = project.id
        }

        val live = StringBuilder()
        var continuations = 0
        val reportProgress = {
            val write = CodeProgressExtractor.currentWrite(live.toString())
            onProgress(
                CodeRoundProgress(
                    writingFilePath = write?.path,
                    writingContent = write?.contentSoFar,
                    streamedChars = live.length,
                )
            )
        }
        val raw: String = try {
            var text = engine.generate(prompt) { token ->
                live.append(token)
                reportProgress()
            }.text
            sessionChars += prompt.length + text.length
            while (continuations < CodeContinuation.MAX_CONTINUATIONS &&
                CodeContinuation.looksCutOff(text)
            ) {
                val continuationPrompt = CodeContinuation.continuationPrompt(text)
                val next = engine.generate(continuationPrompt) { token ->
                    live.append(token)
                    reportProgress()
                }.text
                sessionChars += continuationPrompt.length + next.length
                text = CodeContinuation.appendContinuation(text, next)
                continuations++
            }
            text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = "Generation failed: ${error.message ?: error.javaClass.simpleName}"
            sessionFingerprint = null
            store.appendRoundLog(project, request, "failed: generation error", nowMillis())
            recordedGeneration = engine.conversationGeneration
            return CodeRoundResult.Failed(reason, live.toString(), continuations, reused)
        }
        recordedGeneration = engine.conversationGeneration

        return when (val parsed = CodeActionParser.parse(raw)) {
            is CodeParseResult.Actions -> {
                val hasWrites = parsed.actions.any { it !is CodeAction.Reply }
                store.appendRoundLog(
                    project, request,
                    "parsed: " + parsed.actions.joinToString(", ") { describe(it) },
                    nowMillis()
                )
                sessionFingerprint =
                    if (hasWrites) null else ProjectFingerprint.compute(dir)
                CodeRoundResult.Parsed(parsed.actions, parsed.source, raw, continuations, reused)
            }
            is CodeParseResult.Failed -> {
                store.appendRoundLog(project, request, "failed: ${parsed.reason}", nowMillis())
                sessionFingerprint = ProjectFingerprint.compute(dir)
                CodeRoundResult.Failed(parsed.reason, raw, continuations, reused)
            }
        }
    }

    /** Leaves code mode: releases the engine conversation and forgets the session. */
    suspend fun release() {
        engine.releaseCodeConversation()
        openProjectId = null
        sessionFingerprint = null
        recordedGeneration = -1L
        sessionChars = 0
    }

    private fun shortTurnPrompt(request: String): String =
        request + "\n\n" + CodeSystemPrompt.TURN_REMINDER

    private fun snapshotPrompt(snapshot: String, request: String): String =
        snapshot + "\n## The user's request\n\n" + request + "\n\n" +
            CodeSystemPrompt.TURN_REMINDER + "\n"

    private fun describe(action: CodeAction): String = when (action) {
        is CodeAction.WriteFile -> "write ${action.path}"
        is CodeAction.EditFile -> "edit ${action.path}"
        is CodeAction.UpdateBrief -> "update brief"
        is CodeAction.Reply -> "reply"
    }
}
