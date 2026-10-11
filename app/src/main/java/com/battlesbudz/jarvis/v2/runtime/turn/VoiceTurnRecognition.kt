package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.runtime.FinalVoiceInputResolver
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive

/** Produces final recognition evidence or a deliberate quiet/echo/control exit before dispatch. */
internal class VoiceTurnRecognition(
    private val call: VoiceCallAccess,
    private val conversation: VoiceConversationAccess,
    private val resources: RuntimeVoiceResources,
    private val conversationHistory: ConversationHistory,
    private val memory: VoiceMemoryAccess,
    private val turnOrchestrator: TurnOrchestrator,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val asrComparisonStore: AsrComparisonStore
) {
    suspend fun recognize(request: VoiceTurnRequest, prepared: PreparedVoiceTurn, observation: VoiceTurnObservation, lifetime: VoiceTurnLifetime): VoiceStageResult<FinalizedVoiceTurn> {
        val inputBoundary = lifetime.capturedInputBoundary ?: call.state.inputQueue.captionBoundary(prepared.expectedCallId)
        val inputRevision = lifetime.capturedInputRevision
        if (prepared.correction == null) {
            // The ASR owner retains a begun utterance. A typed draft can hand off the
            // microphone only before speech starts; otherwise it stays FIFO behind that
            // immutable final input, so neither source is discarded or double-run.
            val captureFinished = lifetime.scope.async { prepared.activeCapture.awaitTurnCompletion() }
            val typedAvailable = lifetime.scope.async { call.state.inputQueue.awaitAvailable(prepared.expectedCallId) }
            val typedWon = kotlinx.coroutines.selects.select<Boolean> {
                captureFinished.onAwait { false }
                typedAvailable.onAwait { true }
            }
            if (typedWon && !prepared.activeCapture.hasSpeech) {
                observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
                observation.failure = "typed_input_handoff"
                prepared.activeCapture.stop()
                captureFinished.cancelAndJoin()
                lifetime.finalMessage = "Typed Voice Call input accepted; processing it now."
                return VoiceStageResult.Finished(lifetime.finalMessage)
            }
            typedAvailable.cancelAndJoin()
            if (!captureFinished.isCompleted) captureFinished.await()
        }
        if (prepared.correction == null) {
            observation.benchmark.configuration("final_asr_status", prepared.activeCapture.finalAsrStatus)
            observation.benchmark.configuration("caption_finalization_reason", prepared.activeCapture.captionFinalizationReason)
        }
        observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.RECOGNITION_FINALIZED)
        observation.benchmark.mark("recognition_finalized")
        val endpointAt = System.nanoTime()
        observation.telemetry.finalReadyAt.set(endpointAt)
        val audioBytes = prepared.correction?.wav ?: prepared.activeCapture.stop()
        request.comparison?.wav = audioBytes.copyOf()
        val speculativeCaptureConfirmed = lifetime.nativeSpeculation?.confirmCapture(
            prepared.activeCapture.nativePauseCertificate, audioBytes) == true
        if (lifetime.nativeSpeculation?.consumedEncoder == true) {
            observation.benchmark.configuration("native_speculation_capture", if (speculativeCaptureConfirmed)
                "certified_frozen_candidate" else "full_recording_fallback")
            if (!speculativeCaptureConfirmed) lifetime.nativeSpeculation?.beforeNativeMutation()
        }
        (prepared.correction?.speechEndedAtMs ?: prepared.activeCapture.lastSpeechAtMs)?.let {
            request.comparison?.mark("speech_end", it); observation.telemetry.speechEndedAt.set(it)
            observation.benchmark.markAt("speech_ended", it)
            observation.telemetry.publishLiveMetrics { metrics -> metrics.speechEnded(it) }
        }
        request.comparison?.mark("capture_final")
        if (prepared.correction == null) observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.CAPTURE_CONSUMER_RELEASED)
        kotlin.coroutines.coroutineContext.ensureActive()
        if (call.controller.currentCallId() != prepared.expectedCallId) {
            throw kotlinx.coroutines.CancellationException("voice_call_changed_during_recognition")
        }
        val asrTranscript = prepared.correction?.transcript ?: prepared.activeCapture.finalTranscript
        // One observation of evidence that ALREADY exists after the existing capture join.
        // Missing final evidence stays missing; this check cannot refresh or finalize ASR.
        val rejectedByPlaybackEcho = asrTranscript.isNotBlank() && resources.resources.rejectsFollowupEcho(
            asrTranscript, prepared.correction?.firstSpeechCaptureAtMs ?: prepared.activeCapture.firstSpeechCaptureAtMs)
        val snapshotOwner = com.battlesbudz.jarvis.v2.voice.FinalWhisperFarewellSnapshot.Owner(
            prepared.expectedCallId, request.asrTurnId, inputRevision ?: -1L)
        fun currentSnapshotOwner() = call.controller.currentInputRevision(prepared.expectedCallId)?.let {
            com.battlesbudz.jarvis.v2.voice.FinalWhisperFarewellSnapshot.Owner(prepared.expectedCallId, request.asrTurnId, it)
        }
        val finalWhisperSnapshot = com.battlesbudz.jarvis.v2.voice.FinalWhisperFarewellSnapshot.capture(
            snapshotOwner, currentSnapshotOwner(),
            isWhisper = request.asrEngine == com.battlesbudz.jarvis.v2.voice.AsrEngine.WHISPER &&
                (prepared.correction == null || prepared.correction.handoff != null &&
                    prepared.correction.finalAsrEngineId == request.asrEngine.id),
            transcript = asrTranscript,
            finalAsrStatus = prepared.correction?.finalAsrStatus ?: prepared.activeCapture.finalAsrStatus,
            captionFinalizationReason = prepared.correction?.captionFinalizationReason ?: prepared.activeCapture.captionFinalizationReason,
            recognitionIssue = prepared.correction?.recognitionIssue ?: prepared.activeCapture.recognitionIssue,
            rejectedByPlaybackEcho = rejectedByPlaybackEcho)
        if (prepared.directAudioTurn && request.comparison == null) {
            val ended = call.state.inputQueue.publishWithoutNextInput(inputBoundary) {
                synchronized(call.controller) {
                    val goodbye = finalWhisperSnapshot.farewellIfCurrent(currentSnapshotOwner())
                    goodbye != null && call.controller.finishInputIfCurrent(prepared.expectedCallId,
                        snapshotOwner.inputRevision, goodbye)
                }.also { if (it) call.state.inputQueue.end(prepared.expectedCallId) }
            }
            if (ended) {
                observation.benchmark.configuration("request_scope", "already_final_whisper_goodbye")
                call.events.returnToWake(prepared.expectedCallId)
                lifetime.finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " goodbye."
                return VoiceStageResult.Finished(lifetime.finalMessage)
            }
        }
        if (prepared.correction == null) observation.telemetry.hypothesis = asrTranscript
        val audioIsComplete = prepared.correction?.audioIsComplete ?: prepared.activeCapture.audioIsComplete
        var recognitionIssue = prepared.correction?.recognitionIssue ?: prepared.activeCapture.recognitionIssue
        if (prepared.directAudioTurn) {
            // Caption errors are not audio understanding failures. Only the retained
            // recording's completeness/capacity may block this native audio request.
            observation.benchmark.configuration("caption_recognition_issue", recognitionIssue ?: "none")
            recognitionIssue = com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.retainedAudioIssue(
                recognitionIssue, prepared.engine.audioEnabled, audioIsComplete, audioBytes.size)
        }
        val finalizedCaptionPreview = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.liveTranscript.value
        if (call.state.capture === prepared.activeCapture) call.state.capture = null
        if (prepared.directAudioTurn) call.events.post {
            // Retire this capture's provisional preview at its endpoint. A later
            // interruption may already own a new preview; never clear that text.
            if (call.controller.currentCallId() == prepared.expectedCallId) {
                com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.liveTranscript.compareAndSet(finalizedCaptionPreview, "")
            }
        }
        call.status("Processing your Voice Call turn locally…")
        if (prepared.correction == null && !prepared.activeCapture.hasSpeech) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.NO_SPEECH
            diagnosticRecorder.record("Voice call inactivity: capture window completed; armed session retained")
            lifetime.finalMessage = com.battlesbudz.jarvis.v2.voice.CallLifetimePolicy.waitingStatus()
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        if (prepared.correction == null && rejectedByPlaybackEcho) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
            observation.failure = "own_playback_echo_followup"
            observation.benchmark.configuration("echo_rejection_scope", "playback_tail_onset_and_all_clauses_match")
            prepared.incremental.close()
            call.events.post { com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.liveTranscript.value = "" }
            diagnosticRecorder.recordImportant("Voice input rejected: own playback echo in handoff tail; microphone sensitivity unchanged.")
            lifetime.finalMessage = "Voice Call is listening — speak now."
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        // Final ASR text belongs on screen immediately, before pending input processing is joined.
        if (!prepared.directAudioTurn && asrTranscript.isNotBlank()) call.events.post {
            if (call.controller.currentCallId() == prepared.expectedCallId) call.events.transcript("You", asrTranscript, true)
        }
        diagnosticRecorder.recordSummary("Voice recognition turn=${request.asrTurnId} path=${if (prepared.correction == null) "normal" else "after_keyword"} " +
            "engine=${request.asrEngine.id} asrChars=${asrTranscript.length} gemmaTranscriptionFallback=${asrTranscript.isBlank()} " +
            "speechGate=engine_default")
        val sealStarted = System.nanoTime()
        prepared.incremental.seal()
        val preparedText = prepared.incremental.takeIf { !prepared.directAudioTurn && asrTranscript.isNotBlank() && recognitionIssue == null }
        if (preparedText == null) prepared.incremental.close()
        observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.PREPARATION_SEALED)
        observation.benchmark.mark("preparation_sealed")
        diagnosticRecorder.recordSummary("Voice pipeline turn=${request.asrTurnId} stage=preparation_sealed " +
            "workMs=${(System.nanoTime() - sealStarted) / 1_000_000} " +
            "sinceEndpointMs=${(System.nanoTime() - endpointAt) / 1_000_000}")
        val resolvedInput = FinalVoiceInputResolver(
            engine = prepared.engine, resetConversation = { conversation.reset() },
            benchmark = observation.benchmark, turnTrace = observation.turnTrace, comparison = request.comparison,
            recordDiagnostic = diagnosticRecorder::recordImportant, reportStatus = call::status
        ).resolve(asrTranscript, audioBytes, prepared.directAudioTurn, recognitionIssue, request.asrEngine.label)
        val resolvedTranscript = resolvedInput.text
        recognitionIssue = resolvedInput.recognitionIssue
        observation.failure = recognitionIssue
        if (recognitionIssue != null) observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
        request.comparison?.put("recognition_issue", recognitionIssue ?: "none")
        request.comparison?.put("resolved_transcript", resolvedTranscript)
        if (com.battlesbudz.jarvis.v2.voice.TranscriptContent.isSoundOnly(resolvedTranscript)) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.NO_SPEECH
            prepared.incremental.close()
            diagnosticRecorder.recordImportant("Voice input: nonverbal_candidate ignored=true source=audio_fallback destination=none")
            lifetime.finalMessage = "Voice Call is listening — speak now."
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        val transcript = com.battlesbudz.jarvis.v2.voice.TranscriptContent.speech(resolvedTranscript)
        if (recognitionIssue == null && request.comparison?.request?.path != com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT &&
            com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.hasTranscript(transcript)) request.comparison?.mark("transcript_final")
        request.comparison?.put("resolved_transcript", transcript)
        request.comparison?.put("raw_asr", asrTranscript)
        request.comparison?.put("recognition_issue", recognitionIssue ?: "none")
        diagnosticRecorder.recordTurnEvidence(request.asrTurnId, "recognition_text",
            "engine=${request.asrEngine.id} transcriptionFallback=${asrTranscript.isBlank()} audioComplete=$audioIsComplete\n" +
                "asr=$asrTranscript\nresolved=$transcript")
        if (asrTranscript.isBlank()) {
            diagnosticRecorder.recordImportant("Voice audio fallback finished: chars=${transcript.length} source=gemma")
        }
        if (!prepared.directAudioTurn && recognitionIssue == null && transcript.isNotBlank()) {
            memory.capture(prepared.correction?.utteranceId ?: request.asrTurnId, conversationHistory.current.value.id,
                prepared.expectedCallId, if (prepared.correction?.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                    ConversationMemorySource.TEXT else ConversationMemorySource.VOICE,
                transcript, prepared.correction?.capturedAtMs ?: System.currentTimeMillis())
        }
        if (recognitionIssue == null && com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(transcript)) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.COMPLETE
            observation.benchmark.configuration("request_scope", "voice_control_goodbye")
            prepared.incremental.close()
            call.controller.appendTranscript("You", transcript)
            call.events.returnToWake(prepared.expectedCallId)
            if (!prepared.directAudioTurn && transcript != asrTranscript) call.events.post { call.events.transcript("You", transcript, true) }
            diagnosticRecorder.record("Voice call ended reason=spoken_goodbye")
            lifetime.finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " goodbye."
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        if (recognitionIssue == null && com.battlesbudz.jarvis.v2.voice.VoiceStopRequest.matches(transcript)) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.COMPLETE
            observation.benchmark.configuration("request_scope", "voice_control_stop")
            prepared.incremental.close()
            call.controller.appendTranscript("You", transcript)
            conversation.reset()
            diagnosticRecorder.recordImportant("Voice control: stop_reply source=final_transcript call_remains_active=true")
            lifetime.finalMessage = "Voice Call is listening — speak now."
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        asrComparisonStore.update(request.asrTurnId, "prepared", false)
        diagnosticRecorder.record("Voice ASR final\ntext=$transcript\naudioBytes=${audioBytes.size}\n" +
            "prepared=false inputMode=${if (prepared.directAudioTurn) "gemma_audio" else "incremental_text"} audioForAnswer=${prepared.directAudioTurn}")
        if (!prepared.directAudioTurn && transcript != asrTranscript) call.events.post {
            if (call.controller.currentCallId() == prepared.expectedCallId) call.events.transcript("You", transcript, true)
        }
        diagnosticRecorder.recordSummary("Voice pipeline turn=${request.asrTurnId} stage=reply_dispatch " +
            "sinceEndpointMs=${(System.nanoTime() - endpointAt) / 1_000_000}")
        // Action mode is entered only after final ASR and the shared strict plan. It keeps
        // native work on acceptedVoiceActions while this turn owns an ASR-only follow-up pump.
        val initialActionPlan = if (!prepared.directAudioTurn && recognitionIssue == null)
            turnOrchestrator.plan(transcript, prepared.voiceHistory.map { it.role to it.text }).actionPlan
            else ActionTurnPlan.NotAction
        val sealedNativeAudio = if (prepared.directAudioTurn && recognitionIssue == null &&
            lifetime.nativeSpeculation?.consumedEncoder != true) {
            lifetime.nativeAudioCapture?.sealAfterCaptureJoined(audioBytes,
                prepared.activeCapture.endpointDecisionAtNs, prepared.activeCapture.retainedPreRollSampleCount)
        } else null
        kotlin.coroutines.coroutineContext.ensureActive()
        return VoiceStageResult.Ready(FinalizedVoiceTurn(transcript, asrTranscript, audioBytes, audioIsComplete,
            recognitionIssue, preparedText, endpointAt, initialActionPlan, sealedNativeAudio?.content,
            sealedNativeAudio?.timing,
            lifetime.nativeSpeculation?.takeIf { it.consumedEncoder }))
    }
}
