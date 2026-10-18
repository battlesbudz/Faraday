package com.battlesbudz.jarvis.v2.actions

/**
 * M4 approval-UI wiring for browse_submit (D11).
 *
 * Approving a parked browse_submit in the task panel admits the
 * [BrowserSession] submission for the exact page the user reviewed, via
 * [BrowserSession.proposeSubmit]/[BrowserSession.admitSubmit]. Exact-approval
 * semantics, mirroring [ScreenApprovalAdmission]:
 *
 * - The approval must be unconsumed, name this task's exact action and
 *   revision, and belong to this task/group. A changed token invalidates the
 *   prior approval: the ledger's [ToolTaskLedger.revise] consumes the old
 *   choice as STALE and clears the attempt's approval link, so a stale panel
 *   approval can never admit the session.
 *
 * The admission is one-shot: [BrowserSession.confirmSubmit] consumes it
 * whether the submit proceeds or not, so a second dispatch needs a fresh
 * approval. The executor re-validates against the live DOM at dispatch
 * ([AndroidBrowserExecutor] refreshes the page, then [BrowserSession.confirmSubmit]
 * compares the admission against the current page, token, destination and
 * fill set): a page changed between approval and dispatch fails closed with
 * no submission.
 *
 * JVM-pure: the runtime supplies the shared session.
 */
class BrowserApprovalAdmission(private val session: BrowserSession) {

    /** Only browse_submit needs the browser submission admission. */
    fun needsAdmission(request: ActionRequest): Boolean = request.name == "browse_submit"

    /**
     * Admits the session submission for [task]'s reviewed page when
     * [approval] is the exact, unconsumed approval for that task. Returns
     * [AdmitResult.Denied] with a plain-language reason otherwise; the
     * caller keeps the task parked.
     */
    fun admitForApproval(task: ToolTaskAttempt, approval: ActionApprovalRequest): AdmitResult {
        if (!needsAdmission(task.request)) return AdmitResult.Admitted
        if (approval.consumed) return AdmitResult.Denied("That approval was already used.")
        if (approval.action != task.request || approval.revision != task.actionRevision) {
            return AdmitResult.Denied("That approval is for a changed action. Please approve the current one.")
        }
        if (approval.taskId != task.groupId || approval.stepId != task.stepId) {
            return AdmitResult.Denied("That approval belongs to a different task.")
        }
        val token = task.request.arguments["token"]
            ?: return AdmitResult.Denied("That approval has no page to submit.")
        val proposal = session.proposeSubmit(token)
            ?: return AdmitResult.Denied("That page changed. Please review the fresh page and approve again.")
        session.admitSubmit(proposal)
        return AdmitResult.Admitted
    }
}
