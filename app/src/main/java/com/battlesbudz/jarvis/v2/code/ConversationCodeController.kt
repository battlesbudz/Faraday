package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Code mode in the conversation (Battles, 2026-11-09: the separate
 * code destination decided earlier the same day is reversed — he
 * picks a project from the list, then directs the work from the
 * conversation itself, by typed text or by voice through the
 * conversation's own input paths).
 *
 * This controller is the wiring between the conversation and the
 * unchanged code engine ([CodeModeDriver] over [CodeProjectStore]):
 * it owns which project is active, and a direction typed (or
 * dictated) into the conversation while a project is active becomes
 * a round — his words land in the thread as his message, the round
 * runs, and the outcome lands as the reply, exactly the shape a
 * chat turn takes. The driver's live-round state drives the build
 * view rendered in the conversation above the composer.
 *
 * Turns that are not plain text directions — attachments, sends
 * while a voice call is armed (those belong to the call), sends
 * with no project picked — never reach this controller; the app
 * frame routes them to the normal chat path.
 */
class ConversationCodeController(
    val store: CodeProjectStore,
    val statsStore: CodeStatsStore,
    val driver: CodeModeDriver,
    private val engineAccess: CodeEngineAccess,
    private val history: ConversationHistory,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Console entries for the active project, fed by the preview viewer while it is open. */
    val consoleStore = CodeConsoleStore()

    private val _activeProjectId = MutableStateFlow<String?>(null)

    /** The project directions currently land in, or null for plain conversation. */
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    private val _projectsVersion = MutableStateFlow(0)

    /** Bumped whenever the project list or a project's state changes, so surfaces re-read. */
    val projectsVersion: StateFlow<Int> = _projectsVersion.asStateFlow()

    /** The preview the viewer has mounted, if any; a landing round refreshes it. */
    @Volatile
    var preview: CodePreview? = null

    private val reloadPreview: suspend () -> Unit = {
        val id = _activeProjectId.value
        val mounted = preview
        if (id != null && mounted != null) {
            withContext(Dispatchers.Main) { mounted.refresh(store.projectDir(id)) }
        }
        Unit
    }

    /** The active project as currently stored, or null. */
    fun activeProject(): CodeProjectStore.CodeProject? =
        _activeProjectId.value?.let { store.loadProject(it) }

    /** Makes [id] the project directions land in. */
    fun pickProject(id: String) {
        _activeProjectId.value = id
    }

    /** Returns the conversation to plain chat; a round in flight keeps its session. */
    fun leaveProject() {
        val leaving = _activeProjectId.value
        _activeProjectId.value = null
        if (leaving != null && !driver.roundInFlightFor(leaving)) releaseSession()
    }

    /** Creates a project, makes it active, and returns it. The caller starts its first round. */
    fun createProject(name: String, firstRequest: String): CodeProjectStore.CodeProject {
        val project = store.createProject(name, firstRequest, nowMillis())
        _activeProjectId.value = project.id
        bumpProjectsVersion()
        return project
    }

    /** Renames a project in place; its folder and package name do not change. */
    fun renameProject(project: CodeProjectStore.CodeProject, newName: String) {
        store.saveProject(project.copy(name = newName))
        bumpProjectsVersion()
    }

    /** Deletes a project and everything under it; clears the active pick if it was this one. */
    fun deleteProject(project: CodeProjectStore.CodeProject) {
        if (_activeProjectId.value == project.id) _activeProjectId.value = null
        store.deleteProject(project.id)
        bumpProjectsVersion()
    }

    /**
     * One direction from the conversation. His words are appended to
     * the current thread as his message, a reply placeholder follows,
     * and the round's outcome replaces it when the round lands.
     * Returns a line for the composer when the direction cannot be
     * taken (no project, a round already running), or null once the
     * turn is in the conversation.
     */
    fun sendDirection(text: String): String? {
        val project = activeProject() ?: return "Pick a code project first."
        if (driver.roundInFlightFor(project.id)) {
            return "A round is already running for ${project.name}; it lands in this conversation when it finishes."
        }
        launchOperation(project, userText = text, placeholder = "Working on ${project.name}…") {
            driver.sendUserRound(project, text, consoleStore, reloadPreview)
        }
        return null
    }

    /** The manual console-fix pass for the active project, reported as a reply in the thread. */
    fun fixErrors() {
        val project = activeProject() ?: return
        if (driver.roundInFlightFor(project.id)) return
        launchOperation(project, userText = null, placeholder = "Fixing ${project.name}'s console errors…") {
            driver.fixErrors(project, consoleStore, reloadPreview)
        }
    }

    private fun launchOperation(
        project: CodeProjectStore.CodeProject,
        userText: String?,
        placeholder: String,
        block: suspend () -> CodeOperationReport,
    ) {
        val threadId = history.current.value.id
        val replyId = UUID.randomUUID().toString()
        if (userText != null) history.appendUser(userText)
        history.updateReply(threadId, replyId, placeholder, false)
        scope.launch {
            val report = withContext(Dispatchers.IO) { block() }
            history.updateReply(threadId, replyId, outcomeText(report), true)
            bumpProjectsVersion()
        }
    }

    /** A finished operation as the reply text Battles reads in the thread. */
    fun outcomeText(report: CodeOperationReport): String = when (report) {
        is CodeOperationReport.Round -> when (val outcome = report.outcome) {
            is CodeRoundOutcome.Applied ->
                "Done — wrote ${outcome.writtenPaths.joinToString(", ")}. " +
                    "The state before this round is saved as version ${outcome.version}." +
                    fixSummary(report.fixResult)
            is CodeRoundOutcome.Replied -> outcome.text
            is CodeRoundOutcome.Failed ->
                "The round didn't land: ${outcome.reason} Nothing was written; the last working version is untouched."
            is CodeRoundOutcome.Refused -> "That round was refused: ${outcome.reason}"
        }
        is CodeOperationReport.Fix -> "Fix pass:" + fixSuffixText(report.result)
        is CodeOperationReport.Unavailable -> "Nothing ran: ${report.reason}"
        is CodeOperationReport.FailedToRun -> "The round could not run: ${report.reason}"
    }

    private fun releaseSession() {
        scope.launch {
            withContext(Dispatchers.IO) {
                engineAccess.withEngine { engine, _ -> driver.releaseSession(engine) }
            }
        }
    }

    private fun bumpProjectsVersion() {
        _projectsVersion.value += 1
    }
}

/** The automatic fix pass's verdict, as one sentence of a round's reply. */
internal fun fixSummary(fixResult: CodeFixLoopResult?): String = when (fixResult) {
    null -> ""
    is CodeFixLoopResult.Clean ->
        if (fixResult.fixRounds == 0) " The preview reports no console errors."
        else " The preview's console errors were fixed in ${fixResult.fixRounds} fix round(s); the console is clean now."
    is CodeFixLoopResult.CapReached ->
        " ${fixResult.remainingErrors.size} console error(s) remain after ${fixResult.fixRounds} automatic fix round(s). Open the preview's Console tab, or use Fix errors there."
    is CodeFixLoopResult.Stopped -> " The fix pass stopped: a fix round did not land."
    is CodeFixLoopResult.NoProgress -> " The fix pass stopped: the same errors came back after a fix round."
}

internal fun fixSuffixText(result: CodeFixLoopResult): String = when (result) {
    is CodeFixLoopResult.Clean ->
        if (result.fixRounds == 0) " the console is already clean."
        else " the console is clean after ${result.fixRounds} fix round(s)."
    is CodeFixLoopResult.CapReached ->
        " ${result.remainingErrors.size} console error(s) still remain after ${result.fixRounds} fix round(s)."
    is CodeFixLoopResult.Stopped -> " a fix round did not land, so the pass stopped."
    is CodeFixLoopResult.NoProgress -> " the same errors came back after a fix round, so the pass stopped."
}
