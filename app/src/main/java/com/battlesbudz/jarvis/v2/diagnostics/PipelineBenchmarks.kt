package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.TtsEngine

/** Snapshot the model/conversation that owns this attempt at its creation boundary. */
internal data class PipelineBenchmarkInput(
    val selectedModel: LocalModelSpec,
    val llmAssetBytes: Long,
    val conversationId: String,
)

/** Device/resource observations used by benchmarking; no conversation or voice runtime authority. */
internal interface PipelineBenchmarkResources {
    fun provenance(models: Map<String, PipelineBenchmarkModel>, configuration: Map<String, String>): PipelineBenchmarkProvenance
    fun sample(): Map<String, Double?>
    fun batteryPercent(): Int?
}

/** Creates independent attempt collectors and samples resources at their ownership boundaries. */
internal class PipelineBenchmarks(
    private val inputs: () -> PipelineBenchmarkInput,
    private val resources: PipelineBenchmarkResources,
) {
    constructor(inputs: () -> PipelineBenchmarkInput, store: AndroidPipelineBenchmarkStore, batteryPercent: () -> Int?) :
        this(inputs, object : PipelineBenchmarkResources {
            override fun provenance(models: Map<String, PipelineBenchmarkModel>, configuration: Map<String, String>) =
                store.captureProvenance(models, configuration)
            override fun sample() = store.resourceMetrics()
            override fun batteryPercent() = batteryPercent.invoke()
        })

    fun create(turnId: String, channel: String, asr: AsrEngine? = null, tts: TtsEngine? = null): PipelineBenchmarkCapture {
        val input = inputs()
        val selected = input.selectedModel
        val models = linkedMapOf("llm" to PipelineBenchmarkModel(selected.id, "LiteRT-LM", "0.16.0",
            if (selected.recommendedGpu) "GPU_requested" else "CPU_requested", selected.expectedSha256))
        asr?.let { models["asr"] = PipelineBenchmarkModel(it.id,
            if (it == AsrEngine.WHISPER_NPU) "QNN HTP / Sherpa-ONNX fallback" else "Sherpa-ONNX",
            it.modelVersion, if (it == AsrEngine.WHISPER_NPU) "NPU requested; execution unverified / CPU fallback" else "CPU") }
        tts?.let { models["tts"] = PipelineBenchmarkModel(it.id, "Sherpa-ONNX", it.version, "CPU") }
        val capture = PipelineBenchmarkCapture(turnId, channel, System.currentTimeMillis(),
            resources.provenance(models, mapOf(
                "conversation_id" to input.conversationId,
                "llm_asset_bytes" to input.llmAssetBytes.toString(),
                "asset_hash_provenance" to "catalog_expected_not_rehashed_for_benchmark",
                "context_token_limit" to (selected.contextTokens?.toString() ?: "runtime_default"),
                "token_count_method" to "native_when_exposed_else_visible_char4_estimate",
                "microphone_source_requested" to if (channel == "voice") "pending_recorder" else "not_applicable",
                "speaker_identity" to "disabled", "physical_audio_audibility" to "not_measured",
                "tts_threads" to if (tts != null) "4" else "not_applicable")))
        val resourceStarted = System.nanoTime()
        resources.sample().forEach { (key, value) -> capture.metric("${key}_before", value) }
        capture.metric("resource_sampling_before_ms", (System.nanoTime() - resourceStarted) / 1_000_000.0)
        return capture
    }

    fun finishResources(capture: PipelineBenchmarkCapture) {
        val resourceStarted = System.nanoTime()
        resources.sample().forEach { (key, value) -> capture.metric("${key}_after", value) }
        capture.metric("resource_sampling_after_ms", (System.nanoTime() - resourceStarted) / 1_000_000.0)
        capture.metric("battery_percent_after", runCatching {
            resources.batteryPercent()?.takeIf { it in 0..100 }
        }.getOrNull())
    }
}
