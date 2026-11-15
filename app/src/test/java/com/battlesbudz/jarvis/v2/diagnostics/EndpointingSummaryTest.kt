package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.voice.AsrCaptureMetrics
import com.battlesbudz.jarvis.v2.voice.CaptureAcousticMetrics
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointingSummaryTest {

    private fun acoustic(speechSamples: Long) = CaptureAcousticMetrics(
        sampleCount = speechSamples + 40_000, speechSampleCount = speechSamples,
        nearSilentSampleCount = 0, clippedSampleCount = 0, rmsDbfs = -30.0, peakDbfs = -3.0,
        nonSpeechRmsDbfs = -50.0, speechRmsDbfs = -25.0, speechToNonSpeechEnergyDb = 12.0,
        noiseFloorRms = 10.0)

    private fun metrics(
        endpointReason: String = "trailing_silence",
        finalizationMs: Long = 145,
        endpointDetectionMs: Long? = 210,
        targetSilenceMs: Long? = 350,
        endpointCue: String? = "smart_turn_complete",
        endpointAuthority: String? = "smart_turn",
        silenceWaitedMs: Long? = 402,
        smartTurnState: String? = "COMPLETE",
        smartTurnProbability: Float? = 0.83f,
        endpointDeferrals: List<String> = emptyList(),
        acoustic: CaptureAcousticMetrics? = acoustic(56_000)
    ) = AsrCaptureMetrics(
        modelLoadMs = 0, captureReadyMs = 0, audioMs = 6_000, decodeMs = 0,
        maxDecodeChunkMs = 0, firstPartialAfterSpeechMs = null, partialUpdates = 0,
        finalizationMs = finalizationMs, endpointReason = endpointReason,
        endpointDetectionMs = endpointDetectionMs, targetSilenceMs = targetSilenceMs,
        endpointCue = endpointCue, acoustic = acoustic, finalDecodeMs = 140,
        endpointAuthority = endpointAuthority, silenceWaitedMs = silenceWaitedMs,
        smartTurnState = smartTurnState, smartTurnProbability = smartTurnProbability,
        endpointDeferrals = endpointDeferrals)

    @Test fun smartTurnEndedTurnRendersVerdictMechanismAndRecordedValues() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        assertTrue(block.startsWith("Endpointing:"))
        assertTrue(block.contains("  speech heard: 3.5 s"))
        assertTrue(block.contains("  silence waited: 402 ms (target was 350 ms)"))
        assertTrue(block.contains("  turn ended by: smart-turn verdict (cue: smart_turn_complete)"))
        assertTrue(block.contains("  smart-turn verdict: COMPLETE, probability 0.83"))
        assertTrue(block.contains("  endpoint deferred: never"))
        // 210 detection + 145 finalization, the benchmark's endpointDelayMs definition.
        assertTrue(block.contains("  speech end → endpoint decided: 355 ms"))
        assertTrue(block.contains("  endpoint → first reply text: 480 ms"))
        assertTrue(block.contains("  speech end → first audio out: 1,250 ms — endpointing was ~28% of it"))
        assertTrue(block.contains("  ASR finalization after endpoint: 145 ms"))
    }

    @Test fun trailingSilenceTurnRendersAdaptiveCueAndContinueVerdict() {
        val block = EndpointingSummary.from(metrics(
            endpointCue = "complete_and_stable", endpointAuthority = "fallback",
            targetSilenceMs = 350, silenceWaitedMs = 388,
            smartTurnState = "CONTINUE", smartTurnProbability = 0.31f),
            endpointToFirstTextMs = 900, speechEndToFirstAudioMs = 2_100,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("turn ended by: trailing silence — adaptive cue (cue: complete_and_stable)"))
        assertTrue(block.contains("smart-turn verdict: CONTINUE, probability 0.31"))
        assertTrue(block.contains("silence waited: 388 ms (target was 350 ms)"))
    }

    @Test fun backstopTurnRendersBattlesPasteShape() {
        // The 2026-11-01 build-1655 turn: smart turn said CONTINUE at 0.02 and the
        // 5 s turn-stop backstop ended the turn after 5,085 ms of waited silence.
        val block = EndpointingSummary.from(metrics(
            endpointCue = "turn_stop_timeout", endpointAuthority = "timeout",
            targetSilenceMs = 5_000, silenceWaitedMs = 5_085,
            smartTurnState = "CONTINUE", smartTurnProbability = 0.0206f,
            endpointDetectionMs = 5_040, finalizationMs = 53,
            endpointDeferrals = listOf("resumed_speech")),
            endpointToFirstTextMs = 3_032, speechEndToFirstAudioMs = 9_183,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("silence waited: 5,085 ms (target was 5,000 ms)"))
        assertTrue(block.contains("turn ended by: turn_stop_timeout — the 5 s dead-man backstop"))
        assertTrue(block.contains("smart-turn verdict: CONTINUE, probability 0.02"))
        assertTrue(block.contains("endpoint deferred: 1 time (you resumed speaking mid-decision)"))
        assertTrue(block.contains("speech end → endpoint decided: 5,093 ms"))
        assertTrue(block.contains("endpoint → first reply text: 3,032 ms"))
        assertTrue(block.contains("speech end → first audio out: 9,183 ms — endpointing was ~55% of it"))
    }

    @Test fun flushTurnRendersExplicitStop() {
        val block = EndpointingSummary.from(metrics(
            endpointReason = "explicit_stop", endpointCue = null,
            smartTurnState = null, smartTurnProbability = null),
            endpointToFirstTextMs = 120, speechEndToFirstAudioMs = 700,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("turn ended by: explicit stop (flush)"))
        assertTrue(block.contains("smart-turn verdict: not evaluated — no smart-turn analyzer on this capture"))
    }

    @Test fun deferralsAreCountedGroupedAndPlainlyWorded() {
        val block = EndpointingSummary.from(metrics(endpointDeferrals = listOf(
            "audio_arrived_during_finalization", "resumed_speech", "resumed_speech",
            "final_caption_unfinished")),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("endpoint deferred: 4 times (audio arrived during finalization; " +
            "you resumed speaking mid-decision ×2; final caption read as unfinished)"))
    }

    @Test fun unevaluatedVerdictLinesSayWhy() {
        val pending = EndpointingSummary.from(metrics(smartTurnState = "PENDING",
            smartTurnProbability = null),
            endpointToFirstTextMs = null, speechEndToFirstAudioMs = null,
            nativeAudioTurn = false).render()
        assertTrue(pending.contains("smart-turn verdict: not evaluated — verdict still pending when the endpoint fired"))
        val fallback = EndpointingSummary.from(metrics(smartTurnState = "FALLBACK",
            smartTurnProbability = null),
            endpointToFirstTextMs = null, speechEndToFirstAudioMs = null,
            nativeAudioTurn = false).render()
        assertTrue(fallback.contains("smart-turn verdict: not evaluated — analyzer returned no verdict for this pause"))
    }

    @Test fun missingMeasurementsRenderNotRecordedNeverZero() {
        val block = EndpointingSummary.from(metrics(
            endpointDetectionMs = null, silenceWaitedMs = null, targetSilenceMs = null,
            acoustic = null),
            endpointToFirstTextMs = null, speechEndToFirstAudioMs = null,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("speech heard: not recorded"))
        assertTrue(block.contains("silence waited: not recorded"))
        assertFalse(block.contains("(target was"))
        assertTrue(block.contains("speech end → endpoint decided: not recorded"))
        assertTrue(block.contains("endpoint → first reply text: not recorded"))
        assertTrue(block.contains("speech end → first audio out: not recorded"))
        assertFalse(block.contains("% of it"))
    }

    @Test fun nativeAudioTurnStatesAsrFinalizationNotApplicable() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = true).render()
        assertTrue(block.contains("ASR finalization after endpoint: not applicable — " +
            "native audio turn; Gemma heard the audio directly"))
    }

    @Test fun ttsSplitRendersTheRecordedFirstTextToPlaybackValue() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false, firstTextToFirstAudioMs = 265).render()
        assertTrue(block.contains("  first reply text → first audio out: 265 ms"))
        // The waterfall reads in order: endpoint → text → audio → total.
        val endpointToText = block.indexOf("endpoint → first reply text:")
        val textToAudio = block.indexOf("first reply text → first audio out:")
        val total = block.indexOf("speech end → first audio out:")
        assertTrue(endpointToText in 0 until textToAudio)
        assertTrue(textToAudio in 0 until total)
    }

    @Test fun ttsSplitWithoutASessionRendersNotApplicable() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = null,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("  first reply text → first audio out: " +
            "not applicable — no completed TTS session recorded this turn"))
    }

    @Test fun interruptedTurnRendersSourcePlayedMsAndHonestStopLatency() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false,
            interrupted = true, bargeSource = "keyword", playedMsBeforeStop = 1_840).render()
        assertTrue(block.contains("  reply interrupted: yes — barge source: keyword · " +
            "stop latency after speech start: not recorded · " +
            "reply had played 1,840 ms when it stopped"))
    }

    @Test fun interruptedTurnWithoutSourceStaysHonest() {
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false, interrupted = true, bargeSource = "natural").render()
        assertTrue(block.contains("  reply interrupted: yes — barge source: natural · " +
            "stop latency after speech start: not recorded"))
        assertFalse(block.contains("when it stopped"))
    }

    @Test fun uninterruptedAndUnknownTurnsRenderTheirInterruptionLines() {
        val completed = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false, interrupted = false).render()
        assertTrue(completed.endsWith("  reply interrupted: no"))
        val unknown = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        assertTrue(unknown.endsWith("  reply interrupted: not recorded"))
    }

    @Test fun deadlineTurnRendersDeadlineMechanismAndContinueVerdictWithProbability() {
        // The diagnostics correction: a CONTINUE verdict is reported as
        // CONTINUE with its probability, never as "no verdict".
        val block = EndpointingSummary.from(metrics(
            endpointCue = "deadline", endpointAuthority = "deadline",
            targetSilenceMs = 300, silenceWaitedMs = 300,
            smartTurnState = "CONTINUE", smartTurnProbability = 0.34f),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("turn ended by: deadline — submitted at the 300 ms decision deadline"))
        assertTrue(block.contains("smart-turn verdict: CONTINUE, probability 0.34"))
        assertFalse(block.contains("no verdict"))
        assertTrue(block.contains("silence waited: 300 ms (target was 300 ms)"))
    }

    @Test fun vetoHoldTurnRendersHoldMechanismAndDeferral() {
        val block = EndpointingSummary.from(metrics(
            endpointCue = "smart_turn_veto_hold", endpointAuthority = "smart_turn",
            targetSilenceMs = 600, silenceWaitedMs = 600,
            smartTurnState = "CONTINUE", smartTurnProbability = 0.01f,
            endpointDeferrals = listOf("smart_turn_veto_hold")),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        assertTrue(block.contains("turn ended by: smart-turn veto hold — a high-confidence unfinished verdict " +
            "extended the deadline once, then it expired (cue: smart_turn_veto_hold)"))
        assertTrue(block.contains("smart-turn verdict: CONTINUE, probability 0.01"))
        assertTrue(block.contains("endpoint deferred: 1 time (smart turn held the turn once " +
            "(high-confidence unfinished verdict))"))
    }

    @Test fun blockAppearsInTextExportForARecordedCall() {
        val prefs = preferences()
        val recorder = DiagnosticRecorder(prefs, "test")
        recorder.startSession("Voice Call call-1")
        val block = EndpointingSummary.from(metrics(),
            endpointToFirstTextMs = 480, speechEndToFirstAudioMs = 1_250,
            nativeAudioTurn = false).render()
        // The same two recorder calls VoiceTurnObservation.finish makes.
        recorder.recordSummary(block)
        recorder.recordTurnEvidence("turn-1", "endpointing", block)
        val export = recorder.snapshot()
        assertTrue(export.contains("Endpointing:"))
        assertTrue(export.contains("turn ended by: smart-turn verdict (cue: smart_turn_complete)"))
        assertTrue(export.contains("speech end → first audio out: 1,250 ms — endpointing was ~28% of it"))
        assertEquals(2, export.split("Endpointing:").size - 1)
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; editor }
                "remove" -> { values.remove(args!![0] as String); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
