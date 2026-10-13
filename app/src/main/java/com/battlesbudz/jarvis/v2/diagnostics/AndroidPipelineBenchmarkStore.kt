package com.battlesbudz.jarvis.v2.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Observations are appended once per completed attempt, never once per streamed token. */
class AndroidPipelineBenchmarkStore(context: Context) {
    private val app = context.applicationContext
    private val archive = PipelineBenchmarkArchive(File(app.noBackupFilesDir, "pipeline-benchmarks-v1.json"))
    private val retained = PipelineBenchmarkJournal(File(app.noBackupFilesDir, "pipeline-benchmarks-v2"), archive)
    private val mutableSamples = MutableStateFlow(retained.samples())
    val samples: StateFlow<List<PipelineBenchmarkTurn>> = mutableSamples.asStateFlow()
    private val mutableStorageStatus = MutableStateFlow(retained.status)
    val storageStatus: StateFlow<String?> = mutableStorageStatus.asStateFlow()
    private val monitor = Any()
    private val disk = Mutex()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Unit>(Channel.CONFLATED)
    // Recognition text is only retained in process for explicit user scoring, never in the archive/export.
    private val hypotheses = linkedMapOf<String, String>()
    private val mutableHypothesisGeneration = MutableStateFlow(0L)
    val hypothesisGeneration: StateFlow<Long> = mutableHypothesisGeneration.asStateFlow()
    private var revision = 0L
    private var persistedRevision = 0L
    init { revision = 1L; pending.trySend(Unit); writer.launch { for (ignored in pending) flush() } }

    fun append(turn: PipelineBenchmarkTurn, hypothesis: String? = null, expectedHypothesisEpoch: Long = hypothesisEpoch()) {
        synchronized(monitor) {
            val accepted = retained.append(turn)
            mutableStorageStatus.value = retained.status
            val next = retained.samples()
            mutableSamples.value = next
            hypotheses.keys.retainAll(next.map { it.turnId }.toSet())
            if (accepted && hypothesis != null && expectedHypothesisEpoch == mutableHypothesisGeneration.value && hypothesis.length <= 4096 && next.any { it.turnId == turn.turnId }) {
                hypotheses[turn.turnId] = hypothesis
                while (hypotheses.size > 32 || hypotheses.values.sumOf { it.length } > 32_768) hypotheses.remove(hypotheses.keys.first())
            }
            revision++
        }
        pending.trySend(Unit)
    }

    fun hypothesis(turnId: String): String? = synchronized(monitor) { hypotheses[turnId] }
    fun hypothesisEpoch(): Long = synchronized(monitor) { mutableHypothesisGeneration.value }

    /** Used by transcript/diagnostic erasure barriers without discarding non-content performance data. */
    fun clearHypotheses() { synchronized(monitor) { hypotheses.clear(); mutableHypothesisGeneration.value++ } }

    fun setEnvironment(turnId: String, environment: PipelineBenchmarkEnvironment): Boolean =
        update(turnId) { it.copy(environment = environment) }

    /** Score the original recognizer output, not a conversationally resolved or model-generated correction. */
    fun scoreReference(turnId: String, reference: String): PipelineBenchmarkAccuracy {
        val snapshot = recognitionSnapshot(turnId)
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate(reference, snapshot.first,
            referenceProvenance = "user_verified_transcript_original_asr", retainText = false)
        saveScore(turnId, snapshot.second, score)
        return score
    }

    /** Bounded corpus scoring runs off the UI thread; cancellation and erasure fence its publication. */
    suspend fun scoreReferenceAsync(turnId: String, reference: String): PipelineBenchmarkAccuracy {
        val snapshot = recognitionSnapshot(turnId)
        val score = withContext(Dispatchers.Default) {
            PipelineBenchmarkAccuracyEvaluator.evaluate(reference, snapshot.first,
                referenceProvenance = "user_verified_transcript_original_asr", retainText = false)
        }
        currentCoroutineContext().ensureActive()
        saveScore(turnId, snapshot.second, score)
        return score
    }

    private fun recognitionSnapshot(turnId: String): Pair<String, Long> = synchronized(monitor) {
        (hypotheses[turnId] ?: error("The original ASR transcript is no longer retained. Score a new voice turn in this app session.")) to mutableHypothesisGeneration.value
    }

    private fun saveScore(turnId: String, expectedEpoch: Long, score: PipelineBenchmarkAccuracy) {
        synchronized(monitor) {
            check(mutableHypothesisGeneration.value == expectedEpoch && hypotheses.containsKey(turnId)) { "The recognition evidence was erased while scoring" }
            check(update(turnId) { it.copy(accuracy = score) }) { "This benchmark sample has expired" }
        }
    }

    fun setQuality(turnId: String, quality: PipelineBenchmarkQuality?): Boolean = update(turnId) { it.copy(quality = quality) }

    private fun update(turnId: String, transform: (PipelineBenchmarkTurn) -> PipelineBenchmarkTurn): Boolean {
        val updated = synchronized(monitor) {
            if (!retained.update(turnId, transform)) return@synchronized false
            mutableSamples.value = retained.samples()
            hypotheses.keys.retainAll(mutableSamples.value.map { it.turnId }.toSet())
            revision++
            true
        }
        if (updated) pending.trySend(Unit)
        return updated
    }

    /** Returns after the empty archive is durable, so reset cannot be undone by a queued older snapshot. */
    suspend fun clear() {
        synchronized(monitor) { retained.clear(); mutableSamples.value = emptyList(); hypotheses.clear(); mutableHypothesisGeneration.value++; revision++ }
        flush()
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        disk.withLock {
            val snapshot = synchronized(monitor) { revision to retained.prepared() }
            if (snapshot.first == persistedRevision) return@withLock
            try {
                retained.writePrepared(snapshot.second)
                persistedRevision = snapshot.first
                mutableStorageStatus.value = retained.status
            } catch (_: Exception) {
                mutableStorageStatus.value = "Benchmark storage failed. Current samples remain in memory; export them before closing Jarvis."
            }
        }
    }

    fun report(conversationId: String? = null, callId: String? = null, turnId: String? = null): PipelineBenchmarkReport =
        PipelineBenchmarkReport(PipelineBenchmarkSelection.select(samples.value, conversationId, callId, turnId), System.currentTimeMillis())
    fun exportJson(): String = report().toJson(includeText = false).toString(2)
    fun exportCsv(): String = report().toCsv()

    fun captureProvenance(
        models: Map<String, PipelineBenchmarkModel> = emptyMap(),
        configuration: Map<String, String> = emptyMap()
    ): PipelineBenchmarkProvenance {
        val power = app.getSystemService(PowerManager::class.java)
        val battery = app.getSystemService(BatteryManager::class.java)
        return PipelineBenchmarkProvenance(
            buildName = BuildConfig.VERSION_NAME, buildCode = BuildConfig.VERSION_CODE,
            sourceCommit = BuildConfig.SOURCE_COMMIT.takeUnless { it == "unavailable" },
            deviceManufacturer = Build.MANUFACTURER, deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE, sdkLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull(),
            thermalStatus = runCatching { power.currentThermalStatus }.getOrNull(),
            batteryPercent = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()?.takeIf { it in 0..100 },
            powerSaveMode = runCatching { power.isPowerSaveMode }.getOrNull(), models = models,
            configuration = configuration + mapOf("soc" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE),
                "availableProcessorCount" to Runtime.getRuntime().availableProcessors().toString())
        )
    }

    /** Process and system memory are observations; these values do not describe model allocation alone. */
    fun resourceMetrics(): Map<String, Double?> = runCatching {
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        linkedMapOf<String, Double?>("system_total_memory_bytes" to memory.totalMem.toDouble(),
            "system_available_memory_bytes" to memory.availMem.toDouble(),
            "process_java_used_memory_bytes" to (runtime.totalMemory() - runtime.freeMemory()).toDouble(),
            "process_native_allocated_memory_bytes" to Debug.getNativeHeapAllocatedSize().toDouble(),
            "process_pss_kib" to Debug.getPss().toDouble(),
            "process_cpu_time_ms" to android.os.Process.getElapsedCpuTime().toDouble(),
            "battery_percent" to app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }?.toDouble(),
            "thermal_status" to app.getSystemService(PowerManager::class.java).currentThermalStatus.toDouble())
    }.getOrDefault(emptyMap())
}
