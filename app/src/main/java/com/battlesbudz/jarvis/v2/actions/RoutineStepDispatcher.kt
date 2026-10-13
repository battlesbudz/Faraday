package com.battlesbudz.jarvis.v2.actions

/**
 * Finding 1 production routine-dispatch seam: routine-grant admission
 * followed by [JournaledActionPipeline.executeAttempt]. Extracted from
 * WorkflowCoordinator.dispatchWorkflowStep so the regression test drives
 * the real production path instead of a hand-rolled approximation
 * (manual claim + pipeline.execute), which would pass even if the
 * production pre-claim were reintroduced.
 *
 * Claim discipline: executeAttempt owns the claim — it claims the admitted
 * attempt atomically (eligibility, provider, schema, exact approval). An
 * extra claim inserted before executeAttempt leaves the attempt RUNNING, so
 * executeAttempt's claim — which only accepts
 * QUEUED/READY/WAITING_APPROVAL — rejects, the step never executes, and the
 * reminder never posts. The regression test fails in that state.
 */
class RoutineStepDispatcher(
    private val workflowLedger: WorkflowLedger,
    private val phoneActionLedger: ToolTaskLedger,
    private val executorFactory: () -> MobileActionExecutor,
    private val pipelineFactory: (MobileActionExecutor) -> JournaledActionPipeline =
        { executor -> JournaledActionPipeline(phoneActionLedger, executor) },
    private val occurrencePipelineFactory: ((MobileActionExecutor, WorkflowOccurrence) -> JournaledActionPipeline)? = null
) {
    /**
     * Dispatch one routine-eligible step under the occurrence's routine
     * grant. The grant is reused only when its exact request limits match
     * (T11); a new tool can never broaden it. Each step is admitted as its
     * own group so a failed step stops the run before later steps are
     * admitted.
     */
    fun dispatch(occurrence: WorkflowOccurrence, request: ActionRequest): ExecutionResult {
        fun refusal(message: String) = ExecutionResult(false, message)
        if (!request.isRoutineEligible()) {
            return refusal("The routine asked for an action outside its granted limits. It didn't run.")
        }
        val grant = try {
            workflowLedger.reusableGrant(occurrence.workflowId, listOf(request))
                ?: workflowLedger.createGrant(occurrence.workflowId, listOf(request))
        } catch (_: Exception) { return refusal("I couldn't save this routine's permission, so it didn't run.") }
        val group = try {
            phoneActionLedger.admit(listOf(request), "workflow:${occurrence.id}",
                authority = ToolAuthority.ROUTINE, grantId = grant.id, resumeAfterRestart = false)
        } catch (_: Exception) { return refusal("The routine's actions weren't admitted.") }
        val attempt = phoneActionLedger.get(group.attemptIds.single())
            ?: return refusal("The routine's action disappeared before it could run.")
        // JournaledActionPipeline.executeAttempt owns the claim: it claims the
        // admitted attempt atomically (eligibility, provider, schema, exact
        // approval). A pre-claim here would leave the attempt RUNNING, so its
        // claim — which only accepts QUEUED/READY/WAITING_APPROVAL — rejects
        // and the step never executes.
        return try {
            val executor = executorFactory()
            val pipeline = occurrencePipelineFactory?.invoke(executor, occurrence) ?: pipelineFactory(executor)
            pipeline.executeAttempt(attempt)
        }
        catch (_: Exception) { ExecutionResult(
            ExecutionResult.Outcome.UNKNOWN_COMPLETION,
            "The routine's action may have run; its outcome is unknown and it won't be repeated.") }
    }
}
