package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarks
import com.battlesbudz.jarvis.v2.diagnostics.ReplyCaptureBenchmark
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceActionCoordinator
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceInvocation
import com.battlesbudz.jarvis.v2.runtime.RuntimeVoiceResources
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.SilentWorkController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns continuous followup capture/report delivery while the process action worker retains native work. */
internal class AcceptedVoiceFollowupStage(
    private val call: VoiceCallAccess,
    private val applicationScope: CoroutineScope,
    private val actions: AcceptedVoiceActionCoordinator,
    private val replyCapture: ReplyCaptureBenchmark,
    private val benchmarks: PipelineBenchmarks,
    private val resources: RuntimeVoiceResources,
    private val conversationHistory: ConversationHistory,
    private val memory: VoiceMemoryAccess,
    private val turnOrchestrator: TurnOrchestrator,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val silentWork: SilentWorkController? = null,
    private val onSilentWorkExit: () -> Unit = {}
) {
    suspend fun run(request: VoiceTurnRequest, prepared: PreparedVoiceTurn, finalized: FinalizedVoiceTurn, observation: VoiceTurnObservation, lifetime: VoiceTurnLifetime): AcceptedVoiceStageResult {
        val initialActionPlan = finalized.initialActionPlan
        if (request.comparison != null || initialActionPlan !is ActionTurnPlan.Ready) return AcceptedVoiceStageResult.NotApplicable
        prepared.incremental.close() // Never leave speculative prefill attached to a queued native turn.
        // A typed input can be End-drained while preparation is in progress. Keep
        // the ordinary lease until its successful atomic promotion, then transfer it
        // together with append/begin/enqueue so a rejected promotion cannot strand it.
        fun acquireAcceptedLease(): Boolean = lifetime.promotionLease.transfer {
            if (lifetime.modelLease.owned) {
                lifetime.modelLease.transfer { actions.transferAcceptedVoiceLease() }
            } else actions.retainAcceptedVoiceLease() != null
        }
        val actionSession = com.battlesbudz.jarvis.v2.voice.ContinuousActionSession(
            actions.queue, silentWork = silentWork, onSilentWorkExit = onSilentWorkExit)
        val actionInvocation = AcceptedVoiceInvocation(
            prepared.expectedCallId, request.asrTurnId, prepared.correction?.utteranceId ?: request.asrTurnId, finalized.transcript, prepared.voiceHistory, initialActionPlan
        )
        var accepted = false
        var leaseRejected = false
        fun admitAction() {
            if (!acquireAcceptedLease()) {
                leaseRejected = true
                return
            }
            call.controller.appendTranscript("You", finalized.transcript,
                origin = prepared.correction?.origin ?: com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.SPOKEN)
            call.controller.beginReply(prepared.expectedCallId, request.asrTurnId)
            observation.telemetry.speechEndedAt.get().takeIf { it != 0L }?.let { ended ->
                call.controller.updateReplyMetrics(prepared.expectedCallId, request.asrTurnId) { it.speechEnded(ended) }
            }
            // Deterministic accepted actions may not invoke the model; start a fresh
            // per-task record with unknown timings instead of retaining the prior reply.
            com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.beginLiveMetrics(
                com.battlesbudz.jarvis.v2.voice.LiveReplyMetrics(request.asrTurnId, conversationHistory.current.value.id))
            call.controller.setState(VoiceSessionState.EXECUTING_ACTION)
            call.state.acceptedSession = actionSession
            accepted = actions.enqueueAcceptedVoiceAction(actionSession, actionInvocation)
        }
        if (request.queuedTypedInput != null) {
            val typed = request.queuedTypedInput
            if (!call.state.inputQueue.promote(typed) {
                    call.state.preparingTypedInput.compareAndSet(typed, null)
                    admitAction()
                }) return AcceptedVoiceStageResult.Handled(lifetime.finalMessage)
        } else admitAction()
        if (!accepted) {
            observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
            observation.failure = "accepted_action_admission_rejected"
            lifetime.promotionLease.releaseIfUnadmitted(actions::releaseAcceptedVoiceLeaseIfIdle)
            val rejection = if (leaseRejected) "The local model is still busy. Please repeat that phone request shortly."
                else "I already have three accepted phone requests. Please wait for one to finish."
            call.controller.updateReplyText(prepared.expectedCallId, request.asrTurnId, rejection, finished = true)
            lifetime.finalMessage = rejection
            return AcceptedVoiceStageResult.Handled(lifetime.finalMessage)
        }
        lifetime.promotionLease.markAdmitted() // accepted queue now owns release through its idle lifecycle.
        observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.COMPLETE
        observation.benchmark.configuration("request_scope", "voice_action_listener_task_report_separate")
        observation.benchmark.metric("accepted_action_handoff", 1)
        diagnosticRecorder.recordImportant("Accepted action mode: admitted=${request.asrTurnId} steps=${initialActionPlan.steps.size} call=${prepared.expectedCallId}")
        // The action worker is already running while this listener waits. Do not call
        // runVoiceTurn here: that would reset/prefill the shared native engine.
        var terminalReportsPublished = false
        // SessionCapture cannot carry timing without widening its stable queue API. Keep
        // the capture's monotonic endpoint keyed by its immutable utterance ID.
        val capturedSpeechEnds = java.util.concurrent.ConcurrentHashMap<String, Long>()
        // A fast executor can finish before the listener exists. Reconcile its durable
        // terminal record before deciding whether an idle watcher is needed.
        actions.publishTerminalActionReports(actionSession, prepared.expectedCallId)
        // Stay false until this pump observes/reconciles one idle transition; a
        // completion between the scan and this point still wakes workerIdle.
        // Shared with the persistent ASR listener so it always interrupts the current
        // report output, not a stale per-loop capture.
        var reportOutput: PiperVoiceOutput? = null
        val pumpLifetime = AcceptedFollowupLifetime()
        try {
            actionPump@ while ((actions.queue.hasUnfinished() || actionSession.pendingReportCount() > 0 ||
                !terminalReportsPublished || actionSession.isCaptureInProgress()) &&
                call.state.armed && call.controller.currentCallId() == prepared.expectedCallId) {
                if (com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
                    // Pausing parks microphone/TTS only. A retained typed scoped control
                    // still reaches the durable accepted queue while a task is blocked.
                    val pausedControl = call.state.inputQueue.claim(prepared.expectedCallId) { candidate ->
                        actionSession.control(candidate.text, actions.queue.hasUnfinished()) !=
                            com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None
                    }
                    if (pausedControl != null) {
                        lifetime.activePumpTypedInput.set(pausedControl)
                        val control = actionSession.control(pausedControl.text, actions.queue.hasUnfinished())
                        try {
                            memory.capture(pausedControl.id, pausedControl.conversationId, prepared.expectedCallId,
                                ConversationMemorySource.TEXT, pausedControl.text, pausedControl.capturedAtMs)
                        } catch (failure: Throwable) {
                            if (call.state.inputQueue.terminalize(pausedControl)) {
                                lifetime.activePumpTypedInput.compareAndSet(pausedControl, null)
                                call.controller.recordTerminalInputForCall(pausedControl.callId, pausedControl.id,
                                    "Cancelled before processing typed message: ${pausedControl.text}")
                            }
                            diagnosticRecorder.recordImportant("Paused typed control capture failed: ${failure.javaClass.simpleName}")
                            continue@actionPump
                        }
                        var stopListening = false
                        if (!call.state.inputQueue.promote(pausedControl) {
                                call.controller.appendTranscript("You", pausedControl.text,
                                    origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                                if (control == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly &&
                                    pausedControl.text.trim().lowercase().trimEnd('.', '!', '?') == "stop listening") {
                                    stopListening = true
                                } else if (control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly) {
                                    actions.queue.cancel(control) { it.value.callId == prepared.expectedCallId }
                                }
                            }) continue@actionPump
                        lifetime.activePumpTypedInput.compareAndSet(pausedControl, null)
                        if (stopListening) {
                            call.events.returnToWake(prepared.expectedCallId)
                            break@actionPump
                        }
                        continue@actionPump
                    }
                    delay(100)
                    continue@actionPump
                }
                if (!call.state.armed) break@actionPump
                // Keep a single ASR capture alive while terminal reports become ready and
                // while Piper swaps to a fresh delivery ledger. A barge-in therefore stops
                // only the current report attempt; it never discards the final utterance.
                val followup = pumpLifetime.capture ?: lifetime.scope.async {
                    replyCapture.listenBenchmarkedReply(prepared.output, prepared.asrDirectory, onConfirmed = {
                        actionSession.onCaptureStarted()
                    }, asrEngine = request.asrEngine,
                        inputFactory = { resources.resources.borrowMicrophone("accepted-followup", communication = true) },
                        modelSession = prepared.models, asrOnly = true,
                        outputProvider = { reportOutput ?: prepared.output },
                        log = { diagnosticRecorder.recordImportant("Action follow-up capture: $it") })
                }.also { pumpLifetime.capture = it }
                val deliveryReady = lifetime.scope.async { actionSession.awaitDeliveryReady() }.also { pumpLifetime.deliveryReady = it }
                // Queue cancellation can terminally skip a task without entering its
                // executor callback. Its durable task record still needs a report, so
                // wake on actual worker-idle as well as a pre-existing pending report.
                val workerIdle = (if (actionSession.needsIdleObservation(terminalReportsPublished)) lifetime.scope.async { actions.queue.awaitIdle() } else null)
                    .also { pumpLifetime.workerIdle = it }
                // Typed finals enter the same accepted-call owner. They wake a silent ASR
                // listener without pretending to be WAV; a begun spoken floor wins first.
                fun typedDispatchable(candidate: com.battlesbudz.jarvis.v2.voice.CallFinalInput): Boolean {
                    val control = actionSession.control(candidate.text, actions.queue.hasUnfinished())
                    if (control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None) return true
                    return when (turnOrchestrator.plan(candidate.text, prepared.voiceHistory.map { it.role to it.text }).actionPlan) {
                        is ActionTurnPlan.Ready, is ActionTurnPlan.Rejected -> true
                        else -> !actionSession.hasDeferredConversation() && !actionSession.isCaptureInProgress()
                    }
                }
                val typedAvailable = lifetime.scope.async { call.state.inputQueue.awaitDispatchable(prepared.expectedCallId, ::typedDispatchable) }
                    .also { pumpLifetime.typedAvailable = it }
                var typedInput: com.battlesbudz.jarvis.v2.voice.CallFinalInput? = null
                var captured: com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn? = null
                val pumpEvent = com.battlesbudz.jarvis.v2.voice.awaitActionPumpEvent(
                    followup, deliveryReady, workerIdle, typedAvailable
                ) { capturedTurn ->
                    captured = capturedTurn
                    pumpLifetime.capture = null
                }
                if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.TYPED_AVAILABLE) {
                    typedInput = call.state.inputQueue.claim(prepared.expectedCallId, ::typedDispatchable)
                    // Keep a begun ASR capture alive. The next loop reuses this exact
                    // deferred listener while the typed control/action is admitted.
                    // Never cancel this persistent listener on a typed handoff. Speech
                    // can confirm between a stale state read and cancellation; retaining
                    // its exact deferred preserves the next immutable spoken final.
                    typedInput?.let { typed ->
                        lifetime.activePumpTypedInput.set(typed)
                        captured = com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                            typed.text, byteArrayOf(), utteranceId = typed.id,
                            capturedAtMs = typed.capturedAtMs,
                            origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
                        )
                    }
                }
                if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.DELIVERY_READY ||
                    pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.WORKER_IDLE) {
                    actions.publishTerminalActionReports(actionSession, prepared.expectedCallId)
                    terminalReportsPublished = true
                    val report = actionSession.nextDelivery()
                    if (report != null) {
                        lifetime.speechChunks.close()
                        withContext(kotlinx.coroutines.NonCancellable) { lifetime.speechJob?.cancelAndJoin() }
                        val reportReplyId = "report-" + java.util.UUID.randomUUID().toString()
                        val reportBenchmark = benchmarks.create(reportReplyId, "accepted_report_audio", tts = request.ttsEngine)
                        reportBenchmark.configuration("request_scope", "executor_report_delivery_attempt_excludes_capture_model_task")
                        reportBenchmark.configuration("parent_task_ids", report.reports.joinToString(",") { it.taskId })
                        val reportText = AssistantText.forSpeech(report.reports.joinToString(" ") { it.text })
                        call.controller.beginReply(prepared.expectedCallId, reportReplyId)
                        call.controller.updateReplyText(prepared.expectedCallId, reportReplyId, reportText, finished = true)
                        val reportTerminal = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.SpeechDelivery?>(null)
                        val reportPlaybackObserved = java.util.concurrent.atomic.AtomicBoolean(false)
                        val createdReportOutput = PiperVoiceOutput(prepared.ttsDirectory.path, engine = request.ttsEngine,
                            modelSession = prepared.models,
                            deliveryLedger = com.battlesbudz.jarvis.v2.voice.SpeechDeliveryLedger(reportReplyId) { delivery ->
                                reportTerminal.set(delivery)
                                call.controller.updateDelivery(prepared.expectedCallId, delivery)
                            },
                            onEchoReference = resources.resources::rememberPlayback,
                            onPlaybackEnded = { resources.resources.playbackEnded(System.nanoTime() / 1_000_000) },
                            maxQueuedPassages = 2,
                            onMetrics = { metrics ->
                                reportBenchmark.tts(com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkTts(
                                    request.ttsEngine.id, loadMs = metrics.loadMs, firstTextToPcmMs = metrics.firstTextToPcmMs,
                                    firstTextToPlaybackMs = metrics.firstTextToPlaybackMs, synthesisMs = metrics.synthesisMs,
                                    generatedAudioMs = metrics.audioMs, queueWaitMs = metrics.queueWaitMs,
                                    playbackStarvationMs = metrics.observedPlaybackStarvationMs, underruns = metrics.underruns))
                            },
                            log = { diagnosticRecorder.recordImportant("Accepted report TTS: $it") })
                        val playbackOwner = AcceptedReportPlayback(createdReportOutput::stopSpeaking, createdReportOutput::release) {
                            // Exceptional selector exit retains this report for a fresh delivery attempt.
                            // A successful markDelivered already cleared it; this becomes a no-op.
                            actionSession.interruptDelivery(report.attemptId)
                            if (call.state.output === createdReportOutput) call.state.output = prepared.output
                            reportOutput = null
                        }
                        pumpLifetime.report = playbackOwner
                        reportOutput = createdReportOutput
                        call.state.output = createdReportOutput
                        val reportJob = lifetime.scope.async {
                            var reportBenchmarkOutcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
                            try {
                                reportBenchmark.mark("first_reply_text_ready")
                                createdReportOutput.speak(kotlinx.coroutines.flow.flowOf(reportText)) {
                                    // Piper emits this only once AudioTrack head passes its first audible
                                    // frame. A combined report is its own reply: do not backdate a later
                                    // task's first word to the aggregate opening.
                                    if (reportPlaybackObserved.compareAndSet(false, true)) {
                                        reportBenchmark.mark("first_reply_audio")
                                        val at = System.nanoTime() / 1_000_000
                                        val targetId = report.reports.singleOrNull()?.taskId ?: reportReplyId
                                        call.controller.updateReplyMetrics(prepared.expectedCallId, targetId) {
                                            it.firstActualPlayback(at)
                                        }
                                    }
                                }
                                reportBenchmarkOutcome = when (reportTerminal.get()?.state) {
                                    com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED -> com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.COMPLETE
                                    com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.FAILED -> com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
                                    else -> com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
                                }
                                true
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                // Parent/end-call cancellation still escapes; an interrupted
                                // report attempt remains pending for the same session.
                                if (!call.state.armed) throw cancelled
                                false
                            } catch (failure: Throwable) {
                                reportBenchmarkOutcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
                                diagnosticRecorder.recordImportant("Accepted report delivery failed: ${failure.javaClass.simpleName}")
                                false
                            } finally {
                                benchmarks.finishResources(reportBenchmark)
                                reportBenchmark.finish(reportBenchmarkOutcome, prepared.expectedCallId)?.let { observation.store.append(it) }
                            }
                        }
                        playbackOwner.job = reportJob
                        var reportPlaybackSucceeded = false
                        // Keep the same selector live during playback: a typed scoped
                        // cancellation/action must not wait for unbounded report audio.
                        val reportEvent = kotlinx.coroutines.selects.select<Int> {
                            reportJob.onAwait { reportPlaybackSucceeded = it; 0 }
                            followup.onAwait { captured = it; pumpLifetime.capture = null; 1 }
                            typedAvailable.onAwait {
                                typedInput = call.state.inputQueue.claim(prepared.expectedCallId, ::typedDispatchable)
                                2
                            }
                        }
                        withContext(kotlinx.coroutines.NonCancellable) {
                            when (reportEvent) {
                                // Spoken/typed input interrupts this report attempt; its
                                // ledger remains pending for a fresh delivery, never blocks
                                // the capture/control selector behind playback.
                                1, 2 -> reportJob.cancelAndJoin()
                            }
                        }
                        val reportCompleted = reportEvent == 0 && reportPlaybackSucceeded &&
                            reportTerminal.get()?.state == com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED
                        if (reportCompleted) {
                            actionSession.markDelivered(report.attemptId, report.reports.mapTo(linkedSetOf()) { it.taskId })
                        } else {
                            // STOP_REPLY, Barge-in, and delivery failure retain this exact
                            // pending aggregate for a fresh ledger attempt; no action repeats.
                            actionSession.interruptDelivery(report.attemptId)
                        }
                        pumpLifetime.releaseReport()
                        if (reportEvent == 2) typedInput?.let { typed ->
                            lifetime.activePumpTypedInput.set(typed)
                            captured = com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                                typed.text, byteArrayOf(), utteranceId = typed.id,
                                capturedAtMs = typed.capturedAtMs,
                                origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
                            )
                        }
                        if (reportEvent == 0 && actionSession.isCaptureInProgress()) {
                            // The capture stays owned by the next selector; do not await it
                            // here or typed controls would starve behind a spoken floor.
                            withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                            withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                            withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                            continue@actionPump
                        } else if (reportEvent == 0 && captured == null) {
                            // Retain the idle listener; a late capture-start owns its final.
                            withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                            withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                            withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                            if (reportCompleted && actionSession.tryDetachIdleCapture()) {
                                withContext(kotlinx.coroutines.NonCancellable) { followup.cancelAndJoin() }
                                pumpLifetime.capture = null
                                break@actionPump
                            }
                            continue@actionPump
                        }
                    } else if (actionSession.isCaptureInProgress()) {
                        // Keep the confirmed speech future alive for the next selector so
                        // typed controls remain dispatchable while it finalizes.
                        withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                        continue@actionPump
                    } else {
                        // Retain the idle listener; a late capture-start owns its final.
                        withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                        continue@actionPump
                    }
                }
                withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                // A very fast executor can be terminal before its first listener select.
                // Publish its durable report before classifying an idle ordinary capture.
                if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.CAPTURED &&
                    !terminalReportsPublished && !actions.queue.hasUnfinished()) {
                    actions.publishTerminalActionReports(actionSession, prepared.expectedCallId)
                    terminalReportsPublished = true
                }
                val finalCaptured = captured ?: continue
                val typedOwned = typedInput?.takeIf {
                    finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED &&
                        it.id == finalCaptured.utteranceId
                }
                if (finalCaptured.recognitionIssue == null && finalCaptured.transcript.isNotBlank()) {
                    try {
                        memory.capture(finalCaptured.utteranceId, conversationHistory.current.value.id,
                            prepared.expectedCallId, if (finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                                ConversationMemorySource.TEXT else ConversationMemorySource.VOICE,
                            finalCaptured.transcript, finalCaptured.capturedAtMs)
                    } catch (failure: Throwable) {
                        if (typedOwned != null && call.state.inputQueue.terminalize(typedOwned)) {
                            lifetime.activePumpTypedInput.compareAndSet(typedOwned, null)
                            call.controller.recordTerminalInputForCall(typedOwned.callId, typedOwned.id,
                                "Cancelled before processing typed message: ${typedOwned.text}")
                        }
                        diagnosticRecorder.recordImportant("Accepted-pump typed memory capture failed: ${failure.javaClass.simpleName}")
                        continue@actionPump
                    }
                }
                if (finalCaptured.recognitionIssue == null &&
                    com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(finalCaptured.transcript)) {
                    var admittedGoodbye = false
                    val promoted = typedOwned?.let { typed ->
                        call.state.inputQueue.promote(typed) {
                            call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                            admittedGoodbye = true
                        }
                    } ?: run {
                        call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                        admittedGoodbye = true
                        true
                    }
                    if (!promoted || !admittedGoodbye) continue@actionPump
                    typedOwned?.let { lifetime.activePumpTypedInput.compareAndSet(it, null) }
                    call.events.returnToWake(prepared.expectedCallId)
                    diagnosticRecorder.recordImportant("Accepted action mode: spoken goodbye detached capture; work retained")
                    break@actionPump
                }
                finalCaptured.speechEndedAtMs?.let { capturedSpeechEnds[finalCaptured.utteranceId] = it }
                val finalCapture = com.battlesbudz.jarvis.v2.voice.SessionCapture(
                    finalCaptured.utteranceId, finalCaptured.transcript, finalCaptured.recognitionIssue,
                    finalCaptured.wav, finalCaptured.audioIsComplete, finalCaptured.capturedAtMs, finalCaptured.origin
                )
                val plan = if (finalCaptured.recognitionIssue == null)
                    turnOrchestrator.plan(finalCaptured.transcript, prepared.voiceHistory.map { it.role to it.text }).actionPlan
                else ActionTurnPlan.NotAction
                val control = actionSession.control(finalCaptured.transcript, actions.queue.hasUnfinished())
                val kind = when {
                    finalCaptured.recognitionIssue != null -> com.battlesbudz.jarvis.v2.voice.CapturedKind.Ordinary
                    control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None ->
                        com.battlesbudz.jarvis.v2.voice.CapturedKind.Control(control)
                    plan is ActionTurnPlan.Ready -> com.battlesbudz.jarvis.v2.voice.CapturedKind.AcceptedAction
                    plan is ActionTurnPlan.Rejected -> com.battlesbudz.jarvis.v2.voice.CapturedKind.RejectedAction
                    else -> com.battlesbudz.jarvis.v2.voice.CapturedKind.Ordinary
                }
                var leaveActionPump = false
                fun applyCaptureOutcome(captureOutcome: com.battlesbudz.jarvis.v2.voice.CaptureOutcome) {
                    when (captureOutcome) {
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.Control -> {
                            if (captureOutcome.value == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly) {
                                call.state.output?.stopSpeaking()
                                when (finalCaptured.transcript.trim().lowercase().trimEnd('.', '!', '?')) {
                                    "stop listening" -> leaveActionPump = true
                                    "pause microphone" -> {
                                        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = true
                                        applicationScope.launch { resources.resources.closeMicrophone() }
                                    }
                                    else -> diagnosticRecorder.recordImportant("Accepted action mode: speech delivery detached; work retained")
                                }
                            } else actions.queue.cancel(captureOutcome.value) { it.value.callId == prepared.expectedCallId }
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.AcceptedAction -> {
                            val followPlan = plan as ActionTurnPlan.Ready
                            val followId = java.util.UUID.randomUUID().toString()
                            call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                            call.controller.beginReply(prepared.expectedCallId, followId)
                            finalCaptured.speechEndedAtMs?.let { ended ->
                                call.controller.updateReplyMetrics(prepared.expectedCallId, followId) { it.speechEnded(ended) }
                            }
                            if (!actions.enqueueAcceptedVoiceAction(actionSession, AcceptedVoiceInvocation(
                                    prepared.expectedCallId, followId, finalCaptured.utteranceId, finalCaptured.transcript, prepared.voiceHistory, followPlan))) {
                                val explanation = "I already have accepted phone results waiting to be reported. Please wait a moment."
                                call.controller.updateReplyText(prepared.expectedCallId, followId, explanation, finished = true)
                                actionSession.offerLocalFeedback(explanation)
                            } else terminalReportsPublished = false
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.DeferredConversation -> {
                            diagnosticRecorder.recordImportant("Accepted action mode: ordinary follow-up retained for post-queue normal turn")
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.ConversationReady -> {
                            // An ordinary barge-in never discards already completed Android evidence.
                            // Typed identity remains a queued handoff; spoken audio remains a correction.
                            if (finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                                call.state.pendingTypedHandoff.compareAndSet(null,
                                    com.battlesbudz.jarvis.v2.voice.CallFinalInput(finalCaptured.utteranceId, prepared.expectedCallId,
                                        conversationHistory.current.value.id, finalCaptured.transcript, finalCaptured.capturedAtMs))
                            } else call.state.pendingVoiceCorrection.set(finalCaptured)
                            if (actionSession.pendingReportCount() == 0) leaveActionPump = true
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.RejectedAction -> {
                            val replyId = "local-" + java.util.UUID.randomUUID().toString()
                            call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                            call.controller.beginReply(prepared.expectedCallId, replyId)
                            val explanation = (plan as ActionTurnPlan.Rejected).reason
                            call.controller.updateReplyText(prepared.expectedCallId, replyId, explanation, finished = true)
                            actionSession.offerLocalFeedback(explanation)
                            diagnosticRecorder.recordImportant("Accepted action mode: rejected follow-up did not enqueue work")
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.RecognitionIssue -> {
                            val replyId = "local-" + java.util.UUID.randomUUID().toString()
                            call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                            call.controller.beginReply(prepared.expectedCallId, replyId)
                            val explanation = "I couldn't retain that follow-up reliably. Please repeat it after these phone actions finish."
                            call.controller.updateReplyText(prepared.expectedCallId, replyId, explanation, finished = true)
                            actionSession.offerLocalFeedback(explanation)
                        }
                        is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.ConversationBusy -> {
                            val replyId = "local-" + java.util.UUID.randomUUID().toString()
                            call.controller.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                            call.controller.beginReply(prepared.expectedCallId, replyId)
                            val explanation = "I kept your earlier follow-up; please repeat this later request after it is answered."
                            call.controller.updateReplyText(prepared.expectedCallId, replyId, explanation, finished = true)
                            actionSession.offerLocalFeedback(explanation)
                        }
                        com.battlesbudz.jarvis.v2.voice.CaptureOutcome.Duplicate -> Unit
                    }
                }
                val admitted = typedOwned?.let { typed ->
                    call.state.inputQueue.promote(typed) {
                        applyCaptureOutcome(actionSession.onTyped(finalCapture, kind))
                    }
                } ?: run {
                    applyCaptureOutcome(actionSession.onCaptured(finalCapture, kind))
                    true
                }
                if (!admitted) continue@actionPump
                typedOwned?.let { lifetime.activePumpTypedInput.compareAndSet(it, null) }
                if (leaveActionPump) {
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly &&
                        finalCaptured.transcript.trim().lowercase().trimEnd('.', '!', '?') == "stop listening") {
                        call.events.returnToWake(prepared.expectedCallId)
                    }
                    break@actionPump
                }
            }
            actions.queue.awaitIdle()
            actionSession.takeDeferredConversationIfNoCapture()?.let { deferred ->
                if (deferred.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                    // Preserve TEXT identity/FIFO ownership; it must re-enter the typed
                    // owner rather than masquerading as an audio correction.
                    val handoff = com.battlesbudz.jarvis.v2.voice.CallFinalInput(deferred.utteranceId, prepared.expectedCallId,
                        conversationHistory.current.value.id, deferred.text, deferred.capturedAtMs)
                    if (!call.state.inputQueue.publishHandoff(handoff) {
                            call.state.pendingTypedHandoff.compareAndSet(null, handoff)
                        }) {
                        call.controller.recordTerminalInputForCall(handoff.callId, handoff.id,
                            "Cancelled before processing typed message: ${handoff.text}")
                    }
                } else call.state.pendingVoiceCorrection.set(com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                    deferred.text, deferred.wav, deferred.audioIsComplete,
                    deferred.recognitionIssue, deferred.utteranceId, deferred.capturedAtMs, deferred.origin,
                    capturedSpeechEnds.remove(deferred.utteranceId)
                ))
            }
            val replyIds = actions.queue.tasks.value
                .filter { it.value.callId == prepared.expectedCallId && actionSession.ownsActionTask(it.id) }
                .map { it.value.replyId }.toSet()
            val summary = actions.acceptedVoiceSummary(prepared.expectedCallId, replyIds, includeTerminalText = true)
            call.controller.setStateIfCurrent(prepared.expectedCallId, VoiceSessionState.ACTIVELY_LISTENING)
            call.events.post { call.events.transcript("Jarvis", summary, true) }
            lifetime.finalMessage = "Voice Call accepted actions complete. Jarvis: $summary"
            return AcceptedVoiceStageResult.Handled(lifetime.finalMessage)
        } finally { pumpLifetime.close() }
    }
}
