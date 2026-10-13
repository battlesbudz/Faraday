package com.battlesbudz.jarvis.v2.actions

/**
 * M1d approval-UI wiring for screen tasks.
 *
 * Approving a screen task in the task panel admits the [ScreenControlSession]
 * grant for its task group, so the dispatch gate stops returning
 * needs-approval for exactly the approved work. Exact-approval semantics
 * (D11/D13, T07):
 *
 * - The approval must be unconsumed, name this task's exact action and
 *   revision, and belong to this task/group. A changed target invalidates the
 *   prior approval: the ledger's [ToolTaskLedger.revise] consumes the old
 *   choice as STALE and clears the attempt's approval link, so a stale panel
 *   approval can never admit the session.
 * - Approval consumption and dispatch eligibility commit together in
 *   [ToolTaskLedger.claim]; this admission only opens the session gate. The
 *   ledger claim remains the authority: an approval that fails the claim
 *   cannot dispatch even with an admitted session, and the caller must
 *   release a grant admitted for a failed claim ([ScreenControlSession.releaseIf]).
 *
 * JVM-pure: the runtime supplies the shared session.
 */
class ScreenApprovalAdmission(private val session: ScreenControlSession) {

    /** Screen mutations are the only requests that need the session grant. */
    fun needsSession(request: ActionRequest): Boolean =
        request.name in TaskScheduler.SCREEN_MUTATIONS

    /**
     * Admits the session grant for [task]'s group when [approval] is the
     * exact, unconsumed approval for that task. Returns the session's verdict;
     * [AdmitResult.Denied] means another task holds the lease and this task
     * must queue (T02) rather than fail.
     */
    fun admitForApproval(task: ToolTaskAttempt, approval: ActionApprovalRequest): AdmitResult {
        val groupId = task.groupId
        if (!needsSession(task.request) || groupId.isNullOrBlank()) return AdmitResult.Admitted
        if (approval.consumed) return AdmitResult.Denied("That approval was already used.")
        if (approval.action != task.request || approval.revision != task.actionRevision) {
            return AdmitResult.Denied("That approval is for a changed action. Please approve the current one.")
        }
        if (approval.taskId != groupId || approval.stepId != task.stepId) {
            return AdmitResult.Denied("That approval belongs to a different task.")
        }
        return session.admit(groupId, userApproved = true)
    }
}
