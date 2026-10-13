package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.battlesbudz.jarvis.v2.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Metrics are redacted; entering a reference explicitly scores the original ASR output only. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PipelineBenchmarkScreen(store: AndroidPipelineBenchmarkStore, onClose: () -> Unit, resetEnabled: Boolean = true,
    conversationId: String? = null, callId: String? = null, initialTurnId: String? = null,
    conversationReplies: com.battlesbudz.jarvis.v2.chat.ConversationThread? = null) {
    val scope = rememberCoroutineScope()
    val allSamples by store.samples.collectAsState()
    var scoped by remember(conversationId, callId) { mutableStateOf(conversationId != null || callId != null) }
    val samples = remember(allSamples, scoped, conversationId, callId) {
        PipelineBenchmarkSelection.select(allSamples, conversationId.takeIf { scoped }, callId.takeIf { scoped })
    }
    val storageStatus by store.storageStatus.collectAsState()
    val hypothesisEpoch by store.hypothesisGeneration.collectAsState()
    var status by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf(initialTurnId) }
    LaunchedEffect(initialTurnId, samples) {
        if (initialTurnId != null && selectedId == initialTurnId) {
            PipelineBenchmarkSelection.select(samples, turnId = initialTurnId).firstOrNull()?.let { selectedId = it.turnId }
        }
    }
    var showReset by remember { mutableStateOf(false) }
    var referenceId by remember { mutableStateOf<String?>(null) }
    val report = remember(samples) { PipelineBenchmarkReport(samples, System.currentTimeMillis()) }
    val exportState = rememberBenchmarkExport(report, conversationReplies.takeIf { scoped },
        scopeLabel = if (!scoped) "all retained benchmarks" else
            "conversation=${conversationId ?: "unavailable"}; call=${callId ?: "unavailable"}",
        onStatus = { status = it })
    BenchmarkExportDialog(exportState, onStatus = { status = it })
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).semantics { testTagsAsResourceId = true }.testTag("pipeline_benchmark_screen")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pipeline benchmarks", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, modifier = Modifier.testTag("pipeline_benchmark_close")) { Text("Done") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${samples.size} retained attempts · 90-day retention · 128 MiB / 100,000 attempts capacity")
                Text("Capacity rejects new records visibly; it does not evict existing attempts. Expired records are pruned. Legacy exports have no conversation linkage unless recorded.", style = MaterialTheme.typography.bodySmall)
                if (conversationId != null || callId != null) TextButton(onClick = { scoped = !scoped }, modifier = Modifier.testTag("pipeline_benchmark_scope")) {
                    Text(if (scoped) "Export scope: this conversation/call · Show all" else "Export scope: all benchmarks · Show this conversation/call")
                }
                Text("Automatic observations cover every completed, cancelled and failed attempt collected by this build. A live conversation is observational data, not a controlled benchmark corpus.", style = MaterialTheme.typography.bodySmall)
                Text("Exports contain model/build/device settings, measurements and optional reviewed scores. Prompts, transcripts, reference text and audio are omitted.", style = MaterialTheme.typography.bodySmall)
                Text("Unobserved values stay unavailable. Token estimates are separate from native token counts. Playback-head latency is a device playback proxy; actual acoustic onset is unmeasured.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Column {
                    BenchmarkExportControls(exportState)
                    TextButton(onClick = { showReset = true }, enabled = resetEnabled && !exportState.busy,
                        modifier = Modifier.testTag("pipeline_benchmark_reset")) { Text("Reset retained benchmarks") }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pipeline_benchmark_status"))
                    storageStatus?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { BenchmarkSummary(report) }
            item { Text("Recent samples · latest 50 shown; exports include all retained samples", style = MaterialTheme.typography.titleMedium) }
            if (samples.isEmpty()) item { Text("No pipeline measurements yet. Complete a text or voice turn, then return here.") }
            items(PipelineBenchmarkSelection.select(samples, turnId = initialTurnId).takeIf { initialTurnId != null }.orEmpty().plus(samples.takeLast(50)).distinctBy { it.turnId }.asReversed(), key = { it.turnId }) { sample ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selectedId = if (selectedId == sample.turnId) null else sample.turnId },
                            modifier = Modifier.testTag("pipeline_benchmark_sample_${sample.turnId}")) {
                            Text("${sample.channel} · ${sample.turnId.take(8)} · ${sample.outcome.name.lowercase(Locale.ROOT)}")
                        }
                        Text("Build ${sample.provenance.buildCode} · ${sample.environment.name.lowercase(Locale.ROOT)} · ${sample.provenance.deviceModel ?: "device unavailable"}", style = MaterialTheme.typography.bodySmall)
                        Text(sample.provenance.models.entries.joinToString(" · ") { "${it.key}: ${it.value.id}" }.ifEmpty { "Model identity unavailable" }, style = MaterialTheme.typography.bodySmall)
                        sample.accuracy?.let { Text("ASR WER ${percent(it.wer)} · CER ${percent(it.cer)} · ${it.referenceWords} reference words", style = MaterialTheme.typography.bodySmall) }
                        if (selectedId == sample.turnId) {
                            Row {
                                PipelineBenchmarkEnvironment.entries.forEach { environment ->
                                    TextButton(onClick = { store.setEnvironment(sample.turnId, environment) }, modifier = Modifier.testTag("benchmark_environment_${environment.name}")) {
                                        Text((if (sample.environment == environment) "✓ " else "") + environment.name.lowercase(Locale.ROOT))
                                    }
                                }
                            }
                            if (sample.asr != null) {
                                val hypothesisAvailable = remember(sample.turnId, hypothesisEpoch) { store.hypothesis(sample.turnId) != null }
                                TextButton(onClick = { referenceId = sample.turnId }, enabled = hypothesisAvailable,
                                    modifier = Modifier.testTag("pipeline_benchmark_reference")) { Text("Enter verified ASR reference") }
                                if (!hypothesisAvailable) Text("The original transcript is no longer held in memory. Reference scoring is available for new voice turns in this app session.", style = MaterialTheme.typography.bodySmall)
                            }
                            BenchmarkQualityReview(sample) { store.setQuality(sample.turnId, it) }
                            BenchmarkMeasuredValues(sample)
                            sample.submissions.forEach { submission ->
                                Text("${submission.purpose} · ${submission.outcome} · ${submission.warmState} · ${submission.modelId ?: "model unavailable"}", style = MaterialTheme.typography.titleSmall)
                                submission.metrics().forEach { (key, value) -> Text("$key: ${number(value)}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
    referenceId?.let { id -> BenchmarkReferenceDialog(id, store, onDismiss = { referenceId = null }) { message -> status = message } }
    if (showReset) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { showReset = false }, title = { Text("Reset retained benchmarks?") },
        text = { Text("Delete the retained measurements and reviewed scores. Export a report first if you need them for comparison.") },
        confirmButton = { TextButton(onClick = {
            showReset = false
            scope.launch { store.clear(); selectedId = null; status = if (store.storageStatus.value == null) "Retained benchmarks reset." else "Reset could not be saved. Try again before closing Jarvis." }
        }, modifier = Modifier.testTag("pipeline_benchmark_reset_confirm")) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } })
}

/** Detail visibility is local UI state; it never filters retained samples or exports. */
@Composable
private fun BenchmarkMeasuredValues(sample: PipelineBenchmarkTurn) {
    var timingExpanded by remember(sample.turnId) { mutableStateOf(false) }
    val metrics = remember(sample) { sample.metrics() }
    val timingMetrics = remember(metrics) { metrics.filterKeys { it in VoicePreparationTimingMetrics.keys } }
    Text("Preparation and Smart Turn timing", style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = { timingExpanded = !timingExpanded },
        modifier = Modifier.testTag("pipeline_benchmark_timing_toggle")) {
        Text(if (timingExpanded) "Hide timing measurements" else
            "Show timing measurements (${timingMetrics.values.count { it != null }}/${timingMetrics.size} available)")
    }
    Text("Capture and speech-span overlap, endpoint stages and up to eight Smart Turn calls. All values are included in exports; unobserved values stay unavailable.",
        style = MaterialTheme.typography.bodySmall)
    if (timingExpanded) {
        Column(Modifier.testTag("pipeline_benchmark_timing_details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            timingMetrics.forEach { (key, value) -> BenchmarkMeasuredValue(key, value) }
        }
    }
    Text("Measured pipeline values", style = MaterialTheme.typography.titleSmall)
    metrics.filterKeys { it !in VoicePreparationTimingMetrics.keys }.forEach { (key, value) ->
        BenchmarkMeasuredValue(key, value)
    }
}

@Composable
private fun BenchmarkMeasuredValue(key: String, value: Double?) {
    Text("$key: ${number(value)}", style = MaterialTheme.typography.bodySmall)
    PipelineBenchmarkDefinitions.notes[key]?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun BenchmarkReferenceDialog(id: String, store: AndroidPipelineBenchmarkStore, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val epoch by store.hypothesisGeneration.collectAsState()
    val openedEpoch = remember(id) { store.hypothesisEpoch() }
    var busy by remember(id) { mutableStateOf(false) }
    var reference by remember(id) { mutableStateOf("") }
    var verifiedSilence by remember(id) { mutableStateOf(false) }
    var verified by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf("") }
    LaunchedEffect(epoch) { if (epoch != openedEpoch) onDismiss() }
    AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = onDismiss, title = { Text("Verify what you actually said") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Original ASR: ${store.hypothesis(id).orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("Use the exact words spoken in this capture. This reference only scores recognition; it is never sent to a model or retained as transcript text.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(reference, onValueChange = { reference = it.take(2048) }, enabled = !verifiedSilence && !busy,
                label = { Text("Verified spoken words") }, modifier = Modifier.testTag("pipeline_benchmark_reference_text"))
            Row { Checkbox(verifiedSilence, onCheckedChange = { verifiedSilence = it; verified = false }, enabled = !busy); Text("Verified capture contained no speech", Modifier.weight(1f)) }
            Row { Checkbox(verified, onCheckedChange = { verified = it }, enabled = !busy, modifier = Modifier.testTag("pipeline_benchmark_reference_verify")); Text("I verified this reference against what was spoken", Modifier.weight(1f)) }
            if (busy) Text("Scoring verified recognition…", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        busy = true
        scope.launch {
            try {
                val score = store.scoreReferenceAsync(id, if (verifiedSilence) "" else reference)
                onStatus(if (verifiedSilence) "Silence reference scored: ${score.falsePositiveWords} false-positive words; WER/CER have no denominator."
                    else "Verified ASR WER ${percent(score.wer)} · CER ${percent(score.cer)}. Scores retained; reference text discarded.")
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not score this reference. Use a shorter verified transcript from a current capture." }
            finally { busy = false }
        }
    }, enabled = !busy && verified && (verifiedSilence || reference.isNotBlank()), modifier = Modifier.testTag("pipeline_benchmark_reference_score")) { Text("Score reference") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun BenchmarkQualityReview(sample: PipelineBenchmarkTurn, onSave: (PipelineBenchmarkQuality?) -> Unit) {
    var task by remember(sample.turnId, sample.quality) { mutableStateOf(sample.quality?.taskVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED) }
    var intent by remember(sample.turnId, sample.quality) { mutableStateOf(sample.quality?.intentVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED) }
    var factuality by remember(sample.turnId, sample.quality) { mutableStateOf(sample.quality?.factualityVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED) }
    var checked by remember(sample.turnId) { mutableStateOf(false) }
    Text("Human end-to-end review", style = MaterialTheme.typography.titleSmall)
    Text("Review the actual result against an independently known expected result. Execution completion and fluent wording do not establish accuracy.", style = MaterialTheme.typography.bodySmall)
    VerdictSelector("Task completed correctly", "task", task) { task = it }
    VerdictSelector("Intent understood correctly", "intent", intent) { intent = it }
    VerdictSelector("Factual answer correct", "factuality", factuality) { factuality = it }
    Row { Checkbox(checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag("pipeline_benchmark_quality_verify")); Text("I checked the result against independent reference evidence", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = {
        onSave(PipelineBenchmarkQuality(task, "user_review_independent_expected_result", System.currentTimeMillis(), intent, factuality))
        checked = false
    }, enabled = checked, modifier = Modifier.testTag("pipeline_benchmark_quality_save")) { Text("Save human review") }
    sample.quality?.let { Text("Saved human review: task ${it.taskVerdict} · intent ${it.intentVerdict} · factuality ${it.factualityVerdict}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun VerdictSelector(label: String, tag: String, selected: PipelineBenchmarkVerdict, onSelect: (PipelineBenchmarkVerdict) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Row {
        PipelineBenchmarkVerdict.entries.forEach { verdict ->
            TextButton(onClick = { onSelect(verdict) }, modifier = Modifier.testTag("benchmark_quality_${tag}_${verdict.name}")) {
                Text((if (verdict == selected) "✓ " else "") + when (verdict) {
                    PipelineBenchmarkVerdict.PASS -> "Pass"
                    PipelineBenchmarkVerdict.FAIL -> "Fail"
                    PipelineBenchmarkVerdict.NOT_EVALUATED -> "Unreviewed"
                })
            }
        }
    }
}

@Composable
private fun BenchmarkSummary(report: PipelineBenchmarkReport) {
    Text("Comparable groups", style = MaterialTheme.typography.titleMedium)
    val aggregate = remember(report) { report.aggregate() }
    Text("All retained attempts: ${aggregate.turnCount} · native submissions: ${aggregate.submissionCount}", style = MaterialTheme.typography.bodySmall)
    Text(aggregate.outcomes.entries.joinToString(" · ") { "${it.key.lowercase(Locale.ROOT)} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
    Text("Verified recognition: ${aggregate.evaluatedAsrTurns} samples / ${aggregate.referenceWords} reference words · WER ${percent(aggregate.corpusWer)} · CER ${percent(aggregate.corpusCer)}", style = MaterialTheme.typography.bodySmall)
    Text("Human task review: pass ${aggregate.humanQualityCounts["task.PASS"] ?: 0} · fail ${aggregate.humanQualityCounts["task.FAIL"] ?: 0} · unreviewed ${aggregate.humanQualityCounts["task.NOT_EVALUATED"] ?: 0}", style = MaterialTheme.typography.bodySmall)
    if (aggregate.emptyReferenceTurns > 0) Text("Verified no-speech captures: ${aggregate.emptyReferenceTurns} · false-positive words: ${aggregate.falsePositiveWords}", style = MaterialTheme.typography.bodySmall)
    val groups = remember(report) { report.turns.groupBy(PipelineBenchmarkReport::comparableGroupKey).values.sortedByDescending { it.last().capturedAtEpochMs } }
    Text("${groups.size} groups separate build, device, model, backend, configuration, environment and observed warm/cold state. Showing the latest six; all groups are in JSON.", style = MaterialTheme.typography.bodySmall)
    groups.take(6).forEach { turns ->
        val sample = turns.last()
        val completed = remember(turns) { PipelineBenchmarkReport(turns.filter { it.outcome == PipelineBenchmarkOutcome.COMPLETE }, report.exportedAtEpochMs).aggregate() }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Build ${sample.provenance.buildCode} · ${sample.channel} · ${sample.environment} · ASR ${sample.asr?.warmState ?: PipelineBenchmarkWarmState.UNKNOWN} · TTS ${sample.tts?.warmState ?: PipelineBenchmarkWarmState.UNKNOWN}", style = MaterialTheme.typography.titleSmall)
            Text(sample.provenance.models.entries.joinToString(" · ") { "${it.key}: ${it.value.id}" }.ifEmpty { "Model identity unavailable" }, style = MaterialTheme.typography.bodySmall)
            Text("${completed.turnCount} completed / ${turns.size} attempts · completed-turn statistics below", style = MaterialTheme.typography.bodySmall)
            for ((metric, label) in listOf("speech_end_to_first_answer_playback_ms" to "Speech end → answer playback (ms)",
                "endpoint_to_first_answer_playback_ms" to "ASR final → answer playback (ms)",
                "asr_realtime_factor" to "ASR decode real-time factor", "tts_realtime_factor" to "TTS synthesis real-time factor")) {
                completed.turnMetrics[metric]?.let { Distribution(label, it) }
            }
            turns.flatMap { it.submissions }.groupBy { Triple(it.purpose, it.warmState, it.outcome) }.forEach { (identity, submissions) ->
                Text("${identity.first} · ${identity.second} · ${identity.third} · ${submissions.size} submissions", style = MaterialTheme.typography.bodySmall)
                Distribution("First raw text / TTFT (ms)", PipelineBenchmarkStatistics.from(submissions.map { it.firstTokenMs?.toDouble() }))
                Distribution("Native decode tokens/s", PipelineBenchmarkStatistics.from(submissions.map { it.exactDecodeTokensPerSecond }))
                Distribution("Estimated decode tokens/s", PipelineBenchmarkStatistics.from(submissions.map { it.estimatedDecodeTokensPerSecond }))
            }
        }
    }
}

@Composable
private fun Distribution(label: String, statistics: PipelineBenchmarkStatistics) {
    Text("$label: n=${statistics.n}, unavailable=${statistics.missing}, p50=${number(statistics.p50)}, p95=${number(statistics.p95)}" +
        if (statistics.n in 1..19) " (p95 exploratory: fewer than 20 samples)" else "", style = MaterialTheme.typography.bodySmall)
}

private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"
private fun percent(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100.0) } ?: "unavailable"
