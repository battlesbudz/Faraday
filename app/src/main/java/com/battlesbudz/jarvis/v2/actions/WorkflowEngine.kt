package com.battlesbudz.jarvis.v2.actions

/**
 * M2 workflow engine: runs one occurrence of a pinned workflow definition
 * (D31–D36, T11–T14).
 *
 * The engine is deterministic and JVM-pure except for the injected
 * [dispatch] lambda, which the runtime implements with the ledger's
 * routine-grant admission and the journaled pipeline. Conditions are
 * evaluated in code — never by the model. Screen mutations are never
 * dispatched here: the engine suspends with [WorkflowRunOutcome.NeedsApproval]
 * so each occurrence gets an independent approval branch (D11/D23, T11).
 *
 * Bounded effort (D28, T14): adaptive steps stop at their budget and ask
 * the user; completed steps are never re-run, and an unknown outcome is
 * never blindly repeated.
 */
sealed interface WorkflowRunOutcome {
    data class Completed(val succeeded: Boolean, val summary: String,
        val results: Map<String, Map<String, String>>) : WorkflowRunOutcome
    data class Suspended(
        val wait: WorkflowWait,
        val resumePath: List<Int>,
        /** Steps already executed before the wait, so a resume never re-runs them. */
        val completedStepIds: List<String>,
        /** Step outputs available for argument bindings after the resume. */
        val results: Map<String, Map<String, String>>
    ) : WorkflowRunOutcome
    data class NeedsApproval(val stepId: String, val request: ActionRequest,
        val resumePath: List<Int>) : WorkflowRunOutcome
    data class NeedsUser(val question: String, val completedStepIds: List<String>,
        val resumePath: List<Int>) : WorkflowRunOutcome
    data class Failed(val reason: String, val completedStepIds: List<String>) : WorkflowRunOutcome
}

/** Screen mutations always need an exact approval; D11 categories map here as those tools land. */
internal fun ActionRequest.requiresExactApproval(): Boolean = name in SCREEN_MUTATION_TOOLS

/**
 * M5: the outcome of a script step, produced by the injected [WorkflowEngine.run]
 * `runScript` runner. The Android runtime wires this to the isolated script
 * interpreter ([runWithInterpreter]); the default fails closed.
 */
sealed interface ScriptExecution {
    data class Succeeded(val resultText: String) : ScriptExecution
    data class Failed(val reason: String) : ScriptExecution
    data object Cancelled : ScriptExecution
}

class WorkflowEngine(private val now: () -> Long = System::currentTimeMillis) {

    /**
     * Run [definition] from [startPath]. Steps in [skipStepIds] are treated
     * as already done (their outputs must be in [initialResults]) — this is
     * how an approved screen step or a satisfied wait resumes without
     * re-running completed work.
     *
     * Routine tool steps dispatch one at a time through [dispatch]: a
     * failed step stops the run before any later step is admitted, so a
     * failure never drags already-admitted siblings with it.
     *
     * M5: script steps run through [runScript], which the Android runtime
     * wires to the isolated [ScriptRuntime]. The default fails closed —
     * without a runtime installed, script steps fail instead of running
     * anywhere unisolated.
     */
    fun run(
        definition: WorkflowDefinition,
        startPath: List<Int> = emptyList(),
        skipStepIds: Set<String> = emptySet(),
        initialResults: Map<String, Map<String, String>> = emptyMap(),
        initialCompleted: Set<String> = emptySet(),
        dispatch: (ActionRequest) -> ExecutionResult,
        runScript: (WorkflowStep.Script) -> ScriptExecution = {
            ScriptExecution.Failed("No script runtime is installed on this device.")
        }
    ): WorkflowRunOutcome {
        val run = Run(definition, dispatch, runScript)
        initialResults.forEach { (stepId, outputs) ->
            run.results.getOrPut(stepId) { mutableMapOf() }.putAll(outputs)
        }
        run.completedStepIds.addAll(initialCompleted)
        return when (val control = run.executeSteps(definition.steps, emptyList(), startPath, skipStepIds)) {
            is Flow.Continue -> WorkflowRunOutcome.Completed(true,
                "Finished ${run.completedStepIds.size} step(s).",
                run.results.mapValues { it.value.toMap() })
            is Flow.Suspend -> WorkflowRunOutcome.Suspended(control.wait, control.path,
                run.completedStepIds.toList(), run.results.mapValues { it.value.toMap() })
            is Flow.Approval -> WorkflowRunOutcome.NeedsApproval(control.stepId, control.request, control.path)
            is Flow.AskUser -> WorkflowRunOutcome.NeedsUser(control.question,
                run.completedStepIds.toList(), control.path)
            is Flow.Fail -> WorkflowRunOutcome.Failed(control.reason, run.completedStepIds.toList())
        }
    }

    private sealed interface Flow {
        data object Continue : Flow
        data class Suspend(val wait: WorkflowWait, val path: List<Int>) : Flow
        data class Approval(val stepId: String, val request: ActionRequest, val path: List<Int>) : Flow
        data class AskUser(val question: String, val path: List<Int>) : Flow
        data class Fail(val reason: String) : Flow
    }

    private inner class Run(
        val definition: WorkflowDefinition,
        val dispatch: (ActionRequest) -> ExecutionResult,
        val runScript: (WorkflowStep.Script) -> ScriptExecution
    ) {
        val results = mutableMapOf<String, MutableMap<String, String>>()
        val completedStepIds = mutableListOf<String>()
        var dispatches = 0

        fun executeSteps(
            steps: List<WorkflowStep>,
            prefix: List<Int>,
            path: List<Int>,
            skipStepIds: Set<String>
        ): Flow {
            var i = if (path.isNotEmpty()) path[0] else 0
            while (i < steps.size) {
                val step = steps[i]
                // Distinguish reaching the current path component from reaching the leaf:
                // a resume path like [1,0] must forward its tail [0] when descending
                // through the branch at index 1, so the nested wait sees itself as the
                // leaf target instead of a fresh wait.
                val onResumePath = path.isNotEmpty() && i == path[0]
                val nestedPath = if (onResumePath) path.drop(1) else emptyList()
                val isLeafTarget = onResumePath && path.size == 1
                if (step.id in skipStepIds) { i++; continue }
                when (step) {
                    is WorkflowStep.Tool -> {
                        val resolved = try { resolveArguments(step) }
                        catch (e: IllegalArgumentException) { return Flow.Fail(e.message ?: "Unresolvable bindings.") }
                        val request = ActionRequest(step.request.name, resolved)
                        if (request.requiresExactApproval()) {
                            return Flow.Approval(step.id, request, prefix + i)
                        }
                        when (val dispatched = dispatchStep(step.id, request)) {
                            is Flow.Fail -> return dispatched
                            else -> Unit
                        }
                    }
                    is WorkflowStep.Branch -> {
                        val nested = if (evaluate(step.condition)) step.thenSteps else step.elseSteps
                        when (val nested = executeSteps(nested, prefix + i, nestedPath, skipStepIds)) {
                            is Flow.Continue -> completedStepIds += step.id
                            else -> return nested
                        }
                    }
                    is WorkflowStep.Wait -> {
                        // A resume-target wait is already satisfied — continue past it.
                        if (!isLeafTarget) return Flow.Suspend(step.wait, prefix + i)
                        completedStepIds += step.id
                    }
                    is WorkflowStep.Adaptive -> {
                        when (val adaptive = runAdaptive(step, prefix + i)) {
                            is Flow.Continue -> Unit
                            else -> return adaptive
                        }
                    }
                    is WorkflowStep.Script -> {
                        when (val script = runScript(step)) {
                            is ScriptExecution.Succeeded -> {
                                results.getOrPut(step.id) { mutableMapOf() }["result"] =
                                    script.resultText.take(512)
                                completedStepIds += step.id
                            }
                            is ScriptExecution.Failed ->
                                return Flow.Fail("Script step failed: ${script.reason}")
                            is ScriptExecution.Cancelled ->
                                return Flow.Fail("Script step was cancelled.")
                        }
                    }
                }
                i++
            }
            return Flow.Continue
        }

        /**
         * Dispatch one routine-eligible step. A failure or unknown outcome
         * stops the run here — later steps are never admitted after a
         * failure, and an unknown effect is never repeated.
         */
        private fun dispatchStep(stepId: String, request: ActionRequest): Flow {
            dispatches++
            if (dispatches > definition.maxStepsPerRun) {
                return Flow.Fail("Stopped: this run passed its effort budget of ${definition.maxStepsPerRun} actions.")
            }
            val result = try { dispatch(request) }
            catch (e: Exception) { return Flow.Fail("Dispatch failed before any effect could be confirmed: ${e.message}") }
            return when {
                result.outcome == ExecutionResult.Outcome.UNKNOWN_COMPLETION ->
                    Flow.Fail("“${describeStep(request)}” may have taken effect but its outcome is unknown. " +
                        "It was not repeated — check before running it again.")
                !result.succeeded ->
                    Flow.Fail("“${describeStep(request)}” failed: ${result.message}")
                else -> {
                    recordOutputs(stepId, request, result)
                    completedStepIds += stepId
                    Flow.Continue
                }
            }
        }

        private fun runAdaptive(step: WorkflowStep.Adaptive, path: List<Int>): Flow {
            val startMs = now()
            var attempts = 0
            var noProgress = 0
            var lastMessage: String? = null
            val tried = mutableListOf<String>()
            for (candidate in step.candidates) {
                while (attempts < step.budget.maxAttempts) {
                    if (now() - startMs > step.budget.maxWallMs) break
                    if (candidate.requiresExactApproval()) {
                        return Flow.Approval(step.id, candidate, path)
                    }
                    attempts++
                    dispatches++
                    if (dispatches > definition.maxStepsPerRun) {
                        return Flow.Fail("Stopped: this run passed its effort budget of ${definition.maxStepsPerRun} actions.")
                    }
                    val result = try { dispatch(candidate) }
                    catch (e: Exception) { return Flow.Fail("Dispatch failed: ${e.message}") }
                    tried += describeStep(candidate)
                    val progressed = result.succeeded || result.message != lastMessage
                    lastMessage = result.message
                    if (result.outcome == ExecutionResult.Outcome.UNKNOWN_COMPLETION) {
                        return Flow.Fail("“${describeStep(candidate)}” may have taken effect but its outcome is unknown. " +
                            "It was not repeated — check before running it again.")
                    }
                    if (result.succeeded) {
                        recordOutputs(step.id, candidate, result)
                        completedStepIds += step.id
                        return Flow.Continue
                    }
                    if (progressed) noProgress = 0
                    else {
                        noProgress++
                        if (noProgress >= step.budget.noProgressLimit) break
                    }
                }
            }
            // Bounded effort exhausted: ask the user instead of retrying.
            // Completed steps stay completed — nothing here re-runs them.
            return Flow.AskUser(
                "I tried to ${step.goal} — ${tried.size} attempt(s) (${tried.distinct().take(3).joinToString("; ")}), " +
                    "none worked. Want me to keep trying a different way, or leave it?",
                path)
        }

        private fun resolveArguments(step: WorkflowStep.Tool): Map<String, String> {
            val args = step.request.arguments.toMutableMap()
            for ((argument, binding) in step.bindings) {
                args[argument] = results[binding.stepId]?.get(binding.outputName)
                    ?: throw IllegalArgumentException(
                        "Step “${step.id.take(8)}” needs “${binding.outputName}” from an earlier step, but it has no value yet.")
            }
            for ((key, value) in args) {
                args[key] = substituteBindingPlaceholders(value) { refId, refOutput, text ->
                    results[refId]?.get(refOutput)
                        ?: throw IllegalArgumentException(
                            "Step “${step.id.take(8)}” references an unknown value “$text”.")
                }
            }
            return args
        }

        private fun evaluate(condition: WorkflowCondition): Boolean {
            fun valueOf(binding: WorkflowBinding): String? = results[binding.stepId]?.get(binding.outputName)
            return when (condition) {
                is WorkflowCondition.Equals -> valueOf(condition.binding) == condition.literal
                is WorkflowCondition.NotEquals -> valueOf(condition.binding) != condition.literal
                is WorkflowCondition.GreaterThan ->
                    valueOf(condition.binding)?.toDoubleOrNull()?.let { it > condition.number } ?: false
                is WorkflowCondition.LessThan ->
                    valueOf(condition.binding)?.toDoubleOrNull()?.let { it < condition.number } ?: false
                is WorkflowCondition.Matches -> valueOf(condition.binding)?.let { value ->
                    try { Regex(condition.regex).containsMatchIn(value) } catch (_: Exception) { false }
                } ?: false
            }
        }

        private fun recordOutputs(stepId: String, request: ActionRequest, result: ExecutionResult) {
            val outputs = results.getOrPut(stepId) { mutableMapOf() }
            val declared = standardToolOutputs(request)
            if ("message" in declared) outputs["message"] = result.message.take(512)
            if ("succeeded" in declared) outputs["succeeded"] = result.succeeded.toString()
            if ("battery_percent" in declared) result.batteryPercent?.let { outputs["battery_percent"] = it.toString() }
        }

        private fun describeStep(request: ActionRequest): String = request.describeForOverlay()
    }
}
