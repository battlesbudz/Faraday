package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.chat.*
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    history: ConversationHistory,
    busy: StateFlow<Boolean>,
    callState: StateFlow<VoiceSessionState>,
    onSend: (String, ChatAttachment?) -> String?,
    selectedModel: LocalModelSpec,
    onSelectConversation: (String?) -> String?,
    onEndVoice: ((String) -> Unit) -> Unit,
    onOpenVoiceCalls: () -> Unit,
    resumedVoice: Boolean,
    dictationFactory: (() -> com.battlesbudz.jarvis.v2.voice.ChatDictation)? = null,
    onOpenMemory: () -> Unit = {},
    forceVoiceDestination: Boolean = false,
    onForceVoiceConsumed: () -> Unit = {},
    forceChatDestination: Boolean = false,
    onForceChatConsumed: () -> Unit = {},
    phoneTasks: StateFlow<com.battlesbudz.jarvis.v2.actions.ToolTaskJournal?>? = null,
    phoneTaskError: StateFlow<String?>? = null,
    onPhoneTaskAction: (String, Long, String) -> Unit = { _, _, _ -> },
    pipelineBenchmarkStore: com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore? = null,
    voiceContent: @Composable (visible: Boolean, settingsOpen: Boolean, dismissSettings: () -> Unit, startRequest: Long) -> Unit
) {
    val thread by history.current.collectAsState()
    var showingBenchmarks by remember { mutableStateOf(false) }
    var benchmarkReply by remember { mutableStateOf<String?>(null) }
    if (showingBenchmarks && pipelineBenchmarkStore != null) Dialog(onDismissRequest = { showingBenchmarks = false; benchmarkReply = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { PipelineBenchmarkScreen(pipelineBenchmarkStore, onClose = { showingBenchmarks = false; benchmarkReply = null }, resetEnabled = false, conversationId = thread.id, initialTurnId = benchmarkReply, conversationReplies = thread) }
    }
    val sending by busy.collectAsState()
    val liveTranscript by VoiceSessionUi.liveTranscript.collectAsState()
    val armed by VoiceSessionUi.armed.collectAsState()
    // The live session, not the armed flag or the call segment: the voice
    // surface (and its End-call control) follows this. A farewell can drop
    // the call segment and the armed flag while the session — wake listener,
    // mic, foreground service — stays fully alive.
    val sessionAlive by VoiceSessionUi.sessionAlive.collectAsState()
    val voiceFailure by VoiceSessionUi.failure.collectAsState()
    val voiceState by callState.collectAsState()
    val taskJournal by (phoneTasks?.collectAsState() ?: remember { mutableStateOf<com.battlesbudz.jarvis.v2.actions.ToolTaskJournal?>(null) })
    val taskError by (phoneTaskError?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    var hadCall by remember { mutableStateOf(false) }
    var voiceVisible by rememberSaveable { mutableStateOf(false) }
    // The End-call control must be available for the whole live session, not
    // just the armed call segment. Effective visibility ORs the manual flag
    // (voice button / Back) with the live-session signals, so a farewell
    // (armed true->false) or a passive-listening segment never hides the
    // surface while the session is alive. Derived synchronously (no
    // LaunchedEffect race) so the control is never missing for a live call.
    val effectiveVoiceVisible = voiceVisible || armed || sessionAlive
    var callStartRequest by rememberSaveable { mutableLongStateOf(0L) }
    var wasArmed by remember { mutableStateOf(armed) }
    var settings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(thread.id) { mutableStateOf("") }
    var pendingUri by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable(thread.id) { mutableStateOf(AttachmentKind.IMAGE) }
    var preparingAttachment by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf(false) }
    val inputBusy = preparingAttachment || dictating
    val pendingAttachment = pendingUri?.let { ChatAttachment(it, pendingKind) }
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(thread.id) {
        onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true)
            pendingUri?.let { ChatMediaStore.discard(context, ChatAttachment(it, pendingKind)) } }
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun showVoice() {
        if (sending || inputBusy || effectiveVoiceVisible) return
        voiceVisible = true
        if (!armed) callStartRequest++
    }
    BackHandler(enabled = effectiveVoiceVisible && !settings && !showHistory) {
        // While the session is alive the voice surface IS the call: Back
        // never dismisses it — the End-call control is the way out. Without
        // a live session Back dismisses the pre-call surface as before.
        // (When armed, effective visibility keeps the bubble even though the
        // manual flag clears.)
        if (!sessionAlive && !armed) voiceVisible = false
    }
    LaunchedEffect(voiceState) {
        if (voiceState != VoiceSessionState.PASSIVE_LISTENING) hadCall = true
        else if (hadCall) {
            hadCall = false
            // A farewell returns the call segment to PASSIVE_LISTENING while
            // the session stays alive: keep the surface (and its End-call
            // control) until the session truly ends.
            if (!sessionAlive) voiceVisible = false
        }
    }
    LaunchedEffect(resumedVoice) { if (resumedVoice) voiceVisible = true }
    LaunchedEffect(forceVoiceDestination) {
        if (forceVoiceDestination) {
            voiceVisible = true
            onForceVoiceConsumed()
        }
    }
    LaunchedEffect(forceChatDestination) {
        if (forceChatDestination) {
            // Chat stays visible beneath an active call; returning from Memory does not end it.
            if (armed || resumedVoice) voiceVisible = true
            onForceChatConsumed()
        }
    }
    LaunchedEffect(armed) {
        // An armed true->false transition with no live session ends the
        // call: clear a stale manual open. (While armed or sessionAlive the
        // effective visibility already keeps the surface.)
        if (!armed && wasArmed && !sessionAlive) voiceVisible = false
        wasArmed = armed
    }
    // A true session end dismisses the surface; the manual flag must not
    // outlive the session.
    LaunchedEffect(sessionAlive) {
        if (!sessionAlive && !armed) voiceVisible = false
    }
    LaunchedEffect(effectiveVoiceVisible) {
        if (effectiveVoiceVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(thread.id, thread.messages.lastOrNull()?.text, liveTranscript, armed) {
        if (armed && liveTranscript.isNotBlank()) listState.animateScrollToItem(thread.messages.size)
        else if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("JARVIS", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(enabled = !inputBusy, onClick = onOpenMemory, modifier = Modifier.testTag("memory_open")) { Text("Memory") }
            TextButton(enabled = !inputBusy, onClick = { settings = true }) { Text("Settings") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showHistory = true }, enabled = !sending && !armed && !inputBusy) { Text("Conversations") }
            TextButton(onClick = { error = onSelectConversation(null) }, enabled = !sending && !armed && !inputBusy) { Text("New") }
        }
        if (pipelineBenchmarkStore != null) ConversationMetricsControls(thread, pipelineBenchmarkStore) {
            benchmarkReply = null
            showingBenchmarks = true
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                if (thread.messages.isEmpty()) Text("Type a message or start a voice call. It's all one conversation.",
                    modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    PhoneTaskPanel(taskJournal, thread.id, taskError, onPhoneTaskAction)
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("conversation_transcript"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (effectiveVoiceVisible) 280.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(thread.messages, key = { it.id }) { message ->
                        Surface(color = if (message.role == "You") MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(message.role + if (message.spoken) " · Spoken transcript" else "",
                                    style = MaterialTheme.typography.labelMedium)
                                message.attachment?.let { ChatAttachmentPreview(it, playbackEnabled = !dictating && !armed && !effectiveVoiceVisible) }
                                SelectionContainer {
                                    Text(message.text.ifBlank { if (sending) "Thinking…" else "No reply was saved." },
                                        fontStyle = if (message.spoken) FontStyle.Italic else FontStyle.Normal,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                                if (message.role == "Jarvis")
                                    Text((message.metrics ?: com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.unavailable).withOutputText(message.text).summary(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = pipelineBenchmarkStore != null) { benchmarkReply = message.sourceReplyId ?: message.id; showingBenchmarks = true }.testTag("reply_metrics_${message.id}"))
                                if (!message.complete && message.role == "Jarvis" && message.text.isNotBlank() && !sending)
                                    Text("Reply interrupted or not fully spoken", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (armed && liveTranscript.isNotBlank()) item(key = "live_voice_transcript") {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("You · Live transcript", style = MaterialTheme.typography.labelMedium)
                                Text(liveTranscript, fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(top = 6.dp).testTag("voice_call_live_transcript"))
                            }
                        }
                    }
                }
                voiceFailure?.takeIf { it.conversationId == thread.id }?.let { failure ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("voice_call_failure")) {
                        Text(failure.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { VoiceSessionUi.dismissFailure(failure) },
                            modifier = Modifier.testTag("voice_call_failure_dismiss")) { Text("Dismiss") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                pendingAttachment?.let { attached ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (attached.kind == AttachmentKind.IMAGE) "Image attached" else "Audio clip attached", modifier = Modifier.weight(1f))
                        TextButton(enabled = !sending && !inputBusy, onClick = {
                            ChatMediaStore.discard(context, attached); pendingUri = null
                        }) { Text("Remove") }
                    }
                    if (!AttachmentPolicy.accepts(selectedModel, attached.kind))
                        Text("Choose a compatible model or remove the attachment.", color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (preparingAttachment) Text("Preparing attachment…", modifier = Modifier.padding(horizontal = 16.dp))
                if (armed) Text("Attachments are unavailable during a voice call. End the call to add one.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))

                key(thread.id) {
                    ChatVoiceInput(enabled = !sending && !effectiveVoiceVisible && !armed && !preparingAttachment,
                        canSendAudio = selectedModel.supportsAudio && pendingAttachment == null,
                        audioUnavailableReason = if (!selectedModel.supportsAudio) "This model accepts text only. Use Stop to transcribe."
                            else if (pendingAttachment != null) "Remove the existing attachment to send audio." else null,
                        createRecorder = { dictationFactory?.invoke() ?: com.battlesbudz.jarvis.v2.voice.LocalChatDictation(context) },
                        onBusy = { dictating = it },
                        onAudio = { pcm, transcript ->
                            check(selectedModel.supportsAudio && pendingAttachment == null) { "Select an audio-capable model and remove other attachments to send audio." }
                            var attached: ChatAttachment? = null
                            var accepted = false
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    attached = ChatMediaStore.prepareVoiceNote(context, pcm)
                                }
                                val voiceText = if (draft.isBlank()) transcript else draft.trimEnd() + "\n" + transcript
                                val failure = onSend(voiceText, requireNotNull(attached))
                                check(failure == null) { failure.orEmpty() }
                                accepted = true
                                draft = ""
                                error = null
                            } finally { if (!accepted) attached?.let { ChatMediaStore.discard(context, it) } }
                        },
                        onTranscript = { text ->
                            draft = if (draft.isBlank()) text else draft.trimEnd() + " " + text
                            error = null
                        }, onError = { error = it }) { voiceButton ->
                        Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message Jarvis") },
                                modifier = Modifier.weight(1f).testTag("chat_composer"), maxLines = 5, enabled = !sending && !inputBusy && (!effectiveVoiceVisible || armed),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), trailingIcon = voiceButton,
                                leadingIcon = if (!armed && selectedModel.supportsVision) { {
                                    ChatAttachmentPicker(selectedModel, enabled = !sending && !effectiveVoiceVisible && !inputBusy,
                                        onBusy = { preparingAttachment = it }, onError = { error = it }, onPrepared = { attached ->
                                            pendingAttachment?.let { ChatMediaStore.discard(context, it) }
                                            pendingKind = attached.kind; pendingUri = attached.uri; error = null
                                        })
                                } } else null)
                            IconButton(enabled = !sending && !inputBusy, onClick = { showVoice() },
                                modifier = Modifier.testTag("voice_call_open")) {
                                ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_call,
                                    if (effectiveVoiceVisible) "Show voice call" else "Start voice call")
                            }
                            val canSend = if (armed) draft.isNotBlank() && pendingAttachment == null
                            else (draft.isNotBlank() || pendingAttachment != null) &&
                                (pendingAttachment == null || AttachmentPolicy.accepts(selectedModel, pendingAttachment.kind))
                            FilledIconButton(enabled = canSend && !sending && !inputBusy && (!effectiveVoiceVisible || armed), onClick = {
                                error = onSend(draft, if (armed) null else pendingAttachment)
                                if (error == null) { draft = ""; pendingUri = null }
                            }, modifier = Modifier.testTag("chat_send")) {
                                ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_send, if (sending) "Thinking" else "Send message")
                            }
                        }
                    }
                }
            }
            // Keep the voice controller and shared Settings alive in both modes.
                voiceContent(effectiveVoiceVisible, settings, { settings = false }, callStartRequest)
        }
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Conversations") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(history.list(), key = { it.id }) { saved ->
                TextButton(onClick = { error = onSelectConversation(saved.id); if (error == null) showHistory = false }) {
                    Text(saved.title)
                }
            }
            item { TextButton(onClick = { showHistory = false; onOpenVoiceCalls() }) { Text("Saved voice calls") } }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } })
}
