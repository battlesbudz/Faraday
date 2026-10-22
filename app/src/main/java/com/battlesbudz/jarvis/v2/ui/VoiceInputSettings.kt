package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceInputSettings(enabled: Boolean, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputMode by remember { mutableStateOf(VoiceInputMode.selected(context)) }
    var captionEngine by remember { mutableStateOf(VoiceInputMode.captionEngine(context)) }
    var captionMenuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(AsrEngine.selected(context)) }
    // Reading the capture profile performs its legacy-preference migration
    // as a side effect; that behavior is part of the fixed processing and
    // stays even though the profile itself no longer renders in Settings.
    remember { SpeechCaptureProfile.selected(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(busy) { onBusy(busy) }
    LaunchedEffect(enabled, busy, inputMode) {
        if (!enabled || busy || inputMode != VoiceInputMode.GEMMA_AUDIO) captionMenuOpen = false
    }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    var task by remember { mutableStateOf<Job?>(null) }
    Column {
        Text("Voice input: ${inputMode.label}")
        VoiceInputMode.entries.forEach { mode ->
            TextButton(enabled = enabled && !busy && inputMode != mode, onClick = {
                VoiceInputMode.select(context, mode); inputMode = mode
                message = "${mode.label} will apply to the next captured turn."
            }) { Text("Use ${mode.label}") }
        }
        if (inputMode == VoiceInputMode.GEMMA_AUDIO) {
            Text("Experimental: Gemma answers from your recording. Use speech recognition mode for phone actions. Captions are display-only. Requests longer than 28 seconds are rejected with a request to speak in shorter parts.", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded = captionMenuOpen, onExpandedChange = { captionMenuOpen = enabled && !busy && it }) {
                OutlinedTextField(
                    value = captionEngine?.label ?: "Off",
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled && !busy,
                    label = { Text("Caption engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = captionMenuOpen) },
                    modifier = Modifier.testTag("gemma_caption_engine").menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = captionMenuOpen, onDismissRequest = { captionMenuOpen = false }) {
                    (listOf<AsrEngine?>(null) + AsrEngine.entries).forEach { engine ->
                        DropdownMenuItem(
                            enabled = enabled && !busy,
                            modifier = Modifier.testTag("gemma_caption_${when (engine) {
                                null -> "off"
                                AsrEngine.WHISPER_NPU -> "whisper_npu"
                                AsrEngine.WHISPER -> "whisper"
                            }}"),
                            text = { Text(engine?.label ?: "Off") },
                            onClick = {
                                VoiceInputMode.captionEngine(context, engine); captionEngine = engine
                                captionMenuOpen = false
                                message = "${engine?.label ?: "Off"} captions will apply to the next captured turn."
                            }
                        )
                    }
                }
            }
            Text("Turn captions off to skip all Whisper loading and recognition.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Speech recognition: ${selected.label}")
        AsrEngine.entries.forEach { engine ->
            TextButton(enabled = enabled && !busy && selected != engine, onClick = {
                busy = true
                task = scope.launch {
                    try {
                        engine.prepare(context) { value -> scope.launch { message = value } }
                        AsrEngine.select(context, engine); selected = engine
                        message = if (engine == AsrEngine.WHISPER_NPU) "Whisper will try Snapdragon NPU on the next call, with CPU fallback if unavailable."
                            else "${engine.label} will handle your next voice call."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { message = error.message ?: "Download failed" }
                    finally { busy = false }
                }
            }) { Text("Use ${engine.label}") }
        }
        Text("Whisper transcribes your voice on this phone; the Snapdragon NPU option falls back to CPU when acceleration is unavailable.", style = MaterialTheme.typography.bodySmall)
        // Smart Turn endpointing and microphone noise reduction run
        // automatically and intentionally have no Settings presence
        // (2026-10-22 simplification: no status lines, toggles, or downloads).
        if (busy) TextButton(onClick = { task?.cancel(); message = "Stopped." }) { Text("Cancel") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
