package com.battlesbudz.jarvis.v2.voice

/** Endpoint arbitration only. The capture owner still retains and drains every admitted byte. */
internal object SmartTurnEndpointPolicy {
    // Reuse existing complete/stable and explicit-hesitation acoustic margins. These are
    // bounded product policy, not a claim of calibrated model confidence or phone latency.
    const val COMPLETE_SILENCE_MS = 350L
    const val CONTINUE_SILENCE_MS = 3500L

    /** Pinned Pipecat v3.2 semantics; this threshold is not Jarvis calibration. */
    fun modelState(probability: Float): CaptureEndpointState {
        require(probability.isFinite() && probability in 0f..1f)
        return if (probability > 0.5f) CaptureEndpointState.COMPLETE else CaptureEndpointState.CONTINUE
    }

    fun decision(fallback: AdaptiveTurnEnd.Decision, decision: CaptureEndpointDecision,
                 eligible: NativePauseEndpointPolicy.Eligibility, routeAllowed: Boolean,
                 raw: RawVadCoverage?, collectedBytes: Long, silenceMs: Long): AdaptiveTurnEnd.Decision {
        if (!routeAllowed) return fallback
        val state = decision.state
        if (state == CaptureEndpointState.CONTINUE) {
            return AdaptiveTurnEnd.Decision(maxOf(fallback.silenceMs, CONTINUE_SILENCE_MS), "smart_turn_continue")
        }
        if (state != CaptureEndpointState.COMPLETE || !eligible.allowed) return fallback
        // The model snapshot may precede this boundary by classified quiet audio only.
        // Never infer silence for an unclassified suffix, cached probability, or time gap.
        val modelThrough = decision.modelThroughSample ?: return fallback
        if (modelThrough * 2 > collectedBytes) return fallback
        val modelCoversAll = modelThrough * 2 == collectedBytes
        // COMPLETE covers every byte of its own snapshot, including a partial Silero frame.
        // Any later retained bytes need actual complete raw quiet classification, never padding.
        if (!modelCoversAll && (raw == null || raw.receivedPcmBytes != collectedBytes ||
                raw.classifiedThroughSample * 2 != collectedBytes || raw.quietFromSample > modelThrough)) return fallback
        if (silenceMs < COMPLETE_SILENCE_MS) return fallback
        return AdaptiveTurnEnd.Decision(minOf(fallback.silenceMs, COMPLETE_SILENCE_MS), "smart_turn_complete")
    }

    /** A timestamp gap or unclassified suffix cannot spend CONTINUE's protection.
     * Require the current collector boundary to be fully classified, including the last sample. */
    fun silenceEvidenceMs(endpoint: AdaptiveTurnEnd.Decision, raw: RawVadCoverage?,
                          collectedBytes: Long, lastSpeechSample: Long, captureSilenceMs: Long): Long {
        if (endpoint.cue != "smart_turn_continue") return captureSilenceMs
        if (raw == null || raw.receivedPcmBytes != collectedBytes || collectedBytes % 2 != 0L ||
            raw.classifiedThroughSample != collectedBytes / 2 || raw.quietFromSample < 0 ||
            raw.quietFromSample > raw.classifiedThroughSample || lastSpeechSample < 0) return 0
        val quietSamples = (raw.classifiedThroughSample - maxOf(raw.quietFromSample, lastSpeechSample)).coerceAtLeast(0)
        return minOf(captureSilenceMs, quietSamples / 16).coerceAtLeast(0)
    }

    fun permitsSpeculation(state: CaptureEndpointState): Boolean =
        state == CaptureEndpointState.FALLBACK || state == CaptureEndpointState.COMPLETE
}
