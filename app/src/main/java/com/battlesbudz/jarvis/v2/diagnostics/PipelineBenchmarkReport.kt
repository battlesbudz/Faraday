package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

/** Nearest-rank percentiles of observed samples; unknowns never enter the denominator as zero. */
data class PipelineBenchmarkStatistics(
    val total: Int, val n: Int, val missing: Int,
    val min: Double?, val max: Double?, val mean: Double?, val sampleStandardDeviation: Double?,
    val p50: Double?, val p90: Double?, val p95: Double?, val p99: Double?
) {
    fun json() = benchmarkJson("total" to total, "n" to n, "missing" to missing, "min" to min, "max" to max,
        "mean" to mean, "sampleStandardDeviation" to sampleStandardDeviation,
        "p50" to p50, "p90" to p90, "p95" to p95, "p99" to p99,
        "percentileMethod" to "nearest_rank_v1", "p95LowSampleCount" to (n < 20), "p99LowSampleCount" to (n < 100))
    companion object {
        fun from(values: List<Double?>): PipelineBenchmarkStatistics {
            val sorted = values.mapNotNull { it?.takeIf(Double::isFinite) }.sorted()
            val n = sorted.size
            val mean = sorted.takeIf { it.isNotEmpty() }?.average()
            fun percentile(p: Double): Double? = if (n == 0) null else sorted[(ceil(p * n).toInt() - 1).coerceIn(0, n - 1)]
            val sd = if (n <= 1 || mean == null) null else sqrt(sorted.sumOf { (it - mean) * (it - mean) } / (n - 1))
            return PipelineBenchmarkStatistics(values.size, n, values.size - n, sorted.firstOrNull(), sorted.lastOrNull(), mean,
                sd, percentile(0.5), percentile(0.9), percentile(0.95), percentile(0.99))
        }
    }
}

data class PipelineBenchmarkAggregate(
    val turnCount: Int,
    val submissionCount: Int,
    val outcomes: Map<String, Int>,
    val submissionOutcomes: Map<String, Int>,
    val turnMetrics: Map<String, PipelineBenchmarkStatistics>,
    val submissionMetrics: Map<String, PipelineBenchmarkStatistics>,
    val evaluatedAsrTurns: Int,
    val referenceWords: Long,
    val wordErrors: Long,
    val referenceCharacters: Long,
    val characterErrors: Long,
    val emptyReferenceTurns: Int,
    val falsePositiveWords: Long,
    val falsePositiveCharacters: Long,
    val humanQualityCounts: Map<String, Int>
) {
    val corpusWer: Double? get() = referenceWords.takeIf { it > 0 }?.let { wordErrors.toDouble() / it }
    val corpusCer: Double? get() = referenceCharacters.takeIf { it > 0 }?.let { characterErrors.toDouble() / it }
    fun json(): JSONObject = benchmarkJson("turnCount" to turnCount, "submissionCount" to submissionCount,
        "evaluatedAsrTurns" to evaluatedAsrTurns, "unevaluatedAsrTurns" to turnCount - evaluatedAsrTurns,
        "referenceWords" to referenceWords, "wordErrors" to wordErrors, "referenceCharacters" to referenceCharacters,
        "characterErrors" to characterErrors, "corpusWer" to corpusWer, "corpusCer" to corpusCer,
        "emptyReferenceTurns" to emptyReferenceTurns, "falsePositiveWords" to falsePositiveWords,
        "falsePositiveCharacters" to falsePositiveCharacters).put("outcomes", JSONObject(outcomes.toSortedMap()))
        .put("submissionOutcomes", JSONObject(submissionOutcomes.toSortedMap())).put("humanQualityCounts", JSONObject(humanQualityCounts.toSortedMap()))
        .put("turnMetrics", JSONObject().also { j -> turnMetrics.toSortedMap().forEach { (k,v) -> j.put(k,v.json()) } })
        .put("submissionMetrics", JSONObject().also { j -> submissionMetrics.toSortedMap().forEach { (k,v) -> j.put(k,v.json()) } })
}

/** Portable evidence, not a claim that any model/device ran or that a test fixture was acoustic evidence. */
data class PipelineBenchmarkReport(val turns: List<PipelineBenchmarkTurn>, val exportedAtEpochMs: Long) {
    init { require(turns.map { it.turnId }.distinct().size == turns.size) { "Duplicate turn IDs distort benchmark denominators" } }
    fun aggregate(): PipelineBenchmarkAggregate = aggregate(turns)
    fun toJson(includeText: Boolean = false): JSONObject = JSONObject()
        .put("schema", SCHEMA).put("schemaVersion", SCHEMA_VERSION).put("exportedAtEpochMs", exportedAtEpochMs)
        .put("privacy", benchmarkJson("textIncluded" to includeText, "pcmIncluded" to false, "promptsIncluded" to false))
        .put("methodology", methodology()).put("metricDefinitions", PipelineBenchmarkDefinitions.json())
        .put("allAttempts", aggregate().json())
        .put("completedTurns", aggregate(turns.filter { it.outcome == PipelineBenchmarkOutcome.COMPLETE }).json())
        .put("comparableGroups", JSONArray().also { a -> turns.groupBy { comparableGroupKey(it) }.toSortedMap().forEach { (key,group) ->
            a.put(JSONObject().put("groupKey", key).put("group", groupDescriptor(group.first()))
                .put("allAttempts", aggregate(group).json())
                .put("completedTurns", aggregate(group.filter { it.outcome == PipelineBenchmarkOutcome.COMPLETE }).json()))
        } })
        .put("submissionGroups", JSONArray().also { a ->
            turns.flatMap { t -> t.submissions.map { t to it } }.groupBy { (turn,submission) ->
                "${comparableGroupKey(turn)}:${submission.modelId}:${submission.purpose}:${submission.warmState}:${submission.outcome}"
            }.toSortedMap().forEach { (key, group) ->
                val first = group.first()
                a.put(JSONObject().put("groupKey", key).put("turnGroup", groupDescriptor(first.first))
                    .put("modelId", first.second.modelId ?: JSONObject.NULL).put("purpose", first.second.purpose.name)
                    .put("warmState", first.second.warmState.name).put("outcome", first.second.outcome.name)
                    .put("submissionCount", group.size).put("metrics", statisticsJson(group.map { it.second.metrics() })))
            }
        })
        .put("turns", JSONArray().also { a -> turns.forEach { a.put(it.json(includeText)) } })

    /** One row per turn plus one per native submission. Empty numeric cells mean unknown, not zero. */
    fun toCsv(): String {
        val fixed = listOf("row_type", "turn_id", "call_id", "conversation_id", "captured_at_epoch_ms", "channel", "environment", "outcome",
            "submission_id", "purpose", "warm_state", "model_id", "group_key", "build_name", "build_code", "source_commit",
            "device_manufacturer", "device_model", "android_version", "sdk_level", "abi", "thermal_status", "battery_percent",
            "power_save_mode", "provenance_json", "clock", "failure_code", "reference_provenance", "normalization",
            "reference_words", "word_substitutions", "word_deletions", "word_insertions", "reference_characters",
            "character_substitutions", "character_deletions", "character_insertions", "false_positive_words", "false_positive_characters",
            "human_task_verdict", "human_intent_verdict", "human_factuality_verdict", "quality_reference_provenance",
            "token_telemetry_source", "submission_metadata_json", "turn_metric_status_json", "submission_metric_status_json")
        val turnKeys = turns.flatMap { it.metrics().keys }.distinct().sorted()
        val submissionKeys = turns.flatMap { it.submissions }.flatMap { it.metrics().keys }.distinct().sorted()
        val columns = fixed + turnKeys.map { "turn.$it" } + submissionKeys.map { "submission.$it" }
        return buildString {
            appendLine(columns.joinToString(",", transform = ::csvCell))
            turns.forEach { turn ->
                val p = turn.provenance
                val base = mapOf<String, Any?>("turn_id" to turn.turnId, "call_id" to turn.callId, "conversation_id" to turn.conversationId, "captured_at_epoch_ms" to turn.capturedAtEpochMs,
                    "channel" to turn.channel, "environment" to turn.environment.name, "outcome" to turn.outcome.name,
                    "group_key" to comparableGroupKey(turn), "build_name" to p.buildName, "build_code" to p.buildCode,
                    "source_commit" to p.sourceCommit, "device_manufacturer" to p.deviceManufacturer, "device_model" to p.deviceModel,
                    "android_version" to p.androidVersion, "sdk_level" to p.sdkLevel, "abi" to p.abi, "thermal_status" to p.thermalStatus,
                    "battery_percent" to p.batteryPercent, "power_save_mode" to p.powerSaveMode, "provenance_json" to p.json().toString(),
                    "clock" to turn.clock, "failure_code" to turn.failureCode)
                val accuracy = turn.accuracy
                val turnRow = base + mapOf("row_type" to "turn", "reference_provenance" to accuracy?.referenceProvenance,
                    "normalization" to accuracy?.normalization, "reference_words" to accuracy?.referenceWords,
                    "word_substitutions" to accuracy?.wordSubstitutions, "word_deletions" to accuracy?.wordDeletions,
                    "word_insertions" to accuracy?.wordInsertions, "reference_characters" to accuracy?.referenceCharacters,
                    "character_substitutions" to accuracy?.characterSubstitutions, "character_deletions" to accuracy?.characterDeletions,
                    "character_insertions" to accuracy?.characterInsertions, "false_positive_words" to accuracy?.falsePositiveWords,
                    "false_positive_characters" to accuracy?.falsePositiveCharacters, "human_task_verdict" to turn.quality?.taskVerdict?.name,
                    "human_intent_verdict" to turn.quality?.intentVerdict?.name, "human_factuality_verdict" to turn.quality?.factualityVerdict?.name,
                    "quality_reference_provenance" to turn.quality?.referenceProvenance, "turn_metric_status_json" to PipelineBenchmarkDefinitions.statuses(turn.metrics()).toString()) + turn.metrics().mapKeys { "turn.${it.key}" }
                appendLine(columns.joinToString(",") { csvCell(turnRow[it]?.toString().orEmpty()) })
                turn.submissions.forEach { s ->
                    val row = base + mapOf("row_type" to "submission", "submission_id" to s.submissionId,
                        "outcome" to s.outcome.name, "purpose" to s.purpose.name, "warm_state" to s.warmState.name, "model_id" to s.modelId,
                        "token_telemetry_source" to s.tokenTelemetrySource, "submission_metadata_json" to JSONObject(s.metadata.toSortedMap()).toString(), "submission_metric_status_json" to PipelineBenchmarkDefinitions.statuses(s.metrics()).toString()) + s.metrics().mapKeys { "submission.${it.key}" }
                    appendLine(columns.joinToString(",") { csvCell(row[it]?.toString().orEmpty()) })
                }
            }
        }
    }
    companion object {
        const val SCHEMA = "jarvis.pipeline.benchmark"
        const val SCHEMA_VERSION = 2
        /** Per-turn correlation/observation metadata stays on raw rows without splitting comparable measurements. */
        private val identityConfigurationKeys = setOf(
            "conversation_id", "parent_task_ids", "reply_id", "utterance_id", "returned_utterance_id",
            "result_utterance_id", "result_captured_at_epoch_ms", "linked_reply_turn_id", "followup_smart_turn_utterance_id", "capture_first_raw_transfer"
        )
        private fun comparisonConfiguration(configuration: Map<String, String>): Map<String, String> =
            configuration.filterKeys { it !in identityConfigurationKeys }
        fun read(j: JSONObject): PipelineBenchmarkReport {
            require(j.getString("schema") == SCHEMA && j.getInt("schemaVersion") in 1..SCHEMA_VERSION) { "Unsupported benchmark schema" }
            val a = j.getJSONArray("turns")
            return PipelineBenchmarkReport((0 until a.length()).map { PipelineBenchmarkTurn.read(a.getJSONObject(it)) }, j.getLong("exportedAtEpochMs"))
        }
        private fun aggregate(turns: List<PipelineBenchmarkTurn>): PipelineBenchmarkAggregate {
            val submissions = turns.flatMap { it.submissions }
            val scores = turns.mapNotNull { it.accuracy }
            val verdicts = turns.flatMap { t -> listOf("task" to (t.quality?.taskVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED),
                "intent" to (t.quality?.intentVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED),
                "factuality" to (t.quality?.factualityVerdict ?: PipelineBenchmarkVerdict.NOT_EVALUATED)) }
            return PipelineBenchmarkAggregate(turns.size, submissions.size,
                turns.groupingBy { it.outcome.name }.eachCount(), submissions.groupingBy { it.outcome.name }.eachCount(),
                statistics(turns.map { it.metrics() }), statistics(submissions.map { it.metrics() }), scores.size,
                scores.sumOf { it.referenceWords.toLong() }, scores.sumOf { it.wordErrors.toLong() },
                scores.sumOf { it.referenceCharacters.toLong() }, scores.sumOf { it.characterErrors.toLong() },
                scores.count { it.referenceWords == 0 }, scores.sumOf { it.falsePositiveWords.toLong() },
                scores.sumOf { it.falsePositiveCharacters.toLong() }, verdicts.groupingBy { "${it.first}.${it.second.name}" }.eachCount())
        }
        private fun statistics(rows: List<Map<String, Double?>>): Map<String, PipelineBenchmarkStatistics> =
            rows.flatMap { it.keys }.distinct().sorted().associateWith { key -> PipelineBenchmarkStatistics.from(rows.map { it[key] }) }
        private fun statisticsJson(rows: List<Map<String, Double?>>) = JSONObject().also { j -> statistics(rows).forEach { (k,v) -> j.put(k,v.json()) } }
        private fun groupDescriptor(t: PipelineBenchmarkTurn) = JSONObject().put("channel", t.channel)
            .put("environment", t.environment.name).put("provenance", t.provenance.copy(thermalStatus = null, batteryPercent = null,
                configuration = comparisonConfiguration(t.provenance.configuration)).json())
            .put("asrEngine", t.asr?.engine ?: JSONObject.NULL).put("ttsEngine", t.tts?.engine ?: JSONObject.NULL)
            .put("asrWarmState", t.asr?.warmState?.name ?: "UNKNOWN").put("ttsWarmState", t.tts?.warmState?.name ?: "UNKNOWN")
        fun comparableGroupKey(t: PipelineBenchmarkTurn): String {
            val p = t.provenance
            val stable = listOf(t.channel,t.environment.name,p.buildName,p.buildCode,p.sourceCommit,p.deviceManufacturer,p.deviceModel,
                p.androidVersion,p.sdkLevel,p.abi,p.powerSaveMode,t.asr?.engine,t.asr?.warmState,t.tts?.engine,t.tts?.warmState).joinToString("|") { JSONObject.quote(it?.toString() ?: "<unknown>") } +
                p.models.toSortedMap().entries.joinToString("|") { (key,m) -> listOf(key,m.id,m.runtime,m.runtimeVersion,m.backend,m.assetSha256).joinToString("|") { JSONObject.quote(it ?: "<unknown>") } } +
                comparisonConfiguration(p.configuration).toSortedMap().entries.joinToString("|") { (k,v) -> "${JSONObject.quote(k)}=${JSONObject.quote(v)}" }
            return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        private fun methodology() = JSONArray(listOf(
            "Durations use one named monotonic clock per turn; epoch time is identity/provenance only.",
            "First answer text/playback exclude acknowledgement and filler. Playback head is a proxy, not acoustic audibility.",
            "Submission TTFT uses first raw text; callbacks including control/tool events are separately recorded.",
            "Exact native tokens require telemetry provenance. Estimated tokens/s remain estimates; callbacks are not tokens.",
            "Exact subsequent decode rate requires native first-token timing: (exact output tokens-1)/(generation end-native first token). Timings overlap and must not be added.",
            "ASR RTF=busy decode work/input audio. TTS RTF=synthesis work/generated audio; model loading excluded.",
            "All-attempt and completed-turn statistics are separate. Submission groups separate purpose, outcome and warm state.",
            "Missing values are unknown. Percentiles use nearest-rank; p95 below 20 and p99 below 100 samples are exploratory.",
            "Corpus WER/CER=sum edit errors/sum reference units; empty references contribute false insertions and are separately counted.",
            "WER/CER require explicit verified references. Human task, intent and factuality verdicts are separate labels.",
            "No acoustic, real-model, or device-performance success is inferred from JVM/emulator tests. Claims require observed physical-device evidence.",
            "Groups separate build/source/device/runtime/backend/model/configuration/environment/warm state; dynamic battery/thermal and reserved correlation IDs remain on every raw row, not group identity."
        ))
        /** Prevent spreadsheet formula execution in string cells while preserving ordinary numeric data. */
        private fun csvCell(raw: String): String {
            val leading = raw.trimStart()
            val safe = if (leading.firstOrNull() in listOf('=', '+', '@') || (leading.startsWith('-') && leading.toDoubleOrNull() == null) || raw.startsWith('\t') || raw.startsWith('\r')) "'$raw" else raw
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
        }
    }
}
