package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp


@androidx.annotation.Keep
internal object VoiceCallOverlay {
    @Composable
    fun Bubble(
    phase: String,
    status: String,
    level: Float,
    active: Boolean,
    microphonePaused: Boolean,
    canStart: Boolean,
    stopReplyAvailable: Boolean,
    onStart: () -> Unit,
    onStopReply: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onEndCall: () -> Unit,
    transcriptSpeaker: String = "",
    transcript: String = "",
    // A call is in flight while the voice session is alive OR a voice turn is
    // running. The end-call affordance must not depend on `active` (the armed
    // flag) alone: on device the armed flag can drop during the farewell ->
    // wake-listening phase while the session is fully alive, which left the
    // bubble with no way to end it.
    callInFlight: Boolean = active,
) {
    // Only the orb and its opaque control pill paint over the chat. The live
    // transcript belongs to the conversation, rather than a duplicate overlay.
    Box(Modifier.fillMaxSize().testTag("voice_call_overlay_layer")) {
        Column(Modifier.align(Alignment.BottomEnd).imePadding()
            .padding(end = 16.dp, bottom = 104.dp).testTag("voice_call_overlay"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Surface(shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                VoiceCallControlsLayout {
                    Text(phase, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("voice_call_status").semantics { contentDescription = status.ifBlank { phase } })
                    if (callInFlight) {
                        // The call button becomes the end-call button while a call
                        // is in flight, so there is always a visible way to end it.
                        TextButton(onClick = onEndCall,
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                .testTag("voice_call_end")
                                .semantics { contentDescription = "End call" }) {
                            Text("End call", color = MaterialTheme.colorScheme.error)
                        }
                        if (active) {
                            TextButton(onClick = onToggleMicrophone,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                    .testTag("voice_call_pause").semantics {
                                        contentDescription = if (microphonePaused) "Resume microphone" else "Pause microphone"
                                    }) {
                                Text(if (microphonePaused) "Resume microphone" else "Pause microphone",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (canStart) {
                        TextButton(onClick = onStart, modifier = Modifier.testTag("voice_start")) { Text("Start") }
                    } else Text("…", modifier = Modifier.padding(12.dp))
                    if (stopReplyAvailable) TextButton(onClick = onStopReply,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("voice_call_stop_reply")) {
                        Text("Stop reply", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    }
}

/** Keep the labeled pill above the composer without squeezing its actions after rotation. */
@Composable
private fun VoiceCallControlsLayout(content: @Composable () -> Unit) {
    Layout(content = content) { controls, constraints ->
        val columnWidth = minOf(112.dp.roundToPx(), constraints.maxWidth)
        val columnHeight = controls.sumOf { it.maxIntrinsicHeight(columnWidth) }
        val rowWidths = controls.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        // The actual text/font/action measurements decide whether a short, wide
        // slot can use one row. Status stays inline; no extra row consumes the
        // 48 dp needed by the controls. The parent retains its 104 dp clearance.
        val horizontal = columnHeight > constraints.maxHeight && rowWidths.sum() <= constraints.maxWidth
        var remainingHeight = constraints.maxHeight
        val children = controls.mapIndexed { index, control ->
            control.measure(Constraints(maxWidth = if (horizontal) rowWidths[index] else columnWidth,
                maxHeight = if (horizontal) constraints.maxHeight else remainingHeight)).also {
                if (!horizontal) remainingHeight = (remainingHeight - it.height).coerceAtLeast(0)
            }
        }
        val width = constraints.constrainWidth(if (horizontal) children.sumOf { it.width } else columnWidth)
        val height = constraints.constrainHeight(if (horizontal) children.maxOfOrNull { it.height } ?: 0
            else children.sumOf { it.height })
        layout(width, height) {
            var offset = 0
            children.forEach { child ->
                if (horizontal) {
                    child.placeRelative(offset, (height - child.height) / 2)
                    offset += child.width
                } else {
                    child.placeRelative((width - child.width) / 2, offset)
                    offset += child.height
                }
            }
        }
    }
}

@Composable
internal fun VoiceCallScreen(
    visible: Boolean,
    settingsOpen: Boolean,
    memoryOpen: Boolean,
    onDismissSettings: () -> Unit,
    startRequest: Long,
    chatBusy: kotlinx.coroutines.flow.StateFlow<Boolean>,
    modelSelector: @Composable (Boolean) -> Unit,
    resumedCall: VoiceCallRecord?,
    onResumeConsumed: () -> Unit,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    onVoiceTurn: (Boolean, (String) -> Unit, (String, String, Boolean) -> Unit, (String) -> Unit) -> Unit,
    onEndVoiceCall: ((String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    // M1d explicit silent work (D21/T05): toggle plus observable posture.
    silentWork: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    onSilentWork: (Boolean) -> Unit = {},
    // M2 saved workflows (D36): settings lists them with enable/disable; chat stays the operating surface.
    workflowSettings: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection?>? = null,
    onWorkflowSetEnabled: (String, Boolean) -> Unit = { _, _ -> },
    // M3 guided MCP setup (D07): custom server URL from the settings dialog.
    onConnectMcpServer: (String, String, String, (String) -> Unit) -> Unit = { _, _, _, done -> done("MCP setup is unavailable right now.") },
    callEvidenceActions: CallEvidenceActions,
) {
    val runtime = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
    val runtimePhase by runtime.phase.collectAsState()
    val runtimeStatus by runtime.status.collectAsState()
    val runtimeArmed by runtime.armed.collectAsState()
    // Session aliveness, not the armed flag: the End-call button must survive
    // the farewell -> "Waiting for Hey Jarvis" phase, where armed can drop
    // while the session (wake listener, mic, foreground service) is alive.
    val sessionAlive by runtime.sessionAlive.collectAsState()
    val microphonePaused by runtime.paused.collectAsState()
    val microphoneLevel by runtime.level.collectAsState()
    val chatSending by chatBusy.collectAsState()
    // M1d: explicit silent work. While on, ordinary speech is ignored until
    // the wake phrase; admitted tasks keep running and stop controls work.
    val silentWorkFlow = remember(silentWork) {
        silentWork ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val silentWorkActive by silentWorkFlow.collectAsState()
    val wakeContext = androidx.compose.ui.platform.LocalContext.current
    var inputTesting by remember { mutableStateOf(false) }
    val playback by voicePlayback.collectAsState()
    var listening by remember { mutableStateOf(false) }
    var turnInFlight by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var consumedStartRequest by rememberSaveable { mutableStateOf(0L) }
    var turns by remember { mutableStateOf(resumedCall?.transcript.orEmpty().map { ChatEntry(it.role, it.text) }) }
    var provisionalUser by remember { mutableStateOf("") }
    fun requestVoiceTurn(start: Boolean) {
        listening = false
        status = "Preparing microphone…"
        turnInFlight = true
        onVoiceTurn(
            start,
            { update ->
                status = update
                if (update.startsWith("Waiting") || update.startsWith("Paused")) listening = false
                if (update.startsWith("Jarvis session stopped")) {
                    listening = false; turnInFlight = false
                }
                if (update.startsWith("Processing your Voice Call") || update.startsWith("Preparing")) listening = false
                if (update.startsWith("Voice Call is listening")) listening = true
            },
            { role, text, complete ->
                if (role == "You" && !complete) {
                    provisionalUser = text
                } else {
                    if (role == "You") provisionalUser = ""
                turns = if (role == "Jarvis" && turns.lastOrNull()?.role == "Jarvis") {
                    turns.dropLast(1) + ChatEntry(role, if (complete) text else turns.last().text + text)
                } else {
                    turns + ChatEntry(role, text)
                }
                }
            },
            { result ->
                status = result
                turnInFlight = false
                provisionalUser = ""
                val failed = result.contains("could not start", ignoreCase = true) ||
                    result.contains("turn failed", ignoreCase = true) ||
                    result.contains("permission", ignoreCase = true)
                listening = false
                if (failed) turnInFlight = false
            }
        )
    }

    LaunchedEffect(Unit) {
        if (resumedCall != null) {
            requestVoiceTurn(start = true)
            onResumeConsumed()
        }
    }

    LaunchedEffect(startRequest) {
        if (startRequest > consumedStartRequest) {
            consumedStartRequest = startRequest
            if (!runtimeArmed) requestVoiceTurn(start = true)
        }
    }

    LaunchedEffect(runtimeArmed) {
        if (!runtimeArmed) { listening = false; turnInFlight = false }
    }
    LaunchedEffect(memoryOpen) {
        if (memoryOpen && settingsOpen) onDismissSettings()
    }
    if (visible) {
        val bubblePhase = if (runtimeArmed) runtimePhase
            else if (turnInFlight) com.battlesbudz.jarvis.v2.voice.VoicePhase.PREPARING
            else com.battlesbudz.jarvis.v2.voice.VoicePhase.IDLE
        val visibleStatus = if (runtimeArmed) runtimeStatus else status
        VoiceCallOverlay.Bubble(
            phase = bubblePhase.label,
            status = visibleStatus,
            transcriptSpeaker = if (provisionalUser.isNotBlank()) "You" else turns.lastOrNull()?.role.orEmpty(),
            transcript = provisionalUser.ifBlank { turns.lastOrNull()?.text.orEmpty() },
            level = if (runtimeArmed) {
                if (runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.SPEAKING) playback.level else microphoneLevel
            } else 0f,
            active = runtimeArmed,
            // The session is in flight from the first arm() until a true stop,
            // including the farewell -> "Waiting for Hey Jarvis" phase. The
            // end-call button must not depend on the armed flag or the
            // composable's turn state alone: on device, "hey Jarvis" rearms
            // the call inside the runtime turn chain without touching either,
            // which left the bubble with no way to end it.
            callInFlight = sessionAlive || turnInFlight,
            microphonePaused = microphonePaused,
            canStart = !runtimeArmed && !chatSending && !turnInFlight && !inputTesting,
            stopReplyAvailable = runtimeArmed && (
                runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.SPEAKING ||
                    runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.THINKING
                ),
            onStart = { requestVoiceTurn(start = true) },
            onStopReply = { runtime.controls.trySend(com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY) },
            onToggleMicrophone = {
                runtime.controls.trySend(if (microphonePaused) com.battlesbudz.jarvis.v2.voice.VoiceControl.RESUME
                    else com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE)
            },
            onEndCall = {
                listening = false
                turnInFlight = false
                onEndVoiceCall { result -> status = result }
            }
        )
    }
    if (settingsOpen) androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDismissSettings() },
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = { onDismissSettings() }) { Text("Done") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("One model for chat and voice", style = MaterialTheme.typography.bodySmall)
            modelSelector(!chatSending && !runtimeArmed && !turnInFlight && !inputTesting)
            // M1d explicit silent work (D21/T05): tasks continue while
            // ordinary speech is ignored until "hey jarvis" wakes back up.
            OutlinedButton(onClick = { onSilentWork(!silentWorkActive) },
                modifier = Modifier.fillMaxWidth().testTag("silent_work_toggle")) {
                Text(if (silentWorkActive) "Wake up — resume listening" else "Work silently")
            }
            if (silentWorkActive) {
                Text("Working silently · say \"hey jarvis\" to wake",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("silent_work_status"))
            }
            // M2 saved workflows (D36): list with enable/disable; chat stays the operating surface.
            val workflowProjection by (workflowSettings?.collectAsState()
                ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection?>(null) })
            WorkflowSettingsSection(projection = workflowProjection, onSetEnabled = onWorkflowSetEnabled,
                onConnectMcpServer = onConnectMcpServer)
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Voice & microphone", style = MaterialTheme.typography.titleMedium)
        Text("Voice: Piper Northern English", style = MaterialTheme.typography.bodyMedium)
        val assistantContext = androidx.compose.ui.platform.LocalContext.current
        VoiceInputSettings(enabled = !chatSending && !runtimeArmed && !turnInFlight && !inputTesting, onBusy = { inputTesting = it })
        var assistantSettingsMessage by remember { mutableStateOf(
            if (assistantContext.getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) "Jarvis is your default assistant."
            else "Choose Jarvis as your default assistant to enable hands-free app actions."
        ) }
        val assistantSettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            val roles = assistantContext.getSystemService(android.app.role.RoleManager::class.java)
            assistantSettingsMessage = if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
                "Jarvis is your default assistant."
            else "Select Jarvis under Digital assistant app in Android Settings."
        }
        TextButton(onClick = {
            val roles = assistantContext.getSystemService(android.app.role.RoleManager::class.java)
            if (roles.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                !roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                try {
                    assistantSettingsLauncher.launch(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                    assistantSettingsMessage = "Choose Jarvis for reliable hands-free app launches."
                    return@TextButton
                } catch (_: android.content.ActivityNotFoundException) {
                    // Continue to the manufacturer default-app settings.
                } catch (_: SecurityException) {
                    // Continue to the public settings fallback.
                }
            }
            val actions = listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            var opened = false
            for (action in actions) {
                try {
                    assistantSettingsLauncher.launch(android.content.Intent(action))
                    opened = true
                    break
                } catch (_: android.content.ActivityNotFoundException) {
                    // Some manufacturers expose only the default-apps screen.
                } catch (_: SecurityException) {
                    // Try the public default-apps fallback.
                }
            }
            assistantSettingsMessage = if (opened)
                "Choose Digital assistant app, then Jarvis."
            else "Open Android Settings → Apps → Default apps → Digital assistant app → Jarvis."
        }) { Text("Default assistant settings") }
        if (assistantSettingsMessage.isNotBlank()) {
            Text(assistantSettingsMessage, style = MaterialTheme.typography.bodySmall)
        }
        Text("Automatic microphone handoff", style = MaterialTheme.typography.bodyMedium)
        Text("Jarvis releases its microphone for other recordings and automatically resumes your call or wake listening afterward.",
            style = MaterialTheme.typography.bodySmall)
        val helperConnected by com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.keyboardHelperConnected.collectAsState()
        Text(if (helperConnected) "Keyboard microphone-button handoff is enabled."
            else "Enable keyboard handoff to give dictation priority when you tap its microphone.",
            style = MaterialTheme.typography.bodySmall)
        Text("Checks only the tapped keyboard control’s label or ID. Ordinary typing and leaving a keyboard open do not pause Jarvis.",
            style = MaterialTheme.typography.bodySmall)
        var keyboardSetupError by remember { mutableStateOf("") }
        TextButton(onClick = {
            try { wakeContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { keyboardSetupError = "Open Android Settings → Accessibility → Installed apps → Jarvis keyboard microphone handoff." }
        }) { Text(if (helperConnected) "Keyboard handoff settings" else "Enable keyboard microphone handoff") }
        if (keyboardSetupError.isNotBlank()) Text(keyboardSetupError, style = MaterialTheme.typography.bodySmall)
        var diagnosticsOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) {
            Text(if (diagnosticsOpen) "Hide development diagnostics" else "Development diagnostics")
        }
        if (diagnosticsOpen) {
            CallEvidenceExport(actions = callEvidenceActions, enabled = !chatSending && !runtimeArmed && !turnInFlight)
            TextButton(
                onClick = { onCopyDiagnostics(turns + if (provisionalUser.isNotBlank()) listOf(ChatEntry("You", provisionalUser)) else emptyList()) },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Copy diagnostics")
            }
        }
        } }
    )
}
