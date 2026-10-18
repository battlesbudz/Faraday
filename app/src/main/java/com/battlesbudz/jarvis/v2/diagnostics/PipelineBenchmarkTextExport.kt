package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import java.security.MessageDigest

/** Metrics-only whole-scope report. Full JSON/CSV remain the existing detailed machine-readable exports. */
object PipelineBenchmarkTextExport {
    const val COPY_BYTES = 8_192

    /** Defensive collection copies; recorded DTO scalar values are immutable. */
    fun freeze(report: PipelineBenchmarkReport): PipelineBenchmarkReport = report.copy(turns = report.turns.map { turn ->
        turn.copy(provenance = turn.provenance.copy(models = turn.provenance.models.toMap(), configuration = turn.provenance.configuration.toMap()),
            stageOffsetsMs = turn.stageOffsetsMs.toMap(), observedMetrics = turn.observedMetrics.toMap(),
            submissions = turn.submissions.map { it.copy(measurements = it.measurements.toMap(), metadata = it.metadata.toMap()) })
    })

    private val correlationKeys = setOf("conversation_id", "parent_task_ids", "reply_id", "utterance_id", "returned_utterance_id",
        "result_utterance_id", "result_captured_at_epoch_ms", "linked_reply_turn_id", "followup_smart_turn_utterance_id", "capture_first_raw_transfer")
    private fun stableProvenance(p: PipelineBenchmarkProvenance) = p.copy(thermalStatus = null, batteryPercent = null,
        configuration = p.configuration.filterKeys { it !in correlationKeys })

    fun render(report: PipelineBenchmarkReport, scope: String): String = buildString {
        appendLine("Jarvis pipeline benchmark report v1")
        appendLine("Scope: $scope | exportedAtEpochMs=${report.exportedAtEpochMs}")
        appendLine("Retained attempts=${report.turns.size}; all retained turns in this scope follow, including failures/cancellations.")
        appendLine("This is observational evidence, not a controlled corpus or proof of improvement. Retention gaps/older unlinked turns cannot be reconstructed.")
        appendLine("No prompts, transcripts, reference text or audio. Detailed JSON/CSV remain available separately.")
        appendLine("Unavailable means not observed/not exposed, never zero. Numeric values retain their source precision.")
        appendLine("First answer text/playback exclude acknowledgement/filler. Playback-head timing is a proxy, not acoustic audibility.")
        appendLine("speech_end_* starts at last detected user speech; endpoint_* starts at recognition finalization. *_first_answer_playback ends at first answer playback-head advancement; *_first_answer_text ends at first answer text event.")
        appendLine("Stage offsets use the turn's named monotonic clock from its origin; epoch timestamps identify attempts and are not latency clocks.")
        appendLine("ASR real-time factor is busy decode work / input audio; TTS real-time factor is synthesis work / generated audio, excluding model loading.")
        appendLine("ttft_ms starts at app inference measurement creation, including lazy conversation preparation; submit_to_first_raw_text_ms starts at SDK send entry. SDK TTFT proxy is separate. Estimates are not native tokens. Timings overlap: do not add them.")
        appendLine("Statistics below use COMPLETE turns only within each comparable group. Cancelled/error attempts remain in raw rows and outcome counts.")
        appendLine("Groups separate build/source/device/model/runtime/backend/configuration/environment/warm state. No cross-group averages.")
        appendLine("Percentiles: nearest rank. p95 n<20 and p99 n<100 are exploratory; unknowns excluded, with n/missing reported.")
        appendLine("Metric units are in names (_ms, _bytes, _kib, _tokens, _per_second); ratios are dimensionless. See definitions below.")
        if (report.turns.isEmpty()) {
            appendLine("No retained measurements in this scope. No performance conclusion is available.")
            appendLine("END REPORT: 0 retained attempts.")
            return@buildString
        }

        val turnMetricKeys = report.turns.flatMap { it.metrics().keys }.distinct().sorted()
        val submissionMetricKeys = report.turns.flatMap { it.submissions }.flatMap { it.metrics().keys }.distinct().sorted()
        appendLine("\nMETRIC AVAILABILITY CATALOGS")
        appendLine("Every raw row lists its observed metrics. ALL unlisted keys in its catalog are unavailable for that attempt, never zero or omitted turns.")
        appendLine("TURN catalog: ${turnMetricKeys.joinToString(", ")}")
        appendLine("SUBMISSION catalog: ${submissionMetricKeys.joinToString(", ")}")
        val provenances = report.turns.map { stableProvenance(it.provenance) }.distinct()
        appendLine("\nPROVENANCE DIRECTORY (shared model/configuration/build/device values; battery/thermal/correlation values remain on each turn)")
        provenances.forEachIndexed { index, p ->
            appendLine("P${index + 1}: ${p.json()}")
        }
        val provenanceIds = provenances.withIndex().associate { it.value to "P${it.index + 1}" }
        val groups = report.turns.groupBy(PipelineBenchmarkReport::comparableGroupKey).toSortedMap()
        val groupIds = groups.keys.withIndex().associate { it.value to "G${it.index + 1}" }
        groups.entries.forEachIndexed { index, (key, turns) ->
            val first = turns.first()
            appendLine("\nGROUP G${index + 1} key=$key")
            appendLine("channel=${quoted(first.channel)} environment=${first.environment} ASR=${quoted(first.asr?.engine)} warm=${first.asr?.warmState ?: "UNKNOWN"} TTS=${quoted(first.tts?.engine)} warm=${first.tts?.warmState ?: "UNKNOWN"}")
            appendLine("provenance=${turns.map { provenanceIds.getValue(stableProvenance(it.provenance)) }.distinct().joinToString(",")} outcomes=${turns.groupingBy { it.outcome }.eachCount()}")
            val complete = turns.filter { it.outcome == PipelineBenchmarkOutcome.COMPLETE }
            appendLine("Completed-turn distributions: completed=${complete.size}/${turns.size}")
            statistics(complete.map { it.metrics() })
            // Model submissions cannot mix answer, retry, draft, warm state, model, or outcome.
            turns.flatMap { it.submissions }.groupBy { listOf(it.modelId, it.purpose.name, it.warmState.name, it.outcome.name) }
                .forEach { (identity, submissions) ->
                    appendLine("Submission group model=${quoted(identity[0])} purpose=${identity[1]} warm=${identity[2]} outcome=${identity[3]} count=${submissions.size}")
                    if (identity[3] == PipelineBenchmarkOutcome.COMPLETE.name) statistics(submissions.map { it.metrics() })
                    else appendLine("No success-latency distribution; raw observations follow.")
                }
        }
        appendLine("\nALL RETAINED ATTEMPTS (original order; no latest-50 export limit)")
        report.turns.forEachIndexed { index, turn ->
            appendLine("\nTURN ${index + 1}/${report.turns.size} id=${quoted(turn.turnId)} call=${quoted(turn.callId)} conversation=${quoted(turn.conversationId)}")
            appendLine("${groupIds.getValue(PipelineBenchmarkReport.comparableGroupKey(turn))} ${provenanceIds.getValue(stableProvenance(turn.provenance))} atEpochMs=${turn.capturedAtEpochMs} outcome=${turn.outcome} clock=${quoted(turn.clock)} failure=${quoted(turn.failureCode)}")
            appendLine("thermalStatus=${turn.provenance.thermalStatus ?: "unavailable"} batteryPercent=${turn.provenance.batteryPercent ?: "unavailable"} correlation=${JSONObject(turn.provenance.configuration.filterKeys { it in correlationKeys }.toSortedMap())}")
            appendLine("stageOffsetsMs=${JSONObject(turn.stageOffsetsMs.toSortedMap())}")
            metrics(turn.metrics())
            appendLine("ASR engine=${quoted(turn.asr?.engine)} endpoint=${quoted(turn.asr?.endpointReason)}; TTS engine=${quoted(turn.tts?.engine)} evidence=${quoted(turn.tts?.playbackEvidence)}")
            appendLine("Quality=${turn.quality?.json() ?: "unavailable"}; verified ASR score=${turn.accuracy?.json(false) ?: "unavailable"}")
            turn.submissions.forEach { submission ->
                appendLine("SUBMISSION id=${quoted(submission.submissionId)} purpose=${submission.purpose} outcome=${submission.outcome} model=${quoted(submission.modelId)} warm=${submission.warmState} startOffsetMs=${submission.startedOffsetMs ?: "unavailable"} tokenTelemetry=${quoted(submission.tokenTelemetrySource)}")
                metrics(submission.metrics())
            }
        }
        appendLine("\nMETRIC DEFINITIONS")
        PipelineBenchmarkDefinitions.notes.forEach { (key, note) -> appendLine("$key: $note") }
        appendLine("END REPORT: ${report.turns.size} retained attempts. Full JSON contains detailed submission metadata and saved reply observations when available.")
    }

    private fun StringBuilder.metrics(values: Map<String, Double?>) {
        val statuses = PipelineBenchmarkDefinitions.statuses(values)
        val observed = values.filterValues { it != null && it.isFinite() }
        if (observed.isEmpty()) appendLine("Measured metrics: none; all catalog keys unavailable.")
        else observed.toSortedMap().forEach { (key, value) ->
            appendLine("$key=$value [${statuses.getJSONObject(key).getString("status")}]")
        }
    }

    private fun StringBuilder.statistics(rows: List<Map<String, Double?>>) {
        if (rows.isEmpty()) { appendLine("No completed observations."); return }
        val unavailable = mutableListOf<String>()
        rows.flatMap { it.keys }.distinct().sorted().forEach { key ->
            val s = PipelineBenchmarkStatistics.from(rows.map { it[key] })
            if (s.n == 0) unavailable.add(key)
            else appendLine("$key: n=${s.n} missing=${s.missing} p50=${s.p50} p95=${s.p95}${if (s.n < 20) "(exploratory)" else ""} p99=${s.p99}${if (s.n < 100) "(exploratory)" else ""}")
        }
        if (unavailable.isNotEmpty()) appendLine("Unavailable (each n=0 missing=${rows.size}): ${unavailable.joinToString(", ")}")
    }

    private fun quoted(value: String?): String = value?.let(JSONObject::quote) ?: "unavailable"

    /** Lossless UTF-8 bounded pieces. Headers identify one frozen report; concatenating bodies restores it. */
    fun chunks(report: String, maxBytes: Int = COPY_BYTES): List<String> {
        require(maxBytes >= 256)
        val id = MessageDigest.getInstance("SHA-256").digest(report.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        // Reserve enough for Int-sized part counters and the report identity. No surrogate pair is split.
        val budget = maxBytes - 128
        val bodies = mutableListOf<String>()
        var start = 0
        while (start < report.length) {
            var end = start
            var bytes = 0
            var lastNewline = -1
            while (end < report.length) {
                val cp = Character.codePointAt(report, end)
                val chars = Character.charCount(cp)
                val width = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
                if (bytes + width > budget) break
                bytes += width
                end += chars
                if (cp == 10) lastNewline = end
            }
            if (end < report.length && lastNewline > start) end = lastNewline
            bodies.add(report.substring(start, end))
            start = end
        }
        if (bodies.isEmpty()) bodies.add("")
        return bodies.mapIndexed { index, body -> "Jarvis report $id | part ${index + 1}/${bodies.size}\n$body" }
            .also { parts -> check(parts.all { it.toByteArray(Charsets.UTF_8).size <= maxBytes }) }
    }
}
