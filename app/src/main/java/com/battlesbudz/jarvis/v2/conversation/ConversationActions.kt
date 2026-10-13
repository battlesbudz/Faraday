package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.runNative
import com.battlesbudz.jarvis.v2.actions.runValidated
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One turn's action-journal owner. Admission, Android execution, durable receipt publication and
 * UI acknowledgement stay together on Main. A model claim never serves as an executor receipt.
 * Cancellation marks unfinished journal work; it cannot undo an Android effect already begun.
 */
internal class ConversationActions(
    private val executor: () -> MobileActionExecutor,
    private val admit: (ActionTurnPlan.Ready, String) -> String?,
    private val execute: (ActionRequest, MobileActionExecutor, String?, Int) -> ExecutionResult,
    private val cancelUnfinished: (String) -> Unit,
    private val conversationId: String,
    private val onActionResult: (String, String, Boolean) -> Unit
) {
    private var groupId: String? = null
    private var step = 0

    private suspend fun dispatch(plan: ActionTurnPlan.Ready, request: ActionRequest,
                                 executor: MobileActionExecutor? = null): ExecutionResult = withContext(Dispatchers.Main) {
        if (groupId == null) groupId = admit(plan, conversationId)
        if (groupId == null) ExecutionResult(false,
            "I couldn't save this phone action, so I didn't start it.")
        else execute(request, executor ?: this@ConversationActions.executor(), groupId, step++).also {
            onActionResult(request.name, it.message, it.succeeded)
        }
    }

    suspend fun runValidated(plan: ActionTurnPlan.Ready, benchmark: PipelineBenchmarkCapture): ActionTurnRunner.Outcome {
        val coordinator = ActionTurnRunner(MobileActionExecutor { error("Dispatch is runtime-owned") })
        val executor = executor()
        val started = System.nanoTime()
        benchmark.mark("tool_execution_started")
        val outcome = coordinator.runValidated(plan,
            checkBattery = { withContext(Dispatchers.Main) {
                execute(ActionRequest("read_battery"), executor, null, 0)
            } },
            dispatch = { dispatch(plan, it, executor) })
        benchmark.mark("tool_execution_finished")
        benchmark.metric("tool_execution_ms", (System.nanoTime() - started) / 1_000_000)
        benchmark.metric("tool_requested_steps", plan.steps.size)
        benchmark.metric("tool_executed_steps", outcome.receipts.size)
        benchmark.metric("tool_succeeded_steps", outcome.receipts.count { it.result.succeeded })
        benchmark.metric("tool_failed_steps", outcome.receipts.count { !it.result.succeeded })
        benchmark.metric("tool_condition_matched", outcome.conditionMatched?.let { if (it) 1 else 0 })
        benchmark.configuration("tool_result_provenance", "android_executor_receipts_not_model_claims")
        if (outcome.stopped) cancel()
        return outcome
    }

    suspend fun runNative(plan: ActionTurnPlan.Ready, initialCalls: List<ToolCall>,
                          nextCalls: suspend (List<ActionTurnRunner.Receipt>) -> List<ToolCall>,
                          onNeedsApproval: (suspend (ActionRequest) -> ExecutionResult)? = null): ActionTurnRunner.Outcome {
        val coordinator = ActionTurnRunner(MobileActionExecutor {
            error("ActionTurnRunner dispatch is supplied by the conversation runtime")
        })
        val outcome = coordinator.runNative(plan, initialCalls,
            dispatch = { dispatch(plan, it) }, nextCalls = nextCalls,
            onNeedsApproval = onNeedsApproval)
        if (outcome.stopped) cancel()
        return outcome
    }

    fun cancel() { groupId?.let(cancelUnfinished) }
}
