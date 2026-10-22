package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONArray
import org.json.JSONObject

enum class PipelineBenchmarkOutcome { COMPLETE, CANCELLED, ERROR, NO_SPEECH, REJECTED, UNKNOWN }
enum class PipelineBenchmarkWarmState { WARM, COLD, UNKNOWN }
enum class PipelineBenchmarkPurpose { ANSWER, DRAFT, RETRY, TRANSCRIPTION_FALLBACK, TOOL, UNKNOWN }
enum class PipelineBenchmarkEnvironment { QUIET, NOISY, UNSPECIFIED }
enum class PipelineBenchmarkVerdict { PASS, FAIL, NOT_EVALUATED }

/** Human judgement is distinct from WER, successful execution, or a model's claim of success. */
data class PipelineBenchmarkQuality(
    val taskVerdict: PipelineBenchmarkVerdict,
    val referenceProvenance: String,
    val reviewedAtEpochMs: Long,
    val intentVerdict: PipelineBenchmarkVerdict = PipelineBenchmarkVerdict.NOT_EVALUATED,
    val factualityVerdict: PipelineBenchmarkVerdict = PipelineBenchmarkVerdict.NOT_EVALUATED
) {
    init { require(referenceProvenance.isNotBlank()); require(reviewedAtEpochMs >= 0) }
    fun json() = benchmarkJson("source" to "human_review", "taskVerdict" to taskVerdict.name,
        "referenceProvenance" to referenceProvenance, "reviewedAtEpochMs" to reviewedAtEpochMs,
        "intentVerdict" to intentVerdict.name, "factualityVerdict" to factualityVerdict.name)
    companion object { fun read(j: JSONObject) = PipelineBenchmarkQuality(
        PipelineBenchmarkVerdict.valueOf(j.getString("taskVerdict")), j.getString("referenceProvenance"),
        j.getLong("reviewedAtEpochMs"), PipelineBenchmarkVerdict.valueOf(j.optString("intentVerdict", "NOT_EVALUATED")),
        PipelineBenchmarkVerdict.valueOf(j.optString("factualityVerdict", "NOT_EVALUATED"))) }
}

data class PipelineBenchmarkModel(
    val id: String,
    val runtime: String? = null,
    val runtimeVersion: String? = null,
    val backend: String? = null,
    val assetSha256: String? = null
) {
    fun json() = benchmarkJson("id" to id, "runtime" to runtime, "runtimeVersion" to runtimeVersion,
        "backend" to backend, "assetSha256" to assetSha256)
    companion object { fun read(j: JSONObject) = PipelineBenchmarkModel(j.getString("id"),
        j.benchmarkString("runtime"), j.benchmarkString("runtimeVersion"), j.benchmarkString("backend"), j.benchmarkString("assetSha256")) }
}

/** Non-identifying device/configuration provenance. Do not add serial numbers, account IDs, or location. */
data class PipelineBenchmarkProvenance(
    val buildName: String,
    val buildCode: Int,
    val sourceCommit: String? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val sdkLevel: Int? = null,
    val abi: String? = null,
    val thermalStatus: Int? = null,
    val batteryPercent: Int? = null,
    val powerSaveMode: Boolean? = null,
    val models: Map<String, PipelineBenchmarkModel> = emptyMap(),
    val configuration: Map<String, String> = emptyMap()
) {
    fun json() = benchmarkJson("buildName" to buildName, "buildCode" to buildCode, "sourceCommit" to sourceCommit,
        "deviceManufacturer" to deviceManufacturer, "deviceModel" to deviceModel, "androidVersion" to androidVersion,
        "sdkLevel" to sdkLevel, "abi" to abi, "thermalStatus" to thermalStatus, "batteryPercent" to batteryPercent,
        "powerSaveMode" to powerSaveMode).put("models", JSONObject().also { j -> models.toSortedMap().forEach { (k,v) -> j.put(k,v.json()) } })
        .put("configuration", JSONObject(configuration.toSortedMap()))
    companion object { fun read(j: JSONObject) = PipelineBenchmarkProvenance(j.getString("buildName"), j.getInt("buildCode"),
        j.benchmarkString("sourceCommit"), j.benchmarkString("deviceManufacturer"), j.benchmarkString("deviceModel"),
        j.benchmarkString("androidVersion"), j.benchmarkLong("sdkLevel")?.toInt(), j.benchmarkString("abi"),
        j.benchmarkLong("thermalStatus")?.toInt(), j.benchmarkLong("batteryPercent")?.toInt(),
        j.takeIf { it.has("powerSaveMode") && !it.isNull("powerSaveMode") }?.getBoolean("powerSaveMode"),
        j.optJSONObject("models")?.let { m -> m.keys().asSequence().associateWith { PipelineBenchmarkModel.read(m.getJSONObject(it)) } } ?: emptyMap(),
        j.optJSONObject("configuration")?.let { c -> c.keys().asSequence().associateWith { c.getString(it) } } ?: emptyMap()) }
}

/** One real native submission. Drafts/retries/audio transcription are never counted as answer tokens. */
data class PipelineBenchmarkSubmission(
    val submissionId: String,
    val purpose: PipelineBenchmarkPurpose,
    val outcome: PipelineBenchmarkOutcome,
    val warmState: PipelineBenchmarkWarmState = PipelineBenchmarkWarmState.UNKNOWN,
    val modelId: String? = null,
    val startedOffsetMs: Long? = null,
    val firstTokenMs: Long? = null,
    val firstCallbackMs: Long? = null,
    val totalGenerationMs: Long? = null,
    val prefillMs: Long? = null,
    val queueMs: Long? = null,
    val nativeSubmitMs: Long? = null,
    val promptCharacters: Int? = null,
    val exactInputTokens: Int? = null,
    val exactOutputTokens: Int? = null,
    val tokenTelemetrySource: String? = null,
    val estimatedOutputTokens: Int? = null,
    val estimatedDecodeTokensPerSecond: Double? = null,
    val streamEvents: Int? = null,
    val measurements: Map<String, Double?> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    /** Only native token telemetry can locate the first token within a multi-token callback chunk. */
    val nativeFirstOutputTokenMs: Long? = null
) {
    init {
        require(exactInputTokens == null || exactInputTokens >= 0)
        require(exactOutputTokens == null || exactOutputTokens >= 0)
        require((exactInputTokens == null && exactOutputTokens == null && nativeFirstOutputTokenMs == null) || !tokenTelemetrySource.isNullOrBlank()) {
            "Exact token counts require native telemetry provenance"
        }
    }
    val decodeSpanMs: Long? get() = benchmarkDuration(firstTokenMs, totalGenerationMs)
    val exactDecodeSpanMs: Long? get() = benchmarkDuration(nativeFirstOutputTokenMs, totalGenerationMs)
    /** First token is excluded from the numerator when measuring subsequent decode throughput. */
    val exactDecodeTokensPerSecond: Double? get() = exactOutputTokens?.takeIf { it >= 2 }?.let { tokens ->
        exactDecodeSpanMs?.takeIf { it > 0 }?.let { (tokens - 1) * 1000.0 / it }
    }
    fun metrics(): Map<String, Double?> = linkedMapOf(
        "ttft_ms" to firstTokenMs.benchmarkNumber(), "first_callback_ms" to firstCallbackMs.benchmarkNumber(),
        "generation_ms" to totalGenerationMs.benchmarkNumber(), "decode_span_ms" to decodeSpanMs.benchmarkNumber(),
        "prefill_ms" to prefillMs.benchmarkNumber(), "queue_ms" to queueMs.benchmarkNumber(),
        "native_submit_ms" to nativeSubmitMs.benchmarkNumber(), "prompt_characters" to promptCharacters.benchmarkNumber(),
        "exact_input_tokens" to exactInputTokens.benchmarkNumber(), "exact_output_tokens" to exactOutputTokens.benchmarkNumber(),
        "native_first_output_token_ms" to nativeFirstOutputTokenMs.benchmarkNumber(), "exact_decode_span_ms" to exactDecodeSpanMs.benchmarkNumber(),
        "exact_decode_tokens_per_second" to exactDecodeTokensPerSecond,
        "estimated_output_tokens" to estimatedOutputTokens.benchmarkNumber(),
        "estimated_decode_tokens_per_second" to estimatedDecodeTokensPerSecond?.takeIf { it.isFinite() && it >= 0 },
        "stream_events" to streamEvents.benchmarkNumber()).also { it.putAll(measurements.mapValues { (_,v) -> v?.takeIf(Double::isFinite) }) }
    fun json() = benchmarkJson("submissionId" to submissionId, "purpose" to purpose.name, "outcome" to outcome.name,
        "warmState" to warmState.name, "modelId" to modelId, "startedOffsetMs" to startedOffsetMs,
        "firstTokenMs" to firstTokenMs, "firstCallbackMs" to firstCallbackMs, "totalGenerationMs" to totalGenerationMs,
        "prefillMs" to prefillMs, "queueMs" to queueMs, "nativeSubmitMs" to nativeSubmitMs,
        "promptCharacters" to promptCharacters, "exactInputTokens" to exactInputTokens, "exactOutputTokens" to exactOutputTokens,
        "tokenTelemetrySource" to tokenTelemetrySource, "estimatedOutputTokens" to estimatedOutputTokens,
        "estimatedDecodeTokensPerSecond" to estimatedDecodeTokensPerSecond, "streamEvents" to streamEvents)
        .put("measurements", benchmarkNumericMap(measurements)).put("metadata", JSONObject(metadata.toSortedMap()))
        .put("nativeFirstOutputTokenMs", nativeFirstOutputTokenMs ?: JSONObject.NULL)
        .put("metricStatus", PipelineBenchmarkDefinitions.statuses(metrics()))
    companion object {
        fun read(j: JSONObject) = PipelineBenchmarkSubmission(j.getString("submissionId"),
            PipelineBenchmarkPurpose.valueOf(j.getString("purpose")), PipelineBenchmarkOutcome.valueOf(j.getString("outcome")),
            PipelineBenchmarkWarmState.valueOf(j.optString("warmState", "UNKNOWN")), j.benchmarkString("modelId"),
            j.benchmarkLong("startedOffsetMs"), j.benchmarkLong("firstTokenMs"), j.benchmarkLong("firstCallbackMs"),
            j.benchmarkLong("totalGenerationMs"), j.benchmarkLong("prefillMs"), j.benchmarkLong("queueMs"),
            j.benchmarkLong("nativeSubmitMs"), j.benchmarkLong("promptCharacters")?.toInt(),
            j.benchmarkLong("exactInputTokens")?.toInt(), j.benchmarkLong("exactOutputTokens")?.toInt(),
            j.benchmarkString("tokenTelemetrySource"), j.benchmarkLong("estimatedOutputTokens")?.toInt(),
            j.benchmarkDouble("estimatedDecodeTokensPerSecond"), j.benchmarkLong("streamEvents")?.toInt(),
            j.optJSONObject("measurements").benchmarkReadNumbers(),
            j.optJSONObject("metadata")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap(),
            j.benchmarkLong("nativeFirstOutputTokenMs"))
    }
}

data class PipelineBenchmarkAsr(
    val engine: String,
    val warmState: PipelineBenchmarkWarmState = PipelineBenchmarkWarmState.UNKNOWN,
    val loadMs: Long? = null, val captureReadyMs: Long? = null, val inputAudioMs: Long? = null,
    val decodeWorkMs: Long? = null, val finalizationMs: Long? = null, val firstPartialMs: Long? = null,
    val endpointDelayMs: Long? = null, val maxDecodeChunkMs: Long? = null, val maxBacklogMs: Long? = null,
    val partialUpdates: Int? = null, val transcriptCharacters: Int? = null, val endpointReason: String? = null,
    val transcriptionFallback: Boolean = false,
    val targetSilenceMs: Long? = null, val endpointCue: String? = null, val endpointAuthority: String? = null
) {
    val realtimeFactor: Double? get() = benchmarkRatio(decodeWorkMs, inputAudioMs)
    fun json() = benchmarkJson("engine" to engine, "warmState" to warmState.name, "loadMs" to loadMs,
        "captureReadyMs" to captureReadyMs, "inputAudioMs" to inputAudioMs, "decodeWorkMs" to decodeWorkMs,
        "finalizationMs" to finalizationMs, "firstPartialMs" to firstPartialMs, "endpointDelayMs" to endpointDelayMs,
        "maxDecodeChunkMs" to maxDecodeChunkMs, "maxBacklogMs" to maxBacklogMs, "partialUpdates" to partialUpdates,
        "transcriptCharacters" to transcriptCharacters, "endpointReason" to endpointReason, "transcriptionFallback" to transcriptionFallback,
        "targetSilenceMs" to targetSilenceMs, "endpointCue" to endpointCue, "endpointAuthority" to endpointAuthority)
    companion object { fun read(j: JSONObject) = PipelineBenchmarkAsr(j.getString("engine"),
        PipelineBenchmarkWarmState.valueOf(j.optString("warmState", "UNKNOWN")), j.benchmarkLong("loadMs"),
        j.benchmarkLong("captureReadyMs"), j.benchmarkLong("inputAudioMs"), j.benchmarkLong("decodeWorkMs"),
        j.benchmarkLong("finalizationMs"), j.benchmarkLong("firstPartialMs"), j.benchmarkLong("endpointDelayMs"),
        j.benchmarkLong("maxDecodeChunkMs"), j.benchmarkLong("maxBacklogMs"), j.benchmarkLong("partialUpdates")?.toInt(),
        j.benchmarkLong("transcriptCharacters")?.toInt(), j.benchmarkString("endpointReason"), j.optBoolean("transcriptionFallback"),
        j.benchmarkLong("targetSilenceMs"), j.benchmarkString("endpointCue"), j.benchmarkString("endpointAuthority")) }
}

data class PipelineBenchmarkTts(
    val engine: String, val warmState: PipelineBenchmarkWarmState = PipelineBenchmarkWarmState.UNKNOWN,
    val loadMs: Long? = null, val firstTextToPcmMs: Long? = null, val firstTextToPlaybackMs: Long? = null,
    val synthesisMs: Long? = null, val generatedAudioMs: Long? = null, val queueWaitMs: Long? = null,
    val playbackStarvationMs: Long? = null, val underruns: Int? = null,
    val playbackEvidence: String = "playback_head_proxy_not_acoustic"
) {
    val realtimeFactor: Double? get() = benchmarkRatio(synthesisMs, generatedAudioMs)
    fun json() = benchmarkJson("engine" to engine, "warmState" to warmState.name, "loadMs" to loadMs,
        "firstTextToPcmMs" to firstTextToPcmMs, "firstTextToPlaybackMs" to firstTextToPlaybackMs,
        "synthesisMs" to synthesisMs, "generatedAudioMs" to generatedAudioMs, "queueWaitMs" to queueWaitMs,
        "playbackStarvationMs" to playbackStarvationMs, "underruns" to underruns, "playbackEvidence" to playbackEvidence)
    companion object { fun read(j: JSONObject) = PipelineBenchmarkTts(j.getString("engine"),
        PipelineBenchmarkWarmState.valueOf(j.optString("warmState", "UNKNOWN")), j.benchmarkLong("loadMs"),
        j.benchmarkLong("firstTextToPcmMs"), j.benchmarkLong("firstTextToPlaybackMs"), j.benchmarkLong("synthesisMs"),
        j.benchmarkLong("generatedAudioMs"), j.benchmarkLong("queueWaitMs"), j.benchmarkLong("playbackStarvationMs"),
        j.benchmarkLong("underruns")?.toInt(), j.optString("playbackEvidence", "playback_head_proxy_not_acoustic")) }
}

/** One capture/request, including cancelled/failed attempts. All stage offsets use the same monotonic clock. */
data class PipelineBenchmarkTurn(
    val turnId: String,
    val callId: String? = null,
    val channel: String,
    val capturedAtEpochMs: Long,
    val provenance: PipelineBenchmarkProvenance,
    val outcome: PipelineBenchmarkOutcome,
    val environment: PipelineBenchmarkEnvironment = PipelineBenchmarkEnvironment.UNSPECIFIED,
    val clock: String = "monotonic_unspecified",
    val stageOffsetsMs: Map<String, Long> = emptyMap(),
    val observedMetrics: Map<String, Double?> = emptyMap(),
    val asr: PipelineBenchmarkAsr? = null,
    val tts: PipelineBenchmarkTts? = null,
    val submissions: List<PipelineBenchmarkSubmission> = emptyList(),
    val accuracy: PipelineBenchmarkAccuracy? = null,
    val failureCode: String? = null,
    val quality: PipelineBenchmarkQuality? = null,
    val conversationId: String? = null
) {
    init { require(turnId.isNotBlank()); require(stageOffsetsMs.values.all { it >= 0 }); require(submissions.map { it.submissionId }.distinct().size == submissions.size) }
    /** Text-free structural metrics. The first reply events must already exclude acknowledgement/filler. */
    fun metrics(): Map<String, Double?> = linkedMapOf<String, Double?>(
        "process_cpu_work_ms" to benchmarkDuration(observedMetrics["process_cpu_time_ms_before"]?.toLong(), observedMetrics["process_cpu_time_ms_after"]?.toLong()).benchmarkNumber(),
        "turn_total_ms" to stageOffsetsMs["turn_finished"].benchmarkNumber(),
        "model_load_ms" to span("model_load_started", "model_load_finished"),
        "llm_setup_ms" to span("llm_setup_started", "llm_setup_finished"),
        "attachment_preparation_ms" to span("attachment_preparation_started", "attachment_preparation_finished"),
        "memory_retrieval_ms" to span("memory_retrieval_started", "memory_retrieval_finished"),
        "reference_lookup_ms" to span("reference_lookup_started", "reference_lookup_finished"),
        "tool_execution_ms" to span("tool_execution_started", "tool_execution_finished"),
        "audio_fallback_ms" to span("audio_fallback_started", "audio_fallback_finished"),
        "gemma_final_caption_ms" to span("gemma_final_caption_started", "gemma_final_caption_finished"),
        "endpoint_to_preparation_sealed_ms" to span("recognition_finalized", "preparation_sealed"),
        "request_processing_ms" to span("request_processing_started", "request_processing_finished"),
        "microphone_ready_ms" to stageOffsetsMs["microphone_ready"].benchmarkNumber(),
        "first_reply_text_ready_ms" to stageOffsetsMs["first_reply_text_ready"].benchmarkNumber(),
        "endpoint_to_first_answer_text_ready_ms" to span("recognition_finalized", "first_reply_text_ready"),
        "endpoint_to_first_answer_text_ms" to span("recognition_finalized", "first_reply_text"),
        "endpoint_to_first_answer_playback_ms" to span("recognition_finalized", "first_reply_audio"),
        "speech_end_to_first_answer_text_ms" to span("speech_ended", "first_reply_text"),
        "speech_end_to_first_answer_playback_ms" to span("speech_ended", "first_reply_audio"),
        "reply_dispatch_to_first_answer_text_ms" to span("reply_dispatched", "first_reply_text"),
        "answer_submit_to_first_native_callback_ms" to span("answer_submit_enter", "answer_first_native_callback"),
        "answer_submit_to_first_native_raw_text_ms" to span("answer_submit_enter", "answer_first_native_raw_text"),
        "answer_native_callback_to_consumer_ms" to span("answer_first_native_callback", "answer_first_consumer_callback"),
        "answer_native_raw_text_to_consumer_ms" to span("answer_first_native_raw_text", "answer_first_consumer_raw_text"),
        "answer_consumer_raw_text_to_guard_ms" to span("answer_first_consumer_raw_text", "first_guard_release"),
        "guard_to_piper_first_text_ms" to span("first_guard_release", "first_piper_text"),
        "piper_first_text_to_pcm_ms" to span("first_piper_text", "first_piper_pcm"),
        "piper_first_pcm_to_answer_playback_ms" to span("first_piper_pcm", "first_reply_audio"),
        "answer_native_raw_text_to_piper_pcm_ms" to span("answer_first_native_raw_text", "first_piper_pcm"),
        "answer_native_raw_text_to_playback_ms" to span("answer_first_native_raw_text", "first_reply_audio"),
        "asr_load_ms" to asr?.loadMs.benchmarkNumber(), "asr_capture_ready_ms" to asr?.captureReadyMs.benchmarkNumber(),
        "asr_input_audio_ms" to asr?.inputAudioMs.benchmarkNumber(), "asr_decode_work_ms" to asr?.decodeWorkMs.benchmarkNumber(),
        "asr_finalization_ms" to asr?.finalizationMs.benchmarkNumber(), "asr_first_partial_ms" to asr?.firstPartialMs.benchmarkNumber(),
        "asr_endpoint_delay_ms" to asr?.endpointDelayMs.benchmarkNumber(), "asr_max_decode_chunk_ms" to asr?.maxDecodeChunkMs.benchmarkNumber(),
        "asr_max_backlog_ms" to asr?.maxBacklogMs.benchmarkNumber(), "asr_realtime_factor" to asr?.realtimeFactor,
        "tts_load_ms" to tts?.loadMs.benchmarkNumber(), "tts_first_text_to_pcm_ms" to tts?.firstTextToPcmMs.benchmarkNumber(),
        "tts_first_text_to_playback_ms" to tts?.firstTextToPlaybackMs.benchmarkNumber(), "tts_synthesis_ms" to tts?.synthesisMs.benchmarkNumber(),
        "tts_generated_audio_ms" to tts?.generatedAudioMs.benchmarkNumber(), "tts_queue_wait_ms" to tts?.queueWaitMs.benchmarkNumber(),
        "tts_playback_starvation_ms" to tts?.playbackStarvationMs.benchmarkNumber(), "tts_underruns" to tts?.underruns.benchmarkNumber(),
        "tts_realtime_factor" to tts?.realtimeFactor, "asr_wer" to accuracy?.wer, "asr_cer" to accuracy?.cer
    ).also {
        it.putAll(VoicePreparationTimingMetrics.from(stageOffsetsMs))
        it.putAll(observedMetrics.mapValues { (_,v) -> v?.takeIf(Double::isFinite) })
    }
    private fun span(start: String, end: String): Double? = benchmarkDuration(stageOffsetsMs[start], stageOffsetsMs[end]).benchmarkNumber()
    fun json(includeText: Boolean = false): JSONObject = benchmarkJson("turnId" to turnId, "callId" to callId,
        "conversationId" to conversationId, "channel" to channel, "capturedAtEpochMs" to capturedAtEpochMs, "outcome" to outcome.name,
        "environment" to environment.name, "clock" to clock, "failureCode" to failureCode)
        .put("provenance", provenance.json()).put("stageOffsetsMs", JSONObject(stageOffsetsMs.toSortedMap()))
        .put("observedMetrics", benchmarkNumericMap(observedMetrics))
        .put("metricStatus", PipelineBenchmarkDefinitions.statuses(metrics()))
        .put("asr", asr?.json() ?: JSONObject.NULL).put("tts", tts?.json() ?: JSONObject.NULL)
        .put("submissions", JSONArray().also { a -> submissions.forEach { a.put(it.json()) } })
        .put("accuracy", accuracy?.json(includeText) ?: JSONObject.NULL)
        .put("quality", quality?.json() ?: JSONObject.NULL)
    companion object {
        fun read(j: JSONObject): PipelineBenchmarkTurn {
            val stages = j.optJSONObject("stageOffsetsMs")
            val metrics = j.optJSONObject("observedMetrics")
            val passes = j.optJSONArray("submissions") ?: JSONArray()
            return PipelineBenchmarkTurn(j.getString("turnId"), j.benchmarkString("callId"), j.getString("channel"),
                j.getLong("capturedAtEpochMs"), PipelineBenchmarkProvenance.read(j.getJSONObject("provenance")),
                PipelineBenchmarkOutcome.valueOf(j.getString("outcome")),
                PipelineBenchmarkEnvironment.valueOf(j.optString("environment", "UNSPECIFIED")), j.optString("clock", "elapsedRealtime"),
                stages?.keys()?.asSequence()?.associateWith { stages.getLong(it) } ?: emptyMap(),
                metrics.benchmarkReadNumbers(),
                j.optJSONObject("asr")?.let(PipelineBenchmarkAsr::read), j.optJSONObject("tts")?.let(PipelineBenchmarkTts::read),
                (0 until passes.length()).map { PipelineBenchmarkSubmission.read(passes.getJSONObject(it)) },
                j.optJSONObject("accuracy")?.let(PipelineBenchmarkAccuracy::read), j.benchmarkString("failureCode"),
                j.optJSONObject("quality")?.let(PipelineBenchmarkQuality::read), j.benchmarkString("conversationId"))
        }
    }
}

internal fun benchmarkJson(vararg values: Pair<String, Any?>) = JSONObject().also { j -> values.forEach { (k,v) -> j.put(k,v ?: JSONObject.NULL) } }
internal fun JSONObject.benchmarkString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
internal fun JSONObject.benchmarkLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key).takeIf { it >= 0 } else null
internal fun JSONObject.benchmarkDouble(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key).takeIf { it.isFinite() && it >= 0 } else null
internal fun JSONObject?.benchmarkReadNumbers(): Map<String, Double?> = this?.let { j -> j.keys().asSequence().associateWith { key -> if (j.isNull(key)) null else j.getDouble(key).takeIf(Double::isFinite) } } ?: emptyMap()
internal fun benchmarkNumericMap(values: Map<String, Double?>) = JSONObject().also { j -> values.toSortedMap().forEach { (k,v) -> j.put(k,v?.takeIf(Double::isFinite) ?: JSONObject.NULL) } }
internal fun Number?.benchmarkNumber(): Double? = this?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
internal fun benchmarkDuration(start: Long?, end: Long?): Long? = if (start == null || end == null || start < 0 || end < start) null else end - start
internal fun benchmarkRatio(workMs: Long?, audioMs: Long?): Double? = if (workMs == null || workMs < 0 || audioMs == null || audioMs <= 0) null else workMs.toDouble() / audioMs
