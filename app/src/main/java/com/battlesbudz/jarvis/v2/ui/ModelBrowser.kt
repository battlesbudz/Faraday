package com.battlesbudz.jarvis.v2.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.battlesbudz.jarvis.v2.ai.*
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkTurn
import java.util.Locale
import com.battlesbudz.jarvis.v2.eval.InMemoryReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ToolReliabilityFixtures

/** Family first, then a bounded lazy list of model cards. Browsing never selects/downloads a model. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ModelBrowser(
    phone: PhoneProfile,
    selectedId: String,
    isInstalled: (LocalModelSpec) -> Boolean,
    benchmarkStore: AndroidPipelineBenchmarkStore,
    onSelect: (LocalModelSpec) -> String?,
    onDismiss: () -> Unit,
    selectionEnabled: Boolean = true,
    downloadingId: String? = null,
    onDownload: ((LocalModelSpec) -> Unit)? = null,
    reliabilityStore: ReliabilityReportStore = InMemoryReliabilityReportStore(),
    /** Identity of each spec's installed model file; scores only show for a matching fingerprint. */
    reliabilityFingerprint: (LocalModelSpec) -> String? = { null }
) {
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var warningId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val families = remember(query) { ModelGuide.families(query = query) }
    val familyListState = rememberLazyListState()
    fun goBack() { if (family != null) { family = null; detailId = null } else onDismiss() }
    Dialog(onDismissRequest = { goBack() }, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            val fullSize = WindowManager.LayoutParams.MATCH_PARENT
            if (dialogWindow.attributes.width != fullSize || dialogWindow.attributes.height != fullSize) {
                // Keep the full browser width while measuring against the actual window. The
                // pinned Compose dialog's non-default-width path substitutes display-height
                // constraints, which can crop the final model card at the window's insets.
                dialogWindow.setLayout(fullSize, fullSize)
            }
        }
        BackHandler { goBack() }
        detailId?.let { id -> ModelCatalog.find(id)?.let { spec ->
            ModelDetails(spec, phone, benchmarkStore, isInstalled(spec), reliabilityStore,
                reliabilityFingerprint = reliabilityFingerprint) { detailId = null }
        } }
        warningId?.let { id -> ModelCatalog.find(id)?.let { spec ->
            AlertDialog(onDismissRequest = { warningId = null },
                title = { Text("Known issue — read before choosing") },
                text = { Text(ModelCompatibility.assess(spec).summary) },
                confirmButton = { TextButton(onClick = {
                    warningId = null
                    error = onSelect(spec)
                    if (error == null) onDismiss()
                }, modifier = Modifier.testTag("model_issue_continue")) { Text("Choose anyway") } },
                dismissButton = { TextButton(onClick = { warningId = null }) { Text("Cancel") } })
        } }

        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
            // The dialog owns edge-to-edge insets, including Android 15/16 three-button navigation.
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { goBack() }) { Text(if (family == null) "Close" else "‹ Families") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(family ?: "Choose a model family", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Text(phone.name,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                OutlinedTextField(value = query, onValueChange = { query = it; detailId = null }, singleLine = true,
                    placeholder = { Text("Search families, models or uses") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("model_search"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (family == null) {
                    LazyColumn(state = familyListState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Choose a family, then compare models from smallest to largest.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (families.isEmpty()) item { Text("No matching models. Try a different name or use, such as coding.") }
                        items(families.entries.toList(), key = { it.key }) { (name, specs) ->
                            OutlinedCard(onClick = { family = name; detailId = null; error = null }, modifier = Modifier.fillMaxWidth().testTag("model_family_$name")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$name  ›", style = MaterialTheme.typography.titleMedium)
                                    Text(specs.map { it.provider }.distinct().joinToString(), style = MaterialTheme.typography.labelSmall)
                                    Text(specs.flatMap { ModelGuide.purpose(it).tags }.distinct().take(5).joinToString(" · "),
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                    Text("${specs.size} models · " + specs.mapNotNull { it.downloadBytes }.let { sizes ->
                                        if (sizes.isEmpty()) "Size unknown" else "${ModelGuidance.gb(sizes.min())} – ${ModelGuidance.gb(sizes.max())} downloads"
                                    }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else key(family, query) {
                    val specs = families[family].orEmpty()
                    val startingPoint = ModelGuide.startingPoint(ModelGuide.families()[family].orEmpty(), phone)
                    LazyColumn(modifier = Modifier.weight(1f).testTag("model_list"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Smallest download → largest", style = MaterialTheme.typography.titleSmall)

                        }
                        if (specs.isEmpty()) item { Text("No matches in this family. Clear the search or return to Families.") }
                        items(specs, key = { it.id }) { spec ->
                            val fit = ModelGuidance.assess(spec, phone)
                            val installed = isInstalled(spec)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(spec.id, style = MaterialTheme.typography.titleMedium)
                                    ModelCompatibilityLabel(spec)
                                    Text(ModelGuide.quickUse(spec), color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(ModelGuide.inputsLabel(spec), style = MaterialTheme.typography.labelSmall)
                                    ModelGuide.visibleLimitation(spec)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Download: " + (spec.downloadBytes?.let(ModelGuidance::gb) ?: "Size unknown") +
                                        if (installed) " · Installed" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                    reliabilityStore.loadCurrent(
                                        spec.id,
                                        reliabilityFingerprint(spec),
                                        ToolReliabilityFixtures.SUITE_VERSION
                                    )?.let { stored ->
                                        Text("Tool-call fixture agreement: ${stored.percent}% (${stored.passed}/${stored.total})",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(fit.quickMemoryLabel,
                                        color = if (fit.memoryWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (startingPoint?.id == spec.id) Text("Suggested starting model",
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    ModelGuidance.storageNotice(spec, phone, installed)?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!installed && !spec.requiresAccess && onDownload != null) {
                                        OutlinedButton(modifier = Modifier.fillMaxWidth().testTag("model_download_${spec.id}"), enabled = downloadingId == null, onClick = { onDownload(spec) }) {
                                            Text(if (downloadingId == spec.id) "Downloading" else "Download")
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { detailId = spec.id }, modifier = Modifier.testTag("model_details_${spec.id}")) {
                                            Text("Details")
                                        }
                                        Button(enabled = selectionEnabled && downloadingId != spec.id,
                                            modifier = Modifier.testTag("model_choose_${spec.id}"), onClick = { if (ModelCompatibility.assess(spec).confirmBeforeSelection) warningId = spec.id
                                            else { error = onSelect(spec); if (error == null) onDismiss() } }) {
                                            Text(if (spec.id == selectedId) "Selected" else "Choose")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelCompatibilityLabel(spec: LocalModelSpec) {
    val evidence = ModelCompatibility.assess(spec)
    val color = if (evidence.status == ModelEvidenceStatus.ISSUE) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Text(evidence.status.label, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("model_evidence_${spec.id}"))
    if (evidence.status == ModelEvidenceStatus.ISSUE) {
        Text(evidence.summary, color = color, style = MaterialTheme.typography.bodySmall)
    } else if (evidence.status == ModelEvidenceStatus.EXPERIMENTAL) {
        Text("Not yet verified for this Jarvis setup.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Each valid metric counts completed native submissions, not turns or shared "runs". */
internal fun modelSpeedLabel(samples: List<PipelineBenchmarkTurn>, spec: LocalModelSpec): String? {
    val submissions = samples.flatMap { it.submissions }.filter {
        it.modelId == spec.id && it.outcome == PipelineBenchmarkOutcome.COMPLETE
    }
    val ttfts = submissions.mapNotNull { it.firstTokenMs?.takeIf { ms -> ms >= 0 }?.toDouble() }
    val decode = submissions.mapNotNull {
        it.estimatedDecodeTokensPerSecond?.takeIf { speed -> speed.isFinite() && speed >= 0 }
    }
    if (ttfts.isEmpty() && decode.isEmpty()) return null
    fun median(values: List<Double>): Double = values.sorted().let {
        val middle = it.size / 2
        if (it.size % 2 == 1) it[middle] else it[middle - 1] / 2 + it[middle] / 2
    }
    fun count(size: Int) = "$size ${if (size == 1) "sample" else "samples"}"
    return listOfNotNull(
        ttfts.takeIf { it.isNotEmpty() }?.let {
            val ms = "%.1f".format(Locale.US, median(it)).removeSuffix(".0")
            "Observed TTFT median: $ms ms (${count(it.size)})"
        },
        decode.takeIf { it.isNotEmpty() }?.let {
            "Estimated decode median: ${"%.1f".format(Locale.US, median(it))} tok/s (${count(it.size)})"
        }
    ).joinToString("\n")
}

/** Compact facts first; detailed compatibility and resource provenance remain available. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ModelDetails(
    spec: LocalModelSpec,
    phone: PhoneProfile,
    benchmarkStore: AndroidPipelineBenchmarkStore,
    installed: Boolean,
    reliabilityStore: ReliabilityReportStore,
    reliabilityFingerprint: (LocalModelSpec) -> String? = { null },
    onDismiss: () -> Unit
) {
    val fit = ModelGuidance.assess(spec, phone)
    val evidence = ModelCompatibility.assess(spec)
    val purpose = ModelGuide.purpose(spec)
    val samples by benchmarkStore.samples.collectAsState()
    val speed = remember(samples, spec.id) { modelSpeedLabel(samples, spec) }
    val uriHandler = LocalUriHandler.current
    var expanded by remember(spec.id) { mutableStateOf(false) }
    var linkError by remember(spec.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = Modifier.testTag("model_details_dialog").semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text("About this model") },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("model_details_close")) { Text("Close details") }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                .testTag("model_details_content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(spec.id, style = MaterialTheme.typography.titleSmall)
                // Keep issues and choice-changing limitations visible without opening the explanation.
                ModelCompatibilityLabel(spec)
                ModelGuide.visibleLimitation(spec)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    (if (installed) "Installed" else "Not installed") +
                        " · Download " + (spec.downloadBytes?.let(ModelGuidance::gb) ?: "size unknown"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(fit.quickMemoryLabel,
                    color = if (fit.memoryWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Text("Audio input: ${if (spec.supportsAudio) "yes" else "no"}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Tool calling: ${if (spec.supportsTools) "yes" else "no"}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(speed ?: "No completed speed samples for this model yet.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("model_details_speed"))
                if (speed != null) Text(
                    "Completed native submissions, including drafts and retries. TTFT times the first nonempty text callback. " +
                        "Decode uses character-derived token estimates. Workloads, warm-up and configurations may differ.",
                    style = MaterialTheme.typography.bodySmall)
                val reliability = reliabilityStore.loadCurrent(
                    spec.id,
                    reliabilityFingerprint(spec),
                    ToolReliabilityFixtures.SUITE_VERSION
                )
                if (reliability != null) {
                    Text("Tool-call fixture agreement: ${reliability.percent}% (${reliability.passed}/${reliability.total})",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Exact agreement with the fixture set and tool schema. This does not prove " +
                        "a requested phone action would succeed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(reliability.summary, style = MaterialTheme.typography.bodySmall)
                } else if (installed && spec.supportsTools) {
                    Text("Tool reliability: not yet measured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("model_details_evidence")) {
                    Text(if (expanded) "Hide compatibility & guidance" else "Compatibility & guidance")
                }
                if (expanded) {
                    Text(purpose.description, style = MaterialTheme.typography.bodySmall)
                    Text("Inputs: ${ModelGuide.inputsLabel(spec)}", style = MaterialTheme.typography.bodySmall)
                    if (spec.supportsAudio) Text("Audio clips: up to 30 seconds, 16 kHz mono PCM WAV. Voice calls use the selected voice input path.",
                        style = MaterialTheme.typography.bodySmall)
                    if (spec.supportsTools) Text("Tool support does not mean this model has passed a tool-calling test. Calls are checked against your request before execution.",
                        style = MaterialTheme.typography.bodySmall)
                    if (purpose.caveat.isNotBlank()) Text(purpose.caveat, style = MaterialTheme.typography.bodySmall)
                    if (evidence.status != ModelEvidenceStatus.ISSUE) Text(evidence.summary, style = MaterialTheme.typography.bodySmall)
                    if (evidence.details.isNotBlank()) Text(evidence.details, style = MaterialTheme.typography.bodySmall)
                    Text("An Android test is not a speed or reliability guarantee for your phone.", style = MaterialTheme.typography.bodySmall)
                    Text(fit.explanation, style = MaterialTheme.typography.bodySmall)
                    Text(fit.workloadExplanation, style = MaterialTheme.typography.bodySmall)
                    fit.deviceExperience?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("${phone.name} · ${ModelGuidance.gb(phone.totalRamBytes)} RAM · ${ModelGuidance.gb(phone.freeStorageBytes)} free storage",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Download size is not measured memory use. These estimates do not change when you install a model. Phone checks stay on your device.",
                        style = MaterialTheme.typography.bodySmall)
                    val source = evidence.source ?: spec.downloadUrl?.substringBefore("/resolve/")
                    if (source != null) TextButton(onClick = {
                        runCatching { uriHandler.openUri(source) }.onFailure { linkError = "Could not open the publisher page." }
                    }, modifier = Modifier.testTag("model_details_publisher")) { Text("Publisher evidence") }
                    linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    )
}
