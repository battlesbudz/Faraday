@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.battlesbudz.jarvis.v2.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.battlesbudz.jarvis.v2.code.CodeApkExporter
import com.battlesbudz.jarvis.v2.code.CodeApkInstaller
import com.battlesbudz.jarvis.v2.code.CodeConsoleStore
import com.battlesbudz.jarvis.v2.code.CodeEngineAccess
import com.battlesbudz.jarvis.v2.code.CodeFixLoopResult
import com.battlesbudz.jarvis.v2.code.CodeModeDriver
import com.battlesbudz.jarvis.v2.code.CodeOperationReport
import com.battlesbudz.jarvis.v2.code.CodePreview
import com.battlesbudz.jarvis.v2.code.CodeProjectStore
import com.battlesbudz.jarvis.v2.code.CodeRoundOutcome
import com.battlesbudz.jarvis.v2.code.CodeRoundProgress
import com.battlesbudz.jarvis.v2.code.CodeStatsStore
import com.battlesbudz.jarvis.v2.code.ProjectFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Code destination (scope Unit 6; Decision C: code mode is its
 * own destination with a project list, not in-conversation coding).
 *
 * The list renders the [CodeProjectStore] contents — create, open,
 * rename (through `project.json`; the id, folder, and derived
 * package name never change), delete behind a confirmation. The
 * project view wires the landed machinery through [CodeModeDriver]:
 * the request box runs a round (Decision A: no approval step; the
 * completeness gate and version snapshots are the safety), an
 * applied round reloads the preview and runs the bounded automatic
 * fix pass, the Console pane carries the errors and the Fix errors
 * button, the Files and Brief panes show the project's own record,
 * the Versions dialog rolls back, and Export APK plus the Install
 * button (Decision B, over Unit 5's `CodeApkInstaller` hand-off)
 * finish the loop, with Save and Share beside them.
 *
 * Rounds run on the shared engine through [CodeEngineAccess]; while
 * a chat reply or a voice call holds the model, [roundsEnabled] is
 * false and the borrow would refuse anyway — the status line says
 * so instead of failing silently.
 *
 * [previewContent] is the preview pane's mount point, defaulting to
 * the real WebView preview; screen tests substitute a stub so the
 * list and panes are pinned without a WebView.
 */
@Composable
internal fun CodeScreen(
    store: CodeProjectStore,
    statsStore: CodeStatsStore,
    engineAccess: CodeEngineAccess,
    roundsEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    previewContent: @Composable (
        project: CodeProjectStore.CodeProject,
        consoleStore: CodeConsoleStore,
        onPreviewReady: (CodePreview?) -> Unit,
    ) -> Unit = { project, consoleStore, onPreviewReady ->
        CodePreviewPane(store.projectDir(project.id), consoleStore, onPreviewReady)
    },
) {
    val scope = rememberCoroutineScope()
    val driver = remember(store, statsStore, engineAccess) { CodeModeDriver(store, statsStore, engineAccess) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFirstRequest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CodeProjectStore.CodeProject?>(null) }
    var deleteTarget by remember { mutableStateOf<CodeProjectStore.CodeProject?>(null) }

    val projects = remember(store, refreshKey) { store.listProjects() }
    val openProject = openProjectId?.let { id -> projects.firstOrNull { it.id == id } }

    fun releaseSession() {
        CoroutineScope(Dispatchers.IO).launch {
            engineAccess.withEngine { engine, _ -> driver.releaseSession(engine) }
        }
    }

    BackHandler {
        if (openProjectId != null) {
            openProjectId = null
            releaseSession()
        } else {
            onBack()
        }
    }
    DisposableEffect(driver, engineAccess) {
        onDispose { releaseSession() }
    }

    Column(modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (openProject == null) {
            CodeProjectList(
                projects = projects,
                onBack = onBack,
                onNewProject = { showCreate = true },
                onOpen = { openProjectId = it.id },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
            )
        } else {
            CodeProjectView(
                store = store,
                statsStore = statsStore,
                driver = driver,
                engineAccess = engineAccess,
                initialProject = openProject,
                roundsEnabled = roundsEnabled,
                initialRequest = pendingFirstRequest?.takeIf { it.second == openProject.id }?.first,
                onInitialRequestConsumed = { pendingFirstRequest = null },
                onClose = {
                    openProjectId = null
                    releaseSession()
                },
                onProjectsChanged = { refreshKey++ },
                previewContent = previewContent,
            )
        }
    }

    if (showCreate) {
        CodeCreateDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, firstRequest ->
                showCreate = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        store.createProject(
                            name.ifBlank { deriveProjectName(firstRequest) },
                            firstRequest,
                            System.currentTimeMillis(),
                        )
                    }
                    refreshKey++
                    pendingFirstRequest = firstRequest to created.id
                    openProjectId = created.id
                }
            },
        )
    }

    renameTarget?.let { target ->
        CodeRenameDialog(
            project = target,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                renameTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.saveProject(target.copy(name = newName)) }
                    refreshKey++
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    "Its files, brief, round log, and saved versions are removed from the " +
                        "workspace. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) { store.deleteProject(target.id) }
                            refreshKey++
                        }
                    },
                    modifier = Modifier.testTag("code_delete_confirm"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

/** A blank project name falls back to the first request's opening words. */
private fun deriveProjectName(firstRequest: String): String =
    firstRequest.trim().split(Regex("\\s+")).take(5).joinToString(" ").take(48)
        .ifBlank { "Untitled project" }

@Composable
private fun CodeProjectList(
    projects: List<CodeProjectStore.CodeProject>,
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    onOpen: (CodeProjectStore.CodeProject) -> Unit,
    onRename: (CodeProjectStore.CodeProject) -> Unit,
    onDelete: (CodeProjectStore.CodeProject) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            TextButton(onClick = onBack, modifier = Modifier.testTag("code_back")) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNewProject, modifier = Modifier.testTag("code_new_project")) { Text("New project") }
        }
        Text("Code projects", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Small web apps Faraday builds with the loaded model, kept in the sandbox " +
                "workspace with their files, brief, and saved versions.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        if (projects.isEmpty()) {
            Text(
                "No projects yet. Start one with New project.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("code_empty"),
            )
        } else {
            LazyColumn {
                items(projects, key = { it.id }) { project ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("code_project_${project.id}")) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${project.rounds} rounds · updated ${formatUpdated(project.updatedAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            Button(onClick = { onOpen(project) }, modifier = Modifier.testTag("code_open_${project.id}")) { Text("Open") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onRename(project) }) { Text("Rename") }
                            TextButton(onClick = { onDelete(project) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatUpdated(millis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(millis))

@Composable
private fun CodeCreateDialog(onDismiss: () -> Unit, onCreate: (name: String, firstRequest: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New code project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("code_create_name"),
                )
                OutlinedTextField(
                    value = request, onValueChange = { request = it },
                    label = { Text("First request") },
                    modifier = Modifier.fillMaxWidth().testTag("code_create_request"),
                )
                Text(
                    "The first round starts as soon as the project is created — there is no " +
                        "approval step. The gate keeps the last working version safe.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), request.trim()) },
                enabled = request.isNotBlank(),
                modifier = Modifier.testTag("code_create_confirm"),
            ) { Text("Create and start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CodeRenameDialog(
    project: CodeProjectStore.CodeProject,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("code_rename_input"),
                )
                Text(
                    "Only the name changes. The project's folder and its exported package " +
                        "name stay the same, so earlier exports still update-install.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("code_rename_confirm"),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class CodePane { PREVIEW, CONSOLE, FILES, BRIEF }

private data class ExportedInfo(val filePath: String, val packageName: String, val label: String)

@Composable
private fun CodeProjectView(
    store: CodeProjectStore,
    statsStore: CodeStatsStore,
    driver: CodeModeDriver,
    engineAccess: CodeEngineAccess,
    initialProject: CodeProjectStore.CodeProject,
    roundsEnabled: Boolean,
    initialRequest: String?,
    onInitialRequestConsumed: () -> Unit,
    onClose: () -> Unit,
    onProjectsChanged: () -> Unit,
    previewContent: @Composable (
        project: CodeProjectStore.CodeProject,
        consoleStore: CodeConsoleStore,
        onPreviewReady: (CodePreview?) -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectId = initialProject.id
    var project by remember(projectId) { mutableStateOf(initialProject) }
    var pane by rememberSaveable(projectId) { mutableStateOf(CodePane.PREVIEW) }
    val consoleStore = remember(projectId) { CodeConsoleStore() }
    var preview by remember(projectId) { mutableStateOf<CodePreview?>(null) }
    var requestText by rememberSaveable(projectId) { mutableStateOf("") }
    var busy by remember(projectId) { mutableStateOf(false) }
    var writingFile by remember(projectId) { mutableStateOf<String?>(null) }
    var statusText by remember(projectId) { mutableStateOf<String?>(null) }
    var lastReply by remember(projectId) { mutableStateOf<String?>(null) }
    var exported by remember(projectId) { mutableStateOf<ExportedInfo?>(null) }
    var showVersions by remember(projectId) { mutableStateOf(false) }
    var rollbackTarget by remember(projectId) { mutableStateOf<Int?>(null) }
    var paneRefresh by remember(projectId) { mutableIntStateOf(0) }
    // The Console pane re-reads its store when it is opened, when an
    // operation completes (through paneRefresh), and on Clear — no
    // polling loop watches the WebView.
    var consoleTick by remember(projectId) { mutableIntStateOf(0) }

    val reloadPreview: suspend () -> Unit = remember(projectId) {
        suspend {
            withContext(Dispatchers.Main) { preview?.reload() }
            Unit
        }
    }
    val onProgress = remember(projectId) {
        { progress: CodeRoundProgress -> writingFile = progress.writingFilePath }
    }

    fun applyReport(report: CodeOperationReport) {
        when (report) {
            is CodeOperationReport.Round -> when (val outcome = report.outcome) {
                is CodeRoundOutcome.Applied -> {
                    exported = null
                    lastReply = null
                    statusText = "Applied: wrote ${outcome.writtenPaths.joinToString(", ")}. " +
                        "The state before this round is saved as v${outcome.version} under Versions." +
                        fixSummary(report.fixResult)
                }
                is CodeRoundOutcome.Replied -> {
                    lastReply = outcome.text
                    statusText = "The model replied without writing files."
                }
                is CodeRoundOutcome.Failed ->
                    statusText = "The round failed: ${outcome.reason} Nothing was written; the last working version is untouched."
                is CodeRoundOutcome.Refused ->
                    statusText = "Code mode refused the round: ${outcome.reason}"
            }
            is CodeOperationReport.Fix -> statusText = "Fix errors:" + fixSuffixText(report.result)
            is CodeOperationReport.Unavailable ->
                statusText = "Nothing ran: ${report.reason}. Try again when the model is free."
            is CodeOperationReport.FailedToRun ->
                statusText = "The operation could not run: ${report.reason}"
        }
    }

    fun launchOperation(onReport: (CodeOperationReport) -> Unit = {}, block: suspend () -> CodeOperationReport) {
        if (busy) return
        busy = true
        writingFile = null
        scope.launch {
            val (report, fresh) = withContext(Dispatchers.IO) {
                val result = block()
                result to store.loadProject(projectId)
            }
            if (fresh != null) project = fresh
            applyReport(report)
            onReport(report)
            paneRefresh++
            onProjectsChanged()
            busy = false
            writingFile = null
        }
    }

    fun send(text: String) {
        val request = text.trim()
        if (request.isEmpty() || busy) return
        requestText = ""
        launchOperation(
            block = { driver.sendUserRound(project, request, consoleStore, reloadPreview, onProgress) },
            onReport = { report ->
                // A round that never ran keeps Battles's words in the box.
                if (report is CodeOperationReport.Unavailable || report is CodeOperationReport.FailedToRun) {
                    requestText = request
                }
            },
        )
    }

    LaunchedEffect(projectId, initialRequest) {
        val first = initialRequest ?: return@LaunchedEffect
        onInitialRequestConsumed()
        send(first)
    }

    fun exportApk() {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val exportedResult = CodeApkExporter.forContext(context.applicationContext, store)
                    .exportProjectApk(project)
                when (exportedResult) {
                    is CodeApkExporter.Result.Exported ->
                        statsStore.recordExport(true, "exported ${project.name} as ${exportedResult.packageName}", null)
                    is CodeApkExporter.Result.Refused ->
                        statsStore.recordExport(false, "export refused on ${project.name}", exportedResult.reason)
                }
                exportedResult
            }
            when (result) {
                is CodeApkExporter.Result.Exported -> {
                    exported = ExportedInfo(result.apkFile.absolutePath, result.packageName, result.label)
                    statusText = "Exported ${result.label} (${result.packageName}). Install it here, save it, or share it."
                }
                is CodeApkExporter.Result.Refused -> statusText = "Export refused: ${result.reason}"
            }
            busy = false
        }
    }

    fun installExport() {
        val info = exported ?: return
        if (!CodeApkInstaller.canInstallPackages(context)) {
            runCatching { context.startActivity(CodeApkInstaller.unknownAppSourcesIntent(context).withNewTaskIfNeeded(context)) }
            statusText = "Android needs Faraday allowed to install apps first. Allow it on the screen " +
                "that just opened, come back, and tap Install again."
            return
        }
        val failure = runCatching {
            context.startActivity(CodeApkInstaller.installIntent(context, File(info.filePath)).withNewTaskIfNeeded(context))
        }.exceptionOrNull()
        statusText = if (failure == null) {
            "The installer is open for ${info.label}. Finish there, then open the app — it runs fully offline."
        } else {
            "The installer could not open: ${failure.message}"
        }
    }

    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val info = exported
        if (uri != null && info != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("the destination is not writable")
                        stream.use { out -> File(info.filePath).inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                }
                statusText = if (saved) {
                    "Saved ${info.label} as an APK file. Install it from the saved file any time."
                } else {
                    "Saving the APK failed."
                }
            }
        }
    }

    fun shareExport() {
        val info = exported ?: return
        val file = File(info.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.code-exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(info.label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share ${info.label}")) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            TextButton(onClick = onClose, modifier = Modifier.testTag("code_project_back")) { Text("‹ Projects") }
            Text(
                project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 10.dp),
            )
            TextButton(
                onClick = { showVersions = true },
                enabled = !busy,
                modifier = Modifier.testTag("code_versions"),
            ) { Text("Versions") }
            Button(onClick = ::exportApk, enabled = !busy, modifier = Modifier.testTag("code_export")) { Text("Export APK") }
        }

        exported?.let { info ->
            Row {
                Button(onClick = ::installExport, modifier = Modifier.testTag("code_install")) { Text("Install") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { saveExportLauncher.launch("$projectId.apk") },
                    modifier = Modifier.testTag("code_save_apk"),
                ) { Text("Save") }
                TextButton(onClick = ::shareExport, modifier = Modifier.testTag("code_share_apk")) { Text("Share") }
            }
            Text("${info.label} · ${info.packageName}", style = MaterialTheme.typography.bodySmall)
        }

        Row {
            CodePane.entries.forEach { tab ->
                TextButton(
                    onClick = { pane = tab; if (tab == CodePane.CONSOLE) consoleTick++ },
                    modifier = Modifier.testTag("code_tab_${tab.name.lowercase()}"),
                ) {
                    Text(
                        tab.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = if (pane == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { preview?.reload() }, modifier = Modifier.testTag("code_reload_preview")) { Text("Reload") }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // The preview stays composed under the other panes so tab
            // switches never destroy the WebView or lose the page.
            previewContent(project, consoleStore) { ready -> preview = ready }
            when (pane) {
                CodePane.PREVIEW -> Unit
                CodePane.CONSOLE -> CodeConsolePane(
                    consoleStore = consoleStore,
                    tick = consoleTick + paneRefresh,
                    busy = busy,
                    onFixErrors = {
                        launchOperation { driver.fixErrors(project, consoleStore, reloadPreview, onProgress) }
                    },
                    onClear = { consoleStore.clear(); consoleTick++ },
                )
                CodePane.FILES -> CodeFilesPane(store = store, projectId = projectId, refreshKey = paneRefresh)
                CodePane.BRIEF -> CodeBriefPane(store = store, project = project, refreshKey = paneRefresh)
            }
        }

        lastReply?.let { reply ->
            Text(reply, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).testTag("code_reply"))
        }
        if (busy) {
            Text(
                writingFile?.let { "Writing $it…" } ?: "Working…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("code_status"),
            )
        } else {
            statusText?.let { status ->
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).testTag("code_status"))
            }
        }
        if (!roundsEnabled) {
            Text(
                "Rounds pause while a chat reply or a voice call is using the model.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = requestText,
                onValueChange = { requestText = it },
                label = { Text("What should Faraday build or change?") },
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag("code_request_input"),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { send(requestText) },
                enabled = !busy && requestText.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp).testTag("code_send"),
            ) { Text("Send") }
        }
    }

    if (showVersions) {
        val versions = remember(showVersions, paneRefresh) { store.listVersions(projectId) }
        AlertDialog(
            onDismissRequest = { showVersions = false },
            title = { Text("Saved versions") },
            text = {
                if (versions.isEmpty()) {
                    Text("No saved versions yet. A version is saved before each applied round and each rollback.")
                } else {
                    LazyColumn {
                        items(versions) { version ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("v$version", modifier = Modifier.weight(1f).padding(top = 10.dp))
                                TextButton(
                                    onClick = { rollbackTarget = version },
                                    modifier = Modifier.testTag("code_rollback_$version"),
                                ) { Text("Roll back") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVersions = false }) { Text("Close") } },
        )
    }

    rollbackTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rollbackTarget = null },
            title = { Text("Roll back to v$target?") },
            text = {
                Text(
                    "The project's files return to v$target. The current state is saved as a " +
                        "new version first, so this rollback can itself be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        rollbackTarget = null
                        showVersions = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val rolled = store.rollback(project, target, System.currentTimeMillis())
                                    val savedAs = store.listVersions(projectId).maxOrNull()
                                    statsStore.recordOutcome(
                                        "rolled back ${rolled.name} to v$target " +
                                            "(pre-rollback state saved as v$savedAs)"
                                    )
                                    rolled to savedAs
                                }
                            }
                            result.fold(
                                onSuccess = { (rolled, savedAs) ->
                                    project = rolled
                                    exported = null
                                    statusText = "Rolled back to v$target. The state before the rollback is saved as v$savedAs."
                                    paneRefresh++
                                    onProjectsChanged()
                                    preview?.reload()
                                },
                                onFailure = { failure ->
                                    statusText = "The rollback failed: ${failure.message}"
                                },
                            )
                        }
                    },
                    modifier = Modifier.testTag("code_rollback_confirm"),
                ) { Text("Roll back") }
            },
            dismissButton = { TextButton(onClick = { rollbackTarget = null }) { Text("Cancel") } },
        )
    }
}

/** The automatic fix pass's verdict, as one status sentence. */
private fun fixSummary(fixResult: CodeFixLoopResult?): String = when (fixResult) {
    null -> ""
    is CodeFixLoopResult.Clean ->
        if (fixResult.fixRounds == 0) " The preview reports no console errors."
        else " The preview's console errors were fixed in ${fixResult.fixRounds} fix round(s); the console is clean now."
    is CodeFixLoopResult.CapReached ->
        " ${fixResult.remainingErrors.size} console error(s) remain after ${fixResult.fixRounds} automatic fix round(s). See the Console pane, or tap Fix errors there."
    is CodeFixLoopResult.Stopped -> " The fix pass stopped: a fix round did not land."
    is CodeFixLoopResult.NoProgress -> " The fix pass stopped: the same errors came back after a fix round."
}

private fun fixSuffixText(result: CodeFixLoopResult): String = when (result) {
    is CodeFixLoopResult.Clean ->
        if (result.fixRounds == 0) " the console is already clean."
        else " the console is clean after ${result.fixRounds} fix round(s)."
    is CodeFixLoopResult.CapReached ->
        " ${result.remainingErrors.size} console error(s) still remain after ${result.fixRounds} fix round(s)."
    is CodeFixLoopResult.Stopped -> " a fix round did not land, so the pass stopped."
    is CodeFixLoopResult.NoProgress -> " the same errors came back after a fix round, so the pass stopped."
}

@Composable
private fun CodeConsolePane(
    consoleStore: CodeConsoleStore,
    tick: Int,
    busy: Boolean,
    onFixErrors: () -> Unit,
    onClear: () -> Unit,
) {
    val entries = remember(tick, consoleStore) { consoleStore.entries() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(8.dp).testTag("code_pane_console")
    ) {
        Row {
            Text("Console", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(top = 8.dp))
            TextButton(onClick = onClear) { Text("Clear") }
            Button(onClick = onFixErrors, enabled = !busy, modifier = Modifier.testTag("code_fix_errors")) { Text("Fix errors") }
        }
        if (entries.isEmpty()) {
            Text("Nothing reported yet. Errors the page throws appear here, newest last.")
        } else {
            LazyColumn {
                items(entries) { entry ->
                    val where = entry.sourceId?.let { source ->
                        " (${source.substringAfterLast('/')}:${entry.lineNumber})"
                    } ?: ""
                    Text(
                        "${entry.level.name.lowercase()}: ${entry.message}$where",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeFilesPane(store: CodeProjectStore, projectId: String, refreshKey: Int) {
    val files = remember(refreshKey, projectId) { ProjectFiles.list(store.projectDir(projectId)) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(8.dp).testTag("code_pane_files")
    ) {
        Text("Files", style = MaterialTheme.typography.titleSmall)
        if (files.isEmpty()) {
            Text("No files yet.")
        } else {
            LazyColumn {
                items(files) { entry ->
                    Text(
                        "${entry.path} · ${entry.sizeBytes} bytes",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBriefPane(store: CodeProjectStore, project: CodeProjectStore.CodeProject, refreshKey: Int) {
    val brief = remember(refreshKey, project.id) { store.readBrief(project) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(8.dp).verticalScroll(rememberScrollState()).testTag("code_pane_brief")
    ) {
        Text("Brief", style = MaterialTheme.typography.titleSmall)
        if (brief == null) {
            Text("No brief yet. The brief is the project's memory: it is rebuilt into every round's prompt.")
        } else {
            Text("Goal", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(brief.goal.ifBlank { "Not recorded." }, style = MaterialTheme.typography.bodyMedium)
            Text("Decisions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (brief.decisions.isEmpty()) Text("None recorded yet.", style = MaterialTheme.typography.bodyMedium)
            brief.decisions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Known problems", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (brief.knownProblems.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodyMedium)
            brief.knownProblems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Next steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (brief.nextSteps.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodyMedium)
            brief.nextSteps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * The real preview mount: one [CodePreview] per project folder and
 * console store, its WebView filling the pane. The preview is
 * created once and destroyed with the pane; tab switches never
 * unmount it (the screen keeps it composed under the other panes).
 */
@Composable
private fun CodePreviewPane(
    projectDir: File,
    consoleStore: CodeConsoleStore,
    onPreviewReady: (CodePreview?) -> Unit,
) {
    val context = LocalContext.current
    val preview = remember(projectDir, consoleStore) { CodePreview(context, consoleStore) }
    DisposableEffect(preview) {
        onPreviewReady(preview)
        onDispose {
            onPreviewReady(null)
            preview.destroy()
        }
    }
    LaunchedEffect(preview) { preview.loadProject(projectDir) }
    AndroidView(factory = { preview.webView }, modifier = Modifier.fillMaxSize().testTag("code_preview_webview"))
}

/** Intents that leave the app need the new-task flag from a non-Activity context. */
private fun Intent.withNewTaskIfNeeded(context: Context): Intent =
    if (context is Activity) this else apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
