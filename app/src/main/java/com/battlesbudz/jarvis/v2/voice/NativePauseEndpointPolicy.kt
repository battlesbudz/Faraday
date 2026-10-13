package com.battlesbudz.jarvis.v2.voice

/** Completed decoder work delivered independently of cached stable-caption publication. */
data class CompletedEndpointCue(val revision: Long, val text: String, val coveredAudioSamples: Long)

/** Native-only acoustic eligibility; silence is never proof of a speaker's intent to stop thinking. */
internal object NativePauseEndpointPolicy {
    data class Eligibility(val allowed: Boolean, val reason: String)

    fun eligibility(
        hasSpeech: Boolean,
        strongSpeechMs: Long,
        quietEvidenceUsed: Boolean,
        silenceMs: Long,
        raw: RawVadCoverage?,
        awaitingOnset: Boolean,
        backlogMs: Long,
        audioComplete: Boolean
    ): Eligibility {
        val reason = when {
            !hasSpeech -> "no_speech"
            !audioComplete -> "audio_limit"
            strongSpeechMs < 240 -> "short_or_weak_speech"
            quietEvidenceUsed -> "asr_corroborated_weak_speech"
            awaitingOnset -> "possible_onset"
            backlogMs > 0 -> "audio_backlog"
            silenceMs < 300 -> "pause_too_short"
            raw == null -> "unknown_raw_coverage"
            raw.receivedPcmBytes % 2 != 0L -> "split_sample"
            raw.classifiedThroughSample - raw.quietFromSample < 300 * 16 -> "raw_pause_too_short"
            else -> "clean_native_pause"
        }
        return Eligibility(reason == "clean_native_pause", reason)
    }

    /**
     * Never lengthen the existing 350 ms complete-and-stable endpoint. Known unfinished,
     * uncertain and explicit-hesitation cues retain their 1800/1500/3500 ms safety margins.
     * A strong clean native request with no completed caption can use the existing native
     * acoustic confirmation margin. This has the same undecidable thinking-pause limitation
     * as captions-off native audio; raw VAD confidence is a software heuristic, not certainty.
     */
    fun decision(base: AdaptiveTurnEnd.Decision, eligibility: Eligibility,
                 raw: RawVadCoverage?, collectedPcmBytes: Long,
                 legacyEndpoint: AdaptiveTurnEnd.Decision = base): AdaptiveTurnEnd.Decision {
        // A provisional candidate retains its own incomplete frame. A faster FINAL endpoint
        // cannot cut off an unclassified onset at the current collector boundary.
        val fullyCovered = raw != null && raw.receivedPcmBytes == collectedPcmBytes &&
            raw.classifiedThroughSample * 2 == collectedPcmBytes
        val proposed = if (eligibility.allowed && fullyCovered) when (base.cue) {
            "no_transcript" -> AdaptiveTurnEnd.Decision(minOf(base.silenceMs, 650), "native_clean_no_caption")
            "transcript_settling" -> AdaptiveTurnEnd.Decision(minOf(base.silenceMs, 650), "native_complete_settling")
            else -> base
        } else base
        // Weak raw continuation may not refresh confirmed lastSpeechAt. A NEW shortened
        // endpoint must nevertheless wait its entire chosen margin after that raw tail.
        if (proposed.silenceMs < legacyEndpoint.silenceMs && (!eligibility.allowed || !fullyCovered ||
                raw.classifiedThroughSample - raw.quietFromSample < proposed.silenceMs * 16)) return legacyEndpoint
        return proposed
    }

    fun permitsProposal(endpoint: AdaptiveTurnEnd.Decision): Boolean = endpoint.cue in setOf(
        "complete_and_stable", "transcript_settling", "native_complete_settling", "no_transcript",
        "native_clean_no_caption", "fixed"
    )
}


/** Completed cue delivery is neither a new word nor acoustic speech evidence. */
internal class NativeCaptionEndpointCueTracker {
    private var observedRevision = Long.MIN_VALUE
    private var observedAnyCue = false
    private var coveredThroughSample: Long? = null
    fun update(cue: CompletedEndpointCue?, lastSpeechSample: Long, nowMs: Long, turnEnd: TurnEndDetector): Boolean {
        if (cue == null || cue.revision == observedRevision) return false
        observedRevision = cue.revision
        observedAnyCue = true
        if (cue.coveredAudioSamples < lastSpeechSample || cue.text.isBlank() || TranscriptContent.isSoundOnly(cue.text)) {
            coveredThroughSample = null
            return false
        }
        turnEnd.update(TranscriptContent.speech(cue.text), nowMs)
        coveredThroughSample = cue.coveredAudioSamples
        return true
    }
    fun decision(base: AdaptiveTurnEnd.Decision, lastSpeechSample: Long,
                 fullyCoveredCompletedDecision: AdaptiveTurnEnd.Decision? = null): AdaptiveTurnEnd.Decision {
        if (!observedAnyCue) return base
        val covered = coveredThroughSample
        if (covered != null && covered >= lastSpeechSample) return fullyCoveredCompletedDecision ?: base
        // This feature cannot relabel or slow an independently qualifying legacy endpoint.
        // A stale/missing new cue only loses permission to shorten that existing decision.
        return base
    }
    fun reset() { observedRevision = Long.MIN_VALUE; observedAnyCue = false; coveredThroughSample = null }
}
