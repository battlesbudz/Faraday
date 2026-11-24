package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.diagnostics.InferenceTiming
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency

/** Maps one Omni call turn's measured timings onto the standard [TurnLatency] record the Gemma path attaches to a reply, so the in-app latency footer and the diagnostics export show Omni turns the same way. Missing graph timings stay null (unknown), never zero. */
internal object OmniTurnLatency {
    const val ENGINE_LABEL = "LFM3-Omni-2B"

    fun turnId(endpointAtMs: Long): String = "omni-$endpointAtMs"

    fun build(
        endpointAtMs: Long,
        endpointToFirstSoundMs: Long?,
        graphTimingsMs: Map<String, Long>,
        totalMs: Long,
        answer: OmniCallAnswer,
    ): TurnLatency {
        val speechGenerationMs = graphTimingsMs["speech_generate_ms"]
        val firstTokenMs = graphTimingsMs["audio_encode_ms"]?.let { audioEncodeMs ->
            graphTimingsMs["text_prefill_ms"]?.let { textPrefillMs -> audioEncodeMs + textPrefillMs }
        }
        return TurnLatency(
            id = turnId(endpointAtMs),
            totalMs = totalMs.coerceAtLeast(0),
            firstVisibleTextMs = null,
            modelLoadMs = 0,
            lookupMs = 0,
            passes = listOf(
                InferenceTiming(
                    stage = "omni_speech",
                    firstTokenMs = firstTokenMs,
                    totalMs = speechGenerationMs,
                    estimatedTokens = answer.speechFrames,
                    estimatedTokensPerSecond = speechGenerationMs
                        ?.takeIf { it > 0 }
                        ?.let { answer.speechFrames * 1000.0 / it },
                    prepared = false,
                ),
            ),
            voice = ENGINE_LABEL,
            speechEndToReplyMs = endpointToFirstSoundMs,
            textToPcmMs = null,
            textToPlaybackMs = null,
            supplyGapMs = null,
        )
    }
}
