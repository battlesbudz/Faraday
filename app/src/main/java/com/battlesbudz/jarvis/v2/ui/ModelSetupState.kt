package com.battlesbudz.jarvis.v2.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.eval.ReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ToolReliabilityBenchmark
import com.battlesbudz.jarvis.v2.presentation.MODEL_SETUP_WORK_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Model-management commands supplied by the Android host, independent of app navigation. */
internal data class ModelSetupActions(
    val select: (LocalModelSpec) -> String?,
    val delete: (LocalModelSpec) -> String?,
    val test: ((String) -> Unit) -> Unit,
    val download: (LocalModelSpec, (Long, Long) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    val cancelDownload: () -> Unit,
    val importModel: (Uri, LocalModelSpec, (String) -> Unit) -> Unit,
    /**
     * User-triggered tool-call reliability check, routed through the
     * ModelSetupOperations ownership path (exclusive admission, idle chat
     * engine closed first, model-file integrity verified, gate released).
     */
    val runReliabilityCheck: (
        specs: List<LocalModelSpec>,
        reportStore: ReliabilityReportStore,
        onProgress: suspend (ToolReliabilityBenchmark.Progress) -> Unit,
        onFinished: (kotlin.Result<ToolReliabilityBenchmark.Result>) -> Unit
    ) -> Unit,
)

/** One owner for setup state shared by first-run setup and the in-call model settings. */
internal class ModelSetupState(
    val store: ModelStore,
    smokeTestPassedState: MutableState<Boolean>,
    setupStatusState: MutableState<String>,
    private val actions: () -> ModelSetupActions,
) {
    var selectedModel by mutableStateOf(store.selectedModel())
        private set
    var selectionError by mutableStateOf<String?>(null)
    var modelsReady by mutableStateOf(store.isUsable())
    var smokeTestPassed by smokeTestPassedState
    var setupStatus by setupStatusState
    var smokeTestRunning by mutableStateOf(false)
    var modelImportRunning by mutableStateOf(store.importInProgress())
    var modelDownloadRunning by mutableStateOf(false)
    var downloadingModelId by mutableStateOf<String?>(null)
    var downloadNotice by mutableStateOf("")
    var automaticSmokeTestAttempted by mutableStateOf(false)
    var downloadBytes by mutableStateOf(0L)
    var downloadTotalBytes by mutableStateOf(-1L)
    var setupElapsedSeconds by mutableStateOf(0L)

    val modelInstalled get() = store.hasModel(selectedModel)

    fun refreshReadiness() {
        modelsReady = store.isUsable()
        smokeTestPassed = modelsReady && store.smokeTestPassed()
    }

    fun select(spec: LocalModelSpec): String? {
        selectionError = actions().select(spec)
        if (selectionError == null) {
            selectedModel = store.selectedModel()
            refreshReadiness()
            automaticSmokeTestAttempted = false
            setupStatus = "Selected ${selectedModel.id}."
        }
        return selectionError
    }

    fun deleteSelectedModel() {
        selectionError = actions().delete(selectedModel)
        refreshReadiness()
        automaticSmokeTestAttempted = false
        if (selectionError == null) setupStatus = "${selectedModel.id} deleted. Choose an installed model or download one."
    }

    fun download(spec: LocalModelSpec) {
        downloadBytes = 0L
        downloadTotalBytes = -1L
        downloadNotice = "Preparing ${spec.id}…"
        actions().download(spec, { bytes, total ->
            downloadBytes = bytes
            downloadTotalBytes = total
        }, { message ->
            downloadNotice = message
            if (spec.id == selectedModel.id) setupStatus = message
        }, { result ->
            downloadNotice = result
            if (spec.id == selectedModel.id) setupStatus = result
            refreshReadiness()
        })
    }

    fun cancelDownload() = actions().cancelDownload()

    fun importModel(uri: Uri?, spec: LocalModelSpec) {
        if (uri == null) return
        modelImportRunning = true
        setupStatus = "Importing local model…"
        actions().importModel(uri, spec) { result ->
            modelImportRunning = false
            setupStatus = result
            if (result == "Model imported successfully.") {
                modelsReady = store.isUsable()
                setupStatus = "${selectedModel.id} imported. Test it to start chatting."
            }
        }
    }

    fun test() {
        smokeTestRunning = true
        actions().test { result ->
            smokeTestRunning = false
            setupStatus = result
            smokeTestPassed = store.isUsable() && store.smokeTestPassed()
        }
    }

    fun runReliabilityCheck(
        specs: List<LocalModelSpec>,
        reportStore: ReliabilityReportStore,
        onProgress: suspend (ToolReliabilityBenchmark.Progress) -> Unit,
        onFinished: (kotlin.Result<ToolReliabilityBenchmark.Result>) -> Unit
    ) = actions().runReliabilityCheck(specs, reportStore, onProgress, onFinished)
}

@Composable
internal fun rememberModelSetupState(store: ModelStore, actions: ModelSetupActions): ModelSetupState {
    val smokeTestPassed = rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    val setupStatus = rememberSaveable { mutableStateOf(
        if (store.smokeTestAttempted() && !store.smokeTestPassed())
            "The last model test did not pass or was interrupted. Retry the test or select another model."
        else "",
    ) }
    val currentActions = rememberUpdatedState(actions)
    val state = remember(store) { ModelSetupState(store, smokeTestPassed, setupStatus) { currentActions.value } }
    ObserveModelSetup(state)
    return state
}

/** Synchronize durable files/work after Activity recreation; all effects leave with the composition. */
@Composable
private fun ObserveModelSetup(state: ModelSetupState) {
    val store = state.store
    val context = LocalContext.current
    LaunchedEffect(state.selectedModel.id) {
        // Upgrades/restored files can outlive their verification metadata.
        val selected = state.selectedModel
        if (store.hasModel(selected) && !store.isUsable(selected)) {
            val recovered = withContext(Dispatchers.IO) {
                if (!store.tryBeginModelOperation()) return@withContext null
                try { store.verifyIntegrity(selected) } finally { store.endModelOperation() }
            }
            state.modelsReady = store.isUsable(selected)
            if (recovered == false) state.setupStatus = "The installed model did not pass verification. Re-import it or delete it before downloading a replacement."
        }
    }
    LaunchedEffect(state.selectedModel.id) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(MODEL_SETUP_WORK_NAME).collect { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                state.downloadingModelId = active?.progress?.getString("model_id")
                    ?: active?.tags?.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                    ?: if (active != null) state.selectedModel.id else null
                state.modelDownloadRunning = active != null && state.downloadingModelId == state.selectedModel.id
                if (active != null) {
                    active.progress.getString("stage")?.let {
                        state.downloadNotice = it
                        if (state.modelDownloadRunning) state.setupStatus = it
                    }
                    state.downloadBytes = active.progress.getLong("downloaded", 0L)
                    state.downloadTotalBytes = active.progress.getLong("total", -1L)
                }
            }
    }
    LaunchedEffect(state.modelDownloadRunning) {
        if (!state.modelDownloadRunning) {
            state.setupElapsedSeconds = 0L
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            state.setupElapsedSeconds = (System.currentTimeMillis() - startedAt) / 1_000L
            delay(1_000L)
        }
    }
    LaunchedEffect(Unit) {
        // Import completion may belong to a destroyed Activity after a rotation/fold change.
        while (true) {
            delay(500)
            state.modelsReady = store.isUsable()
            state.modelImportRunning = store.importInProgress()
            state.smokeTestPassed = state.modelsReady && store.smokeTestPassed()
        }
    }
    LaunchedEffect(state.modelsReady, state.smokeTestPassed, state.modelDownloadRunning, state.smokeTestRunning) {
        if (!state.modelsReady) state.automaticSmokeTestAttempted = false
        if (state.modelsReady && !state.smokeTestPassed && !state.modelDownloadRunning &&
            !state.smokeTestRunning && !state.automaticSmokeTestAttempted && !store.smokeTestAttempted()
        ) {
            state.automaticSmokeTestAttempted = true
            state.test()
        }
    }
}
