package com.battlesbudz.jarvis.v2

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.conversation.ConversationActions
import com.battlesbudz.jarvis.v2.conversation.ConversationCallbacks
import com.battlesbudz.jarvis.v2.conversation.ConversationContextPreparation
import com.battlesbudz.jarvis.v2.conversation.ConversationCoordinator
import com.battlesbudz.jarvis.v2.conversation.ConversationDiagnostics
import com.battlesbudz.jarvis.v2.conversation.ConversationGeneration
import com.battlesbudz.jarvis.v2.conversation.ConversationInvocation
import com.battlesbudz.jarvis.v2.conversation.ConversationMemoryAccess
import com.battlesbudz.jarvis.v2.conversation.ConversationModelSession
import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy
import com.battlesbudz.jarvis.v2.conversation.ConversationRecovery
import com.battlesbudz.jarvis.v2.conversation.ConversationReferences
import com.battlesbudz.jarvis.v2.conversation.ConversationRouting
import com.battlesbudz.jarvis.v2.conversation.ConversationSessionState
import com.battlesbudz.jarvis.v2.conversation.openConversationAttachment
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkInput
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarks
import com.battlesbudz.jarvis.v2.diagnostics.ReplyCaptureBenchmark
import com.battlesbudz.jarvis.v2.memory.AndroidMemoryOs
import com.battlesbudz.jarvis.v2.memory.ConversationMemory
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import com.battlesbudz.jarvis.v2.presentation.AgentActivityMonitor
import com.battlesbudz.jarvis.v2.runtime.AcceptedActionConversation
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceActionCoordinator
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceInvocation
import com.battlesbudz.jarvis.v2.runtime.PhoneTaskCoordinator
import com.battlesbudz.jarvis.v2.runtime.RuntimeMemoryCoordinator
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.runtime.WorkflowCoordinator
import com.battlesbudz.jarvis.v2.runtime.turn.AcceptedVoiceFollowupStage
import com.battlesbudz.jarvis.v2.runtime.turn.OrdinaryVoiceReplyStage
import com.battlesbudz.jarvis.v2.runtime.turn.TypedVoiceInputStage
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceCallAccess
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceCallEvents
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceCallState
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceConversationAccess
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceConversationDispatch
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceDialogueContext
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceMemoryAccess
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceMemoryDeliveryOwner
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnFinalizer
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnModelLease
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnObservation
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnPreparation
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnRecognition
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnRequest
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTurnRunner
import com.battlesbudz.jarvis.v2.runtime.turn.VoiceTypedInputOwnership
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-context runtime. The foreground service owns voice execution; UI only observes. */
internal class JarvisRuntime private constructor(context: android.content.Context) : android.content.ContextWrapper(context),
    com.battlesbudz.jarvis.v2.actions.ReminderScheduling {
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val voiceCallState = VoiceCallState()
    private val voiceMemoryDelivery = VoiceMemoryDeliveryOwner()
    private val acceptedActionCoordinator by lazy {
        AcceptedVoiceActionCoordinator(
            scope = runtimeScope,
            acquireModelLease = { modelStore.tryBeginModelOperation() },
            releaseModelLease = { modelStore.endModelOperation() },
            controllerProvider = { voiceSessionController },
            callStoreProvider = { voiceCallStore },
            benchmarkStoreProvider = { pipelineBenchmarkStore },
            newBenchmark = { id, channel -> newPipelineBenchmark(id, channel) },
            finishBenchmarkResources = { finishPipelineResources(it) },
            bindPromptRecorder = { task ->
                val accepted = task.value
                conversationEngine?.onPromptSubmitted = { submitted, audioSize ->
                    diagnosticRecorder.recordInferencePrompt(
                        "acceptedTask=${task.id} replyId=${accepted.replyId} utteranceId=${accepted.utteranceId} " +
                            "callId=${accepted.callId} audioBytes=$audioSize\nsource=${accepted.prompt}\nsubmitted=$submitted")
                }
            },
            recordDiagnostic = { diagnosticRecorder.recordImportant(it) },
            conversation = AcceptedActionConversation { accepted, benchmark, callbacks ->
                runConversationInternal(
                    prompt = accepted.prompt, history = accepted.history, imageUri = null,
                    onToken = {}, onComplete = callbacks.onComplete,
                    onActionResult = callbacks.onActionResult,
                    onLiveInference = callbacks.onLiveInference,
                    onPhonePlanFinished = callbacks.onPhonePlanFinished,
                    frozenActionPlan = accepted.plan, frozenVoiceFinal = true,
                    benchmarkCapture = benchmark)
            })
    }
    private val acceptedVoiceActions get() = acceptedActionCoordinator.queue
    private var activeContinuousActionSession: com.battlesbudz.jarvis.v2.voice.ContinuousActionSession<AcceptedVoiceInvocation>?
        get() = voiceCallState.acceptedSession
        set(value) { voiceCallState.acceptedSession = value }
    internal lateinit var modelStore: ModelStore
    internal var conversationEngine: LiteRtLmEngine? = null
    internal var conversationJob: Job? = null
    internal var conversationCharacters = 0
    // The full transcript and rolling summary live in the app. This flag only
    // describes whether the current native Conversation has received that
    // app-managed context capsule.
    internal var nativeConversationHasContext = false
    internal val shortTermContext = ShortTermConversationContext()
    private val agentActivityMonitor = AgentActivityMonitor()
    internal val agentActivity = agentActivityMonitor.state
    private fun showAgentFailure(conversationId: String) {
        val lease = agentActivityMonitor.beginFailure(conversationId)
        runtimeScope.launch {
            try { kotlinx.coroutines.delay(5_000) } finally { lease.close() }
        }
    }
    internal val referenceGrounding = ReferenceGroundingClient(
        onReadStarted = { publicActivity ->
            val lease = agentActivityMonitor.beginTurnReferences(conversationHistory.current.value.id, publicActivity)
            val finish: () -> Unit = { lease.close() }
            finish
        },
        pdfText = { bytes -> com.battlesbudz.jarvis.v2.ai.ReferencePdfText.read(applicationContext, bytes) },
    )
    internal val factualityVerifier = com.battlesbudz.jarvis.v2.ai.FactualityVerifier()
    internal val turnOrchestrator = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(referenceGrounding)
    internal val promptBuilder = com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder(shortTermContext)
    private val conversationMemory by lazy {
        ConversationMemory(AndroidMemoryOs.get(applicationContext), AndroidMemoryOs.sources(applicationContext))
    }
    private val memoryCoordinator by lazy {
        RuntimeMemoryCoordinator(
            memory = { conversationMemory },
            currentConversationId = { conversationHistory.current.value.id },
            persistHistoryCutoff = { conversationHistory.markMemoryContextCutoff(durable = true) },
            clearSummary = {
                shortTermContext.clear()
                sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).commit()
            },
            readStateToken = { sessionPreferences.getString("approved_memory_context_token", null) },
            persistStateToken = { token ->
                val edit = sessionPreferences.edit()
                if (token == null) edit.remove("approved_memory_context_token")
                else edit.putString("approved_memory_context_token", token)
                edit.commit()
            },
            recordDiagnostic = { diagnosticRecorder.recordImportant(it) })
    }
    internal val memoryDeliveryFence get() = memoryCoordinator.deliveryFence
    internal var nativeMemoryStateToken: String?
        get() = memoryCoordinator.stateToken
        set(value) { memoryCoordinator.stateToken = value }
    internal val actionIntentRouter = com.battlesbudz.jarvis.v2.actions.ActionIntentRouter()
    /**
     * M1d explicit silent work (D21/T05). [com.battlesbudz.jarvis.v2.voice.ContinuousActionSession]
     * classifies final captures through this controller: ordinary speech is ignored while silent,
     * the wake phrase reopens conversation, and a required question may temporarily open an
     * answer window. Tasks continue unaffected in every case.
     */
    internal val silentWork = com.battlesbudz.jarvis.v2.voice.SilentWorkController()

    /** Puts Jarvis into (or out of) explicit silent work. Returns true when the mode changed. */
    fun setSilentWork(enabled: Boolean): Boolean {
        val changed = if (enabled) silentWork.enterSilentWork() else silentWork.exitSilentWork()
        if (changed) {
            silentWorkState.value = silentWork.isSilent
            diagnosticRecorder.recordImportant("Silent work ${if (enabled) "entered" else "exited"} by user request.")
        }
        return changed
    }

    /** Observable silent-work posture for the UI toggle. */
    val silentWorkState = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Shared durable store for the phone-task journal and the workflow ledger. */
    private val durableTaskRecovery = com.battlesbudz.jarvis.v2.runtime.DurableTaskRecovery()
    internal val taskRecoveryReady get() = durableTaskRecovery.ready
    private val phoneActionStore by lazy {
        com.battlesbudz.jarvis.v2.actions.FileToolTaskStore(java.io.File(noBackupFilesDir, "phone-action-attempts.json"))
    }
    private val phoneTaskCoordinator by lazy {
        PhoneTaskCoordinator(
            scope = runtimeScope,
            appContext = this,
            ledgerFactory = {
                com.battlesbudz.jarvis.v2.actions.ToolTaskLedger(phoneActionStore)
            },
            isDeviceLocked = { getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true },
            createExecutor = {
                AndroidMobileActionExecutor(this, canLaunchDirectly = { activityVisible },
                    onDiagnostic = diagnosticRecorder::recordImportant)
            },
            conversationExists = { id -> conversationHistory.list().any { it.id == id } },
            projectReply = { conversationId, replyId, text, receipts ->
                conversationHistory.updateReply(conversationId, replyId, text, true, receipts)
            },
            silentWork = silentWork,
            recordDiagnostic = diagnosticRecorder::recordImportant)
    }
    internal val phoneTasks get() = phoneTaskCoordinator.tasks
    internal val phoneTaskError get() = phoneTaskCoordinator.error
    internal fun admitPhoneTask(plan: ActionTurnPlan.Ready, conversationId: String): String? =
        phoneTaskCoordinator.admitPhoneTask(plan, conversationId)
    internal fun refreshPhoneTasks() = phoneTaskCoordinator.refreshPhoneTasks()
    internal fun cancelPhoneTask(groupId: String) = phoneTaskCoordinator.cancelPhoneTask(groupId)
    internal fun executePhoneAction(
        request: com.battlesbudz.jarvis.v2.actions.ActionRequest,
        executor: com.battlesbudz.jarvis.v2.actions.MobileActionExecutor,
        groupId: String? = null,
        stepIndex: Int = 0
    ): com.battlesbudz.jarvis.v2.actions.ExecutionResult =
        phoneTaskCoordinator.executePhoneAction(request, executor, groupId, stepIndex)
    internal fun resumePhoneTasksAfterUnlock() = phoneTaskCoordinator.resumePhoneTasksAfterUnlock()
    internal fun phoneTaskAction(id: String, generation: Long, command: String) =
        phoneTaskCoordinator.phoneTaskAction(id, generation, command)
    /**
     * M1d: a model-proposed screen mutation never auto-dispatches (D23). Park
     * it in the ledger awaiting the user's explicit approval.
     */
    internal fun parkScreenTaskForApproval(request: com.battlesbudz.jarvis.v2.actions.ActionRequest):
        com.battlesbudz.jarvis.v2.actions.ExecutionResult =
        phoneTaskCoordinator.parkScreenTaskForApproval(request, conversationHistory.current.value.id)
    // -- M2 reusable workflows / M3 ecosystem providers / reminders --------
    private val workflowCoordinator by lazy {
        WorkflowCoordinator(
            scope = runtimeScope,
            appContext = this,
            taskStore = phoneActionStore,
            isActivityVisible = { activityVisible },
            recordDiagnostic = diagnosticRecorder::recordImportant,
            reportError = { message -> mainHandler.post { phoneTaskCoordinator.reportError(message) }; Unit },
            // Workflow dispatch runs off Main. Snapshot its already-durable boundaries on
            // the existing presentation dispatcher; observation never resumes a task.
            onJournalChanged = { mainHandler.post { phoneTaskCoordinator.refreshPhoneTasks() }; Unit })
    }
    /** Settings projection: saved workflows plus connected tools. Chat stays the operating surface. */
    internal val workflowSettings get() = workflowCoordinator.workflowSettings
    internal fun refreshWorkflowSettings() = workflowCoordinator.refreshWorkflowSettings()
    /** Settings toggle: explicit enable/disable; disabling pauses affected unfinished work (D17). */
    internal fun setWorkflowEnabled(id: String, enabled: Boolean) =
        workflowCoordinator.setWorkflowEnabled(id, enabled)
    /**
     * Alarm fire: claim the occurrence atomically, then run it. Redeliveries
     * find it claimed and stop. Called by [com.battlesbudz.jarvis.v2.actions.WorkflowScheduleReceiver]
     * and the reminder coordinator; the signature is a compatibility boundary.
     */
    internal fun onWorkflowAlarm(occurrenceId: String, done: () -> Unit) {
        if (!durableTaskRecovery.runWhenReady { workflowCoordinator.onWorkflowAlarm(occurrenceId, done) }) {
            phoneTaskCoordinator.reportError("Saved tasks could not be recovered. Workflow actions remain paused; restart after the journal problem is resolved.")
            done()
        }
    }
    /** Evaluate past-due occurrences against current circumstances (D33, T14). */
    internal fun evaluateMissedWorkflowRuns() {
        durableTaskRecovery.runWhenReady { workflowCoordinator.evaluateMissedWorkflowRuns() }
    }
    /** Guided MCP setup from settings (D07). */
    internal fun connectMcpServer(name: String, url: String, token: String, done: (String) -> Unit) =
        workflowCoordinator.connectMcpServer(name, url, token, done)
    internal val providerRegistry get() = workflowCoordinator.providerRegistry
    internal val mcpRegistry get() = workflowCoordinator.mcpRegistry
    internal val appFunctionPlatformStatus get() = workflowCoordinator.appFunctionPlatformStatus
    // ReminderScheduling: the Android executor reaches the reminder
    // coordinator through the runtime as its context.
    override fun createReminder(message: String, atMs: Long): com.battlesbudz.jarvis.v2.actions.ExecutionResult =
        workflowCoordinator.createReminder(message, atMs)
    override fun describeSchedule(): com.battlesbudz.jarvis.v2.actions.ExecutionResult =
        workflowCoordinator.describeSchedule()
    internal lateinit var sessionPreferences: android.content.SharedPreferences
    internal lateinit var pipelineBenchmarkStore: com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
    internal lateinit var diagnosticRecorder: com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
    internal val conversationHistory = com.battlesbudz.jarvis.v2.chat.ConversationHistory(
        getSharedPreferences("conversations", MODE_PRIVATE))
    internal val chatBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal lateinit var voiceCallStore: com.battlesbudz.jarvis.v2.voice.VoiceCallStore
    internal lateinit var voiceSessionController: VoiceSessionController
    internal lateinit var ttsComparisonStore: com.battlesbudz.jarvis.v2.voice.TtsComparisonStore
    internal lateinit var ttsModels: com.battlesbudz.jarvis.v2.voice.TtsModelStore
    internal val voicePlayback get() = voiceCallState.playback
    internal lateinit var asrComparisonStore: com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
    internal var activeVoiceCapture: AudioTurnCapture?
        get() = voiceCallState.capture
        set(value) { voiceCallState.capture = value }
    internal var voiceTurnJob: Job?
        get() = voiceCallState.turnJob
        set(value) { voiceCallState.turnJob = value }
    internal var audioRecoveryAttempts: Int
        get() = voiceCallState.audioRecoveryAttempts
        set(value) { voiceCallState.audioRecoveryAttempts = value }
    internal val returnToWakeCuePending get() = voiceCallState.returnToWakeCuePending
    internal var voiceSessionArmed: Boolean
        get() = voiceCallState.armed
        set(value) { voiceCallState.armed = value }
    @Volatile internal var sessionReport: (String) -> Unit = {}
    internal var activeVoiceOutput: PiperVoiceOutput?
        get() = voiceCallState.output
        set(value) { voiceCallState.output = value }
    init {
        modelStore = ModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
        nativeMemoryStateToken = sessionPreferences.getString("approved_memory_context_token", null)
        voiceCallStore = com.battlesbudz.jarvis.v2.voice.CoalescingVoiceCallStore(
            com.battlesbudz.jarvis.v2.chat.ConversationVoiceCallStore(
                SharedPreferencesVoiceCallStore(getSharedPreferences("voice_calls", MODE_PRIVATE)), conversationHistory),
            onFailure = { diagnosticRecorder.recordImportant("Voice checkpoint failed: ${it.javaClass.simpleName}") })
        voiceSessionController = VoiceSessionController(voiceCallStore)
        // Video capture is call-scoped: every new call segment starts it for
        // its own identity, and finishVoiceCall ends it for the ended call.
        // A spoken farewell therefore ends capture while wake listening stays
        // armed; only the owning call's identity can stop its capture.
        voiceSessionController.onCallBegan = { callId ->
            com.battlesbudz.jarvis.v2.voice.VideoCallService.startCapture(
                this, callId, voiceSessionController::currentCallId)
        }
        asrComparisonStore = com.battlesbudz.jarvis.v2.voice.AsrComparisonStore(getSharedPreferences("asr_comparison", MODE_PRIVATE))
        ttsComparisonStore = com.battlesbudz.jarvis.v2.voice.TtsComparisonStore(getSharedPreferences("tts_comparison", MODE_PRIVATE))
        ttsModels = com.battlesbudz.jarvis.v2.voice.TtsModelStore(applicationContext)
        val installedPackage = packageManager.getPackageInfo(packageName, 0)
        diagnosticRecorder = com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder(sessionPreferences,
            "${installedPackage.versionName} (${installedPackage.longVersionCode})")
        pipelineBenchmarkStore = com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore(applicationContext)
        diagnosticRecorder.restore()
        diagnosticRecorder.recordPreviousProcessExit(applicationContext)
        // Construction is the publication barrier: no cold alarm sees this runtime until
        // both durable recovery passes finish, and neither pass starts new task work.
        if (durableTaskRecovery.recover(phoneTaskCoordinator::recoverAfterRestart, workflowCoordinator::recoverAfterRestart)) {
            phoneTaskCoordinator.resumePhoneTasksAfterUnlock()
        }
        // M3 ecosystem providers (D05/D07, T16/T17): seed the T08 scope
        // resolver before any provider grant is recorded, and probe the
        // AppFunctions platform once with ordinary app access.
        workflowCoordinator.seedProviderScopeResolver()
        workflowCoordinator.probeAppFunctionPlatform()
        shortTermContext.restoreSummary(sessionPreferences.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, null))
        AndroidMemoryOs.get(applicationContext).addApprovedStateObserver {
            // Fence output immediately, then durably publish the context boundary off the caller
            // thread. Advancing the epoch last prevents a fresh packet being paired with history
            // that still contains an erased/corrected fact.
            memoryCoordinator.beginMemoryBoundary()
            pipelineBenchmarkStore.clearHypotheses()
            memoryDeliveryFence.invalidate()
            voiceMemoryDelivery.revoke(runtimeScope)
            // Production mutation calls are already owned IO. If a future caller invokes the
            // observer on main, fail closed until this owned IO boundary is durable.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runtimeScope.launch(Dispatchers.IO) { memoryCoordinator.publishMemoryContextBoundary() }
            } else memoryCoordinator.publishMemoryContextBoundary()
        }
        acceptedActionCoordinator.observeTerminalReports()
        runtimeScope.launch {
            for (control in com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.controls) {
                if (!voiceSessionArmed) continue
                val ui = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
                if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.RESUME) ui.paused.value = false
                else {
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE) ui.paused.value = true
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE ||
                        control == com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) {
                        com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
                        runtimeScope.launch { callResources.closeMicrophone() }
                    }
                    activeVoiceOutput?.stopSpeaking()
                    val acceptedMode = acceptedVoiceActions.hasUnfinished() ||
                        (activeContinuousActionSession?.pendingReportCount() ?: 0) > 0
                    if (acceptedMode && control == com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY) {
                        activeContinuousActionSession?.interruptDelivery()
                        diagnosticRecorder.recordImportant("UI STOP_REPLY detached accepted delivery; ASR/action worker retained")
                    } else if (acceptedMode && control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE) {
                        // The pump remains the call owner and waits for Resume; it must not
                        // reenter ordinary runVoiceTurn while its accepted batch is active.
                        diagnosticRecorder.recordImportant("UI PAUSE suspended accepted capture; worker/report obligations retained")
                    } else voiceTurnJob?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(control))
                }
            }
        }
    }
    @Volatile internal var activityVisible = false
    @Volatile internal var lastPhoneActionStatus: com.battlesbudz.jarvis.v2.actions.PhoneActionStatus? = null
    @Volatile private var transcriptListener: (String, String, Boolean) -> Unit = { _, _, _ -> }
    @Volatile private var finishedListener: (String) -> Unit = {}
    fun attachUi(report: (String) -> Unit, transcript: (String, String, Boolean) -> Unit, finished: (String) -> Unit) {
        sessionReport = report; transcriptListener = transcript; finishedListener = finished
    }
    fun detachUi() {
        sessionReport = {}; transcriptListener = { _, _, _ -> }; finishedListener = {}
    }
    fun arm() {
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.clearFailure(conversationHistory.current.value.id)
        audioRecoveryAttempts = 0
        returnToWakeCuePending.set(false)
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
        voiceSessionArmed = true
        // The session now exists until a true stop: the End-call button gates
        // on this, not on the armed flag, which can drop during the
        // farewell -> "Waiting for Hey Jarvis" phase while the session lives.
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.sessionAlive.value = true
        startVoiceDiagnostics("Jarvis session — awaiting wake word")
    }
    fun sendChat(text: String, attachment: com.battlesbudz.jarvis.v2.chat.ChatAttachment? = null): String? {
        if (text.isBlank() && attachment == null) return "Write a message first."
        if (attachment != null && !com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.accepts(modelStore.selectedModel(), attachment.kind))
            return "The selected download does not support this attachment. Choose a compatible model or remove it."
        val userText = text.trim().ifBlank { if (attachment?.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO)
            "Respond to this voice message." else "Describe this image." }
        if (text.length > ConversationPolicy.MAX_USER_PROMPT_CHARS) return "That message is too long. Please send it in smaller parts."
        if (voiceSessionArmed) {
            if (attachment != null) return "Attachments are unavailable while a Voice Call is active."
            val callId = voiceSessionController.currentCallId() ?: return "Voice Call is waiting for its wake word; keep this draft until the call is listening."
            val queued = com.battlesbudz.jarvis.v2.voice.CallFinalInput(java.util.UUID.randomUUID().toString(), callId,
                conversationHistory.current.value.id, userText, System.currentTimeMillis())
            val priorityControl = com.battlesbudz.jarvis.v2.voice.VoiceActionControl.parse(userText, hasUnfinished = true) !=
                com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None
            return when (callInputQueue.offer(queued, callId, priorityControl)) {
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Queued -> { runVoiceTurn(); null }
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Full -> "Voice Call input queue is full. Wait for the current turn."
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Ended -> "Voice Call ended before that message was accepted."
            }
        }
        if (chatBusy.value || voiceTurnJob?.isCompleted == false ||
            conversationJob?.isCompleted == false || acceptedVoiceActions.hasUnfinished() ||
                ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
            return "Wait for the current response or voice session to finish."
        if (!modelStore.isUsable() || !modelStore.smokeTestPassed()) return "Set up and test a model first."
        val history = conversationHistory.context()
        val threadId = conversationHistory.current.value.id
        val replyId = java.util.UUID.randomUUID().toString()
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.beginLiveMetrics(
            com.battlesbudz.jarvis.v2.voice.LiveReplyMetrics(replyId, threadId))
        val userMessageId = conversationHistory.appendUser(userText, attachment)
        val capturedAtMs = System.currentTimeMillis()
        conversationHistory.updateReply(threadId, replyId, "", false)
        chatBusy.value = true
        runtimeScope.launch {
            val response = StringBuilder()
            try {
                withContext(Dispatchers.IO) {
                    captureFinalMemory(userMessageId, threadId, null,
                        if (attachment?.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO) ConversationMemorySource.VOICE else ConversationMemorySource.TEXT,
                        userText, capturedAtMs)
                }
                // Mode changes may leave a native voice session behind. App history is authoritative.
                shortTermContext.clear()
                resetNativeConversation()
                runConversationInternal(userText, history,
                    attachment?.takeIf { it.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE }?.let { android.net.Uri.parse(it.uri) },
                    replyIdentity = replyId, conversationIdentity = threadId,
                    audioUri = attachment?.takeIf { it.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO }?.let { android.net.Uri.parse(it.uri) },
                    onToken = { token -> synchronized(response) {
                        response.append(token)
                        conversationHistory.updateReply(threadId, replyId, response.toString(), false)
                    } },
                    onComplete = { answer ->
                        conversationHistory.updateReply(threadId, replyId, answer, true)
                    },
                    onActionResult = { name, message, succeeded ->
                        conversationHistory.recordReplyAction(threadId, replyId,
                            com.battlesbudz.jarvis.v2.chat.ActionReceipt(name, message, succeeded))
                    }, onLiveInference = { submittedAt, firstTokenAt, tokensPerSecond, durable ->
                        conversationHistory.updateReplyMetrics(threadId, replyId, durable = durable) { current ->
                            var updated = current
                            submittedAt?.let { updated = updated.submitted(it) }
                            firstTokenAt?.let { updated = updated.firstRawToken(it) }
                            if (tokensPerSecond != null && tokensPerSecond.isFinite())
                                updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                            updated
                        }
                        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.updateLiveMetrics(replyId, threadId) { metrics ->
                            var updated = metrics
                            submittedAt?.let { updated = updated.submitted(it) }
                            firstTokenAt?.let { updated = updated.firstText(it) }
                            if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                            updated
                        }
                    })
                conversationJob?.join()
            } catch (error: Exception) {
                conversationHistory.updateReply(threadId, replyId,
                    response.toString().ifBlank { "The response was interrupted. Please try again." }, false)
            } finally {
                // Completion is posted before this callback on the same main queue.
                mainHandler.post { chatBusy.value = false }
            }
        }
        return null
    }

    fun selectConversation(id: String? = null): String? {
        if (chatBusy.value || voiceSessionArmed || voiceTurnJob?.isCompleted == false ||
            acceptedVoiceActions.hasUnfinished() || ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
            return "Finish the current response or voice session first."
        if (id == null) conversationHistory.newConversation() else conversationHistory.select(id)
        shortTermContext.clear()
        turnOrchestrator.reset()
        nativeConversationHasContext = false
        sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
        return null
    }

    private val runtimeVoiceResources by lazy {
        RuntimeVoiceResources(applicationContext, runtimeScope,
            recordDiagnostic = diagnosticRecorder::recordImportant,
            onLevel = { com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.level.value = it })
    }
    private val callResources get() = runtimeVoiceResources.resources
    private val latestStatus get() = voiceCallState.latestStatus
    private val pendingVoiceCorrection get() = voiceCallState.pendingVoiceCorrection
    private val callInputQueue get() = voiceCallState.inputQueue
    private val pendingTypedHandoff get() = voiceCallState.pendingTypedHandoff
    private val preparingTypedInput get() = voiceCallState.preparingTypedInput
    private val resumeCommandCue get() = voiceCallState.resumeCommandCue
    internal fun freshMemoryTurnContext(query: String, maxChars: Int): MemoryTurnContext? =
        memoryCoordinator.freshMemoryTurnContext(query, maxChars)
    internal fun adoptMemoryState(context: MemoryTurnContext): Boolean = memoryCoordinator.adoptMemoryState(context)
    internal fun isMemoryTurnCurrent(context: MemoryTurnContext): Boolean = memoryCoordinator.isMemoryTurnCurrent(context)
    internal fun consumeMemoryHistoryCutoff(): Boolean = memoryCoordinator.consumeMemoryHistoryCutoff()
    private fun captureFinalMemory(eventId: String, conversationId: String, callId: String?,
                                   source: ConversationMemorySource, text: String, capturedAtMs: Long): Boolean =
        memoryCoordinator.captureFinalMemory(eventId, conversationId, callId, source, text, capturedAtMs)
    internal fun takeMemoryCaptureReceipt(text: String): com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult? =
        memoryCoordinator.takeMemoryCaptureReceipt(text)

    fun onMicrophoneInterruption(interrupted: Boolean, reason: String) {
        activeVoiceOutput?.setInterrupted(interrupted)
        if (interrupted) {
            activeVoiceCapture?.yieldMicrophone()
            runtimeScope.launch { callResources.closeMicrophone(com.battlesbudz.jarvis.v2.voice.MicrophoneBusyException()) }
        }
        if (interrupted) runtimeScope.launch(Dispatchers.IO) {
            runCatching { voiceSessionController.flushCheckpoint() }
                .onFailure { diagnosticRecorder.recordImportant("Voice checkpoint handoff flush failed: ${it.javaClass.simpleName}") }
        }
        diagnosticRecorder.recordImportant("Microphone ${if (interrupted) "suspended" else "available"}: call=${voiceSessionController.currentCallId()} phase=$latestStatus $reason")
        if (!voiceSessionArmed) return
        if (interrupted) {
            if (voiceSessionController.currentCallId() == null) returnToWakeCuePending.set(true)
            else resumeCommandCue.set(true)
            com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus("Paused — another app has microphone priority; session retained.")
        } else if (!com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
            com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus(latestStatus)
        }
    }
    /** Shared native state is read only under the existing exclusive conversation/model lease. */
    private val nativeSessionState = object : ConversationSessionState {
        override var engine: LiteRtLmEngine?
            get() = conversationEngine
            set(value) { conversationEngine = value }
        override var hasContext: Boolean
            get() = nativeConversationHasContext
            set(value) { nativeConversationHasContext = value }
        override var characters: Int
            get() = conversationCharacters
            set(value) { conversationCharacters = value }
    }
    internal val pipelineBenchmarks by lazy {
        PipelineBenchmarks(inputs = {
            val selected = modelStore.selectedModel()
            PipelineBenchmarkInput(selected, modelStore.fileFor(selected).length(), conversationHistory.current.value.id)
        }, store = pipelineBenchmarkStore, batteryPercent = {
            getSystemService(android.os.BatteryManager::class.java)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        })
    }
    internal val replyCaptureBenchmark by lazy {
        ReplyCaptureBenchmark(applicationContext, pipelineBenchmarks, pipelineBenchmarkStore,
            currentCallId = { voiceSessionController.currentCallId() }, onFailure = diagnosticRecorder::recordImportant)
    }
    internal fun newPipelineBenchmark(turnId: String, channel: String,
        asr: com.battlesbudz.jarvis.v2.voice.AsrEngine? = null, tts: com.battlesbudz.jarvis.v2.voice.TtsEngine? = null) =
        pipelineBenchmarks.create(turnId, channel, asr, tts)
    internal fun finishPipelineResources(capture: PipelineBenchmarkCapture) = pipelineBenchmarks.finishResources(capture)

    private val conversationCoordinator by lazy {
        val diagnostics = ConversationDiagnostics(diagnosticRecorder::record, diagnosticRecorder::recordImportant,
            diagnosticRecorder::recordSummary, diagnosticRecorder::recordInferencePrompt)
        val models = ConversationModelSession(nativeSessionState, modelStore::selectedModel,
            modelStore::tryBeginModelOperation, modelStore::endModelOperation, modelStore::verifyIntegrity,
            { modelStore.fileFor(it).path }, cacheDir.path, shortTermContext,
            { sessionPreferences.edit().putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, it).apply() })
        val memory = object : ConversationMemoryAccess {
            override val deliveryFence get() = memoryDeliveryFence
            override fun approvedSnapshot(query: String, maxChars: Int) = freshMemoryTurnContext(query, maxChars)
            override fun adopt(context: MemoryTurnContext) = adoptMemoryState(context)
            override fun clearNativeToken() { nativeMemoryStateToken = null }
            override fun isCurrent(context: MemoryTurnContext) = isMemoryTurnCurrent(context)
            override fun consumeHistoryCutoff() = consumeMemoryHistoryCutoff()
            override fun takeCaptureReceipt(prompt: String) = takeMemoryCaptureReceipt(prompt)
        }
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String) = referenceGrounding.fetchIfRequested(query)?.context
            override fun isInsufficientAnswer(answer: String) = referenceGrounding.isInsufficientAnswer(answer)
        }
        val history = { conversationHistory.contextAfterMemoryCutoff() }
        ConversationCoordinator(runtimeScope, { mainHandler.post(it) }, { conversationHistory.current.value.id }, models,
            ConversationRouting(history, turnOrchestrator, models, { lastPhoneActionStatus },
                { lastPhoneActionStatus = it }, diagnostics),
            ConversationContextPreparation(history, memory, turnOrchestrator, models, references, diagnostics),
            ConversationGeneration(models, references, promptBuilder, { uri ->
                openConversationAttachment(
                    primary = { contentResolver.openInputStream(uri) },
                    fallback = { contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream() })
            }, diagnostics,
                // M1d: model-proposed screen mutations never auto-dispatch
                // (D23); park them for the user's explicit approval.
                onNeedsApproval = { request ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        parkScreenTaskForApproval(request)
                    }
                }),
            ConversationRecovery(models::reset, references, factualityVerifier, turnOrchestrator::automaticFallbackQuery, diagnostics),
            promptBuilder, { prompt, entries -> actionIntentRouter.classifyActionIntent(prompt, entries) != null },
            turnOrchestrator::recordResponse,
            createActions = { id, onResult -> ConversationActions(
                executor = { AndroidMobileActionExecutor(this, canLaunchDirectly = { activityVisible },
                    onDiagnostic = diagnosticRecorder::recordImportant) },
                admit = ::admitPhoneTask, execute = ::executePhoneAction, cancelUnfinished = ::cancelPhoneTask,
                conversationId = id, onActionResult = onResult) },
            createBenchmark = { id, channel -> pipelineBenchmarks.create(id, channel) },
            finishOwnedBenchmark = { capture, outcome, callId, failure ->
                pipelineBenchmarks.finishResources(capture)
                capture.finish(outcome, callId = callId, failureCode = failure)?.let { pipelineBenchmarkStore.append(it) }
            }, diagnostics = diagnostics,
            onTurnStarted = agentActivityMonitor::clearFailure, onTurnFailure = ::showAgentFailure,
            beginActivity = { id ->
                val lease = agentActivityMonitor.beginWork(id)
                object : com.battlesbudz.jarvis.v2.conversation.ConversationActivity {
                    override fun progress(publicBlurb: String, sequence: Long) = lease.progress(publicBlurb, sequence)
                    override fun close() = lease.close()
                }
            })
    }
    private val voiceTurns: VoiceTurnRunner by lazy {
        val call = VoiceCallAccess(voiceCallState, voiceSessionController, VoiceCallEvents(
            post = { mainHandler.post(it) }, report = { sessionReport(it) },
            serviceStatus = com.battlesbudz.jarvis.v2.voice.VoiceCallService::updateStatus,
            transcript = { role, text, complete ->
                if (role == "You") com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.liveTranscript.value = if (complete) "" else text
                transcriptListener(role, text, complete)
            }, finished = { finishedListener(it) }, startDiagnostics = ::startVoiceDiagnostics,
            endCall = { endVoiceCall() }, returnToWake = ::returnToWakeListening,
            stopService = ::stopVoiceService, restartTurn = ::runVoiceTurn))
        val conversation = VoiceConversationAccess(VoiceConversationDispatch(::startConversation),
            currentJob = { conversationJob }, resetConversation = ::resetNativeConversation,
            previewNativeAudioPrompt = conversationCoordinator::previewNativeAudioPrompt)
        val memory = VoiceMemoryAccess(memoryDeliveryFence, voiceMemoryDelivery, ::captureFinalMemory)
        val typedInputs = VoiceTypedInputOwnership(call)
        val createLease = { VoiceTurnModelLease(modelStore::tryBeginModelOperation, modelStore::endModelOperation) }
        VoiceTurnRunner(runtimeScope, call, acceptedActionCoordinator, turnOrchestrator,
            historyAfterCutoff = { conversationHistory.contextAfterMemoryCutoff() },
            selectRequest = { typed ->
                val comparison = com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.take(java.util.UUID.randomUUID().toString())
                val inputMode = com.battlesbudz.jarvis.v2.voice.VoiceInputMode.selected(applicationContext)
                val captionEngine = com.battlesbudz.jarvis.v2.voice.VoiceInputMode.captionEngine(applicationContext)
                val direct = comparison?.request?.path == com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT ||
                    (comparison == null && typed == null && inputMode == com.battlesbudz.jarvis.v2.voice.VoiceInputMode.GEMMA_AUDIO)
                val recognizer = com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.usesRecognizer(
                    direct, captionEngine != null, comparison?.request?.path?.usesAudio == true)
                val asr = comparison?.request?.path?.captureEngine ?: if (direct) captionEngine ?: com.battlesbudz.jarvis.v2.voice.AsrEngine.WHISPER
                    else com.battlesbudz.jarvis.v2.voice.AsrEngine.selected(applicationContext)
                VoiceTurnRequest(typed, comparison, direct, recognizer, asr, ttsComparisonStore.selectedEngine(),
                    comparison?.id ?: java.util.UUID.randomUUID().toString())
            }, createObservation = { request ->
                VoiceTurnObservation(applicationContext, request, conversationHistory.current.value.id,
                    pipelineBenchmarkStore, pipelineBenchmarks, diagnosticRecorder, asrComparisonStore, ttsComparisonStore, voiceCallStore)
            }, createModelLease = createLease,
            typedStage = TypedVoiceInputStage(runtimeScope, call, conversation, conversationHistory, memory, createLease,
                modelStore::isModelOperationActive, diagnosticRecorder), typedInputs = typedInputs,
            preparation = VoiceTurnPreparation(applicationContext, call, nativeSessionState, conversation, modelStore,
                ttsModels, runtimeVoiceResources, conversationHistory,
                VoiceDialogueContext(shortTermContext, turnOrchestrator, sessionPreferences, diagnosticRecorder::recordImportant),
                promptBuilder, memory, diagnosticRecorder)::prepare,
            recognition = VoiceTurnRecognition(call, conversation, runtimeVoiceResources, conversationHistory, memory,
                turnOrchestrator, diagnosticRecorder, asrComparisonStore),
            acceptedReplies = AcceptedVoiceFollowupStage(call, runtimeScope, acceptedActionCoordinator,
                replyCaptureBenchmark, pipelineBenchmarks, runtimeVoiceResources, conversationHistory, memory, turnOrchestrator, diagnosticRecorder,
                silentWork = silentWork, onSilentWorkExit = { silentWorkState.value = false }),
            ordinaryReplies = OrdinaryVoiceReplyStage(call, runtimeScope, conversation, replyCaptureBenchmark,
                runtimeVoiceResources, conversationHistory, memory, turnOrchestrator, diagnosticRecorder, asrComparisonStore),
            finalizer = VoiceTurnFinalizer(call, conversation, nativeSessionState, acceptedActionCoordinator,
                runtimeVoiceResources, memory, turnOrchestrator, typedInputs, diagnosticRecorder), diagnosticRecorder = diagnosticRecorder,
            currentConversationId = { conversationHistory.current.value.id },
            onTurnStarted = agentActivityMonitor::clearFailure,
            onTerminalFailure = ::showAgentFailure)
    }

    internal fun runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        incrementalVoice: com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput? = null,
        voiceAudio: ByteArray? = null,
        voiceAudioIsComplete: Boolean = true,
        /** Original audio is authoritative; caption text must never route tools or lookup. */
        directVoiceAudio: Boolean = false,
        replyIdentity: String? = null,
        conversationIdentity: String? = null,
        callIdentity: String? = null,
        comparison: com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Trial? = null,
        onLatency: (com.battlesbudz.jarvis.v2.diagnostics.TurnLatency) -> Unit = {},
        onLiveInference: (submittedAtMs: Long?, firstTokenAtMs: Long?, estimatedTokensPerSecond: Double?, durable: Boolean) -> Unit = { _, _, _, _ -> },
        onActionResult: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onPhonePlanFinished: (com.battlesbudz.jarvis.v2.actions.ActionTurnRunner.Outcome) -> Unit = {},
        audioUri: Uri? = null,
        /** A queue admission freezes authorization before it waits for native ownership. */
        frozenActionPlan: com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready? = null,
        /** Frozen final ASR still requires the same source-clause guard as attached voice input. */
        frozenVoiceFinal: Boolean = false,
        /** The call owner holds the one model lease and joins this invocation before restart. */
        callOwned: Boolean = false,
        /** Binds an ordinary answer's exact voice output to its mutation/expiry delivery ticket. */
        onMemoryBound: (com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence.Ticket, MemoryTurnContext) -> Unit = { _, _ -> },
        benchmarkCapture: com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture? = null
    ): Job? {
        return startConversation(
            ConversationInvocation(prompt, history, imageUri, incrementalVoice, voiceAudio, voiceAudioIsComplete,
                directVoiceAudio, replyIdentity, conversationIdentity, callIdentity, comparison, audioUri,
                frozenActionPlan, frozenVoiceFinal, callOwned, benchmarkCapture),
            ConversationCallbacks(onToken, onComplete, onLatency, onLiveInference, onActionResult, onPhonePlanFinished, onMemoryBound))
    }

    private fun startConversation(input: ConversationInvocation, callbacks: ConversationCallbacks): Job? =
        conversationCoordinator.start(input, callbacks).also { if (it != null) conversationJob = it }

    fun runVoiceTurn(): Unit = voiceTurns.start()

    fun onServiceStopped() {
        endVoiceCall()
        val previousVoice = voiceTurnJob
        val previousConversation = conversationJob
        runtimeScope.launch {
            previousVoice?.join()
            previousConversation?.join()
            acceptedVoiceActions.awaitIdle()
            if (!voiceSessionArmed && ConversationWork.activeJobs.get() == 0 && modelStore.tryBeginModelOperation()) {
                try {
                    conversationEngine?.close()
                    conversationEngine = null
                    nativeConversationHasContext = false
                } finally { modelStore.endModelOperation() }
            }
        }
    }
    fun endVoiceCall(report: (String) -> Unit = sessionReport) {
        finishVoiceCall(stopSession = true, report = report)
    }

    /** A final farewell ends this call segment; the user-armed wake session stays alive. */
    internal fun returnToWakeListening(expectedCallId: String) {
        if (!voiceSessionArmed || voiceSessionController.currentCallId() != expectedCallId) return
        finishVoiceCall(stopSession = false, report = sessionReport)
    }

    private fun finishVoiceCall(stopSession: Boolean, report: (String) -> Unit) {
        com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.cancelPending()
        if (!voiceSessionArmed && voiceTurnJob?.isActive != true) return
        // Ending a call must also release an armed microphone turn. Otherwise
        // the capture coroutine can survive the UI transition and the next
        // Voice Call cannot acquire the microphone.
        diagnosticRecorder.recordImportant(if (stopSession) "Session stop requested by UI or foreground service."
            else "Voice call ended by farewell; wake session retained. Rearm follows turn cleanup.")
        returnToWakeCuePending.set(!stopSession)
        resumeCommandCue.set(false)
        val endedCallId = voiceSessionController.currentCallId()
        // Typed-turn/farewell paths can bypass the voice-turn finalizer. Revoke
        // only this exact ended call, without waiting for the shadow native worker.
        runtimeVoiceResources.smartTurn.closeCall(endedCallId)
        // The ended call's video capture stops here — capture never survives
        // into passive wake listening. Only the ended call's identity can
        // stop its capture: a stale farewell for an older call is a no-op
        // against a newer call's capture (see CallVisionController). The
        // farewell ends capture outside the video service's command path, so
        // route the new state into its notification update path — otherwise
        // the notification keeps reading "Video on" after the controller is
        // IDLE.
        endedCallId?.let { callId ->
            runCatching {
                com.battlesbudz.jarvis.v2.voice.VideoCallService
                    .refreshVideoStatusAfterFarewell(callId)
            }
        }
        // Close the shared queue gate before inspecting deferred handoffs. A promotion either
        // completed its transfer before this drain, or sees End and cannot create a new handoff.
        callInputQueue.end(endedCallId ?: "").forEach { input ->
            diagnosticRecorder.recordImportant("Typed Voice Call input not processed because call ended: event=${input.id}")
            runCatching { voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                "Cancelled before processing typed message: ${input.text}") }
        }
        pendingVoiceCorrection.getAndSet(null)?.let { deferred ->
            runCatching { voiceSessionController.appendTranscript("Jarvis", "Cancelled before processing voice follow-up: ${deferred.transcript}") }
        }
        activeContinuousActionSession?.cancelDeferredConversation()?.let { deferred ->
            if (deferred.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                endedCallId?.let { callId -> runCatching { voiceSessionController.recordTerminalInputForCall(callId, deferred.utteranceId,
                    "Cancelled before processing typed message: ${deferred.text}") } }
            } else runCatching { voiceSessionController.appendTranscript("Jarvis", "Cancelled before processing voice follow-up: ${deferred.text}") }
        }
        pendingTypedHandoff.getAndSet(null)?.takeIf { it.callId == endedCallId }?.let { input ->
            runCatching { voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                "Cancelled before processing typed message: ${input.text}") }
        }
        if (stopSession) {
            voiceSessionArmed = false
            // True session end: the End-call button's sessionAlive gate drops here.
            com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.sessionAlive.value = false
        }
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.liveTranscript.value = ""
        val status = if (stopSession) "Jarvis session stopped — microphone off."
            else "Returning to Hey Jarvis — finishing call audio…"
        com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus(status)
        if (stopSession) {
            stopVoiceService()
            runtimeScope.launch { callResources.closeMicrophone() }
        }
        activeVoiceOutput?.stopSpeaking()
        activeContinuousActionSession?.detach()
        activeContinuousActionSession = null // Detached call reports remain durable; never block a new call.
        // A spoken farewell returns through its stage and the exact turn finalizer. Keeping
        // that job alive lets its completion callback rearm after all audio borrowers join.
        if (stopSession) voiceTurnJob?.cancel()
        // End Call detaches microphone/TTS immediately but does not retract a bounded accepted
        // task. Its saved-ID callbacks continue to checkpoint evidence for this original call.
        if (stopSession && !acceptedVoiceActions.hasUnfinished()) conversationJob?.cancel()
        if (stopSession) activeVoiceCapture = null
        runCatching {
            if (voiceSessionController.currentCallId() != null) {
                voiceSessionController.end()
            }
        }.onFailure { report("Voice Call could not be saved: ${it.message ?: "unknown error"}") }
            .onSuccess { report(status) }
    }

    internal fun startVoiceDiagnostics(label: String) {
        if (label.startsWith("Voice Call ")) com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.begin(label)
        com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.clearDiagnostics()
        asrComparisonStore.clearDiagnostics()
        ttsComparisonStore.clearDiagnostics()
        diagnosticRecorder.startSession(label)
    }

    private fun stopVoiceService() {
        com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
        stopService(android.content.Intent(this, com.battlesbudz.jarvis.v2.voice.VoiceCallService::class.java))
    }

    /** Reset the native conversation without tearing down the initialized Engine. */
    internal suspend fun resetNativeConversation() {
        conversationEngine?.resetConversation()
        nativeConversationHasContext = false
        conversationCharacters = 0
    }

    internal fun cleanSpeechText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forSpeech(text)
    internal fun cleanAssistantText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forDisplay(text)

    companion object {
        @Volatile private var instance: JarvisRuntime? = null
        fun get(context: android.content.Context): JarvisRuntime = instance ?: synchronized(this) {
            instance ?: JarvisRuntime(context.applicationContext).also { instance = it }
        }
    }
}
