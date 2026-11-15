package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics
import java.util.Locale

/**
 * Plain-language endpointing account of one voice turn, rendered at the end of
 * the turn's diagnostics section (Battles's plan, approved 2026-11-01).
 *
 * Presentation only: every value comes from measurements the capture, reply and
 * playback paths already recorded for the turn. A value that was not recorded
 * renders as "not recorded" — never as a zero and never as an estimate.
 */
data class EndpointingSummary(
    val speechHeardMs: Long?,
    val silenceWaitedMs: Long?,
    val targetSilenceMs: Long?,
    val endedBy: String,
    val smartTurnVerdict: String,
    val deferrals: List<String>,
    val speechEndToEndpointMs: Long?,
    val endpointToFirstTextMs: Long?,
    val firstTextToFirstAudioMs: Long?,
    val speechEndToFirstAudioMs: Long?,
    val asrFinalization: String,
    val interruption: String
) {
    fun render(): String = buildString {
        appendLine("Endpointing:")
        appendLine("  speech heard: ${speechHeardMs?.let { seconds(it) } ?: NOT_RECORDED}")
        appendLine("  silence waited: ${ms(silenceWaitedMs)}" +
            (targetSilenceMs?.let { " (target was ${ms(it)})" } ?: ""))
        appendLine("  turn ended by: $endedBy")
        appendLine("  smart-turn verdict: $smartTurnVerdict")
        appendLine("  endpoint deferred: ${deferralText()}")
        appendLine("  speech end → endpoint decided: ${ms(speechEndToEndpointMs)}")
        appendLine("  endpoint → first reply text: ${ms(endpointToFirstTextMs)}")
        appendLine("  first reply text → first audio out: ${firstTextToFirstAudioMs?.let { ms(it) } ?: "not applicable — no completed TTS session recorded this turn"}")
        appendLine("  speech end → first audio out: ${ms(speechEndToFirstAudioMs)}${shareText()}")
        appendLine("  ASR finalization after endpoint: $asrFinalization")
        append("  reply interrupted: $interruption")
    }

    private fun deferralText(): String {
        if (deferrals.isEmpty()) return "never"
        val counted = LinkedHashMap<String, Int>()
        deferrals.forEach { counted[it] = (counted[it] ?: 0) + 1 }
        val reasons = counted.entries.joinToString("; ") { (reason, count) ->
            plainDeferral(reason) + if (count > 1) " ×$count" else ""
        }
        return "${deferrals.size} ${if (deferrals.size == 1) "time" else "times"} ($reasons)"
    }

    private fun shareText(): String {
        val decided = speechEndToEndpointMs ?: return ""
        val total = speechEndToFirstAudioMs ?: return ""
        if (total <= 0) return ""
        val percent = (decided * 100 + total / 2) / total
        return " — endpointing was ~$percent% of it"
    }

    companion object {
        private const val NOT_RECORDED = "not recorded"

        fun from(metrics: AsrCaptureMetrics, endpointToFirstTextMs: Long?,
                 speechEndToFirstAudioMs: Long?, nativeAudioTurn: Boolean,
                 firstTextToFirstAudioMs: Long? = null, interrupted: Boolean? = null,
                 bargeSource: String? = null, playedMsBeforeStop: Long? = null): EndpointingSummary =
            EndpointingSummary(
                // VAD-admitted speech samples at 16 kHz: 16 samples per millisecond.
                speechHeardMs = metrics.acoustic?.let { it.speechSampleCount / 16 },
                silenceWaitedMs = metrics.silenceWaitedMs,
                targetSilenceMs = metrics.targetSilenceMs,
                endedBy = endedBy(metrics),
                smartTurnVerdict = smartTurnVerdict(metrics),
                deferrals = metrics.endpointDeferrals,
                // Same definition the pipeline benchmark exports as endpointDelayMs:
                // detection after last speech plus the finalization that sealed it.
                speechEndToEndpointMs = metrics.endpointDetectionMs?.let { it + metrics.finalizationMs },
                endpointToFirstTextMs = endpointToFirstTextMs,
                firstTextToFirstAudioMs = firstTextToFirstAudioMs,
                speechEndToFirstAudioMs = speechEndToFirstAudioMs,
                asrFinalization = if (nativeAudioTurn)
                    "not applicable — native audio turn; Gemma heard the audio directly"
                else "${metrics.finalizationMs} ms",
                interruption = interruptionText(interrupted, bargeSource, playedMsBeforeStop))

        /**
         * What the barge-in records prove about this turn's reply. The stop
         * latency after the user's speech start is not captured anywhere in
         * the pipeline today, so it is reported as not recorded rather than
         * estimated; how much of the reply had played is recorded exactly
         * (SpeechDelivery playedFrames at the delivery's sample rate).
         */
        private fun interruptionText(interrupted: Boolean?, bargeSource: String?,
                                     playedMsBeforeStop: Long?): String = when (interrupted) {
            null -> NOT_RECORDED
            false -> "no"
            true -> buildString {
                append("yes — barge source: ").append(bargeSource ?: NOT_RECORDED)
                append(" · stop latency after speech start: ").append(NOT_RECORDED)
                if (playedMsBeforeStop != null)
                    append(" · reply had played ").append(ms(playedMsBeforeStop)).append(" when it stopped")
            }
        }

        private fun endedBy(metrics: AsrCaptureMetrics): String {
            val cue = metrics.endpointCue
            val mechanism = when {
                metrics.endpointReason == "explicit_stop" -> "explicit stop (flush)"
                metrics.endpointReason == "audio_input_limit" -> "max-turn cap — audio input limit"
                metrics.endpointReason == "utterance_capacity" -> "max-turn cap — utterance capacity"
                metrics.endpointReason == "initial_silence" -> "initial silence timeout — no speech heard"
                cue == "smart_turn_complete" -> "smart-turn verdict"
                cue == "deadline" -> "deadline — submitted at the 300 ms decision deadline"
                cue == "smart_turn_veto_hold" ->
                    "smart-turn veto hold — a high-confidence unfinished verdict extended the deadline once, then it expired"
                cue == "turn_stop_timeout" -> "turn_stop_timeout — the 5 s dead-man backstop"
                cue == "speech_timeout" -> "speech timeout — no analyzer verdict"
                cue == "smart_turn_continue" -> "smart-turn CONTINUE protection elapsed"
                cue == "fixed" -> "trailing silence — fixed target"
                cue != null -> "trailing silence — adaptive cue"
                else -> "trailing silence"
            }
            return if (cue != null && !mechanism.contains(cue)) "$mechanism (cue: $cue)" else mechanism
        }

        private fun smartTurnVerdict(metrics: AsrCaptureMetrics): String = when (metrics.smartTurnState) {
            null -> "not evaluated — no smart-turn analyzer on this capture"
            "COMPLETE", "CONTINUE" -> metrics.smartTurnState +
                (metrics.smartTurnProbability?.let {
                    ", probability " + String.format(Locale.US, "%.2f", it)
                } ?: " (probability not recorded)")
            "PENDING" -> "not evaluated — verdict still pending when the endpoint fired"
            "FALLBACK" -> "not evaluated — analyzer returned no verdict for this pause"
            else -> "not evaluated"
        }

        private fun plainDeferral(reason: String): String = when {
            reason == "smart_turn_veto_hold" ->
                "smart turn held the turn once (high-confidence unfinished verdict)"
            reason == "resumed_speech" -> "you resumed speaking mid-decision"
            reason == "audio_arrived_during_finalization" -> "audio arrived during finalization"
            reason == "audio_arrived_during_recovery" -> "audio arrived during ASR recovery"
            reason.startsWith("final_caption_") ->
                "final caption read as " + reason.removePrefix("final_caption_").replace('_', ' ')
            else -> reason.replace('_', ' ')
        }

        private fun ms(value: Long?): String =
            value?.let { String.format(Locale.US, "%,d ms", it) } ?: NOT_RECORDED

        private fun seconds(value: Long): String =
            String.format(Locale.US, "%.1f s", value / 1000.0)
    }
}
