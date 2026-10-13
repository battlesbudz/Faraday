package com.battlesbudz.jarvis.v2.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.battlesbudz.jarvis.v2.chat.ConversationThread
import com.battlesbudz.jarvis.v2.diagnostics.*
import kotlinx.coroutines.*

/** Screen-owned state: never owned by a lazily disposed export-button row. */
@Stable
internal class BenchmarkExportState {
    var busy by mutableStateOf(false)
    var pendingSave by mutableStateOf<String?>(null)
    var parts by mutableStateOf<List<String>>(emptyList())
    var partIndex by mutableIntStateOf(0)
    var copiedParts by mutableStateOf<Set<Int>>(emptySet())
    var export: (BenchmarkExportFormat, String) -> Unit = { _, _ -> }
    var dismissParts: () -> Unit = {}
    fun tryBegin(): Boolean {
        if (busy || parts.isNotEmpty()) return false
        busy = true
        return true
    }
}

/** Freeze before a picker/clipboard. Configuration recreation intentionally aborts pending exports:
 * a fresh, non-restored launcher namespace prevents a late old URI from receiving a newer payload.
 * Only the interruption notice is saved, never an oversized report in Activity saved state. */
@Composable
internal fun rememberBenchmarkExport(report: PipelineBenchmarkReport, replies: ConversationThread?, scopeLabel: String,
    onStatus: (String) -> Unit): BenchmarkExportState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state = remember { BenchmarkExportState() }
    val instanceId = remember { UUID.randomUUID().toString() }
    var interrupted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (interrupted) onStatus("Export interrupted by screen recreation. Save or copy the report again.")
        interrupted = false
    }
    state.dismissParts = { state.parts = emptyList(); state.copiedParts = emptySet(); interrupted = false }
    fun setBusy(value: Boolean) { state.busy = value; interrupted = value || state.parts.isNotEmpty() }
    val save: (android.net.Uri?) -> Unit = { uri ->
        val payload = state.pendingSave
        state.pendingSave = null
        if (uri == null) { onStatus("Save cancelled. Retained measurements are unchanged."); setBusy(false) }
        else if (payload == null) { onStatus("Export interrupted. Save the report again."); setBusy(false) }
        else coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) { BenchmarkExportFiles.save(context, uri, payload) }
                onStatus("Redacted benchmark report saved.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onStatus("Could not save the benchmark report. Try a different destination.") }
            finally { setBusy(false) }
        }
    }
    val saveTxt = key(instanceId, "txt") { rememberLauncherForActivityResult(BenchmarkExportFiles.createDocument(BenchmarkExportFormat.TXT), save) }
    val saveJson = key(instanceId, "json") { rememberLauncherForActivityResult(BenchmarkExportFiles.createDocument(BenchmarkExportFormat.JSON), save) }
    val saveCsv = key(instanceId, "csv") { rememberLauncherForActivityResult(BenchmarkExportFiles.createDocument(BenchmarkExportFormat.CSV), save) }
    state.export = export@{ format, action ->
        if (!state.tryBegin()) return@export
        // Capture the exact report/scope once, including every retained attempt, before async generation.
        val snapshot = PipelineBenchmarkTextExport.freeze(report)
        val snapshotReplies = replies?.copy(messages = replies.messages.toList())
        val snapshotScope = scopeLabel
        setBusy(true)
        onStatus("Preparing ${format.name} report…")
        coroutineScope.launch {
            var waitingForSave = false
            try {
                val payload = withContext(Dispatchers.Default) {
                    when (format) {
                        BenchmarkExportFormat.TXT -> PipelineBenchmarkTextExport.render(snapshot, snapshotScope)
                        BenchmarkExportFormat.JSON -> ConversationMetricsExport.json(snapshot, snapshotReplies).let {
                            if (action == "copy") it.toString() else it.toString(2)
                        }
                        BenchmarkExportFormat.CSV -> snapshot.toCsv()
                    }
                }
                when (action) {
                    "save" -> {
                        state.pendingSave = payload
                        val filename = BenchmarkExportFiles.filename(format)
                        waitingForSave = true
                        when (format) {
                            BenchmarkExportFormat.TXT -> saveTxt.launch(filename)
                            BenchmarkExportFormat.JSON -> saveJson.launch(filename)
                            BenchmarkExportFormat.CSV -> saveCsv.launch(filename)
                        }
                    }
                    "copy" -> if (format == BenchmarkExportFormat.TXT) {
                        state.parts = withContext(Dispatchers.Default) { PipelineBenchmarkTextExport.chunks(payload) }
                        state.partIndex = 0
                        state.copiedParts = emptySet()
                    } else if (payload.toByteArray(Charsets.UTF_8).size > 400_000) {
                        onStatus("The full report is too large for the clipboard. Use Copy report for numbered parts, or save/share the full report.")
                    } else {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Jarvis redacted benchmarks", payload))
                        onStatus("Redacted ${format.name} report copied.")
                    }
                    "share" -> {
                        val intent = withContext(Dispatchers.IO) { BenchmarkExportFiles.share(context, format, payload) }
                        context.startActivity(Intent.createChooser(intent, "Share redacted benchmark report"))
                        onStatus("Share sheet opened. Choose a destination; Jarvis cannot verify delivery.")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                waitingForSave = false
                state.pendingSave = null
                onStatus("Could not export the report. The retained samples remain available.")
            } finally { if (!waitingForSave) setBusy(false) }
        }
    }
    return state
}

@Composable
internal fun BenchmarkExportControls(state: BenchmarkExportState) {
    Column {
        Row {
            TextButton(onClick = { state.export(BenchmarkExportFormat.TXT, "copy") }, enabled = !state.busy,
                modifier = Modifier.testTag("pipeline_benchmark_copy_text")) { Text("Copy report") }
            TextButton(onClick = { state.export(BenchmarkExportFormat.TXT, "save") }, enabled = !state.busy,
                modifier = Modifier.testTag("pipeline_benchmark_save_text")) { Text("Save TXT") }
            TextButton(onClick = { state.export(BenchmarkExportFormat.TXT, "share") }, enabled = !state.busy,
                modifier = Modifier.testTag("pipeline_benchmark_share_text")) { Text("Share TXT") }
        }
        listOf("copy", "save", "share").forEach { action ->
            Row {
                listOf(BenchmarkExportFormat.JSON, BenchmarkExportFormat.CSV).forEach { format ->
                    TextButton(onClick = { state.export(format, action) }, enabled = !state.busy,
                        modifier = Modifier.testTag("pipeline_benchmark_${action}_${format.extension}")) {
                        Text("${action.replaceFirstChar(Char::uppercase)} ${format.name}")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun BenchmarkExportDialog(state: BenchmarkExportState, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    if (state.parts.isNotEmpty()) AlertDialog(onDismissRequest = state.dismissParts,
        // A dialog has its own semantics root; the screen's resource-ID policy is not inherited.
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("pipeline_benchmark_parts"),
        title = { Text("Copy whole report") },
        text = { Column {
            Text("Part ${state.partIndex + 1} of ${state.parts.size} · ${state.parts[state.partIndex].toByteArray(Charsets.UTF_8).size} bytes", modifier = Modifier.testTag("pipeline_benchmark_part_status"))
            Text("Paste each numbered part into the chat separately. All ${state.parts.size} parts belong to one frozen report; nothing is silently cut off.")
            Text("Copied ${state.copiedParts.size}/${state.parts.size} parts. Copying is not proof of pasting or delivery.")
            Row {
                TextButton(enabled = state.partIndex > 0, onClick = { state.partIndex-- }, modifier = Modifier.testTag("pipeline_benchmark_part_previous")) { Text("Previous") }
                TextButton(enabled = state.partIndex < state.parts.lastIndex, onClick = { state.partIndex++ }, modifier = Modifier.testTag("pipeline_benchmark_part_next")) { Text("Next") }
            }
        } },
        confirmButton = { TextButton(onClick = {
            try {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Jarvis report part ${state.partIndex + 1}", state.parts[state.partIndex]))
                state.copiedParts = state.copiedParts + state.partIndex
                onStatus("Copied report part ${state.partIndex + 1} of ${state.parts.size}.")
            } catch (_: Exception) { onStatus("Could not copy this part. Save TXT or Share TXT instead.") }
        }, modifier = Modifier.testTag("pipeline_benchmark_part_copy")) { Text("Copy part ${state.partIndex + 1}") } },
        dismissButton = { TextButton(onClick = state.dismissParts, modifier = Modifier.testTag("pipeline_benchmark_parts_done")) { Text("Done") } })
}
