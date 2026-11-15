package com.battlesbudz.jarvis.v2.voice

/** Endpoint arbitration only. The capture owner still retains and drains every admitted byte. */
internal object SmartTurnEndpointPolicy {
    // Reuse existing complete/stable and explicit-hesitation acoustic margins. These are
    // bounded product policy, not a claim of calibrated model confidence or phone latency.
    const val COMPLETE_SILENCE_MS = 350L
    const val CONTINUE_SILENCE_MS = 3500L

    // Deadline-first turn-stop constants (Battles's design, approved
    // 2026-11-05; replaces the REFERENCE arbitration). Silence creates a
    // deadline, not a request for the analyzer's permission to finish: the
    // turn submits DEADLINE_MS after the last speech unless the analyzer has
    // already produced a high-confidence unfinished verdict (the veto), which
    // buys exactly one VETO_EXTENSION_MS window. No verdict, a late verdict,
    // an uncertain verdict and a COMPLETE verdict all resolve to submission
    // at the deadline.
    const val DEADLINE_MS = 300L
    const val VETO_EXTENSION_MS = 300L
    const val VETO_MAX_COMPLETION_PROBABILITY = 0.02f
    const val CUE_DEADLINE = "deadline"
    const val CUE_VETO_HOLD = "smart_turn_veto_hold"

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
     * Input sanity for a verdict under deadline-first arbitration. [eligible]
     * is [NativePauseEndpointPolicy.smartTurnSanity] on every route — real
     * speech existed and the model snapshot covers the speech span it judged.
     * Later retained bytes are the pause itself and need no byte-exact
     * coverage: the capture owner's finalization drain, not this policy,
     * protects unclassified audio behind the seal.
     */
    fun saneVerdict(decision: CaptureEndpointDecision,
                    eligible: NativePauseEndpointPolicy.Eligibility,
                    collectedBytes: Long, lastSpeechSample: Long): Boolean {
        if (!eligible.allowed) return false
        val modelThrough = decision.modelThroughSample ?: return false
        if (modelThrough * 2 > collectedBytes) return false
        return modelThrough >= lastSpeechSample
    }

    /**
     * The veto: the analyzer may hold a turn past the deadline ONLY with a
     * CONTINUE verdict whose completion probability is at most
     * [VETO_MAX_COMPLETION_PROBABILITY] (at least 98% confident the sentence
     * is unfinished) under input sanity. A CONTINUE lean (e.g. p=0.34), a
     * verdict without a probability, or a sanity-failed verdict never holds.
     * The caller additionally requires the verdict to be in hand by the
     * deadline and latches the hold: exactly one extension per pause.
     */
    fun vetoQualifies(decision: CaptureEndpointDecision,
                      eligible: NativePauseEndpointPolicy.Eligibility,
                      collectedBytes: Long, lastSpeechSample: Long): Boolean {
        if (decision.state != CaptureEndpointState.CONTINUE) return false
        val probability = decision.probability ?: return false
        if (probability > VETO_MAX_COMPLETION_PROBABILITY) return false
        return saneVerdict(decision, eligible, collectedBytes, lastSpeechSample)
    }

    /**
     * Deadline-first arbitration. The default ending is submission at
     * [DEADLINE_MS] after the last speech: no verdict, a pending or fallback
     * state, an uncertain verdict and a sanity-failed verdict all land there.
     * A sane COMPLETE keeps its cue (the caption-omission consumer keys on
     * it) but still ends at the deadline, never earlier. While [vetoHoldActive]
     * the one extension runs to DEADLINE_MS + VETO_EXTENSION_MS under the
     * veto cue, whatever later verdicts say.
     */
    fun deadlineDecision(decision: CaptureEndpointDecision,
                         eligible: NativePauseEndpointPolicy.Eligibility,
                         collectedBytes: Long, lastSpeechSample: Long,
                         vetoHoldActive: Boolean): AdaptiveTurnEnd.Decision {
        if (vetoHoldActive) return AdaptiveTurnEnd.Decision(
            DEADLINE_MS + VETO_EXTENSION_MS, CUE_VETO_HOLD)
        if (decision.state == CaptureEndpointState.COMPLETE &&
            saneVerdict(decision, eligible, collectedBytes, lastSpeechSample))
            return AdaptiveTurnEnd.Decision(DEADLINE_MS, "smart_turn_complete")
        return AdaptiveTurnEnd.Decision(DEADLINE_MS, CUE_DEADLINE)
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
