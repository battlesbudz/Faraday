package com.battlesbudz.jarvis.v2

import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.presentation.ModelSetupOperations
import com.battlesbudz.jarvis.v2.presentation.ModelSetupSession
import com.battlesbudz.jarvis.v2.voice.AndroidAudioInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val runtime get() = JarvisRuntime.get(applicationContext)
    private val modelSetup by lazy {
        ModelSetupOperations(
            context = applicationContext,
            scope = lifecycleScope,
            store = runtime.modelStore,
            mainHandler = runtime.mainHandler,
            session = object : ModelSetupSession {
                override fun busy(): Boolean =
                    runtime.chatBusy.value || runtime.voiceSessionArmed ||
                        runtime.voiceSessionController.currentCallId() != null ||
                        runtime.voiceTurnJob?.isCompleted == false || ConversationWork.activeJobs.get() != 0

                override fun closeConversation(resetCharacters: Boolean) {
                    runtime.conversationEngine?.close()
                    runtime.conversationEngine = null
                    runtime.nativeConversationHasContext = false
                    if (resetCharacters) runtime.conversationCharacters = 0
                }

                override fun record(message: String) = runtime.diagnosticRecorder.recordImportant(message)
            },
        )
    }
    private var notificationPermissionAsked = false
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val voiceCallResumer by lazy {
        com.battlesbudz.jarvis.v2.voice.VoiceCallResumer(runtime.voiceSessionController)
    }
    private val activeVoiceOutput get() = runtime.activeVoiceOutput
    private data class PendingVoiceTurn(
        val start: Boolean,
        val report: (String) -> Unit,
        val onTranscript: (String, String, Boolean) -> Unit,
        val onFinished: (String) -> Unit
    )
    private var pendingVoiceTurn: PendingVoiceTurn? = null
    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingVoiceTurn
        pendingVoiceTurn = null
        if (pending == null) return@registerForActivityResult
        if (granted) {
            runVoiceTurn(pending.start, pending.report, pending.onTranscript, pending.onFinished)
        } else {
            runtime.voiceSessionArmed = false
            com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.sessionAlive.value = false
            pending.report("Microphone permission is required for Voice Calls.")
            pending.onFinished("Voice Call could not start because microphone permission was denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val interruptedSession = runtime.sessionPreferences.getBoolean("sending", false)
        if (!runtime.voiceSessionArmed) runtime.shortTermContext.restoreSummary(
            if (interruptedSession) null else {
                savedInstanceState?.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY)
                    ?: runtime.sessionPreferences.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, null)
            }
        )
        if (interruptedSession) {
            // Do not reuse context captured while the native engine was being
            // torn down. The visible transcript remains recoverable.
            runtime.sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
        }
        // M2: refresh the saved-workflow settings projection and evaluate
        // past-due routine occurrences against current circumstances.
        runtime.refreshWorkflowSettings()
        runtime.evaluateMissedWorkflowRuns()
        setContent {
            JarvisApp(
                store = runtime.modelStore,
                conversationHistory = runtime.conversationHistory,
                chatBusy = runtime.chatBusy,
                agentActivity = runtime.agentActivity,
                phoneTasks = runtime.phoneTasks,
                phoneTaskError = runtime.phoneTaskError,
                onPhoneTaskAction = runtime::phoneTaskAction,
                // M1d explicit silent work (D21/T05).
                silentWork = runtime.silentWorkState,
                onSilentWork = runtime::setSilentWork,
                // M2 saved workflows (D36): settings lists them; chat stays the operating surface.
                workflowSettings = runtime.workflowSettings,
                onWorkflowSetEnabled = runtime::setWorkflowEnabled,
                // M3 guided MCP setup (D07): custom server URL from the settings dialog.
                onConnectMcpServer = runtime::connectMcpServer,
                callState = runtime.voiceSessionController.state,
                onSendChat = runtime::sendChat,
                onSelectConversation = runtime::selectConversation,
                onSelectModel = modelSetup::select,
                onDeleteModel = modelSetup::delete,
                voicePlayback = runtime.voicePlayback,
                voiceModelStore = runtime.ttsModels,
                initialVoiceCalls = runtime.voiceCallStore.list(),
                onRunModelSmokeTest = { modelSetup.test(it) },
                onRunReliabilityCheck = modelSetup::runReliabilityCheck,
                onVoiceTurn = { start, report, onTranscript, onFinished ->
                    com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
                    com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.report("Preparing microphone…")
                    runtime.audioRecoveryAttempts = 0
                    runtime.returnToWakeCuePending.set(false)
                    runtime.sessionReport = report
                    runVoiceTurn(start, report, onTranscript, onFinished)
                },
                onEndVoiceCall = { report -> endVoiceCall(report) },
                onResumeVoiceCall = { call, onComplete ->
                    lifecycleScope.launch {
                        val result = voiceCallResumer.resume(call) {
                            pendingVoiceTurn = null
                            activeVoiceOutput?.stopSpeaking()
                            val previousVoice = runtime.voiceTurnJob
                            val previousConversation = runtime.conversationJob
                            previousVoice?.cancel(kotlinx.coroutines.CancellationException("resuming_saved_voice_call"))
                            previousConversation?.cancel()
                            previousVoice?.join()
                            previousConversation?.join()
                        }
                        result.onSuccess {
                            runtime.conversationHistory.openCall(call)
                            runtime.voiceSessionController.linkConversation(runtime.conversationHistory.current.value.id)
                            startVoiceDiagnostics("Voice Call ${it.id} (resumed)")
                        }.onFailure {
                            runtime.diagnosticRecorder.record("Voice resume failed: ${it.stackTraceToString().take(4000)}")
                        }
                        onComplete(result.exceptionOrNull()?.let {
                            "Could not resume this call: ${it.message ?: "unknown error"}"
                        })
                    }
                },
                onDeleteVoiceCall = { callId -> runtime.voiceCallStore.delete(callId) },
                onRefreshVoiceCalls = { runtime.voiceCallStore.list() },
                onDownloadGemma = { spec, onProgress, onStatus, onFinished ->
                    modelSetup.download(spec, onProgress, onStatus, onFinished)
                },
                onCancelModelDownload = modelSetup::cancelDownload,
                onImportModel = modelSetup::importModel,
                onCopyDiagnostics = { _ -> copyDiagnostics() },
            )
        }
    }

    /** Streaming ASR -> speculative Gemma -> final validation -> tools and speech. */
    private fun runVoiceTurn(
        start: Boolean,
        report: (String) -> Unit,
        onTranscript: (String, String, Boolean) -> Unit,
        onFinished: (String) -> Unit
    ) {
        if (runtime.chatBusy.value || ConversationWork.activeJobs.get() != 0) {
            onFinished("Voice Call could not start: wait for the text response to finish.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTurn = PendingVoiceTurn(start, report, onTranscript, onFinished)
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!start || runtime.voiceTurnJob?.isActive == true) {
            val message = "Voice Call turn failed: the previous turn is still finishing."
            report(message)
            onFinished(message)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtime.attachUi(report, onTranscript, onFinished)
        runtime.arm()
        try {
            startForegroundService(android.content.Intent(this, com.battlesbudz.jarvis.v2.voice.VoiceCallService::class.java))
        } catch (error: Exception) {
            runtime.endVoiceCall(report)
            onFinished("Voice Call turn failed: background audio could not start: ${error.message}")
        }
    }

    private fun endVoiceCall(report: (String) -> Unit) {
        pendingVoiceTurn = null
        runtime.endVoiceCall(report)
    }

    private fun startVoiceDiagnostics(label: String) = runtime.startVoiceDiagnostics(label)

    private fun copyDiagnostics() {
        // The runtime ring contains the latest call's ASR, inference and playback events.
        // Comparison archives and full chat histories do not belong in a call failure report.
        val diagnostics = "Jarvis OS V2 — latest Voice Call diagnostics\n\n${runtime.diagnosticRecorder.snapshot()}\n\n${com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.diagnostics()}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis diagnostics", diagnostics))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, runtime.shortTermContext.summaryForDiagnostics())
        runtime.sessionPreferences.edit()
            .putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, runtime.shortTermContext.summaryForDiagnostics())
            .apply()
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        runtime.activityVisible = true
        runtime.resumePhoneTasksAfterUnlock()
    }
    override fun onPause() {
        runtime.activityVisible = false
        super.onPause()
    }
    override fun onDestroy() {
        runtime.detachUi()
        // Voice jobs and models belong to the service runtime, including during Activity recreation.
        super.onDestroy()
    }
}
