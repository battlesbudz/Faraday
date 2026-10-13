package com.battlesbudz.jarvis.v2.diagnostics

/** One owner per request; no token callback writes, text retention, or global current-turn slot. */
class PipelineBenchmarkCapture(
    val turnId: String,
    private val channel: String,
    private val capturedAtEpochMs: Long,
    private var provenance: PipelineBenchmarkProvenance,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val started = nowMs()
    private val stages = linkedMapOf("turn_started" to 0L)
    private val measurements = linkedMapOf<String, Double?>()
    private val passes = linkedMapOf<String, PipelineBenchmarkSubmission>()
    private var asr: PipelineBenchmarkAsr? = null
    private var tts: PipelineBenchmarkTts? = null
    private var droppedSubmissions = 0
    private var ended = false
    private var childOutcome: PipelineBenchmarkOutcome? = null
    private var childFailure: String? = null

    @Synchronized fun mark(stage: String) = markAt(stage, nowMs())
    @Synchronized fun markAt(stage: String, monotonicMs: Long) {
        if (!ended && monotonicMs >= started) stages.putIfAbsent(stage, monotonicMs - started)
    }
    @Synchronized fun metric(name: String, value: Number?) {
        if (!ended) measurements[name] = value?.toDouble()?.takeIf(Double::isFinite)
    }
    @Synchronized fun asr(value: PipelineBenchmarkAsr) { if (!ended) asr = value }
    @Synchronized fun tts(value: PipelineBenchmarkTts) { if (!ended) tts = value }
    @Synchronized fun configuration(name: String, value: String) {
        if (!ended) provenance = provenance.copy(configuration = provenance.configuration + (name to value))
    }
    @Synchronized fun transcriptionFallback() {
        if (!ended) asr = asr?.copy(transcriptionFallback = true)
    }
    @Synchronized fun noteOutcome(outcome: PipelineBenchmarkOutcome, failureCode: String? = null) {
        if (!ended && outcome !in setOf(PipelineBenchmarkOutcome.COMPLETE, PipelineBenchmarkOutcome.UNKNOWN)) {
            childOutcome = outcome
            childFailure = failureCode
        }
    }
    @Synchronized fun submission(value: PipelineBenchmarkSubmission) {
        if (ended) return
        if (passes.containsKey(value.submissionId)) return // Exactly one terminal observation per native attempt.
        if (passes.size == 64) { droppedSubmissions++; return }
        val nativeStart = value.metadata["started_monotonic_ms"]?.toLongOrNull()
        passes[value.submissionId] = value.copy(startedOffsetMs = nativeStart?.let { (it - started).takeIf { offset -> offset >= 0 } })
    }
    /** Never attribute a retry/draft/tool's guard or PCM to another native submission. */
    private fun alignSingleAnswerEvents() {
        val candidates = passes.values.filter { it.purpose != PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK &&
            it.metadata["generation_attempted"] != "false" }
        val answer = candidates.singleOrNull()?.takeIf { it.purpose == PipelineBenchmarkPurpose.ANSWER &&
            it.outcome == PipelineBenchmarkOutcome.COMPLETE && it.metadata["app_event_clock"] == "System.nanoTime" }
        val reason = when {
            droppedSubmissions > 0 -> "unavailable_omitted_submissions"
            candidates.size != 1 -> "unavailable_ambiguous_or_missing_answer"
            answer == null -> "unavailable_incomplete_answer_or_missing_clock"
            else -> "single_completed_answer_same_java_clock"
        }
        configuration("answer_processing_alignment", reason)
        if (answer == null || droppedSubmissions > 0) return
        val latest = started + (stages["turn_finished"] ?: return)
        listOf("submit_enter", "submit_return", "first_native_callback", "first_native_raw_text",
            "first_consumer_callback", "first_consumer_raw_text", "native_terminal", "consumer_drained").forEach { event ->
            answer.metadata["app_event_${event}_monotonic_ms"]?.toLongOrNull()
                ?.takeIf { it in started..latest }?.let { markAt("answer_$event", it) }
        }
    }

    @Synchronized fun finish(outcome: PipelineBenchmarkOutcome, callId: String? = null,
                             failureCode: String? = null): PipelineBenchmarkTurn? {
        if (ended) return null
        mark("turn_finished")
        alignSingleAnswerEvents()
        ended = true
        measurements["submissions_omitted"] = droppedSubmissions.toDouble()
        val resolvedOutcome = if (outcome in setOf(PipelineBenchmarkOutcome.COMPLETE, PipelineBenchmarkOutcome.UNKNOWN)) childOutcome ?: outcome else outcome
        return PipelineBenchmarkTurn(turnId, callId, channel, capturedAtEpochMs, provenance, resolvedOutcome,
            clock = "System.nanoTime", stageOffsetsMs = stages.toMap(), observedMetrics = measurements.toMap(),
            asr = asr, tts = tts, submissions = passes.values.toList(), failureCode = failureCode ?: childFailure, conversationId = provenance.configuration["conversation_id"])
    }
}
