package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Shared bounded coordinator for production and deterministic JVM tests. */
class ActionTurnRunner(
    private val executor: MobileActionExecutor,
    internal val maxModelPasses: Int = 6
) {
    init {
        require(maxModelPasses > 0) { "maxModelPasses must be positive." }
    }
    data class Receipt(val request: ActionRequest, val result: ExecutionResult)
    data class Outcome(val receipts: List<Receipt>, val completed: Boolean, val stopped: Boolean, val message: String,
        val conditionResult: ExecutionResult? = null, val conditionMatched: Boolean? = null)
    sealed interface Batch {
        data class Accepted(val requests: List<ActionRequest>, val reported: List<ActionRequest>) : Batch
        data class Rejected(val message: String = "I couldn't verify the requested phone actions.") : Batch
        /**
         * M1d: model-proposed screen mutations (D23). They never auto-dispatch:
         * [accepted] companions (if any) dispatch normally, then each entry of
         * [proposed] is handed to the caller's approval path instead of the
         * Android executor.
         */
        data class NeedsApproval(val proposed: List<ActionRequest>, val accepted: Accepted? = null) : Batch
    }

    /** Validates every call before a caller is permitted to execute any of them. */
    fun validateBatch(plan: ActionTurnPlan.Ready, completed: List<Receipt>, calls: List<ToolCall>): Batch {
        if (plan.batteryCondition != null) return Batch.Rejected("A battery condition needs a fresh Android reading before dispatch.")
        val remaining = plan.steps.drop(completed.size)
        val decoded = calls.map { NativeActionDecoder.decodeStrict(it) }
        if (decoded.any { it == null }) return Batch.Rejected()
        val requests = decoded.filterNotNull()
        // Screen mutations proposed by the model never auto-dispatch (D23):
        // they are parked for the user's explicit approval while the other
        // calls validate exactly as before.
        val proposed = requests.filter { it.name in SCREEN_MUTATION_TOOLS }
        val rest = requests.filter { it.name !in SCREEN_MUTATION_TOOLS }
        val accepted: Batch.Accepted? = if (rest.isEmpty()) {
            if (proposed.isEmpty()) return Batch.Rejected()
            null
        } else {
            if (remaining.isEmpty()) return Batch.Rejected()
            when (val validated = validateRest(remaining, completed, rest)) {
                is Batch.Rejected -> return validated
                is Batch.Accepted -> validated
                is Batch.NeedsApproval -> return Batch.Rejected()
            }
        }
        return if (proposed.isNotEmpty()) Batch.NeedsApproval(proposed, accepted)
        else accepted ?: Batch.Rejected()
    }

    private fun validateRest(remaining: List<ActionTurnPlan.Step>, completed: List<Receipt>, requests: List<ActionRequest>): Batch {
        val accepted = mutableListOf<ActionRequest>()
        val reported = mutableListOf<ActionRequest>()
        var next = 0
        for (request in requests) {
            val expected = remaining.getOrNull(next)?.request
            if (expected != null && same(request, expected)) {
                accepted += request; reported += request; next++ // explicit repeated user steps take precedence
            } else if ((completed.map { it.request } + accepted).any { same(request, it) }) {
                // Replay is acknowledged with its existing receipt but never dispatched again.
                reported += request
            } else return Batch.Rejected()
        }
        return accepted.takeIf { it.isNotEmpty() }?.let { Batch.Accepted(it, reported) } ?: Batch.Rejected()
    }

    fun run(plan: ActionTurnPlan, responses: Iterable<List<ToolCall>>): Outcome =
        kotlinx.coroutines.runBlocking {
            val iterator = responses.iterator()
            runNative(plan, if (iterator.hasNext()) iterator.next() else emptyList(),
                dispatch = { request -> MobileActionPipeline(executor = executor).execute(request) },
                nextCalls = { if (iterator.hasNext()) iterator.next() else emptyList() })
        }

    private fun complete(receipts: List<Receipt>) = Outcome(receipts, true, false, receipts.joinToString(" ") { it.result.message })
    private fun stopped(receipts: List<Receipt>) = Outcome(receipts, false, true,
        if (receipts.isEmpty()) "I couldn't verify the requested phone actions."
        else "I completed ${receipts.size} action(s), but stopped before the rest.")

    fun same(left: ActionRequest, right: ActionRequest): Boolean = left.name == right.name && when (left.name) {
        "read_battery", "screen_observe" -> left.arguments.isEmpty() && right.arguments.isEmpty()
        "set_volume" -> left.arguments["level"] == right.arguments["level"]
        "open_app" -> left.arguments.keys == setOf("app") && right.arguments.keys == setOf("app") &&
            left.arguments["app"]?.trim()?.equals(right.arguments["app"]?.trim(), ignoreCase = true) == true
        "media_control" -> left.arguments["action"] == right.arguments["action"]
        "show_schedule" -> left.arguments.isEmpty() && right.arguments.isEmpty()
        "create_reminder" -> left.arguments["message"] == right.arguments["message"] &&
            left.arguments["at_ms"] == right.arguments["at_ms"]
        "post_notification" -> left.arguments["title"] == right.arguments["title"] &&
            left.arguments["text"] == right.arguments["text"]
        else -> false
    }
}

/** Complete explicit phone plans already have exact authority; no model acknowledgements are needed. */
suspend fun ActionTurnRunner.runValidated(
    plan: ActionTurnPlan.Ready,
    dispatch: suspend (ActionRequest) -> ExecutionResult,
    checkBattery: suspend () -> ExecutionResult
): ActionTurnRunner.Outcome {
    currentCoroutineContext().ensureActive()
    if (!plan.steps.all { it.request.isRoutineEligible() }) return ActionTurnRunner.Outcome(emptyList(), false, true,
        "I couldn't validate all the requested phone actions. Nothing was started.")
    val conditionResult = plan.batteryCondition?.let { checkBattery() }
    currentCoroutineContext().ensureActive()
    val matched = plan.batteryCondition?.let { condition ->
        val percent = conditionResult?.batteryPercent
        if (conditionResult?.succeeded != true || percent == null || percent !in 0..100)
            return ActionTurnRunner.Outcome(emptyList(), false, true,
                "I couldn't verify the battery condition. No requested actions were started.", conditionResult)
        condition.matches(percent)
    }
    if (matched == false) return ActionTurnRunner.Outcome(emptyList(), true, false,
        "${conditionResult!!.message} The condition was not met. Skipped: " +
            plan.steps.joinToString { it.sourceClause } + ".", conditionResult, false)
    val receipts = mutableListOf<ActionTurnRunner.Receipt>()
    for (step in plan.steps) {
        currentCoroutineContext().ensureActive()
        val result = dispatch(step.request)
        receipts += ActionTurnRunner.Receipt(step.request, result)
        currentCoroutineContext().ensureActive()
        if (!result.succeeded) return ActionTurnRunner.Outcome(receipts, false, true,
            summary(receipts, plan.steps.drop(receipts.size)), conditionResult, matched)
        yield()
    }
    return ActionTurnRunner.Outcome(receipts, true, false,
        (listOfNotNull(conditionResult?.message) + receipts.map { it.result.message }).joinToString(" "),
        conditionResult, matched)
}


data class AuthorizedDispatch(
    val task: ToolTaskAttempt,
    val approval: ActionApprovalRequest
)

class ActionDispatchGate(
    private val approvals: ActionApprovalStore,
    private val ledger: ToolTaskLedger
) {
    init { approvals.attach(ledger.store) }

    fun prepare(task: ToolTaskAttempt, provider: String = "native", schemaVersion: Int = MobileToolCatalog.VERSION): AuthorizedDispatch {
        require(ledger.get(task.id) == task)
        return ledger.requestApproval(task.id, task.generation, provider, schemaVersion)
    }

    fun authorize(dispatch: AuthorizedDispatch, provider: String = "native",
        schemaVersion: Int = MobileToolCatalog.VERSION): ToolTaskAttempt? {
        if (ledger.get(dispatch.task.id) != dispatch.task) return null
        return ledger.claim(dispatch.task.id, dispatch.task.generation, provider, schemaVersion, dispatch.approval)
    }

    fun complete(running: ToolTaskAttempt, result: ExecutionResult): ToolTaskAttempt? {
        return ledger.finish(running, result)
    }

    fun authorizeSpoken(dispatch: AuthorizedDispatch): ToolTaskAttempt? {
        if (ledger.get(dispatch.task.id) != dispatch.task) return null
        return ledger.claim(dispatch.task.id, dispatch.task.generation, approval = dispatch.approval, spoken = true)
    }
}

/** Suspended production path. Native generation happens only between accepted batches. */
suspend fun ActionTurnRunner.runNative(
    plan: ActionTurnPlan,
    initialCalls: List<ToolCall>,
    dispatch: suspend (ActionRequest) -> ExecutionResult,
    nextCalls: suspend (List<ActionTurnRunner.Receipt>) -> List<ToolCall>,
    /**
     * M1d: handles model-proposed screen mutations. The default (null) keeps
     * the historical behavior of rejecting them; production parks them for
     * the user's explicit approval instead of dispatching.
     */
    onNeedsApproval: (suspend (ActionRequest) -> ExecutionResult)? = null
): ActionTurnRunner.Outcome {
    val ready = plan as? ActionTurnPlan.Ready
        ?: return ActionTurnRunner.Outcome(emptyList(), false, true, (plan as? ActionTurnPlan.Rejected)?.reason.orEmpty())
    val receipts = mutableListOf<ActionTurnRunner.Receipt>()
    var calls = initialCalls
    suspend fun dispatchAccepted(requests: List<ActionRequest>): ActionTurnRunner.Outcome? {
        for (request in requests) {
            currentCoroutineContext().ensureActive()
            try {
                val result = dispatch(request)
                receipts += ActionTurnRunner.Receipt(request, result)
                if (!result.succeeded) return ActionTurnRunner.Outcome(receipts, false, true,
                    summary(receipts, ready.steps.drop(receipts.size), result.message))
                currentCoroutineContext().ensureActive()
                yield()
            } catch (cancelled: CancellationException) { throw cancelled }
        }
        return null
    }
    // `initialCalls` comes from the first generation and consumes the first pass.
    for (pass in 0 until maxModelPasses) {
        currentCoroutineContext().ensureActive()
        val batch = validateBatch(ready, receipts, calls)
        if (batch is ActionTurnRunner.Batch.Rejected) return ActionTurnRunner.Outcome(receipts, false, true,
            summary(receipts, ready.steps.drop(receipts.size), batch.message))
        if (batch is ActionTurnRunner.Batch.NeedsApproval) {
            batch.accepted?.let { dispatchAccepted(it.requests)?.let { outcome -> return outcome } }
            val park = onNeedsApproval ?: return ActionTurnRunner.Outcome(receipts, false, true,
                summary(receipts, ready.steps.drop(receipts.size)))
            for (proposal in batch.proposed) {
                currentCoroutineContext().ensureActive()
                try {
                    val result = park(proposal)
                    receipts += ActionTurnRunner.Receipt(proposal, result)
                    currentCoroutineContext().ensureActive()
                    yield()
                } catch (cancelled: CancellationException) { throw cancelled }
            }
            return ActionTurnRunner.Outcome(receipts, false, true,
                receipts.joinToString(" ") { it.result.message }.ifBlank { "I couldn't verify the requested phone actions." })
        }
        batch as ActionTurnRunner.Batch.Accepted
        dispatchAccepted(batch.requests)?.let { return it }
        if (receipts.size == ready.steps.size) return ActionTurnRunner.Outcome(receipts, true, false,
            receipts.joinToString(" ") { it.result.message })
        // Do not start another inference after the final configured model pass.
        if (pass == maxModelPasses - 1) break
        currentCoroutineContext().ensureActive()
        yield()
        currentCoroutineContext().ensureActive()
        val reportedReceipts = batch.reported.map { request ->
            receipts.asReversed().firstOrNull { this.same(request, it.request) }
                ?: error("Validated replay has no receipt")
        }
        calls = nextCalls(reportedReceipts)
    }
    return ActionTurnRunner.Outcome(receipts, false, true, summary(receipts, ready.steps.drop(receipts.size)))
}

private fun summary(receipts: List<ActionTurnRunner.Receipt>, remaining: List<ActionTurnPlan.Step>, reason: String? = null): String =
    buildString {
        if (receipts.isNotEmpty()) append(receipts.joinToString(" ") { it.result.message })
        if (reason != null) { if (isNotEmpty()) append(' '); append(reason) }
        if (remaining.isNotEmpty()) { if (isNotEmpty()) append(' '); append("Did not attempt: ").append(remaining.joinToString { it.request.name }).append('.') }
    }.ifBlank { "I couldn't verify the requested phone actions." }
