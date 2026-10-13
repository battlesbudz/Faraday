package com.battlesbudz.jarvis.v2.voice.comparison

import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.WordErrorRate
import com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in instrumentation of real voice turns. Reference text is never passed to inference. */
internal object LiveComparison {
    enum class Path(val label: String) {
        WHISPER("Whisper CPU → Gemma → Piper"), WHISPER_NPU("Whisper NPU with CPU fallback → Gemma → Piper"),
        GEMMA_ASR("Gemma transcription → Gemma answer → Piper"), GEMMA_DIRECT("Gemma audio answer → Piper");
        val usesAudio get() = this == GEMMA_ASR || this == GEMMA_DIRECT
        val captureEngine get() = if (this == WHISPER_NPU) AsrEngine.WHISPER_NPU else AsrEngine.WHISPER
    }
    data class Request(val path: Path, val reference: String, val repeat: Int)
    val status = MutableStateFlow("No comparison armed.")
    val results = MutableStateFlow<List<Trial>>(emptyList())
    private val pending = ArrayDeque<Request>()
    @Synchronized fun arm(path: Path, reference: String) {
        require(reference.isNotBlank() && reference.length <= 1000)
        check(pending.isEmpty())
        repeat(3) { pending.addLast(Request(path, reference, it + 1)) }
        status.value = "Armed: ${path.label}. Start a fresh call and read the reference three times, once per turn."
    }
    @Synchronized fun take(id: String): Trial? = if (pending.isEmpty()) null else Trial(id, pending.removeFirst()).also {
        status.value = "Measuring ${it.request.path.label}, turn ${it.request.repeat}/3."
    }
    @Synchronized fun cancelPending() { pending.clear(); status.value = "Comparison stopped; completed ZIPs remain available." }
    @Synchronized fun finish(trial: Trial) {
        results.value = (results.value + trial).takeLast(12)
        status.value = "Saved measurement ${trial.request.repeat}/3. ${pending.size} comparison turns remain."
    }
    @Synchronized fun clear() { check(pending.isEmpty()); results.value = emptyList() }

    class Trial(val id: String, val request: Request) {
        private val began = System.nanoTime() / 1_000_000
        private val events = linkedMapOf<String, Long>()
        private val details = linkedMapOf<String, Any>()
        private val logs = mutableListOf<String>()
        var wav: ByteArray = byteArrayOf()
        @Synchronized fun mark(name: String, at: Long = System.nanoTime() / 1_000_000) { events.putIfAbsent(name, at) }
        @Synchronized fun put(key: String, value: Any) { details[key] = value }
        @Synchronized fun log(value: String) { if (logs.size < 500) logs += if (value.length > 1500) value.take(1500) + " [truncated]" else value }
        @Synchronized fun report(): String {
            val json = JSONObject(details as Map<*, *>).put("turn", id).put("path", request.path.name)
                .put("repeat", request.repeat).put("reference_for_scoring_only", request.reference)
                .put("scope", "real_voice_call_live_microphone_real_generation_piper_and_barge_listener")
                .put("audio_timing_precision", "first_non_silent_answer_PCM_rendered_by_AudioTrack_not_acoustic_measurement")
                .put("speech_end_precision", "capture_VAD_estimate_monotonic_clock")
                .put("input_comparability", "separate_live_readings_not_identical_waveforms")
                .put("reference_declared_by_user", true).put("lookup_and_device_tools", "disabled_for_repeatable_benchmark")
                .put("asr_partial_is_text_hypothesis_not_native_token", true)
                .put("filler_excluded", true).put("no_synthetic_load", true)
                .put("events_ms_from_trial_start", JSONObject(events.mapValues { it.value - began }))
            fun duration(key: String, start: String, end: String) {
                val a = events[start]; val b = events[end]
                json.put(key, if (a != null && b != null) b - a else JSONObject.NULL)
            }
            duration("answer_submit_to_first_token_ms", "answer_submit", "answer_first_token")
            duration("speech_end_to_answer_first_token_ms", "speech_end", "answer_first_token")
            duration("speech_end_to_first_audible_answer_frame_ms", "speech_end", "answer_audio")
            duration("asr_submit_to_first_token_ms", "asr_submit", "asr_first_token")
            duration("speech_end_to_final_transcript_ms", "speech_end", "transcript_final")
            duration("speech_end_to_asr_first_partial_ms", "speech_end", "asr_first_partial")
            val transcript = details["resolved_transcript"] as? String
            val recognitionOk = (details["recognition_issue"] == null || details["recognition_issue"] == "none") &&
                (request.path == Path.GEMMA_DIRECT || (transcript != null && VoiceTranscriptResolver.hasTranscript(transcript)))
            json.put("recognition_valid", recognitionOk)
            if (!recognitionOk) json.put("invalid_reason", details["recognition_issue"]?.takeUnless { it == "none" } ?: "missing_usable_transcript")
            json.put("complete_trial", recognitionOk && details["turn_completed"] == true && details["interrupted"] != true &&
                details["input_is_interruption_correction"] != true && !details.containsKey("generation_error") &&
                !details.containsKey("tts_error") && events.containsKey("answer_first_token") && events.containsKey("answer_audio"))
            json.put("answer_audio_observed", events.containsKey("answer_audio"))
            json.put("barge_keyword_ready_observed", logs.any { it.startsWith("barge barge_keyword_ready") })
            json.put("prefill_observed", logs.any { it.startsWith("incremental input_prefilled") })
            if (request.path != Path.GEMMA_DIRECT) {
                val score = transcript?.takeIf { recognitionOk }?.let { WordErrorRate.score(request.reference, it) }
                json.put("word_error_rate_percent", score?.percent ?: JSONObject.NULL)
            } else json.put("word_error_rate_percent", JSONObject.NULL).put("accuracy", "direct_answer_requires_human_correctness_review")
            return json.toString(2) + "\n\n" + logs.joinToString("\n")
        }
        fun writeZip(output: OutputStream) = ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("report.txt", report().toByteArray())
            if (wav.isNotEmpty()) entry("input.wav", wav)
        }
    }
}
