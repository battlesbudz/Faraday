package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.conversation.ConversationCallbacks
import com.battlesbudz.jarvis.v2.conversation.ConversationInvocation
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.diagnostics.ReplyCaptureBenchmark
import com.battlesbudz.jarvis.v2.memory.AutoMemorySource
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
import com.battlesbudz.jarvis.v2.voice.VoiceTurnCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin

/** Publishes one ordinary answer through its immutable memory binding and joins speech before completion. */
internal class OrdinaryVoiceReplyStage(
    private val call: VoiceCallAccess,
    private val applicationScope: CoroutineScope,
    private val conversation: VoiceConversationAccess,
    private val replyCapture: ReplyCaptureBenchmark,
    private val resources: RuntimeVoiceResources,
    private val conversationHistory: ConversationHistory,
    private val memory: VoiceMemoryAccess,
    private val turnOrchestrator: TurnOrchestrator,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val asrComparisonStore: AsrComparisonStore
) {
    suspend fun reply(request: VoiceTurnRequest, prepared: PreparedVoiceTurn, finalized: FinalizedVoiceTurn, observation: VoiceTurnObservation, lifetime: VoiceTurnLifetime): VoiceStageResult<Nothing> {
        val firstFinalToken = java.util.concurrent.atomic.AtomicBoolean(true)
        val captionConversationId = conversationHistory.current.value.id
        val captionInputBoundary = call.state.inputQueue.captionBoundary(prepared.expectedCallId)
        val acceptedNextSpeech = CompletableDeferred<Unit>()
        val captionPublication = com.battlesbudz.jarvis.v2.voice.CaptionPublicationFence()
        val followupPlaybackBoundary = java.util.concurrent.atomic.AtomicLong()
        val followupCapturedAt = java.util.concurrent.atomic.AtomicLong()
        val followupPlaybackReference = java.util.concurrent.atomic.AtomicReference("")
        val exactTurnJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        fun ownsCaption(): Boolean = exactTurnJob?.isActive == true && call.state.turnJob === exactTurnJob &&
            call.state.armed && call.controller.currentCallId() == prepared.expectedCallId &&
            conversationHistory.current.value.id == captionConversationId &&
            call.state.pendingVoiceCorrection.get() == null && call.state.pendingTypedHandoff.get() == null &&
            !acceptedNextSpeech.isCompleted
        fun hasNextInput() = acceptedNextSpeech.isCompleted || call.state.inputQueue.hasInputAfter(captionInputBoundary)
        suspend fun awaitTypedInput() {
            call.state.inputQueue.awaitInputAfter(captionInputBoundary)
            captionPublication.revoke { }
        }
        suspend fun awaitNextInput() = kotlinx.coroutines.coroutineScope {
            val typed = async { awaitTypedInput() }
            try { kotlinx.coroutines.selects.select<Unit> {
                typed.onAwait { }
                acceptedNextSpeech.onAwait { }
            } } finally { typed.cancelAndJoin() }
        }
        val captureFirst = prepared.directAudioTurn && request.comparison == null && finalized.recognitionIssue == null
        val rawInput = if (captureFirst) com.battlesbudz.jarvis.v2.voice.CaptureFirstAudioInput(
            resources.resources.borrowMicrophone("command", communication = true), lifetime.scope,
            observe = { observation.benchmark.configuration("capture_first_raw_transfer", it) },
            onRawFrame = captionPublication::rawOffered,
            onTransfer = captionPublication::transferToCapture) else null
        val outcome = try {
            rawInput?.start()
            com.battlesbudz.jarvis.v2.voice.runCaptureFirstReply(
            reply = {
                observation.telemetry.activateLiveMetrics()
                observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.REPLY_DISPATCHED)
                observation.benchmark.mark("reply_dispatched")
                val coordinator = VoiceTurnCoordinator(call.controller)
                // Queued Main callbacks retain immutable ticket authority after answer
                // resources release. A bound answer must fail closed, never become unguarded.
                val publicationGuard = com.battlesbudz.jarvis.v2.memory.MemoryPublicationGuard(memory.fence)
                fun publishBound(block: () -> Unit): Boolean = publicationGuard.publish(block)
                val response = coordinator.processTurn(if (prepared.directAudioTurn) com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.PENDING_TRANSCRIPT else finalized.transcript, replyId = request.asrTurnId, publish = ::publishBound) { onToken ->
                    finalized.nativeSpeculation?.bindConfirmedTiming { timing ->
                        observation.telemetry.acceptNativeAudioTiming(timing)
                        timing.metrics()?.let { measured ->
                            call.controller.updateReplyMetrics(prepared.expectedCallId, request.asrTurnId) {
                                it.copy(nativeAudioTiming = measured)
                            }
                        }
                    }
                    finalized.nativeAudioTiming?.let { timing ->
                        observation.telemetry.acceptNativeAudioTiming(timing)
                        timing.metrics()?.let { measured ->
                            call.controller.updateReplyMetrics(prepared.expectedCallId, request.asrTurnId) {
                                it.copy(nativeAudioTiming = measured)
                            }
                        }
                    }
                    observation.telemetry.speechEndedAt.get().takeIf { it != 0L }?.let { ended ->
                        call.controller.updateReplyMetrics(prepared.expectedCallId, request.asrTurnId) { it.speechEnded(ended) }
                    }
                    val completed = CompletableDeferred<String>()
                    val streamed = StringBuilder()
                    fun recordFirstText(text: String) {
                        if (text.isNotBlank() && firstFinalToken.compareAndSet(true, false)) {
                            observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.FIRST_REPLY_TEXT)
                            observation.benchmark.mark("first_reply_text")
                            val elapsedMs = (System.nanoTime() - finalized.endpointAt) / 1_000_000
                            asrComparisonStore.update(request.asrTurnId, "final_to_first_text_ms", elapsedMs)
                            if (observation.telemetry.speechEndedAt.get() != 0L) {
                                asrComparisonStore.update(request.asrTurnId, "speech_end_to_first_text_ms",
                                    System.nanoTime() / 1_000_000 - observation.telemetry.speechEndedAt.get())
                            }
                            diagnosticRecorder.recordSummary("Voice latency: endpoint_to_first_text_ms=$elapsedMs turn=${request.asrTurnId}")
                            observation.telemetry.recordEndpointToFirstText(elapsedMs)
                        }
                    }
                    val interruptionTest = com.battlesbudz.jarvis.v2.voice.VoiceInterruptionTest.requested(finalized.transcript)
                    if (finalized.recognitionIssue != null) {
                        prepared.incremental.close()
                        val clarification = if (finalized.recognitionIssue == "audio_fallback_timeout" || finalized.recognitionIssue == "audio_fallback_empty" || finalized.recognitionIssue == "selected_model_has_no_audio_fallback")
                            "I couldn't make out that request, sir. Please say it again."
                        else "I couldn't retain that whole request reliably. Please repeat it in shorter parts, sir."
                        diagnosticRecorder.recordImportant("Voice input rejected reason=${finalized.recognitionIssue} action=clarify tools=disabled")
                        recordFirstText(clarification)
                        onToken(clarification)
                        lifetime.speechChunks.trySend(clarification)
                        completed.complete(clarification)
                    } else if (interruptionTest) {
                        prepared.incremental.close()
                        val passage = com.battlesbudz.jarvis.v2.voice.VoiceInterruptionTest.passage
                        diagnosticRecorder.recordImportant("Voice interruption test: started source=local_passage normal_call_pipeline=true")
                        recordFirstText(passage)
                        onToken(passage)
                        lifetime.speechChunks.trySend(passage)
                        completed.complete(passage)
                    } else {
                        conversation.start(ConversationInvocation(
                            prompt = finalized.transcript,
                            history = prepared.voiceHistory,
                            imageUri = null,
                            incrementalVoice = finalized.preparedText,
                            voiceAudio = finalized.audioBytes,
                            voiceAudioIsComplete = finalized.audioIsComplete,
                            directVoiceAudio = prepared.directAudioTurn,
                            comparison = request.comparison,
                            benchmarkCapture = observation.benchmark,
                            sealedVoiceAudio = finalized.sealedVoiceAudio,
                            nativeSpeculation = finalized.nativeSpeculation
                        ), ConversationCallbacks(
                            onLiveInference = { submittedAt, firstTokenAt, tokensPerSecond, durable ->
                                call.controller.updateReplyMetrics(prepared.expectedCallId, request.asrTurnId, durable = durable) { current ->
                                    var updated = current
                                    submittedAt?.let { updated = updated.submitted(it) }
                                    firstTokenAt?.let { updated = updated.firstRawToken(it) }
                                    if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                                    updated
                                }
                                observation.telemetry.publishLiveMetrics { metrics ->
                                    var updated = metrics
                                    submittedAt?.let { updated = updated.submitted(it) }
                                    firstTokenAt?.let { updated = updated.firstText(it) }
                                    if (tokensPerSecond != null && tokensPerSecond.isFinite()) updated = updated.copy(estimatedTokensPerSecond = tokensPerSecond)
                                    updated
                                }
                            },
                            onLatency = { observation.replyLatency.set(it) },
                            onMemoryBound = { ticket, context ->
                                val binding = VoiceMemoryBinding(ticket, context, prepared.output, lifetime.speechJob, prepared.expectedCallId, request.asrTurnId, lifetime.answerExpiryJob)
                                lifetime.answerMemoryBinding.set(binding)
                                publicationGuard.bind(ticket) {
                                    context.isCurrent() && call.controller.currentCallId() == prepared.expectedCallId && call.state.armed
                                }
                                memory.delivery.binding.set(binding)
                                context.expiresAtMs?.let { expiry ->
                                    val expiryJob = applicationScope.launch {
                                        kotlinx.coroutines.delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L))
                                        // Compare with the immutable binding captured for this answer. A normal
                                        // cleanup may have cleared the turn-local reference; it must never turn
                                        // a null/null CAS into revoking a later answer's output.
                                        if (!memory.fence.isValid(ticket) && memory.delivery.binding.compareAndSet(binding, null)) {
                                            binding.speechJob?.cancel()
                                            binding.output.stopSpeaking()
                                        }
                                    }
                                    lifetime.answerExpiryJob.set(expiryJob)
                                }
                            },
                            onActionResult = { name, message, succeeded ->
                                call.controller.recordReplyAction(prepared.expectedCallId, request.asrTurnId,
                                    com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome(name, message, succeeded))
                            },
                            onToken = { token ->
                                publishBound {
                                    recordFirstText(token)
                                    onToken(token)
                                    streamed.append(token)
                                    call.events.post { publishBound { call.events.transcript("Jarvis", token, false) } }
                                    lifetime.speechChunks.trySend(AssistantText.forSpeech(token))
                                }
                            },
                            onComplete = { text ->
                                // Guarded/tool replies may arrive only through completion, with no token callback.
                                // Keep the delivery flag armed through TTS so a later approved mutation can
                                // stop queued audio as well as generation tokens.
                                publishBound {
                                    recordFirstText(text)
                                    if (streamed.isBlank() && text.isNotBlank()) lifetime.speechChunks.trySend(AssistantText.forSpeech(text))
                                }
                                completed.complete(text)
                                // Automatic memory (D-3): a finalized ordinary voice answer queues a
                                // candidate exactly like a chat turn, so the idle evaluator can commit
                                // it above thresholds. Comparison trials never capture (chat parity);
                                // direct-audio turns queue from the final-caption site below instead,
                                // where the real transcript exists. Recognition-issue and
                                // interruption-test turns never start a conversation, so they never
                                // reach this callback.
                                if (request.comparison == null && !prepared.directAudioTurn && finalized.transcript.isNotBlank()) {
                                    memory.autoCaptureTurn(
                                        prepared.correction?.utteranceId ?: request.asrTurnId,
                                        if (prepared.correction?.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                                            AutoMemorySource.CHAT else AutoMemorySource.VOICE,
                                        finalized.transcript, text,
                                        prepared.correction?.capturedAtMs ?: System.currentTimeMillis())
                                }
                            }
                        ))
                    }
                    val text = completed.await()
                    if (!interruptionTest && finalized.recognitionIssue == null) conversation.job?.join()
                    // Job.join does not rethrow a failed child. In particular an idle
                    // Conversation cannot certify a separately quarantined encoder.
                    // Keep this fence outside the caption try/finally: neither its
                    // initial nor final reset may run unless the exact owner drained.
                    finalized.nativeSpeculation?.beforeNativeMutation()
                    // Generation/delivery persistence cannot be skipped by optional-caption cancellation.
                    publishBound {
                        call.controller.updateReplyText(prepared.expectedCallId, request.asrTurnId, text,
                            finished = true, latency = observation.replyLatency.get())
                    }
                    val captionEngineClosed = prepared.engine.isNativeClosed
                    if (prepared.directAudioTurn && request.comparison == null && finalized.recognitionIssue == null && captionEngineClosed) {
                        // The answer generation already failed and the
                        // conversation owner closed the native engine (its
                        // failure path always does). The caption is optional:
                        // attempting its native reset against a closing or
                        // closed engine fails the reset, marks the caption
                        // release failed and poisons model ownership for the
                        // rest of the call. Skip cleanly instead. This guard
                        // covers only the already-failed/closing case: on a
                        // healthy, open engine the continuation below still
                        // runs, and a genuine native drain failure there
                        // still escapes and quarantines exactly as before.
                        observation.benchmark.configuration("gemma_final_caption_result", "skipped_engine_closed")
                        diagnosticRecorder.recordImportant("Gemma final caption skipped; answer engine already closed")
                    }
                    if (prepared.directAudioTurn && request.comparison == null && finalized.recognitionIssue == null && !captionEngineClosed) {
                        observation.benchmark.mark("answer_generation_finished")
                        lifetime.speechChunks.close() // Caption inference must not hold answer EOF/audio drain.
                        observation.benchmark.configuration("gemma_final_caption_scope", "structured_backstop_capture_first")
                        val ticket = call.controller.captionTicket(prepared.expectedCallId, request.asrTurnId,
                            com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.PENDING_TRANSCRIPT)
                        var finalCaptionPublished = false
                        // Caption/session reconciliation state: written by the
                        // generate lambda, read by checkedReset, which always
                        // runs after a started caption.
                        var captionReusedConversation = false
                        var captionCompletedCleanly = false
                        var captionEmittedToolCalls = false
                        try {
                            val result = com.battlesbudz.jarvis.v2.voice.PostAnswerCaptionContinuation.run(
                                isCurrent = { ticket != null && ownsCaption() },
                                hasNextInput = ::hasNextInput, awaitNextInput = { awaitNextInput() },
                                generate = {
                                    observation.benchmark.mark("gemma_final_caption_started")
                                    prepared.engine.onInferenceProgress = {}
                                    prepared.engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK
                                    // Persistent call conversation: when the
                                    // resident conversation cleanly holds this
                                    // call's turns, the caption transcribes on
                                    // it directly — no reset, no tool-policy
                                    // flip — and the transcribe exchange joins
                                    // the call's native history. Any doubt
                                    // about the resident state takes the
                                    // original reset path unchanged.
                                    captionReusedConversation = !prepared.engine.isNativeClosed &&
                                        prepared.engine.nativeResourcesSafeToRelease &&
                                        !prepared.engine.isNativeQuarantined &&
                                        !prepared.engine.requiresConfirmedHistoryRebuild &&
                                        prepared.engine.hasNativeConversationHistory
                                    observation.benchmark.configuration("gemma_final_caption_session",
                                        if (captionReusedConversation) "resident_call_conversation" else "fresh_conversation")
                                    if (!captionReusedConversation) {
                                        conversation.reset()
                                        prepared.engine.setToolsEnabled(false)
                                    }
                                    val heard = prepared.engine.generateAudio(com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.instructions, finalized.audioBytes, {})
                                    captionEmittedToolCalls = heard.toolCalls.isNotEmpty()
                                    captionCompletedCleanly = true
                                    heard
                                },
                                checkedReset = {
                                    check(prepared.engine.nativeResourcesSafeToRelease && !prepared.engine.isNativeQuarantined) {
                                        "Caption native drain failed; model ownership retained, reuse disabled"
                                    }
                                    // Keep the resident conversation only when
                                    // the caption reused it AND completed
                                    // cleanly without emitting tool calls: an
                                    // unanswered tool call, or a cancelled /
                                    // failed generation (whose conversation
                                    // the lifecycle already disposed), would
                                    // leave the next send malformed, so
                                    // re-establish from confirmed history.
                                    if (!captionReusedConversation || !captionCompletedCleanly ||
                                        captionEmittedToolCalls || prepared.engine.requiresConfirmedHistoryRebuild)
                                        conversation.reset()
                                },
                                observe = { event -> observation.benchmark.mark(event) }
                            )
                            observation.benchmark.configuration("gemma_final_caption_result", result.end.name.lowercase())
                            val heard = result.value
                            val finalCaption = heard?.let { com.battlesbudz.jarvis.v2.voice.TranscriptContent.speech(it.text).trim() }.orEmpty()
                            // Transcript-disagreement hard block (Battles, 2026-11-09):
                            // when Whisper and this Gemma caption share no words and
                            // differ in script, neither is the user's words (build
                            // 1771, call 83164c01: "and it does not matter." against
                            // "प्रस्थान मेरा है" — both engines invented words on the
                            // same audio, and the caption was still committed into
                            // dialogue history and request evidence). Commit neither:
                            // no caption commit, no turn evidence, no memory capture.
                            // Leaving finalCaptionPublished false routes the turn to
                            // the same dropped outcome as a failed caption — the
                            // finally below commits UNTRANSCRIBED in place of either
                            // text, so nothing downstream can plan from them.
                            val disagreementBlocked = com.battlesbudz.jarvis.v2.voice.TranscriptDisagreement.hardBlock(finalized.asrTranscript, finalCaption)
                            if (disagreementBlocked) {
                                observation.benchmark.configuration("gemma_final_caption_result", "disagreement_hard_block")
                                diagnosticRecorder.recordImportant("Voice transcript disagreement: result=hard_block action=commit_neither evidence=none memory_capture=false " +
                                    "whisperChars=${finalized.asrTranscript.length} gemmaChars=${finalCaption.length}")
                            }
                            if (heard != null && !disagreementBlocked && heard.toolCalls.isEmpty() && com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.hasTranscript(finalCaption) &&
                                !com.battlesbudz.jarvis.v2.voice.TranscriptContent.isSoundOnly(finalCaption)) {
                                val farewell = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(finalCaption)
                                var committed: com.battlesbudz.jarvis.v2.voice.VoiceSessionController.CaptionCommit? = null
                                val published = ticket != null && captionPublication.publishWhenResolved(::ownsCaption) { claim ->
                                    call.state.inputQueue.publishWithoutNextInput(captionInputBoundary) {
                                        committed = call.controller.commitCaption(ticket, finalCaption, ::ownsCaption, farewell,
                                            authorize = { captionPublication.commit(claim, farewell) })
                                        (committed != null).also { if (it && farewell) call.state.inputQueue.end(prepared.expectedCallId) }
                                    }
                                }
                                try {
                                    committed?.let { publication ->
                                        finalCaptionPublished = true
                                        // Immutable accepted source identity: these effects do not hold the raw
                                        // ingress fence, queue or call lock, and never overwrite a saved old snapshot.
                                        call.controller.checkpointCaption(publication)
                                        diagnosticRecorder.recordTurnEvidence(request.asrTurnId, "gemma_final_caption", "whisper=${finalized.asrTranscript}\ngemma=$finalCaption")
                                        runCatching { memory.capture(prepared.correction?.utteranceId ?: request.asrTurnId, captionConversationId,
                                            prepared.expectedCallId, ConversationMemorySource.VOICE, finalCaption,
                                            prepared.correction?.capturedAtMs ?: System.currentTimeMillis()) }
                                            .onFailure { diagnosticRecorder.recordImportant("Final caption memory capture failed; not retried reason=${it.javaClass.simpleName}") }
                                        // Automatic memory (D-3): the committed final caption is this
                                        // direct-audio turn's real transcript, so the turn's candidate
                                        // queues from here with the answer actually given, mirroring
                                        // the receipt capture above.
                                        runCatching { memory.autoCaptureTurn(prepared.correction?.utteranceId ?: request.asrTurnId,
                                            AutoMemorySource.VOICE, finalCaption, text,
                                            prepared.correction?.capturedAtMs ?: System.currentTimeMillis()) }
                                            .onFailure { diagnosticRecorder.recordImportant("Final caption auto-memory capture failed; not retried reason=${it.javaClass.simpleName}") }
                                    }
                                } finally {
                                    // A storage failure cannot leave an atomically ended input gate armed.
                                    // Call-scoped cleanup runs outside publication/queue/controller locks.
                                    if (published && farewell) call.events.returnToWake(prepared.expectedCallId)
                                }
                            } else if (heard != null && !disagreementBlocked) observation.benchmark.configuration("gemma_final_caption_result", "empty_or_invalid")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Throwable) {
                            if (error is com.battlesbudz.jarvis.v2.voice.PostAnswerCaptionContinuation.NativeReleaseFailure ||
                                !prepared.engine.nativeResourcesSafeToRelease || prepared.engine.isNativeQuarantined) {
                                lifetime.captionNativeReleaseFailed = true
                                throw error
                            }
                            observation.benchmark.configuration("gemma_final_caption_result", error.javaClass.simpleName)
                            diagnosticRecorder.recordImportant("Gemma final caption failed; answer preserved reason=${error.javaClass.simpleName}")
                        } finally {
                            prepared.engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.ANSWER
                            observation.benchmark.mark("gemma_final_caption_finished")
                            if (ticket != null && !lifetime.captionNativeReleaseFailed && prepared.engine.nativeResourcesSafeToRelease && !prepared.engine.isNativeQuarantined && !finalCaptionPublished) runCatching {
                                var committed: com.battlesbudz.jarvis.v2.voice.VoiceSessionController.CaptionCommit? = null
                                captionPublication.publishWhenResolved(::ownsCaption) { claim ->
                                    call.state.inputQueue.publishWithoutNextInput(captionInputBoundary) {
                                        committed = call.controller.commitCaption(ticket, com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.UNTRANSCRIBED,
                                            ::ownsCaption, authorize = { captionPublication.commit(claim) })
                                        committed != null
                                    }
                                }
                                committed?.let(call.controller::checkpointCaption)
                            }.onFailure { diagnosticRecorder.recordImportant("Gemma caption status could not be saved reason=${it.javaClass.simpleName}") }
                        }
                    }
                    call.events.post { publishBound { call.events.transcript("Jarvis", text, true) } }
                    com.battlesbudz.jarvis.v2.ai.GenerationResult(text, -1L, null)
                }
                // The same binding guards the persisted/coordinator completion, not only
                // the streaming callback. A mutation must not let buffered old text replace
                // the safe terminal outcome after its delivery ticket was detached.
                if (!publishBound {
                        call.controller.updateReplyText(prepared.expectedCallId, request.asrTurnId, response.text,
                            finished = true, latency = observation.replyLatency.get())
                    }) {
                    // The old bound reply is revoked; replace any partial with an explicit
                    // safe terminal outcome on the saved call/reply identity.
                    call.controller.updateReplyText(prepared.expectedCallId, request.asrTurnId,
                        "Memory changed while I was responding. Please ask again.", finished = true)
                }
                lifetime.speechChunks.close()
                lifetime.speechJob?.join()
                val releasedBinding = lifetime.answerMemoryBinding.getAndSet(null)
                if (releasedBinding != null) memory.delivery.binding.compareAndSet(releasedBinding, null)
                lifetime.answerExpiryJob.getAndSet(null)?.cancel()
                observation.replyLatency.get()?.let { latency ->
                    val metrics = observation.telemetry.replyTtsMetrics.get()
                    call.controller.updateReplyLatency(latency.copy(voice = request.ttsEngine.label,
                        speechEndToReplyMs = observation.telemetry.speechEndToReplyMs.get().takeIf { it >= 0 },
                        textToPcmMs = metrics?.firstTextToPcmMs,
                        textToPlaybackMs = metrics?.firstTextToPlaybackMs,
                        supplyGapMs = metrics?.supplyGapMs))
                }
                response
            },
            listen = { confirmed ->
                // The reply capture's speech becomes this call's next user turn;
                // its stop is judged by Smart Turn from the same call owner as
                // the conversation captures (compare the follow-up capture's
                // telemetry below, prefix followup_).
                val replyShadowTelemetry = com.battlesbudz.jarvis.v2.diagnostics.SmartTurnBenchmarkTelemetry(
                    observation.benchmark, prefix = "reply_") { category, evidence ->
                    diagnosticRecorder.recordTurnEvidence(request.asrTurnId, category, evidence)
                }
                replyCapture.listenBenchmarkedReply(prepared.output, prepared.asrDirectory, confirmed, asrEngine = request.asrEngine, trace = observation.turnTrace,
                    recognitionEnabled = prepared.replyAsrEnabled,
                    probeEnabled = prepared.replyProbeEnabled,
                    shadowOwner = resources.smartTurn,
                    shadowTelemetry = replyShadowTelemetry,
                    expectedCallId = prepared.expectedCallId,
                    shadowOwnerIsCurrent = { exactTurnJob?.isActive == true && call.state.turnJob === exactTurnJob &&
                        call.state.armed && call.controller.currentCallId() == prepared.expectedCallId },
                    smartTurnEndpointEnabled = request.comparison == null,
                    smartTurnAdmissionBlocker = {
                        when {
                            lifetime.nativeSpeculation?.consumedEncoder == true -> "gemma_speculation"
                            else -> null
                        }
                    },
                    onCandidateRetained = { sequence, bytes ->
                        rawInput?.retainCandidate(sequence, bytes)
                        if (rawInput != null) captionPublication.replyCandidate(true)
                    },
                    onCandidateCleared = {
                        rawInput?.clearCandidate()
                        captionPublication.replyCandidate(false)
                    },
                    inputFactory = { rawInput?.replyInput ?: resources.resources.borrowMicrophone("reply", communication = true) }, modelSession = prepared.models,
                    onPartialTranscript = { text ->
                        call.events.post {
                            if (call.state.output === prepared.output && call.state.armed) call.events.transcript("You", text, false)
                        }
                    }, log = {
                    request.comparison?.log("barge $it")
                    diagnosticRecorder.recordImportant("Voice interruption: $it")
                    if (it.startsWith("barge_keyword_summary")) observation.telemetry.recordBargeSource("keyword")
                    else if (it.startsWith("barge_natural_summary")) observation.telemetry.recordBargeSource("natural")
                    if (it.startsWith("barge_natural_summary") || it.startsWith("barge_keyword_summary") || it.startsWith("barge_evidence_"))
                        diagnosticRecorder.recordTurnEvidence(request.asrTurnId, it.substringBefore(" "), it)
                })
            },
            stopReply = {
                prepared.output.stopSpeaking()
                observation.turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.PLAYBACK_STOP_REQUESTED)
                lifetime.speechJob?.cancel()
                conversation.job?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(
                    com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY))
                diagnosticRecorder.recordImportant("Voice reply interrupted by speech; call retained, action not replayed.")
                call.status("Voice Call is listening — speak now.")
            },
            handoff = rawInput?.let { raw -> com.battlesbudz.jarvis.v2.voice.CaptureFirstReplyHandoff.Config(
                normalPlayback = lifetime.normalReplyPlayback,
                beginCapture = { delivery ->
                    val boundary = resources.resources.consumeFollowupBoundary() ?: delivery.completedAtMs
                    followupPlaybackBoundary.set(boundary)
                    followupCapturedAt.set(System.currentTimeMillis())
                    followupPlaybackReference.set(prepared.output.recentSpokenText())
                    raw.beginFollowup(boundary, retainOverlap = prepared.replyAsrEnabled).also {
                        observation.benchmark.mark("capture_first_recording_ready")
                        observation.benchmark.metric("playback_to_capture_rearm_ms",
                            (System.nanoTime() / 1_000_000 - delivery.completedAtMs).coerceAtLeast(0))
                    }
                },
                capture = { input, previousReplyCompleted ->
                    val nextId = java.util.UUID.randomUUID().toString()
                    var ownedCapture: com.battlesbudz.jarvis.v2.voice.AudioTurnCapture? = null
                    val shadowTelemetry = com.battlesbudz.jarvis.v2.diagnostics.SmartTurnBenchmarkTelemetry(
                        observation.benchmark, prefix = "followup_") { category, evidence ->
                        diagnosticRecorder.recordTurnEvidence(nextId, category, evidence)
                    }
                    observation.benchmark.configuration("followup_smart_turn_utterance_id", nextId)
                    observation.benchmark.configuration("followup_smart_turn_scope", "next_capture_in_previous_reply_row;correlation_id_only;native_endpoint_when_enabled;no_reply_cleanup_wait")
                    replyCapture.captureFirstFollowup(input,
                        com.battlesbudz.jarvis.v2.runtime.VoiceCapturePlan(nextId, request.asrEngine, prepared.asrDirectory,
                            prepared.directAudioTurn, request.captionAsrEnabled, guardFollowupSpeech = true),
                        prepared.models, diagnosticRecorder,
                        shadowOwner = resources.smartTurn,
                        shadowTelemetry = shadowTelemetry,
                        expectedCallId = prepared.expectedCallId,
                        shadowOwnerIsCurrent = { exactTurnJob?.isActive == true && call.state.turnJob === exactTurnJob &&
                            call.state.armed && call.controller.currentCallId() == prepared.expectedCallId &&
                            !call.state.inputQueue.hasInputAfter(captionInputBoundary) },
                        previousReplyCompleted = previousReplyCompleted,
                        nativeResourcesSafe = { !lifetime.captionNativeReleaseFailed &&
                            prepared.engine.nativeResourcesSafeToRelease && !prepared.engine.isNativeQuarantined },
                        playbackEndedAtMs = followupPlaybackBoundary.get(),
                        playbackReference = followupPlaybackReference.get(),
                        capturedAtMs = followupCapturedAt.get(),
                        prefixThroughSequence = raw.transferredThroughSequence,
                        onAcousticDecision = captionPublication::rawClassified,
                        onConsumed = captionPublication::rawConsumed,
                        onCandidatePending = captionPublication::candidatePending,
                        onCandidateRejected = captionPublication::candidateRejected,
                        onCapture = { capture ->
                            if (capture != null) {
                                ownedCapture = capture; lifetime.capture = capture; call.state.capture = capture
                            } else if (call.state.capture === ownedCapture) call.state.capture = null
                        },
                        onAcceptedSpeech = {
                            if (!captionPublication.revoke {
                                acceptedNextSpeech.complete(Unit)
                                observation.benchmark.mark("capture_first_speech_accepted_caption_revoked")
                            }) throw kotlinx.coroutines.CancellationException("capture_first_farewell_already_committed")
                        },
                        onReady = {
                            observation.benchmark.mark("capture_first_asr_capture_ready")
                            call.controller.setStateIfCurrent(prepared.expectedCallId,
                                com.battlesbudz.jarvis.v2.voice.VoiceSessionState.ACTIVELY_LISTENING)
                            call.status("Voice Call is listening — speak now.")
                        },
                        needsFollowupTranscript = resources.resources::needsFollowupTranscript,
                        rejectsFollowupEcho = resources.resources::rejectsFollowupEcho,
                        onPartialTranscript = { text -> call.events.post {
                            if (call.controller.currentCallId() == prepared.expectedCallId && call.state.capture === ownedCapture)
                                call.events.transcript("You", text, false)
                        } })
                },
                awaitTypedInput = { awaitTypedInput() },
                hasTypedInput = { call.state.inputQueue.hasInputAfter(captionInputBoundary) },
                observe = { observation.benchmark.mark(it) }
            ) }
        )
        } finally { rawInput?.close() }
        if (outcome is com.battlesbudz.jarvis.v2.voice.ReplyOutcome.Interrupted) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
            observation.failure = "barge_in"
            request.comparison?.put("interrupted", true)
            conversation.job?.join()
            if (outcome.endsCallSegment) {
                call.controller.appendTranscript("You", outcome.correction.transcript)
                call.events.returnToWake(prepared.expectedCallId)
                lifetime.finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " goodbye."
                return VoiceStageResult.Finished(lifetime.finalMessage)
            }
            if (outcome.correction.wav.size > 44) call.state.pendingVoiceCorrection.set(outcome.correction)
            lifetime.finalMessage = "Voice reply interrupted; continuing the same call."
            return VoiceStageResult.Finished(lifetime.finalMessage)
        }
        val finished = outcome as com.battlesbudz.jarvis.v2.voice.ReplyOutcome.Finished<com.battlesbudz.jarvis.v2.ai.GenerationResult>
        finished.followup?.takeIf { it.wav.size > 44 }?.let { followup ->
            val sealed = followup.copy(wav = followup.wav.copyOf(), handoff = com.battlesbudz.jarvis.v2.voice.VoiceCaptureHandoff(
                prepared.expectedCallId, captionConversationId, request.asrTurnId, followup.utteranceId, followup.wav))
            call.state.inputQueue.publishWithoutNextInput(captionInputBoundary) {
                synchronized(call.controller) {
                    if (call.controller.currentCallId() != prepared.expectedCallId ||
                        conversationHistory.current.value.id != captionConversationId || !call.state.armed) false
                    else call.state.pendingVoiceCorrection.compareAndSet(null, sealed)
                }
            }
        }
        val response = finished.value
        call.state.audioRecoveryAttempts = 0
        if (observation.outcome == com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.UNKNOWN)
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.COMPLETE
        lifetime.finalMessage = "Voice Call turn complete. Heard: ${finalized.transcript}\nJarvis: ${response.text}"
        return VoiceStageResult.Finished(lifetime.finalMessage)
    }
}
