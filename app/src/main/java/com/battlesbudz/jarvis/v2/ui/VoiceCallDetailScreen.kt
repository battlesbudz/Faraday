package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Date
import java.text.DateFormat


@Composable
internal fun VoiceCallDetailScreen(
    call: VoiceCallRecord,
    benchmarkStore: AndroidPipelineBenchmarkStore,
    onBack: () -> Unit,
    onContinueChat: () -> Unit,
    onResume: ((String?) -> Unit) -> Unit
) {
    var resuming by remember(call.id) { mutableStateOf(false) }
    var resumeError by remember(call.id) { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    var showBenchmarks by remember { mutableStateOf(false) }
    if (showBenchmarks) androidx.compose.ui.window.Dialog(onDismissRequest = { showBenchmarks = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) { PipelineBenchmarkScreen(benchmarkStore, onClose = { showBenchmarks = false }, resetEnabled = false, callId = call.id) }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(call.title ?: "Voice Call", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, enabled = !resuming) { Text("Back") }
        }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(call.startedAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { showBenchmarks = true }) { Text("Call benchmarks · view / export") }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            call.transcript.forEach { entry ->
                val label = if (entry.role == "Jarvis" && entry.delivery != null &&
                    entry.delivery.state != com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED) "Jarvis (generated)" else entry.role
                Text(
                    "$label: ${entry.text}",
                    fontStyle = if (entry.role == "You") androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    color = if (entry.role == "You") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.role == "Jarvis") {
                    entry.delivery?.let { delivery ->
                        if (delivery.state != com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED) {
                            Text("Playback ${delivery.state.name.lowercase()}. Completed spoken text: " +
                                delivery.deliveredText.ifBlank { "None" } +
                                if (delivery.partialSpanIndex != null) " · Last segment partly played." else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    entry.actions.forEach { action -> Text("Action result: ${action.message}", style = MaterialTheme.typography.bodySmall) }
                    entry.latency?.let { TurnLatencyFooter(it) }
                }
            }
            call.taskStatus?.let { task ->
                Text("Task status: ${task.state}", style = MaterialTheme.typography.labelLarge)
                task.completedSteps.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                task.pendingSteps.forEach { Text("○ $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        TextButton(onClick = onContinueChat, enabled = !resuming) { Text("Continue in chat") }
        resumeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (!resuming) {
                resuming = true
                resumeError = null
                onResume { error ->
                    // Keep a successful button latched until this screen leaves composition.
                    if (error != null) resuming = false
                    resumeError = error
                }
            }
        }, enabled = !resuming, modifier = Modifier.fillMaxWidth()) {
            Text(if (resuming) "Preparing call…" else "Resume conversation")
        }
    }
}

