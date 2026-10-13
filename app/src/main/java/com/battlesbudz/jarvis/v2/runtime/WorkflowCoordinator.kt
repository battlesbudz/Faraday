package com.battlesbudz.jarvis.v2.runtime

import android.content.Context
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.AndroidToolCapabilityProbe
import com.battlesbudz.jarvis.v2.actions.AppFunctionPlatformProbe
import com.battlesbudz.jarvis.v2.actions.AppFunctionPlatformStatus
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.JournaledActionPipeline
import com.battlesbudz.jarvis.v2.actions.McpRegistry
import com.battlesbudz.jarvis.v2.actions.McpSetupFlow
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.ProviderKind
import com.battlesbudz.jarvis.v2.actions.ProviderRegistry
import com.battlesbudz.jarvis.v2.actions.ProviderSettings
import com.battlesbudz.jarvis.v2.actions.ProviderWireNames
import com.battlesbudz.jarvis.v2.actions.ReminderCoordinator
import com.battlesbudz.jarvis.v2.actions.ReminderScheduling
import com.battlesbudz.jarvis.v2.actions.ScriptHost
import com.battlesbudz.jarvis.v2.actions.ScriptExecution
import com.battlesbudz.jarvis.v2.actions.WorkflowStep
import com.battlesbudz.jarvis.v2.actions.runWithInterpreter
import com.battlesbudz.jarvis.v2.actions.ToolSourceAccess
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.actions.ToolTaskStorageException
import com.battlesbudz.jarvis.v2.actions.ToolTaskStore
import com.battlesbudz.jarvis.v2.actions.MissedRunDecision
import com.battlesbudz.jarvis.v2.actions.UrlConnectionMcpHttpClient
import com.battlesbudz.jarvis.v2.actions.WorkflowAlarmRunner
import com.battlesbudz.jarvis.v2.actions.WorkflowAlarmScheduler
import com.battlesbudz.jarvis.v2.actions.WorkflowEngine
import com.battlesbudz.jarvis.v2.actions.WorkflowEventKind
import com.battlesbudz.jarvis.v2.actions.WorkflowLedger
import com.battlesbudz.jarvis.v2.actions.WorkflowOccurrence
import com.battlesbudz.jarvis.v2.actions.WorkflowOccurrenceState
import com.battlesbudz.jarvis.v2.actions.WorkflowRunOutcome
import com.battlesbudz.jarvis.v2.actions.WorkflowScheduling
import com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection
import com.battlesbudz.jarvis.v2.actions.WorkflowTrigger
import com.battlesbudz.jarvis.v2.actions.WorkflowWait
import com.battlesbudz.jarvis.v2.actions.androidLockGate
import com.battlesbudz.jarvis.v2.actions.describeForOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns reusable-workflow (M2), ecosystem-provider (M3) and reminder wiring.
 * The workflow ledger shares the phone-task journal's durable store file;
 * alarms, provider grants and the reminder coordinator all enter through
 * this component's explicit ports. Android execution reaches the reminder
 * path through [ReminderScheduling], which the runtime re-exposes so the
 * executor's context cast keeps working.
 */
internal class WorkflowCoordinator(
    private val scope: CoroutineScope,
    private val appContext: Context,
    taskStore: ToolTaskStore,
    private val isActivityVisible: () -> Boolean,
    private val recordDiagnostic: (String) -> Unit,
    private val reportError: (String) -> Unit,
    private val onJournalChanged: () -> Unit = {},
) : ReminderScheduling {
    /** The runtime startup barrier invokes this once, before accepting alarm work. */
    fun recoverAfterRestart(): Boolean = try {
        workflowLedger.recoverAfterRestart()
        true
    } catch (failure: ToolTaskStorageException) {
        reportError(failure.userMessage())
        false
    }

    /** M2: the workflow ledger shares the task ledger's durable store. */
    private val workflowLedger = WorkflowLedger(taskStore)
    /**
     * M2 alarm delivery: claim -> run -> terminal checkpoint, on the caller's thread.
     * Cancellation follows the coordinator scope: cancelling the scope (teardown)
     * cancels a running alarm occurrence instead of leaving it uninterruptible.
     */
    private val alarmRunner = WorkflowAlarmRunner(
        claimDue = { id -> try { workflowLedger.claimDueOccurrence(id) } catch (_: ToolTaskStorageException) { null } },
        claimResume = { id -> try { workflowLedger.claimResumeOccurrence(id) } catch (_: ToolTaskStorageException) { null } },
        runOccurrence = { occurrence, resume -> runOrResume(occurrence, resume, isCancelled = { !scope.isActive }) }
    )
    private val phoneActionLedger = ToolTaskLedger(taskStore)

    /**
     * One-shot reminder scheduling over the workflow ledger. The Android
     * executor reaches this through the ReminderScheduling interface, so the
     * deterministic turn path's create_reminder/show_schedule requests land here.
     */
    private val reminderCoordinator by lazy {
        ReminderCoordinator(
            ledger = workflowLedger,
            alarmScheduler = { occurrence ->
                WorkflowAlarmScheduler(appContext).schedule(occurrence)
            },
        )
    }
    override fun createReminder(message: String, atMs: Long): ExecutionResult =
        reminderCoordinator.createReminder(message, atMs)
    override fun describeSchedule(): ExecutionResult =
        reminderCoordinator.describeSchedule()

    // -- M3 ecosystem providers (D05/D07, T16/T17) ---------------------------

    internal val providerRegistry = ProviderRegistry()
    private val mcpHttpClient = UrlConnectionMcpHttpClient()
    private val mcpCredentialStore =
        com.battlesbudz.jarvis.v2.actions.AndroidKeystoreMcpCredentialStore(appContext)
    /** Connected MCP servers: guided setup, refresh, enablement, disconnect. */
    internal val mcpRegistry = McpRegistry(mcpHttpClient, mcpCredentialStore)
    private val mcpSetupFlow = McpSetupFlow(mcpHttpClient, mcpCredentialStore)
    /** Honest AppFunctions platform state (ordinary-app probe); null until the probe runs. */
    internal var appFunctionPlatformStatus: AppFunctionPlatformStatus? = null
        private set

    /**
     * M3 provider scope resolution for the T08 grant discipline: a provider
     * wire name resolves to its declared scopes from the live registries.
     * Secrets never pass through here — only scope names.
     */
    fun seedProviderScopeResolver() {
        com.battlesbudz.jarvis.v2.actions.ToolSourcePolicy.setProviderScopeResolver { wireName ->
            ProviderWireNames.parseToolName(wireName)?.let { parsed ->
                when (parsed.provider.kind) {
                    ProviderKind.APP_FUNCTIONS ->
                        providerRegistry.metadataFor(parsed.provider, parsed.functionId)?.scopes.orEmpty()
                    ProviderKind.MCP ->
                        mcpRegistry.toolsFor(parsed.provider.id)
                            .firstOrNull { it.name == parsed.functionId }
                            ?.scopes?.map { scope ->
                                ProviderWireNames.scopedName(parsed.provider, scope)
                            }.orEmpty().toSet()
                }
            }.orEmpty()
        }
    }

    /** Run the AppFunctions platform probe once (ordinary app access); refresh the settings rows after. */
    fun probeAppFunctionPlatform() {
        scope.launch(Dispatchers.IO) {
            appFunctionPlatformStatus = try {
                AppFunctionPlatformProbe(appContext).probe()
            } catch (_: Exception) {
                null
            }
            refreshWorkflowSettings()
        }
    }

    /**
     * Guided MCP setup from settings (D07): custom URL, staged negotiation
     * and discovery, secret stored under an opaque reference. The result
     * message is honest about which stage failed and why.
     */
    fun connectMcpServer(name: String, url: String, token: String, done: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val message = try {
                when (val result = mcpSetupFlow.run(name, url, token.ifBlank { null })) {
                    is McpSetupFlow.FlowResult.Connected -> {
                        val placed = mcpRegistry.add(result.status)
                        "Connected to ${placed.config.name}: ${placed.tools.size} tool(s) discovered, " +
                            "${placed.enabledTools.size} free tool(s) enabled by default."
                    }
                    is McpSetupFlow.FlowResult.Failed ->
                        "Could not connect (${result.stage.name.lowercase()}): ${result.reason}"
                }
            } catch (_: Exception) {
                "Could not connect: an unexpected error stopped setup. Nothing was saved."
            }
            withContext(Dispatchers.Main) {
                refreshWorkflowSettings()
                done(message)
            }
        }
    }

    // -- M2 reusable workflows (D31–D36, T11–T14) ---------------------------

    /** Settings projection: saved workflows plus connected tools. Chat stays the operating surface. */
    internal val workflowSettings = MutableStateFlow<WorkflowSettingsProjection?>(null)

    fun refreshWorkflowSettings() {
        workflowSettings.value = try {
            WorkflowSettingsProjection.from(
                phoneActionLedger.journal(), System.currentTimeMillis(),
                ProviderSettings.rows(providerRegistry, mcpRegistry, appFunctionPlatformStatus))
        } catch (_: ToolTaskStorageException) { null }
    }

    /** Settings toggle: explicit enable/disable; disabling pauses affected unfinished work (D17). */
    fun setWorkflowEnabled(id: String, enabled: Boolean) {
        try {
            if (enabled) {
                val scheduled = workflowLedger.enable(id)
                val scheduler = WorkflowAlarmScheduler(appContext)
                for (occurrence in scheduled) {
                    try { scheduler.schedule(occurrence) } catch (_: Exception) { /* receipt kept; retry on launch */ }
                }
            } else {
                val result = workflowLedger.disable(id)
                val scheduler = WorkflowAlarmScheduler(appContext)
                for (occurrenceId in result.pausedOccurrenceIds) {
                    try { scheduler.cancel(occurrenceId) } catch (_: Exception) { }
                }
            }
        } catch (_: ToolTaskStorageException) {
            reportError("The action journal is unavailable. The routine was not changed.")
        } catch (_: IllegalArgumentException) {
            reportError("I couldn't find that routine.")
        } finally { refreshWorkflowSettings() }
    }

    /**
     * Alarm fire: claim the occurrence atomically, then run it. A fresh
     * trigger claims from SCHEDULED; a timer-resume re-fire claims from
     * WAITING_EVENT via [WorkflowLedger.claimResumeOccurrence] — event waits
     * are never claimed here. Redeliveries find the occurrence already
     * claimed and do nothing — triggers never double-fire.
     */
    fun onWorkflowAlarm(occurrenceId: String, done: () -> Unit) {
        scope.launch(Dispatchers.Default) {
            // done() fires only after the run's terminal checkpoint (or
            // resume re-arm) completes — the receiver's PendingResult is no
            // longer finished while work is still detached.
            try { alarmRunner.onAlarm(occurrenceId) }
            finally { done() }
        }
    }

    fun runWorkflowOccurrence(occurrence: WorkflowOccurrence) {
        scope.launch(Dispatchers.Default) {
            runOrResume(occurrence, resume = false, isCancelled = { !isActive })
        }
    }

    /**
     * Finding 4 (timer continuation resume): continue a run from its saved
     * position with its saved progress. Completed steps are never re-run and
     * their outputs stay available for argument bindings.
     */
    fun resumeWorkflowOccurrence(occurrence: WorkflowOccurrence) {
        scope.launch(Dispatchers.Default) {
            runOrResume(occurrence, resume = true, isCancelled = { !isActive })
        }
    }

    private fun runOrResume(occurrence: WorkflowOccurrence, resume: Boolean, isCancelled: () -> Boolean) {
        val definition = try { workflowLedger.definitionFor(occurrence) }
        catch (_: ToolTaskStorageException) { null }
        if (definition == null) {
            try { workflowLedger.completeOccurrence(occurrence.id, false, "The routine's definition is gone.") }
            catch (_: Exception) { }
            return
        }
        val outcome = try {
            // M5: script steps run through the real isolated interpreter
            // with the production allowlisted host — never the "no runtime"
            // fallback. Cancellation follows the run's coroutine.
            val runScript: (WorkflowStep.Script) -> ScriptExecution = { step ->
                step.runWithInterpreter(productionScriptHost(), isCancelled = isCancelled)
            }
            if (resume) {
                WorkflowEngine().run(definition,
                    startPath = occurrence.resumePath,
                    skipStepIds = occurrence.completedStepIds.toSet(),
                    initialResults = occurrence.stepResults,
                    initialCompleted = occurrence.completedStepIds.toSet(),
                    dispatch = { request -> dispatchWorkflowStep(occurrence, request) },
                    runScript = runScript)
            } else {
                WorkflowEngine()
                    .run(definition,
                        dispatch = { request -> dispatchWorkflowStep(occurrence, request) },
                        runScript = runScript)
            }
        } catch (e: Exception) {
            WorkflowRunOutcome.Failed(
                "The routine stopped on an internal error: ${e.message}", emptyList())
        }
        try {
            when (outcome) {
                is WorkflowRunOutcome.Completed ->
                    workflowLedger.completeOccurrence(occurrence.id, outcome.succeeded, outcome.summary)
                is WorkflowRunOutcome.Suspended -> {
                    // Persist the engine's progress with the wait so the
                    // resume continues exactly here. Timer/UntilTime waits
                    // arm a resume alarm; event waits are picked up by the
                    // notification/location listeners when they land.
                    val resumeAt = when (val wait = outcome.wait) {
                        is WorkflowWait.Timer -> System.currentTimeMillis() + wait.durationMs
                        is WorkflowWait.UntilTime -> wait.epochMs
                        is WorkflowWait.Event -> null
                    }
                    workflowLedger.markWaiting(occurrence.id,
                        WorkflowOccurrenceState.WAITING_EVENT,
                        outcome.resumePath, "Waiting: ${describeWorkflowWait(outcome.wait)}",
                        resumeAtMs = resumeAt,
                        completedStepIds = outcome.completedStepIds,
                        stepResults = outcome.results)
                    if (resumeAt != null) scheduleWorkflowResume(occurrence.id, resumeAt)
                }
                is WorkflowRunOutcome.NeedsApproval -> {
                    workflowLedger.markWaiting(occurrence.id,
                        WorkflowOccurrenceState.WAITING_APPROVAL,
                        outcome.resumePath, "Needs your approval: ${outcome.request.describeForOverlay()}")
                    // The chat layer picks up WAITING_APPROVAL occurrences
                    // and asks through the exact-approval path; each
                    // occurrence keeps its independent approval branch.
                }
                is WorkflowRunOutcome.NeedsUser -> {
                    workflowLedger.markWaiting(occurrence.id,
                        WorkflowOccurrenceState.WAITING_USER,
                        outcome.resumePath, outcome.question)
                }
                is WorkflowRunOutcome.Failed ->
                    workflowLedger.completeOccurrence(occurrence.id, false, outcome.reason)
            }
        } catch (_: ToolTaskStorageException) { }
        refreshWorkflowSettings()
    }

    /**
     * Production routine-dispatch seam (finding 1): routine-grant admission
     * followed by JournaledActionPipeline.executeAttempt. The seam lives in
     * [com.battlesbudz.jarvis.v2.actions.RoutineStepDispatcher] so the
     * regression test drives this exact path; see its KDoc for the claim
     * discipline the test pins.
     */
    private val routineStepDispatcher = com.battlesbudz.jarvis.v2.actions.RoutineStepDispatcher(
        workflowLedger = workflowLedger,
        phoneActionLedger = phoneActionLedger,
        executorFactory = {
            AndroidMobileActionExecutor(appContext,
                canLaunchDirectly = { isActivityVisible() }, onDiagnostic = recordDiagnostic)
        },
        occurrencePipelineFactory = { executor, occurrence -> phoneActionPipeline(executor, occurrence.id) }
    )

    private fun dispatchWorkflowStep(
        occurrence: WorkflowOccurrence,
        request: ActionRequest
    ): ExecutionResult = routineStepDispatcher.dispatch(occurrence, request)

    /**
     * M5: the allowlisted script host installed in production. Scripts run
     * through the isolated JarvisScript interpreter ([runWithInterpreter]);
     * the only effect a script can produce today is appending to the run's
     * output via log(). The allowlist grows only as new host functions are
     * designed and reviewed — never implicitly.
     */
    private fun productionScriptHost(): ScriptHost = ScriptHost.withLog()

    private fun phoneActionPipeline(executor: MobileActionExecutor, occurrenceId: String) =
        JournaledActionPipeline(
            phoneActionLedger,
            executor,
            sourceAccess = ToolSourceAccess(phoneActionLedger),
            capabilityProbe = AndroidToolCapabilityProbe(appContext),
            lockGate = androidLockGate(appContext, privateNotificationAuthorized = {
                val journal = phoneActionLedger.journal()
                journal.occurrences.any { occurrence ->
                    occurrence.id == occurrenceId && occurrence.state == WorkflowOccurrenceState.RUNNING &&
                        occurrence.scheduledForMs <= System.currentTimeMillis() && journal.workflows.any {
                            it.id == occurrence.workflowId && it.version == occurrence.definitionVersion && it.enabled
                        }
                }
            }),
            onJournalChanged = onJournalChanged,
            onStorageFailure = { failure, message -> reportError(message ?: failure.userMessage()) }
        )

    /**
     * Re-arms the occurrence's own alarm slot for a timer resume. The resume
     * time is already persisted on the occurrence (resumeAtMs); the alarm is
     * only the wake-up — the claim is what authorizes the run.
     */
    private fun scheduleWorkflowResume(occurrenceId: String, fireAt: Long) {
        try {
            val scheduler = WorkflowAlarmScheduler(appContext)
            // Reuse the occurrence's own alarm slot for the resume.
            val occurrence = workflowLedger.occurrence(occurrenceId) ?: return
            scheduler.schedule(occurrence.copy(scheduledForMs = fireAt, windowEndMs = fireAt))
        } catch (_: Exception) { }
    }

    private fun describeWorkflowWait(wait: WorkflowWait): String = when (wait) {
        is WorkflowWait.Timer -> "a timer"
        is WorkflowWait.UntilTime -> "a scheduled time"
        is WorkflowWait.Event -> when (wait.kind) {
            WorkflowEventKind.NOTIFICATION -> "a notification"
            WorkflowEventKind.LOCATION -> "arriving at a location"
        }
    }

    /**
     * Evaluate past-due occurrences against current circumstances (D33,
     * T14). Coalesces duplicate missed slots so there is no catch-up
     * duplicate storm; relevant runs are claimed and run, the rest get an
     * honest missed receipt.
     */
    fun evaluateMissedWorkflowRuns() {
        scope.launch(Dispatchers.Default) {
            try {
                val at = System.currentTimeMillis()
                val journal = phoneActionLedger.journal()
                val pastDue = journal.occurrences.filter {
                    it.state == WorkflowOccurrenceState.SCHEDULED && it.scheduledForMs <= at
                }
                if (pastDue.isEmpty()) return@launch
                val (candidates, coalesced) = WorkflowScheduling.coalesceMissed(pastDue)
                for (skipped in coalesced) {
                    workflowLedger.recordMissedEvaluation(skipped.id,
                        MissedRunDecision.Irrelevant(
                            "A later occurrence of the same routine covers this time — skipped to avoid a duplicate run."))
                }
                for (occurrence in candidates) {
                    val definition = workflowLedger.definitionFor(occurrence) ?: continue
                    val trigger = definition.triggers.getOrNull(occurrence.triggerIndex)
                    val decision = WorkflowScheduling.evaluateMissedRun(
                        occurrence, definition,
                        WorkflowScheduling.MissedRunCircumstances(
                            triggerStillValid = trigger !is WorkflowTrigger.Deadline ||
                                trigger.atMs >= at - 86_400_000,
                            // Proxy: an unseen question is unlikely to be
                            // answered while the app is in the background.
                            userActiveRecently = isActivityVisible(),
                            latenessMs = at - occurrence.scheduledForMs))
                    when (decision) {
                        is MissedRunDecision.Relevant -> {
                            val claimed = workflowLedger.claimDueOccurrence(occurrence.id)
                            if (claimed != null) runWorkflowOccurrence(claimed)
                        }
                        else -> workflowLedger.recordMissedEvaluation(occurrence.id, decision)
                    }
                }
                refreshWorkflowSettings()
            } catch (_: ToolTaskStorageException) { }
        }
    }
}
