package com.battlesbudz.jarvis.v2.presentation

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.eval.ReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ToolReliabilityBenchmark
import com.battlesbudz.jarvis.v2.eval.admitReliabilityCheck
import com.battlesbudz.jarvis.v2.eval.checkIdleBeforeClose
import com.battlesbudz.jarvis.v2.voice.JarvisModelSetupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity-scoped model management. Downloads use durable WorkManager work;
 * import and tests use the host's lifecycle scope. This never owns a voice job.
 */
internal class ModelSetupOperations(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: ModelStore,
    private val mainHandler: Handler,
    private val session: ModelSetupSession,
) {
    fun select(spec: LocalModelSpec): String? {
        if (session.busy()) return "End the Jarvis session and any tests before switching AI models."
        if (!store.tryBeginModelSelection(spec)) return "This model is downloading or a model test is running. Choose another installed model."
        return try {
            session.closeConversation(resetCharacters = true)
            store.selectModel(spec)
            session.record("AI model selected: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not switch models: ${error.message}"
        } finally {
            store.endModelOperation()
        }
    }

    fun delete(spec: LocalModelSpec): String? {
        if (session.busy()) return "End the Jarvis session and any tests before deleting AI models."
        if (!store.tryBeginModelOperation()) return "Wait for model setup or testing to finish."
        return try {
            session.closeConversation(resetCharacters = true)
            store.deleteModel(spec)
            session.record("AI model deleted: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not delete model: ${error.message}"
        } finally {
            store.endModelOperation()
        }
    }

    fun test(report: (String) -> Unit, onFinished: ((String) -> Unit)? = null) {
        if (!store.tryBeginModelOperation()) {
            val message = "A model test is still finishing. Please try again in a moment."
            report(message)
            onFinished?.invoke(message)
            return
        }
        val job = scope.launch(Dispatchers.Default) {
            mainHandler.post { report("Loading ${store.selectedModel().id}…") }
            var engine: LiteRtLmEngine? = null
            var succeeded = false
            var finalMessage: String? = null
            try {
                store.markSmokeTestStarted()
                check(store.verifyIntegrity(store.selectedModel())) {
                    "The selected model file changed or failed integrity verification. Re-import it."
                }
                session.closeConversation(resetCharacters = false)
                engine = LiteRtLmEngine(
                    store.selectedModel().id,
                    store.fileFor(store.selectedModel()).path,
                    context.cacheDir.path,
                    useGpu = store.selectedModel().recommendedGpu,
                    tools = if (store.selectedModel().supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                    visionEnabled = false,
                    audioEnabled = false,
                )
                engine.initialize()
                val probe = engine.generate("Say hello in one short sentence.", onToken = {})
                check(AssistantText.forDisplay(probe.text).any { it.isLetterOrDigit() }) {
                    "The selected model loaded but did not return readable text."
                }
                succeeded = true
            } catch (error: Throwable) {
                finalMessage = "Selected model test failed: ${error.message ?: "unknown error"}"
            } finally {
                engine?.close()
                if (succeeded) {
                    store.markSmokeTestPassed()
                    finalMessage = "${store.selectedModel().id} initialized successfully."
                }
                finalMessage?.let { message ->
                    mainHandler.post {
                        report(message)
                        onFinished?.invoke(message)
                    }
                }
            }
        }
        job.invokeOnCompletion { store.endModelOperation() }
    }

    fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(MODEL_SETUP_WORK_NAME)
    }

    /**
     * User-triggered tool-call reliability check over [specs], routed through
     * the same ownership path as the model test: exclusive admission (the
     * session must not be busy and the model-operation gate must be free),
     * the retained idle chat engine closed before the benchmark allocates its
     * own native engine — a second native engine is never allocated over the
     * live owner — model-file integrity verified before the engine loads, and
     * the gate released afterwards. Scores are bound to the verified
     * model-file identity and the suite version. Cancellation propagates; the
     * engine is always closed and the gate always released.
     */
    fun runReliabilityCheck(
        specs: List<LocalModelSpec>,
        reportStore: ReliabilityReportStore,
        onProgress: suspend (ToolReliabilityBenchmark.Progress) -> Unit,
        onFinished: (kotlin.Result<ToolReliabilityBenchmark.Result>) -> Unit
    ) {
        scope.launch(Dispatchers.Default) {
            val result = runCatching {
                ToolReliabilityBenchmark(
                    owner = object : ToolReliabilityBenchmark.ModelOwner {
                        override fun tryBeginModel(spec: LocalModelSpec): Boolean =
                            admitReliabilityCheck(
                                acquireGate = { store.tryBeginModelSelection(spec) },
                                releaseGate = { store.endModelOperation() },
                                isBusy = { session.busy() }
                            )
                        override fun endModelOperation() = store.endModelOperation()
                        override fun closeIdleEngine() {
                            checkIdleBeforeClose { session.busy() }
                            session.closeConversation(resetCharacters = false)
                        }
                        override fun verifyModelFile(spec: LocalModelSpec): Boolean =
                            store.verifyIntegrity(spec)
                        override fun modelFingerprint(spec: LocalModelSpec): String? =
                            store.modelFingerprint(spec)
                        override fun reportTeardownIssue(message: String) =
                            session.record(message)
                    },
                    engineFactory = { spec ->
                        LiteRtLmEngine(
                            spec.id,
                            store.fileFor(spec).path,
                            context.cacheDir.path,
                            useGpu = spec.recommendedGpu,
                            tools = MobileActionToolDefinitions.all()
                        )
                    },
                    reportStore = reportStore
                ).runModels(specs, onProgress)
            }
            withContext(Dispatchers.Main) { onFinished(result) }
        }
    }

    fun download(
        spec: LocalModelSpec,
        onProgress: (Long, Long) -> Unit,
        report: (String) -> Unit,
        onFinished: (String) -> Unit,
    ) {
        val request = OneTimeWorkRequestBuilder<JarvisModelSetupWorker>()
            .setInputData(workDataOf("model_id" to spec.id,
                "prepare_voice" to (spec.id == store.selectedModel().id)))
            .addTag(MODEL_SETUP_WORK_NAME)
            .addTag("model:${spec.id}")
            .build()
        val workManager = WorkManager.getInstance(context)
        scope.launch {
            val observedId = withContext(Dispatchers.IO) {
                val priorActive = workManager.getWorkInfosForUniqueWork(MODEL_SETUP_WORK_NAME).get()
                    .firstOrNull { !it.state.isFinished }
                if (priorActive != null && "model:${spec.id}" !in priorActive.tags) return@withContext null
                workManager.enqueueUniqueWork(MODEL_SETUP_WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
                val infos = workManager.getWorkInfosForUniqueWork(MODEL_SETUP_WORK_NAME).get()
                if (infos.any { it.id == request.id }) request.id else priorActive?.id
            }
            if (observedId == null) {
                onFinished("Another download is running. Let it finish or cancel it before downloading this model.")
                return@launch
            }
            var terminal: WorkInfo? = null
            workManager.getWorkInfosForUniqueWorkFlow(MODEL_SETUP_WORK_NAME)
                .takeWhile { infos ->
                    // KEEP may attach to active work; a prior completed request is never this transfer.
                    val info = infos.firstOrNull { it.id == observedId }
                    if (info != null) {
                        val stage = info.progress.getString("stage")
                        val downloaded = info.progress.getLong("downloaded", 0L)
                        val total = info.progress.getLong("total", -1L)
                        if (!stage.isNullOrBlank()) report(stage)
                        if (downloaded > 0L) onProgress(downloaded, total)
                        if (info.state.isFinished) {
                            terminal = info
                            false
                        } else true
                    } else true
                }
                .collect { }
            val result = terminal
            if (result?.state == WorkInfo.State.SUCCEEDED) {
                // A background transfer must never close, load or test the user's current engine.
                onFinished("${spec.id} downloaded. Choose it when you're ready.")
            } else {
                val message = result?.outputData?.getString("error")
                    ?: if (result?.state == WorkInfo.State.CANCELLED) "Download cancelled. Saved progress can be resumed."
                    else "Jarvis model setup did not complete."
                report(message)
                onFinished(message)
            }
        }
    }

    fun importModel(uri: Uri, spec: LocalModelSpec, report: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = store.importModel(uri, spec)
            withContext(Dispatchers.Main) {
                if (result.isSuccess && spec.id == store.selectedModel().id) {
                    session.closeConversation(resetCharacters = false)
                }
                report(result.fold(
                    { "Model imported successfully." },
                    { error -> "Import failed: ${error.message ?: "unknown error"}" },
                ))
            }
        }
    }
}
