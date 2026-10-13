package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.WithPipelineDiagnostics
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.memory.AndroidMemoryOs

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun JarvisApp(
    store: ModelStore,
    conversationHistory: com.battlesbudz.jarvis.v2.chat.ConversationHistory,
    chatBusy: kotlinx.coroutines.flow.StateFlow<Boolean>,
    callState: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoiceSessionState>,
    onSendChat: (String, com.battlesbudz.jarvis.v2.chat.ChatAttachment?) -> String?,
    onSelectConversation: (String?) -> String?,
    onSelectModel: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> String?,
    onDeleteModel: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> String?,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    voiceModelStore: com.battlesbudz.jarvis.v2.voice.TtsModelStore,
    initialVoiceCalls: List<VoiceCallRecord>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onRunReliabilityCheck: (
        List<com.battlesbudz.jarvis.v2.ai.LocalModelSpec>,
        com.battlesbudz.jarvis.v2.eval.ReliabilityReportStore,
        suspend (com.battlesbudz.jarvis.v2.eval.ToolReliabilityBenchmark.Progress) -> Unit,
        (kotlin.Result<com.battlesbudz.jarvis.v2.eval.ToolReliabilityBenchmark.Result>) -> Unit
    ) -> Unit,
    onVoiceTurn: (Boolean, (String) -> Unit, (String, String, Boolean) -> Unit, (String) -> Unit) -> Unit,
    onEndVoiceCall: ((String) -> Unit) -> Unit,
    onResumeVoiceCall: (VoiceCallRecord, (String?) -> Unit) -> Unit,
    onDeleteVoiceCall: (String) -> Unit,
    onRefreshVoiceCalls: () -> List<VoiceCallRecord>,
    onDownloadGemma: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (Long, Long) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    agentActivity: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot?>? = null,
    phoneTasks: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.actions.ToolTaskJournal?>? = null,
    phoneTaskError: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    onPhoneTaskAction: (String, Long, String) -> Unit = { _, _, _ -> },
    silentWork: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    onSilentWork: (Boolean) -> Unit = {},
    workflowSettings: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection?>? = null,
    onWorkflowSetEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onConnectMcpServer: (String, String, String, (String) -> Unit) -> Unit = { _, _, _, done -> done("MCP setup is unavailable right now.") },
) {
    WithPipelineDiagnostics { benchmarkStore, callEvidence ->
        val setup = rememberModelSetupState(store, ModelSetupActions(
            select = onSelectModel, delete = onDeleteModel, test = onRunModelSmokeTest,
            download = onDownloadGemma, cancelDownload = onCancelModelDownload, importModel = onImportModel,
            runReliabilityCheck = onRunReliabilityCheck,
        ))
        var pickerModelId by rememberSaveable { mutableStateOf<String?>(null) }
        var showingVoiceCalls by rememberSaveable { mutableStateOf(false) }
        var showingMemory by rememberSaveable { mutableStateOf(false) }
        var memoryReturnToVoice by rememberSaveable { mutableStateOf(false) }
        var memoryReturnToChat by rememberSaveable { mutableStateOf(false) }
        var voiceCalls by remember { mutableStateOf(initialVoiceCalls) }
        var selectedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }
        var resumedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }

        val phoneContext = androidx.compose.ui.platform.LocalContext.current
        val memoryOs = remember(phoneContext.applicationContext) { AndroidMemoryOs.get(phoneContext.applicationContext) }
        val activeVoiceCall by com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.armed.collectAsState()
        val activeVoiceStatus by com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.status.collectAsState()
        val modelSelector: @Composable (Boolean) -> Unit = { enabled ->
            ModelSelectionSection(setup, enabled, benchmarkStore, onOpenMemory = { showingMemory = true })
        }
        val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            setup.importModel(uri, ModelCatalog.resolve(pickerModelId))
        }

        MaterialTheme(
            colorScheme = darkColorScheme()
        ) {
            Surface(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                WispAppFrame(presence = {
                    WispPresence(conversationHistory, chatBusy, callState, voicePlayback, phoneTasks, phoneTaskError,
                        agentActivity, setupActivity = when {
                            setup.modelDownloadRunning -> WispPresentation(WispActivity.CONNECTING, "Installing model")
                            setup.modelImportRunning -> WispPresentation(WispActivity.CHECKING, "Importing model")
                            setup.smokeTestRunning -> WispPresentation(WispActivity.THINKING, "Testing model")
                            else -> null
                        })
                }) {
                    if (setup.modelsReady && setup.smokeTestPassed) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().then(if (showingMemory) Modifier.clearAndSetSemantics { } else Modifier)) {
                                when {
                                    selectedVoiceCall != null -> {
                                        val selected = requireNotNull(selectedVoiceCall)
                                        VoiceCallDetailScreen(
                                            call = selected,
                                            benchmarkStore = benchmarkStore,
                                            onBack = { selectedVoiceCall = null },
                                            onContinueChat = {
                                                conversationHistory.openCall(selected)
                                                onSelectConversation(conversationHistory.current.value.id)
                                                selectedVoiceCall = null
                                                showingVoiceCalls = false
                                            },
                                            onResume = { done ->
                                                onResumeVoiceCall(selected) { error ->
                                                    done(error)
                                                    if (error == null) {
                                                        resumedVoiceCall = selected
                                                        selectedVoiceCall = null
                                                        showingVoiceCalls = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    showingVoiceCalls -> VoiceCallsScreen(
                                        calls = voiceCalls,
                                        onBack = { showingVoiceCalls = false },
                                        onSelect = { selectedVoiceCall = it },
                                        onDelete = {
                                            onDeleteVoiceCall(it)
                                            voiceCalls = onRefreshVoiceCalls()
                                        }
                                    )
                                    else -> ConversationScreen(
                                        history = conversationHistory, busy = chatBusy, callState = callState, onSend = onSendChat,
                                        phoneTasks = phoneTasks, phoneTaskError = phoneTaskError, onPhoneTaskAction = onPhoneTaskAction,
                                        selectedModel = setup.selectedModel,
                                        onSelectConversation = onSelectConversation, onEndVoice = onEndVoiceCall,
                                        onOpenVoiceCalls = {
                                            voiceCalls = onRefreshVoiceCalls()
                                            showingVoiceCalls = true
                                        },
                                        resumedVoice = resumedVoiceCall != null,
                                        onOpenMemory = { showingMemory = true },
                                        forceVoiceDestination = memoryReturnToVoice,
                                        onForceVoiceConsumed = { memoryReturnToVoice = false },
                                        forceChatDestination = memoryReturnToChat,
                                        onForceChatConsumed = { memoryReturnToChat = false },
                                        pipelineBenchmarkStore = benchmarkStore
                                    ) { visible, settingsOpen, dismissSettings, startRequest ->
                                        VoiceCallScreen(
                                            visible = visible, settingsOpen = settingsOpen, memoryOpen = showingMemory,
                                            onDismissSettings = dismissSettings, startRequest = startRequest,
                                            chatBusy = chatBusy, modelSelector = modelSelector,
                                            resumedCall = resumedVoiceCall,
                                            onResumeConsumed = { resumedVoiceCall = null },
                                            voicePlayback = voicePlayback,
                                            onVoiceTurn = onVoiceTurn,
                                            onEndVoiceCall = onEndVoiceCall,
                                            onCopyDiagnostics = onCopyDiagnostics,
                                            silentWork = silentWork,
                                            onSilentWork = onSilentWork,
                                            workflowSettings = workflowSettings,
                                            onWorkflowSetEnabled = onWorkflowSetEnabled,
                                            onConnectMcpServer = onConnectMcpServer,
                                            callEvidenceActions = callEvidence,
                                        )
                                    }
                                }
                            }
                            // Keep the conversation and voice controller composed beneath this review surface.
                            if (showingMemory) MemoryScreen(
                                memoryOs = memoryOs,
                                onBack = { showingMemory = false },
                                callActive = activeVoiceCall,
                                callStatus = activeVoiceStatus,
                                onEndCall = onEndVoiceCall,
                                onOpenChat = { memoryReturnToVoice = false; memoryReturnToChat = true; showingMemory = false },
                                onOpenVoice = { memoryReturnToChat = false; memoryReturnToVoice = true; showingMemory = false },
                            )
                        }
                    } else if (showingMemory) {
                        MemoryScreen(
                            memoryOs = memoryOs,
                            onBack = { showingMemory = false },
                            callActive = activeVoiceCall,
                            callStatus = activeVoiceStatus,
                            onEndCall = onEndVoiceCall,
                            onOpenChat = { memoryReturnToVoice = false; memoryReturnToChat = true; showingMemory = false },
                            onOpenVoice = { memoryReturnToChat = false; memoryReturnToVoice = true; showingMemory = false },
                        )
                    } else {
                        ModelSetup(
                            modelSelector = modelSelector,
                            ready = setup.modelInstalled,
                            gemmaReady = setup.modelInstalled,
                            downloadAvailable = !setup.selectedModel.requiresAccess,
                            testing = setup.smokeTestRunning,
                            importing = setup.modelImportRunning,
                            downloading = setup.modelDownloadRunning,
                            downloadBytes = setup.downloadBytes,
                            downloadTotalBytes = setup.downloadTotalBytes,
                            status = setup.setupStatus,
                            elapsedSeconds = setup.setupElapsedSeconds,
                            onDownload = { setup.download(setup.selectedModel) },
                            onPickGemma = { pickerModelId = setup.selectedModel.id; gemmaPicker.launch(arrayOf("*/*")) },
                            onTest = setup::test
                        )
                    }
                }
            }
        }
    }
}
