package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ai.storage.modelFiles
import com.battlesbudz.jarvis.v2.ai.storage.removeModelFiles
import com.battlesbudz.jarvis.v2.ai.storage.ModelDownloader
import com.battlesbudz.jarvis.v2.ai.storage.DownloadedModelLookup
import com.battlesbudz.jarvis.v2.ai.storage.sha256
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission

class ModelStore @JvmOverloads constructor(
    context: Context,
    private val conversationActive: () -> Boolean = ProcessConversationAdmission::isActive
) {
    private val downloader = ModelDownloader()
    private val downloadedModels = DownloadedModelLookup(context)

    private companion object {
        val activeImports = AtomicInteger(0)
        val operations = ModelOperationGate()
    }

    private val preferences = context.getSharedPreferences("model_setup", Context.MODE_PRIVATE)
    private val modelDirectory = File(context.filesDir, "models").apply { mkdirs() }

    init {
        if (activeImports.get() == 0 && preferences.getBoolean("import_in_progress", false)) {
            preferences.edit().putBoolean("import_in_progress", false).apply()
            modelDirectory.listFiles()
                ?.filter { it.name.endsWith(".part") && ModelCatalog.all.none { spec -> it.name == "${spec.fileName}.part" } }
                ?.forEach { it.delete() }
        }
    }

    fun selectedModel(): LocalModelSpec = ModelCatalog.resolve(preferences.getString("selected_model", null))

    /** Caller owns the model-operation lock and has released the idle native engine. */
    fun selectModel(spec: LocalModelSpec) {
        require(ModelCatalog.find(spec.id) == spec) { "Unsupported model." }
        check(isModelOperationActive()) { "Model selection requires exclusive ownership." }
        check(!operations.downloading(spec.id)) { "This model is still downloading. Choose an installed model or cancel its download." }
        check(preferences.edit().putString("selected_model", spec.id).commit()) {
            "Could not save the selected model."
        }
    }

    private fun smokeTestKey(spec: LocalModelSpec) = "smoke_test_passed_${spec.id}"

    fun fileFor(spec: LocalModelSpec): File = File(modelDirectory, spec.fileName)

    /** Caller holds the model-operation lock and has closed the idle engine. */
    fun deleteModel(spec: LocalModelSpec) {
        require(ModelCatalog.find(spec.id) == spec) { "Unsupported model." }
        check(isModelOperationActive()) { "Model deletion requires exclusive ownership." }
        check(!operations.downloading(spec.id)) { "Cancel this model's download before deleting it." }
        removeModelFiles(modelDirectory, spec.fileName, File(context.cacheDir, spec.id))
        val key = fingerprintKey(spec)
        val editor = preferences.edit()
        listOf(key, "${key}_length", "${key}_modified", "${key}_invalid",
            "${key}_enforce_catalog_hash", smokeTestKey(spec), "smoke_test_attempted_${spec.id}")
            .forEach { editor.remove(it) }
        if (spec == ModelCatalog.gemma4E2b) editor.remove("smoke_test_passed")
        check(editor.commit()) { "Model removed, but its test state could not be cleared." }
    }

    fun storedBytes(spec: LocalModelSpec): Long = modelFiles(modelDirectory, spec.fileName)
        .sumOf { it.length() } + File(modelDirectory, "${spec.fileName}.part.chunks").let { chunks ->
            if (chunks.exists()) chunks.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        } + File(context.cacheDir, spec.id).let { cache ->
            if (cache.exists()) cache.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }

    fun hasModel(spec: LocalModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() > 0L }

    fun isReady(): Boolean =
        hasModel(selectedModel())

    /**
     * Pinned SHA-256 of the installed model file, or null when the file is
     * missing, replaced, or failed integrity verification. Saved reliability
     * scores bind to this, so only a score measured against the exact current
     * valid bytes is ever presented as current — a stale cached digest is
     * never returned for a file that no longer matches it.
     */
    fun modelFingerprint(spec: LocalModelSpec): String? {
        val key = fingerprintKey(spec)
        val file = fileFor(spec)
        if (!file.isFile || file.length() == 0L) return null
        if (preferences.getBoolean("${key}_invalid", false)) return null
        if (preferences.getLong("${key}_length", -1L) != file.length()) return null
        if (preferences.getLong("${key}_modified", -1L) != file.lastModified()) return null
        return preferences.getString(key, null)
    }

    fun isUsable(spec: LocalModelSpec = selectedModel()): Boolean {
        val file = fileFor(spec)
        val key = fingerprintKey(spec)
        return hasModel(spec) &&
            !preferences.getBoolean("${key}_invalid", false) &&
            preferences.contains(key) &&
            preferences.getLong("${key}_length", -1L) == file.length() &&
            preferences.getLong("${key}_modified", -1L) == file.lastModified()
    }

    /**
     * Verifies that the file is unchanged since it was accepted by setup.
     * A first verification pins an imported file's SHA-256 so a later file
     * replacement cannot silently pass the smoke test.
     */
    fun verifyIntegrity(
        spec: LocalModelSpec,
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val file = fileFor(spec)
        if (!file.isFile || file.length() == 0L) return false
        val length = file.length()
        val modified = file.lastModified()
        val key = fingerprintKey(spec)
        val pinned = preferences.getString(key, null)
        if (pinned != null && preferences.getBoolean("${key}_invalid", false)) return false
        if (pinned != null &&
            preferences.getLong("${key}_length", -1L) == length &&
            preferences.getLong("${key}_modified", -1L) == modified
        ) {
            return true
        }
        onProgress(0L, length)
        val actual = file.sha256(onProgress)
        val enforceCatalogHash = preferences.getBoolean("${key}_enforce_catalog_hash", pinned == null)
        if (enforceCatalogHash) spec.expectedSha256?.let { expected ->
            if (!actual.equals(expected, ignoreCase = true)) {
                markIntegrityInvalid(key, length, modified)
                return false
            }
        }
        if (pinned != null && !actual.equals(pinned, ignoreCase = true)) {
            markIntegrityInvalid(key, length, modified)
            return false
        }
        preferences.edit()
            .putString(key, actual)
            .putLong("${key}_length", length)
            .putLong("${key}_modified", modified)
            .putBoolean("${key}_invalid", false)
            .apply()
        return true
    }

    fun smokeTestPassed(spec: LocalModelSpec = selectedModel()): Boolean {
        return preferences.getBoolean(smokeTestKey(spec),
            spec == ModelCatalog.gemma4E2b && preferences.getBoolean("smoke_test_passed", false))
    }

    /** Exact installed-byte identity, including user imports. Call while owning
     * the existing model-operation lease; catalog selection alone is insufficient
     * for a separately derived native audio encoder. */
    internal fun matchesVerifiedArtifact(spec: LocalModelSpec, expectedSha256: String): Boolean =
        verifyIntegrity(spec) &&
            preferences.getString(fingerprintKey(spec), null)?.equals(expectedSha256, ignoreCase = true) == true

    fun smokeTestAttempted(): Boolean =
        preferences.getBoolean("smoke_test_attempted_${selectedModel().id}", false)

    // Persist before native initialization: a process death must leave setup recoverable.
    fun markSmokeTestStarted() {
        val spec = selectedModel()
        check(preferences.edit()
            .putBoolean("smoke_test_attempted_${spec.id}", true)
            .putBoolean(smokeTestKey(spec), false).commit()) { "Could not save model test state." }
    }

    fun importInProgress(): Boolean = preferences.getBoolean("import_in_progress", false)

    fun tryBeginModelOperation(): Boolean = operations.tryBeginRuntime(selectedModel().id)

    fun tryBeginModelSelection(spec: LocalModelSpec): Boolean = operations.tryBeginRuntime(spec.id)

    fun endModelOperation() {
        operations.endRuntime()
    }

    fun isModelOperationActive(): Boolean = operations.runtimeActive() || operations.downloading(selectedModel().id)

    fun isDownloading(spec: LocalModelSpec): Boolean = operations.downloading(spec.id)

    /** Reuses a verified app copy, imports a matching local file, or downloads the pinned model. */
    suspend fun downloadOrReuse(
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result<File> = runCatching {
        check(operations.tryBeginDownload(spec.id, selectedModel().id,
            conversationActive())) {
            "This model is currently in use or already downloading. Other models can still be used."
        }
        val transferContext = kotlinx.coroutines.currentCoroutineContext()
        val reportProgress: (Long, Long) -> Unit = { bytes, total ->
            transferContext.ensureActive()
            onProgress(bytes, total)
        }
        try {
            onStatus("Checking Jarvis’s app storage…")
            if (verifyIntegrity(spec, reportProgress)) {
                onProgress(fileFor(spec).length(), fileFor(spec).length())
                return@runCatching fileFor(spec)
            }
            // Try the exact Downloads lookup first, but preserve download as
            // the explicit one-tap setup fallback when Android hides the file
            // from the app's background provider query.
            onStatus("Searching Downloads for the exact filename: ${spec.fileName}")
            val firstLookup = withTimeoutOrNull(30_000L) {
                DownloadLookupResult.Completed(downloadedModels.find(spec))
            }
            val exactDownload = when (firstLookup) {
                is DownloadLookupResult.Completed -> firstLookup.uri
                null -> {
                    onStatus("The Downloads index is slow. Retrying the exact filename check…")
                    when (val retry = withTimeoutOrNull(30_000L) {
                        DownloadLookupResult.Completed(downloadedModels.find(spec))
                    }) {
                        is DownloadLookupResult.Completed -> retry.uri
                        null -> error("Could not finish checking Downloads for ${spec.fileName}. No download was started.")
                    }
                }
            }
            if (exactDownload != null) {
                onStatus("Found ${spec.fileName} in Downloads. Verifying that exact file…")
                onStatus("Importing the existing AI model from Downloads…")
                val imported = importExactDownloadedModel(exactDownload, spec, reportProgress, onStatus)
                if (imported != null) return@runCatching imported
            }
            onStatus("No exact ${spec.fileName} file was found in Downloads. Starting the verified download…")
            onStatus("Downloading the selected AI model from the verified model source…")
            val url = requireNotNull(spec.downloadUrl) { "No automatic download is configured for ${spec.id}." }
            val destination = fileFor(spec)
            val temporary = File(modelDirectory, "${spec.fileName}.part")
            // A stopped worker may already have assembled the complete file.
            // Verify it before contacting the host or restarting its transfer.
            val completedDownload = temporary.isFile && spec.downloadBytes != null &&
                temporary.length() == spec.downloadBytes && spec.expectedSha256 != null &&
                temporary.sha256(reportProgress) == spec.expectedSha256
            if (!completedDownload) downloader.download(
                url = url,
                temporary = temporary,
                onProgress = reportProgress,
                onStatus = onStatus
            )
            check(temporary.isFile && temporary.length() > 0L) { "The downloaded model is empty." }
            onStatus("Verifying the downloaded AI model…")
            val actualSha256 = temporary.sha256(reportProgress)
            spec.expectedSha256?.let { expected ->
                if (!actualSha256.equals(expected, ignoreCase = true)) {
                    temporary.delete()
                    error("The downloaded model failed integrity verification. It was not installed. Retry the download.")
                }
            }
            check(temporary.renameTo(destination)) { "Unable to finalize the downloaded model." }
            val key = fingerprintKey(spec)
            preferences.edit()
                .putString(key, actualSha256)
                .putLong("${key}_length", destination.length())
                .putLong("${key}_modified", destination.lastModified())
                .putBoolean("${key}_invalid", false)
                .putBoolean("${key}_enforce_catalog_hash", true)
                .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
                .commit().also { check(it) { "The model was installed but its verification state could not be saved. Check the installed model again." } }
            destination
        } finally {
            operations.endDownload(spec.id)
        }
    }.onFailure { if (it is CancellationException) throw it }

    private sealed interface DownloadLookupResult {
        data class Completed(val uri: Uri?) : DownloadLookupResult
    }

    private fun importExactDownloadedModel(
        uri: Uri,
        spec: LocalModelSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStatus: (String) -> Unit
    ): File? {
        // Imports must never erase a resumable HTTP checkpoint.
        val temporary = File.createTempFile("${spec.fileName}.import.", ".part", modelDirectory)
        return runCatching {
            temporary.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val totalBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { it.length }
                        ?.takeIf { it > 0L }
                        ?: -1L
                    var copiedBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        onProgress(copiedBytes, totalBytes)
                    }
                    output.flush()
                }
            } ?: return@runCatching null
            if (!temporary.isFile || temporary.length() == 0L) return@runCatching null
            onStatus("Verifying the selected AI model copied from Downloads…")
            val actualSha256 = temporary.sha256(onProgress)
            if (spec.expectedSha256 != null &&
                !actualSha256.equals(spec.expectedSha256, ignoreCase = true)
            ) return@runCatching null
            val destination = fileFor(spec)
            check(temporary.renameTo(destination)) { "Unable to finalize the existing model file." }
            val key = fingerprintKey(spec)
            preferences.edit()
                .putString(key, actualSha256)
                .putLong("${key}_length", destination.length())
                .putLong("${key}_modified", destination.lastModified())
                .putBoolean("${key}_invalid", false)
                .putBoolean("${key}_enforce_catalog_hash", true)
                .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
                .apply()
            destination
        }.onFailure { if (it is CancellationException) throw it }.getOrNull().also {
            if (it == null) temporary.delete()
        }
    }

    fun markSmokeTestPassed(spec: LocalModelSpec = selectedModel()) {
        preferences.edit().putBoolean(smokeTestKey(spec), true).apply()
    }

    fun clearSmokeTest() {
        preferences.edit().putBoolean(smokeTestKey(selectedModel()), false).apply()
    }

    suspend fun importModel(uri: Uri, spec: LocalModelSpec): Result<File> {
        val destination = fileFor(spec)
        return runCatching {
            // Create the temporary file inside runCatching so storage errors
            // are returned through the UI callback instead of escaping launch.
            check(!conversationActive() &&
                operations.tryBeginRuntime(spec.id)) { "Another model operation is still running." }
            val temporary = try {
                File.createTempFile("${spec.fileName}.", ".part", modelDirectory)
            } catch (error: Throwable) {
                endModelOperation()
                throw error
            }
            activeImports.incrementAndGet()
            preferences.edit().putBoolean("import_in_progress", true).apply()
            try {
                val selectedName = context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                require(selectedName == null || selectedName in spec.importFileNames()) {
                    "Select ${spec.importFileNames().joinToString(" or ")}."
                }
                val resolver = context.contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to open selected model file.")
                require(temporary.length() > 0L) { "The selected model file is empty." }
                val actualSha256 = temporary.sha256()
                if (selectedName != null && selectedName != spec.fileName) {
                    require(actualSha256.equals(spec.expectedSha256, ignoreCase = true)) {
                        "The publisher file does not match the selected model. Select its exact model bundle."
                    }
                }
                // An explicitly selected model is validated by the native
                // Gemma smoke test below, not forced to match the catalog's
                // download hash. This makes “import your own compatible Gemma” supported.
                check(temporary.renameTo(destination)) { "Unable to finalize model file." }
                val fingerprint = fingerprintKey(spec)
                preferences.edit()
                    .putString(fingerprint, actualSha256)
                    .putLong("${fingerprint}_length", destination.length())
                    .putLong("${fingerprint}_modified", destination.lastModified())
                    .putBoolean("${fingerprint}_invalid", false)
                    .putBoolean("${fingerprint}_enforce_catalog_hash", false)
                    .putBoolean(smokeTestKey(spec), false)
                .putBoolean("smoke_test_attempted_${spec.id}", false)
                    .apply()
                destination
            } finally {
                temporary.delete()
                if (activeImports.decrementAndGet() == 0) {
                    preferences.edit().putBoolean("import_in_progress", false).apply()
                }
                endModelOperation()
            }
        }
    }

    private val context: Context = context.applicationContext

    private fun fingerprintKey(spec: LocalModelSpec): String =
        "sha256_${spec.id}"

    private fun markIntegrityInvalid(key: String, length: Long, modified: Long) {
        preferences.edit()
            .putLong("${key}_length", length)
            .putLong("${key}_modified", modified)
            .putBoolean("${key}_invalid", true)
            .apply()
    }


}
