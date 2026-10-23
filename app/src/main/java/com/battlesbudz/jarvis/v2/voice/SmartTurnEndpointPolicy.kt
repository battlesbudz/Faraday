package com.battlesbudz.jarvis.v2.voice

/** Endpoint arbitration only. The capture owner still retains and drains every admitted byte. */
internal object SmartTurnEndpointPolicy {
    // Reuse existing complete/stable and explicit-hesitation acoustic margins. These are
    // bounded product policy, not a claim of calibrated model confidence or phone latency.
    const val COMPLETE_SILENCE_MS = 350L
    const val CONTINUE_SILENCE_MS = 3500L

    // Reference (Pipecat) turn-stop constants. The analyzer is the stop strategy;
    // the only ending behind it is the user_turn_stop_timeout backstop, which
    // Pipecat documents as "a backstop, not part of normal timing". With no
    // analyzer available, Pipecat's plain stop strategy is a fixed silence
    // window after the VAD stop (SpeechTimeoutUserTurnStopStrategy, 0.6s default).
    const val TURN_STOP_TIMEOUT_MS = 5_000L
    const val SPEECH_TIMEOUT_MS = 600L
    const val CUE_TURN_STOP_TIMEOUT = "turn_stop_timeout"
    const val CUE_SPEECH_TIMEOUT = "speech_timeout"

    /** Pinned Pipecat v3.2 semantics; this threshold is not Jarvis calibration. */
    fun modelState(probability: Float): CaptureEndpointState {
        require(probability.isFinite() && probability in 0f..1f)
        return if (probability > 0.5f) CaptureEndpointState.COMPLETE else CaptureEndpointState.CONTINUE
    }

    /**
     * [bindVerdict] is the Whisper/ASR-authoritative route: a COMPLETE verdict is the
     * endpoint authority there, and [eligible] is the input-sanity eligibility from
     * [NativePauseEndpointPolicy.smartTurnSanity] (speech existed; the model snapshot
     * covers the speech span). The native-audio route passes bindVerdict = false and
     * keeps the full native eligibility conjunction plus the byte-exact raw-coverage
     * clauses below: its snapshot must cover every collected byte, because no
     * recognizer finalization stands behind that route's endpoint.
     */
    fun decision(fallback: AdaptiveTurnEnd.Decision, decision: CaptureEndpointDecision,
                 eligible: NativePauseEndpointPolicy.Eligibility, routeAllowed: Boolean,
                 raw: RawVadCoverage?, collectedBytes: Long, silenceMs: Long,
                 lastSpeechSample: Long, bindVerdict: Boolean): AdaptiveTurnEnd.Decision {
        if (!routeAllowed) return fallback
        val state = decision.state
        if (state == CaptureEndpointState.CONTINUE) {
            return AdaptiveTurnEnd.Decision(maxOf(fallback.silenceMs, CONTINUE_SILENCE_MS), "smart_turn_continue")
        }
        if (state != CaptureEndpointState.COMPLETE || !eligible.allowed) return fallback
        val modelThrough = decision.modelThroughSample ?: return fallback
        if (modelThrough * 2 > collectedBytes) return fallback
        if (bindVerdict) {
            // Sanity already established the snapshot covers the speech span; later
            // retained bytes are the pause itself and need no byte-exact coverage.
            if (modelThrough < lastSpeechSample) return fallback
        } else {
            // The model snapshot may precede this boundary by classified quiet audio only.
            // Never infer silence for an unclassified suffix, cached probability, or time gap.
            val modelCoversAll = modelThrough * 2 == collectedBytes
            // COMPLETE covers every byte of its own snapshot, including a partial Silero frame.
            // Any later retained bytes need actual complete raw quiet classification, never padding.
            if (!modelCoversAll && (raw == null || raw.receivedPcmBytes != collectedBytes ||
                    raw.classifiedThroughSample * 2 != collectedBytes || raw.quietFromSample > modelThrough)) return fallback
        }
        if (silenceMs < COMPLETE_SILENCE_MS) return fallback
        return AdaptiveTurnEnd.Decision(minOf(fallback.silenceMs, COMPLETE_SILENCE_MS), "smart_turn_complete")
    }

    /**
     * Reference turn-stop arbitration: a COMPLETE verdict under input sanity is
     * the ONLY verdict-based endpoint. [eligible] is
     * [NativePauseEndpointPolicy.smartTurnSanity] on every route — real speech
     * existed and the model snapshot covers the speech span it judged. Later
     * retained bytes are the pause itself and need no byte-exact coverage: the
     * capture owner's finalization drain, not this policy, protects unclassified
     * audio behind the seal. Any other state (CONTINUE, PENDING, FALLBACK, or a
     * sanity-blocked COMPLETE) returns null: the turn stays open and only the
     * caller's dead-man backstop may end it.
     */
    fun referenceDecision(decision: CaptureEndpointDecision,
                          eligible: NativePauseEndpointPolicy.Eligibility,
                          collectedBytes: Long, silenceMs: Long,
                          lastSpeechSample: Long): AdaptiveTurnEnd.Decision? {
        if (decision.state != CaptureEndpointState.COMPLETE || !eligible.allowed) return null
        val modelThrough = decision.modelThroughSample ?: return null
        if (modelThrough * 2 > collectedBytes) return null
        if (modelThrough < lastSpeechSample) return null
        if (silenceMs < COMPLETE_SILENCE_MS) return null
        return AdaptiveTurnEnd.Decision(COMPLETE_SILENCE_MS, "smart_turn_complete")
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
