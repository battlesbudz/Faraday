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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.code.CodeApkExporter
import com.battlesbudz.jarvis.v2.code.CodeApkInstaller
import com.battlesbudz.jarvis.v2.code.CodeConsoleStore
import com.battlesbudz.jarvis.v2.code.CodePreview
import com.battlesbudz.jarvis.v2.code.CodeProjectStore
import com.battlesbudz.jarvis.v2.code.CodeRoundProgress
import com.battlesbudz.jarvis.v2.code.ConversationCodeController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Code mode's conversation surfaces (Battles, 2026-11-09: the
 * separate code destination is reversed; code mode lives in the
 * conversation). Three pieces, none of them a working surface of
 * their own:
 *
 *  - [CodeConversationBar] sits in the conversation directly above
 *    the composer: the pick entry, the active project's name with
 *    Preview and Export, the export's Install/Save/Share row, and —
 *    while a round runs — the live build view. Directions are typed
 *    or dictated into the conversation's own composer; the bar has
 *    no input of its own.
 *  - [CodeProjectsPicker] is the project list, opened from the bar
 *    (or the voice call screen) to pick, create, rename, or delete.
 *    Picking returns to the conversation; creating also starts the
 *    project's first round there.
 *  - [CodePreviewViewer] runs the built project, opened from the
 *    bar: the preview and its console, nothing else.
 */

// ---------------------------------------------------------------------------
// The bar above the conversation's composer
// ---------------------------------------------------------------------------

private data class ExportedInfo(val filePath: String, val packageName: String, val label: String)

private val ExportedInfoSaver: Saver<MutableState<ExportedInfo?>, Any> = listSaver(
    save = { state -> state.value?.let { listOf(it.filePath, it.packageName, it.label) } ?: emptyList() },
    restore = { parts -> mutableStateOf(if (parts.size == 3) ExportedInfo(parts[0], parts[1], parts[2]) else null) },
)

@Composable
fun CodeConversationBar(
    controller: ConversationCodeController,
    onOpenPicker: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val activeId by controller.activeProjectId.collectAsState()
    val projectsVersion by controller.projectsVersion.collectAsState()
    val project = remember(activeId, projectsVersion) { activeId?.let { controller.store.loadProject(it) } }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (project == null) {
            Row {
                TextButton(onClick = onOpenPicker, modifier = Modifier.testTag("code_pick_project")) {
                    Text("Code projects")
                }
                Text(
                    "Pick a project to build it from this conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            CodeActiveProjectBar(controller, project, onOpenPicker, onOpenPreview)
        }
    }
}

@Composable
private fun CodeActiveProjectBar(
    controller: ConversationCodeController,
    project: CodeProjectStore.CodeProject,
    onOpenPicker: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val store = controller.store
    val statsStore = controller.statsStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectId = project.id
    // Saveable on purpose: the install-permission detour can restart
    // the activity, and losing the export on return is what made
    // Export look dead on build 1771 — the Install row vanished and
    // the flow had to start over.
    var exported by rememberSaveable(projectId, saver = ExportedInfoSaver) { mutableStateOf<ExportedInfo?>(null) }
    // Set when Install sent Battles to the unknown-sources settings;
    // the resume observer below finishes the install on return.
    var pendingInstall by rememberSaveable(projectId) { mutableStateOf(false) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var busyNote by remember(projectId) { mutableStateOf<String?>(null) }
    var statusText by remember(projectId) { mutableStateOf<String?>(null) }
    var elapsedSeconds by remember(projectId) { mutableIntStateOf(0) }

    val liveRound by controller.driver.liveRound.collectAsState()
    val roundInFlight = liveRound?.projectId == projectId
    val roundProgress = if (roundInFlight) liveRound?.progress else null
    val working = busy || roundInFlight

    // The busy clock: while anything runs, the elapsed seconds tick,
    // so a working round and a frozen one are distinguishable at a
    // glance even before the first streamed character lands.
    LaunchedEffect(working, projectId) {
        elapsedSeconds = 0
        if (working) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(500)
                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            }
        }
    }

    fun exportApk() {
        if (working) return
        busy = true
        busyNote = "Exporting…"
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
            busyNote = null
        }
    }

    fun openInstaller() {
        val info = exported ?: return
        val failure = runCatching {
            context.startActivity(CodeApkInstaller.installIntent(context, File(info.filePath)).withNewTaskIfNeeded(context))
        }.exceptionOrNull()
        statusText = if (failure == null) {
            "The installer is open for ${info.label}. Finish there, then open the app — it runs fully offline."
        } else {
            "The installer could not open: ${failure.message}"
        }
    }

    fun installExport() {
        if (exported == null) return
        if (!CodeApkInstaller.canInstallPackages(context)) {
            // The permission detour: remember the pending install,
            // and the resume observer below finishes it when Battles
            // comes back — on build 1771 the return path lost the
            // export and the flow died here.
            pendingInstall = true
            runCatching { context.startActivity(CodeApkInstaller.unknownAppSourcesIntent(context).withNewTaskIfNeeded(context)) }
            statusText = "Android needs Faraday allowed to install apps first. Allow it on the screen " +
                "that just opened, then come back — the install starts on its own."
            return
        }
        openInstaller()
    }

    // Returning from the unknown-sources settings with the permission
    // granted completes a pending install without another tap.
    val installLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(installLifecycleOwner, projectId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingInstall &&
                CodeApkInstaller.canInstallPackages(context)
            ) {
                pendingInstall = false
                openInstaller()
            }
        }
        installLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { installLifecycleOwner.lifecycle.removeObserver(observer) }
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

    // While a round runs, the build is visible here in the
    // conversation: the file being written, its code arriving, and
    // the working signals (attempt, character count, clock).
    if (roundInFlight && busyNote == null) {
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            CodeLiveBuildView(progress = roundProgress, elapsedSeconds = elapsedSeconds)
        }
    }
    Row {
        TextButton(onClick = onOpenPicker, modifier = Modifier.testTag("code_switch_project")) { Text("Projects") }
        Text(
            project.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(top = 10.dp),
        )
        TextButton(
            onClick = onOpenPreview,
            modifier = Modifier.testTag("code_preview_open"),
        ) { Text("Preview") }
        Button(onClick = ::exportApk, enabled = !working, modifier = Modifier.testTag("code_export")) { Text("Export APK") }
        TextButton(
            onClick = { controller.leaveProject() },
            modifier = Modifier.testTag("code_leave_project"),
        ) { Text("Leave") }
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
    if (working) {
        Text(
            busyNote ?: roundProgress?.writingFilePath?.let { "Writing $it…" } ?: "Working…",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp).testTag("code_status"),
        )
    } else {
        statusText?.let { status ->
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp).testTag("code_status"))
        }
    }
}

/**
 * The live build view: while a round runs, the file currently being
 * written and its code appearing as it is generated, plus the round/
 * attempt and the streamed character count beside the ticking clock —
 * a frozen generation is distinguishable from a working one at a
 * glance (the characters and the clock both stop). The streamed text
 * is a display of the partial response, capped to its tail so a long
 * file cannot grow the composition without bound.
 */
@Composable
private fun CodeLiveBuildView(progress: CodeRoundProgress?, elapsedSeconds: Int) {
    val content = progress?.writingContent.orEmpty()
    val shown = if (content.length > LIVE_CONTENT_TAIL_CHARS) {
        content.substring(content.length - LIVE_CONTENT_TAIL_CHARS)
    } else {
        content
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(shown.length) { scrollState.scrollTo(scrollState.maxValue) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(12.dp).testTag("code_live_view")
    ) {
        val headline = buildString {
            append(progress?.writingFilePath?.let { "Writing $it" } ?: "Working")
            if (progress != null && progress.attempt > 0) {
                append(" · attempt ${progress.attempt} of ${progress.maxAttempts}")
            }
            append(" · ${progress?.streamedChars ?: 0} characters")
            append(" · ${elapsedSeconds}s")
        }
        Text(
            headline,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("code_live_status"),
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                "Waiting for the model's first file…",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier.testTag("code_live_content"),
                )
            }
        }
    }
}

/** How much of the streaming file body the live view keeps on screen. */
private const val LIVE_CONTENT_TAIL_CHARS = 12_000

// ---------------------------------------------------------------------------
// The project picker
// ---------------------------------------------------------------------------

@Composable
fun CodeProjectsPicker(
    controller: ConversationCodeController,
    onDismiss: () -> Unit,
) {
    val store = controller.store
    val scope = rememberCoroutineScope()
    val projectsVersion by controller.projectsVersion.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CodeProjectStore.CodeProject?>(null) }
    var deleteTarget by remember { mutableStateOf<CodeProjectStore.CodeProject?>(null) }
    val projects = remember(store, projectsVersion) { store.listProjects() }

    BackHandler {
        when {
            showCreate -> showCreate = false
            renameTarget != null -> renameTarget = null
            deleteTarget != null -> deleteTarget = null
            else -> onDismiss()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val renaming = renameTarget
        when {
            showCreate -> CodeCreatePanel(
                onDismiss = { showCreate = false },
                onCreate = { name, firstRequest ->
                    showCreate = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            controller.createProject(
                                name.ifBlank { deriveProjectName(firstRequest) },
                                firstRequest,
                            )
                        }
                        onDismiss()
                        // The first round starts as a turn in the
                        // conversation, as if Battles had typed the
                        // request there himself.
                        controller.sendDirection(firstRequest)
                    }
                },
            )
            renaming != null -> CodeRenamePanel(
                project = renaming,
                onDismiss = { renameTarget = null },
                onRename = { newName ->
                    renameTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { controller.renameProject(renaming, newName) }
                    }
                },
            )
            else -> CodeProjectList(
                projects = projects,
                onBack = onDismiss,
                onNewProject = { showCreate = true },
                onOpen = { project ->
                    controller.pickProject(project.id)
                    onDismiss()
                },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
            )
        }
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
                            withContext(Dispatchers.IO) { controller.deleteProject(target) }
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
            "Small web apps Faraday builds with the loaded model. Pick one and direct " +
                "the work from the conversation — type or dictate what to build or change.",
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
                            Button(onClick = { onOpen(project) }, modifier = Modifier.testTag("code_open_${project.id}")) { Text("Work here") }
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

/**
 * The create and rename forms are inline panels, not dialog windows:
 * full-width fields suit a phone, and the repo's Robolectric screen
 * tests cannot idle a text field inside a dialog window (it
 * recomposes forever there and every interaction times out as
 * not-idle). The delete confirmation stays a dialog — it has no
 * text field and behaves in both worlds.
 */
@Composable
private fun CodeCreatePanel(onDismiss: () -> Unit, onCreate: (name: String, firstRequest: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
        }
        Text("New code project", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
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
            "The first round starts in the conversation as soon as the project is " +
                "created — there is no approval step. The gate keeps the last working version safe.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onCreate(name.trim(), request.trim()) },
            enabled = request.isNotBlank(),
            modifier = Modifier.testTag("code_create_confirm"),
        ) { Text("Create and start") }
    }
}

@Composable
private fun CodeRenamePanel(
    project: CodeProjectStore.CodeProject,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
        }
        Text("Rename project", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onRename(name.trim()) },
            enabled = name.isNotBlank(),
            modifier = Modifier.testTag("code_rename_confirm"),
        ) { Text("Rename") }
    }
}

// ---------------------------------------------------------------------------
// The preview viewer
// ---------------------------------------------------------------------------

private enum class CodeViewerPane { PREVIEW, CONSOLE }

@Composable
fun CodePreviewViewer(
    controller: ConversationCodeController,
    onDismiss: () -> Unit,
) {
    val projectsVersion by controller.projectsVersion.collectAsState()
    val activeId by controller.activeProjectId.collectAsState()
    val project = remember(activeId, projectsVersion) { activeId?.let { controller.store.loadProject(it) } }
    val scope = rememberCoroutineScope()
    var pane by remember { mutableStateOf(CodeViewerPane.PREVIEW) }
    var consoleTick by remember { mutableIntStateOf(0) }
    val liveRound by controller.driver.liveRound.collectAsState()
    val roundInFlight = project != null && liveRound?.projectId == project.id

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (project == null) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("No project is picked.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row {
                        Text(
                            project.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(top = 10.dp),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val mounted = controller.preview
                                    withContext(Dispatchers.Main) {
                                        mounted?.refresh(controller.store.projectDir(project.id))
                                    }
                                }
                            },
                            modifier = Modifier.testTag("code_reload_preview"),
                        ) { Text("Reload") }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("code_preview_close")) { Text("Close") }
                    }
                    Row {
                        CodeViewerPane.entries.forEach { tab ->
                            TextButton(
                                onClick = { pane = tab; if (tab == CodeViewerPane.CONSOLE) consoleTick++ },
                                modifier = Modifier.testTag("code_tab_${tab.name.lowercase()}"),
                            ) {
                                Text(
                                    tab.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (pane == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        // The preview stays composed under the console
                        // so switching tabs never destroys the WebView
                        // or loses the page — and its console feed is
                        // what the fix pass reads.
                        CodePreviewPane(controller.store.projectDir(project.id), controller.consoleStore) { ready ->
                            controller.preview = ready
                        }
                        if (pane == CodeViewerPane.CONSOLE) {
                            CodeConsolePane(
                                consoleStore = controller.consoleStore,
                                tick = consoleTick + projectsVersion,
                                busy = roundInFlight,
                                onFixErrors = { controller.fixErrors() },
                                onClear = { controller.consoleStore.clear(); consoleTick++ },
                            )
                        }
                    }
                }
            }
        }
    }
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

/**
 * The real preview mount: one [CodePreview] per project folder and
 * console store, its WebView filling the pane. The preview is
 * created once and destroyed with the viewer; tab switches never
 * unmount it (the viewer keeps it composed under the console pane).
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
