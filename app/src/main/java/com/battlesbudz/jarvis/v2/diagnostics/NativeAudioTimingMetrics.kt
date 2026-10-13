package com.battlesbudz.jarvis.v2.diagnostics

import androidx.annotation.Keep
import org.json.JSONObject
import java.util.Locale

/** Bounded, content-free final-candidate observations. No absolute clock or native identity. */
@Keep
data class NativeAudioTimingMetrics(
    val retainedPcmSamples: Int,
    val retainedPreRollSamples: Int?,
    val completedSteps: Int,
    val validatedRows: Int,
    val clockMappingAvailable: Boolean,
    val alignmentUncertaintyNanos: Long?,
    val firstAudioToSpeechLowerMs: Double? = null,
    val firstAudioToSpeechUpperMs: Double? = null,
    val firstStepFromAdmissionLowerMs: Double? = null,
    val firstStepFromAdmissionUpperMs: Double? = null,
    val endpointFromAdmissionLowerMs: Double? = null,
    val endpointFromAdmissionUpperMs: Double? = null,
    val stepsBeforeEndpointLowerBound: Int? = null,
    val stepsBeforeEndpointExact: Int? = null,
    val rowsBeforeEndpointExact: Int? = null,
    val checkedCloseDurationMs: Double? = null,
    val lastStepFromAdmissionLowerMs: Double? = null,
    val lastStepFromAdmissionUpperMs: Double? = null,
    val firstToLastStepLowerMs: Double? = null,
    val firstToLastStepUpperMs: Double? = null,
    val endpointToLastStepLowerMs: Double? = null,
    val endpointToLastStepUpperMs: Double? = null,
    val endpointToSealCommitLowerMs: Double? = null,
    val endpointToSealCommitUpperMs: Double? = null,
) {
    init {
        require(retainedPcmSamples in 1..480_000)
        require(retainedPreRollSamples == null || retainedPreRollSamples in 0..minOf(19_200, retainedPcmSamples))
        require(completedSteps in 0..750 && validatedRows in 0..750)
        require(alignmentUncertaintyNanos == null || alignmentUncertaintyNanos >= 0)
        require(clockMappingAvailable == (alignmentUncertaintyNanos != null))
        require(validInterval(firstAudioToSpeechLowerMs, firstAudioToSpeechUpperMs, false))
        require(validInterval(firstStepFromAdmissionLowerMs, firstStepFromAdmissionUpperMs, true))
        require(validInterval(endpointFromAdmissionLowerMs, endpointFromAdmissionUpperMs, true))
        require(validInterval(lastStepFromAdmissionLowerMs, lastStepFromAdmissionUpperMs, true))
        require(validInterval(firstToLastStepLowerMs, firstToLastStepUpperMs, true))
        require(validInterval(endpointToLastStepLowerMs, endpointToLastStepUpperMs, true))
        require(validInterval(endpointToSealCommitLowerMs, endpointToSealCommitUpperMs, true))
        require(checkedCloseDurationMs == null || checkedCloseDurationMs.isFinite() && checkedCloseDurationMs in 0.0..MAX_DURATION_MS)
        require(stepsBeforeEndpointLowerBound == null || stepsBeforeEndpointLowerBound in 0..completedSteps)
        require(stepsBeforeEndpointExact == null || stepsBeforeEndpointExact in 0..completedSteps)
        require(rowsBeforeEndpointExact == null || rowsBeforeEndpointExact in 0..validatedRows)
        require(stepsBeforeEndpointExact == null || stepsBeforeEndpointLowerBound == stepsBeforeEndpointExact)
        require((stepsBeforeEndpointExact == null) == (rowsBeforeEndpointExact == null))
        if (!clockMappingAvailable) require(listOf(firstAudioToSpeechLowerMs, firstStepFromAdmissionLowerMs,
            endpointFromAdmissionLowerMs, stepsBeforeEndpointLowerBound,
            lastStepFromAdmissionLowerMs, firstToLastStepLowerMs, endpointToLastStepLowerMs, endpointToSealCommitLowerMs).all { it == null })
    }

    fun summary(): String {
        val lower = firstAudioToSpeechLowerMs ?: return "First audio → speech —"
        val upper = requireNotNull(firstAudioToSpeechUpperMs)
        // The displayed midpoint rounds to 10 ms; include its 5 ms quantization
        // uncertainty and round the bound outward rather than printing false zero.
        val displayedUncertaintyMs = kotlin.math.ceil(((upper - lower) / 2 + 5.0) / 10.0) * 10.0
        return String.format(Locale.US, "First audio → speech %.2fs (clock ±%.2fs)",
            (lower + (upper - lower) / 2) / 1000, displayedUncertaintyMs / 1000)
    }

    /** Shared scalar lane is included by the existing per-turn JSON and CSV exporters. */
    fun observations(): Map<String, Number?> = linkedMapOf(
        "native_audio_retained_pcm_samples" to retainedPcmSamples,
        "native_audio_retained_preroll_samples" to retainedPreRollSamples,
        "native_audio_completed_steps" to completedSteps,
        "native_audio_validated_rows" to validatedRows,
        "native_audio_clock_mapping_available" to if (clockMappingAvailable) 1 else 0,
        "native_audio_alignment_uncertainty_ns" to alignmentUncertaintyNanos,
        "native_first_audio_to_speech_lower_ms" to firstAudioToSpeechLowerMs,
        "native_first_audio_to_speech_upper_ms" to firstAudioToSpeechUpperMs,
        "native_audio_first_step_from_admission_lower_ms" to firstStepFromAdmissionLowerMs,
        "native_audio_first_step_from_admission_upper_ms" to firstStepFromAdmissionUpperMs,
        "native_audio_endpoint_from_admission_lower_ms" to endpointFromAdmissionLowerMs,
        "native_audio_endpoint_from_admission_upper_ms" to endpointFromAdmissionUpperMs,
        "native_audio_steps_before_endpoint_lower_bound" to stepsBeforeEndpointLowerBound,
        "native_audio_steps_before_endpoint_exact" to stepsBeforeEndpointExact,
        "native_audio_rows_before_endpoint_exact" to rowsBeforeEndpointExact,
        "native_audio_checked_close_ms" to checkedCloseDurationMs,
        "native_audio_last_step_from_admission_lower_ms" to lastStepFromAdmissionLowerMs,
        "native_audio_last_step_from_admission_upper_ms" to lastStepFromAdmissionUpperMs,
        "native_audio_first_to_last_step_elapsed_lower_ms" to firstToLastStepLowerMs,
        "native_audio_first_to_last_step_elapsed_upper_ms" to firstToLastStepUpperMs,
        "native_audio_endpoint_to_last_step_lower_ms" to endpointToLastStepLowerMs,
        "native_audio_endpoint_to_last_step_upper_ms" to endpointToLastStepUpperMs,
        "native_audio_endpoint_to_seal_commit_lower_ms" to endpointToSealCommitLowerMs,
        "native_audio_endpoint_to_seal_commit_upper_ms" to endpointToSealCommitUpperMs,
    )

    fun json(): JSONObject = JSONObject().put("schema", 1).also { json ->
        observations().forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
    }

    companion object {
        private const val MAX_DURATION_MS = Long.MAX_VALUE / 1_000_000.0
        private fun validInterval(lower: Double?, upper: Double?, signed: Boolean): Boolean =
            if (lower == null || upper == null) lower == null && upper == null
            else lower.isFinite() && upper.isFinite() && upper >= lower &&
                lower >= -MAX_DURATION_MS && upper <= MAX_DURATION_MS && (signed || lower >= 0)

        /** Malformed new metrics remain unavailable without discarding older reply metrics. */
        fun read(json: JSONObject?): NativeAudioTimingMetrics? = json?.let { data -> runCatching {
            require(data.opt("schema") == 1)
            fun number(key: String): Number? = data.opt(key).let { if (it == null || it == JSONObject.NULL) null
                else (it as? Number)?.also { n -> require(n.toDouble().isFinite()) } ?: error("invalid_timing_number") }
            fun integer(key: String): Int? = number(key)?.let {
                require(it.toDouble() == it.toInt().toDouble()); it.toInt()
            }
            fun long(key: String): Long? = number(key)?.let {
                require(it is Long || it is Int); it.toLong()
            }
            val mapping = requireNotNull(integer("native_audio_clock_mapping_available"))
            require(mapping in 0..1)
            NativeAudioTimingMetrics(
                retainedPcmSamples = requireNotNull(integer("native_audio_retained_pcm_samples")),
                retainedPreRollSamples = integer("native_audio_retained_preroll_samples"),
                completedSteps = requireNotNull(integer("native_audio_completed_steps")),
                validatedRows = requireNotNull(integer("native_audio_validated_rows")),
                clockMappingAvailable = mapping == 1,
                alignmentUncertaintyNanos = long("native_audio_alignment_uncertainty_ns"),
                firstAudioToSpeechLowerMs = number("native_first_audio_to_speech_lower_ms")?.toDouble(),
                firstAudioToSpeechUpperMs = number("native_first_audio_to_speech_upper_ms")?.toDouble(),
                firstStepFromAdmissionLowerMs = number("native_audio_first_step_from_admission_lower_ms")?.toDouble(),
                firstStepFromAdmissionUpperMs = number("native_audio_first_step_from_admission_upper_ms")?.toDouble(),
                endpointFromAdmissionLowerMs = number("native_audio_endpoint_from_admission_lower_ms")?.toDouble(),
                endpointFromAdmissionUpperMs = number("native_audio_endpoint_from_admission_upper_ms")?.toDouble(),
                stepsBeforeEndpointLowerBound = integer("native_audio_steps_before_endpoint_lower_bound"),
                stepsBeforeEndpointExact = integer("native_audio_steps_before_endpoint_exact"),
                rowsBeforeEndpointExact = integer("native_audio_rows_before_endpoint_exact"),
                checkedCloseDurationMs = number("native_audio_checked_close_ms")?.toDouble(),
                lastStepFromAdmissionLowerMs = number("native_audio_last_step_from_admission_lower_ms")?.toDouble(),
                lastStepFromAdmissionUpperMs = number("native_audio_last_step_from_admission_upper_ms")?.toDouble(),
                firstToLastStepLowerMs = number("native_audio_first_to_last_step_elapsed_lower_ms")?.toDouble(),
                firstToLastStepUpperMs = number("native_audio_first_to_last_step_elapsed_upper_ms")?.toDouble(),
                endpointToLastStepLowerMs = number("native_audio_endpoint_to_last_step_lower_ms")?.toDouble(),
                endpointToLastStepUpperMs = number("native_audio_endpoint_to_last_step_upper_ms")?.toDouble(),
                endpointToSealCommitLowerMs = number("native_audio_endpoint_to_seal_commit_lower_ms")?.toDouble(),
                endpointToSealCommitUpperMs = number("native_audio_endpoint_to_seal_commit_upper_ms")?.toDouble(),
            )
        }.getOrNull() }
    }
}
