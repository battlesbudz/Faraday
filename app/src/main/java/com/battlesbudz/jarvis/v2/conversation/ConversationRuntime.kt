package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.eval.ConversationAdmission
import com.battlesbudz.jarvis.v2.eval.admitConversationTurn
import com.battlesbudz.jarvis.v2.work.DiscardedTurnSignal
import com.battlesbudz.jarvis.v2.work.TurnOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * User-facing text for a failed turn. The classes stay distinct: a native
 * tool-call parse failure means the model loaded and generated but emitted
 * a call the SDK could not parse, so it is reported as an action that could
 * not be completed — never as a model-load failure, and never with the
 * SDK's internal status text attached.
 */
internal fun conversationFailureMessage(isNativeQuarantined: Boolean, error: Throwable): String = when {
    isNativeQuarantined ->
        "The local model did not stop safely. Restart Jarvis before starting another request."
    com.battlesbudz.jarvis.v2.ai.ToolCallParseFailure.isFailure(error) ->
        "I couldn't complete that action. Please try again."
    else -> "I could not load the local model: ${error.message ?: "unknown error"}"
}

/**
 * Turn admission and ordered stage orchestration. Its returned job owns native callbacks until
 * cleanup completes; the voice caller retains its existing model lease and joins this job.
 * Android entry points compose the narrow owners here, without exposing their runtime object.
 */
internal class ConversationCoordinator(
    private val scope: CoroutineScope,
    private val post: (() -> Unit) -> Unit,
    private val currentConversationId: () -> String,
    private val modelSession: ConversationModelSession,
    private val routing: ConversationRouting,
    private val contexts: ConversationContextPreparation,
    private val generation: ConversationGeneration,
    private val recovery: ConversationRecovery,
    private val promptBuilder: ConversationPromptBuilder,
    private val actionIntent: (String, List<ChatEntry>) -> Boolean,
    private val recordResponse: (String, String, TurnPlan) -> Unit,
    private val createActions: (String, (String, String, Boolean) -> Unit) -> ConversationActions,
    private val createBenchmark: (String, String) -> PipelineBenchmarkCapture,
    private val finishOwnedBenchmark: (PipelineBenchmarkCapture, PipelineBenchmarkOutcome, String?, String?) -> Unit,
    private val diagnostics: ConversationDiagnostics,
    private val onTurnStarted: () -> Unit = {},
    private val onTurnFailure: (conversationId: String) -> Unit = {},
    private val beginActivity: (conversationId: String) -> ConversationActivity? = { null }
) {
    fun previewNativeAudioPrompt() = contexts.previewDirectAudio(promptBuilder)

    fun start(input: ConversationInvocation, callbacks: ConversationCallbacks): Job? {
        val conversationId = input.conversationIdentity ?: currentConversationId()
        val actions = createActions(conversationId, callbacks.onActionResult)
        val contextLimit = ConversationPrompt.contextLimit(modelSession.selectedModel().contextTokens, input.imageUri != null)
        val started = System.nanoTime()
        val replyId = input.replyIdentity ?: java.util.UUID.randomUUID().toString()
        val capture = input.benchmarkCapture ?: createBenchmark(replyId,
            if (input.voiceAudio != null) "voice_reply" else if (input.callOwned) "typed_in_call" else "text")
        val ownsBenchmark = input.benchmarkCapture == null
        if (ownsBenchmark) {
            capture.configuration("reply_id", replyId)
            capture.configuration("conversation_id", conversationId)
        }
        val reply = ConversationReply(replyId, capture, ownsBenchmark, contexts.memory.deliveryFence,
            post, callbacks.onToken, callbacks.onComplete, callbacks.onLatency,
            onAnswer = { input.comparison?.put("answer", it) },
            finishOwnedBenchmark = { benchmark, outcome, failure ->
                finishOwnedBenchmark(benchmark, outcome, input.callIdentity, failure)
            }, startedNanos = started)
        val prompt = ConversationPrompt(promptBuilder, input.voiceAudio != null,
            contextTokens = { modelSession.selectedModel().contextTokens }, memoryContext = { reply.memoryContext })
        // One atomic admission mechanism shared with the reliability check:
        // the model gate is acquired BEFORE the conversation marks itself
        // active, and released once the activeJobs claim is taken. Reading
        // the gate's state without acquiring it leaves a race on
        // Dispatchers.Default where the check acquires the gate, observes
        // idle, and closes the idle engine after this read but before the
        // compareAndSet below — starting a conversation on a closed engine.
        // Serializing both admissions on the gate closes it: either this turn
        // wins the gate and the check later observes activeJobs != 0, or the
        // check holds the gate and this acquire fails. A second independent
        // busy check cannot fix this; the gate is the one serialization point.
        // Voice and accepted-action turns skip the gate: their caller holds
        // the model lease for the whole turn.
        if (input.voiceAudio == null && input.frozenActionPlan == null && !input.callOwned) {
            when (admitConversationTurn(
                acquireGate = modelSession.tryBeginModelOperation,
                releaseGate = modelSession.endModelOperation,
                markActive = { ConversationWork.activeJobs.compareAndSet(0, 1) }
            )) {
                ConversationAdmission.ADMITTED -> Unit
                ConversationAdmission.GATE_BUSY -> {
                    reply.recordOutcome(PipelineBenchmarkOutcome.REJECTED, "model_operation_busy")
                    reply.finish("A voice or model operation is still active. Please finish it first.")
                    reply.finishBenchmark()
                    return null
                }
                ConversationAdmission.SESSION_BUSY -> {
                    reply.recordOutcome(PipelineBenchmarkOutcome.REJECTED, "conversation_busy")
                    reply.finish("The previous response is still finishing. Please try again in a moment.")
                    reply.finishBenchmark()
                    return null
                }
            }
        } else if (!ConversationWork.activeJobs.compareAndSet(0, 1)) {
            reply.recordOutcome(PipelineBenchmarkOutcome.REJECTED, "conversation_busy")
            reply.finish("The previous response is still finishing. Please try again in a moment.")
            reply.finishBenchmark()
            return null
        }
        runCatching { onTurnStarted() }
        // M7 turn guard: this admitted turn claims the next sequence.
        // Claiming supersedes any earlier turn whose teardown or posted
        // callbacks are still in flight; every shared-state touch below is
        // fenced on this ownership.
        val ownership = TurnOwnership(ConversationWork.turnGuard, ConversationWork.turnGuard.claim())
        val job = scope.launch(Dispatchers.Default) {
            var failed = false
            var ownedBackend: ConversationBackend? = null
            var telemetry: ConversationInferenceTelemetry? = null
            // Discarded-turn signal (M7): exactly one emission, only for a
            // turn that ends with nothing persisted, on the callback
            // channel the UI/state layers already listen to.
            val discardedSignal = DiscardedTurnSignal(callbacks.onTurnDiscarded)
            fun emitDiscarded() = discardedSignal.emit()
            val activity = runCatching { beginActivity(conversationId) }.getOrNull()
            fun progress(message: String, sequence: Long) { runCatching { activity?.progress(message, sequence) } }
            // Only this admitted invocation may install the native-mutation fence.
            // Unchanged context can promote a running draft; any reset/close must
            // invalidate and drain it before touching the same native owner.
            val installedFence: suspend () -> Unit = { input.nativeSpeculation?.beforeNativeMutation() }
            modelSession.beforeNativeMutation = installedFence
            try {
                capture.mark("request_processing_started")
                capture.metric("request_queue_ms", reply.elapsed())
                if (input.prompt.length > ConversationPolicy.MAX_USER_PROMPT_CHARS) {
                    reply.recordOutcome(PipelineBenchmarkOutcome.REJECTED, "request_too_large")
                    diagnostics.record("Turn rejected before action routing\\nuserLength=${input.prompt.length}\\n" +
                        "reason=single user message exceeds safe mobile budget")
                    reply.postFinish("That request is too large for the local model's safe mobile budget. Please send it in smaller parts.")
                    return@launch
                }
                val request = ConversationTurnRequest(input.prompt, input.history, conversationId,
                    input.imageUri != null, input.audioUri != null, input.voiceAudio != null,
                    input.directVoiceAudio, input.comparison != null, input.incrementalVoice,
                    input.frozenActionPlan, input.frozenVoiceFinal)
                val routed = routing.route(request, reply, actions, callbacks.onPhonePlanFinished) ?: return@launch
                progress("Preparing context for your reply", 1)
                val prepared = contexts.prepare(request, routed, reply, prompt, contextLimit, callbacks.onMemoryBound) ?: return@launch
                val history = modelSession.prepareHistory(prepared.history, prepared.memoryHistoryInvalidated,
                    prompt.pendingSize(input.prompt, null, prepared.history, prepared.referenceContext), contextLimit)
                progress("Preparing the local model", 2)
                val backend = modelSession.prepare(input.imageUri != null, input.audioUri != null, input.voiceAudio != null, reply)
                ownedBackend = backend
                val timings = ConversationInferenceTelemetry(input, callbacks, reply, diagnostics)
                telemetry = timings
                progress(when {
                    input.imageUri != null -> "Working with your attached image"
                    input.audioUri != null || input.voiceAudio != null -> "Working with your audio request"
                    else -> "Writing your reply"
                }, 3)
                val draft = generation.generate(input, contextLimit, routed, prepared, history, prompt,
                    backend, reply, actions, timings, turnOwnership = ownership)
                progress("Checking your reply", 4)
                recovery.recover(draft)
                finishDraft(draft, ownership, ::emitDiscarded)
            } catch (cancelled: CancellationException) {
                // Cancelled (or superseded-aborted) turn: nothing was
                // persisted, so emit the discarded-turn signal once.
                reply.recordOutcome(PipelineBenchmarkOutcome.CANCELLED, cancelled.javaClass.simpleName)
                actions.cancel()
                telemetry?.finishProgress()
                emitDiscarded()
                throw cancelled
            } catch (error: Throwable) {
                if (!ownership.isCurrent()) {
                    // M7: a superseded turn's error is dropped silently.
                    // It must not close the shared session, publish a
                    // failure over its replacement, or raise the visible
                    // error surface; the turn persisted nothing, so the
                    // discarded-turn signal fires instead.
                    reply.recordOutcome(PipelineBenchmarkOutcome.CANCELLED, "superseded_turn")
                    actions.cancel()
                    telemetry?.finishProgress()
                    diagnostics.summary("Superseded turn ${ownership.sequence} error dropped: " +
                        "${error.javaClass.simpleName}: ${error.message?.take(200)}")
                    emitDiscarded()
                    return@launch
                }
                failed = true
                reply.recordOutcome(PipelineBenchmarkOutcome.ERROR, error.javaClass.simpleName)
                actions.cancel()
                telemetry?.finishProgress()
                input.comparison?.put("generation_error", error.message ?: error.javaClass.simpleName)
                runCatching { input.incrementalVoice?.close() }.exceptionOrNull()?.let {
                    if (it !== error) error.addSuppressed(it)
                }
                runCatching { modelSession.close() }.exceptionOrNull()?.let {
                    if (it !== error) error.addSuppressed(it)
                }
                diagnostics.record("Turn failed\nuser=${input.prompt.take(1_000)}\nimageAttached=${input.imageUri != null}\n" +
                    "error=${error.stackTraceToString().take(4_000)}")
                reply.postFinish(conversationFailureMessage(modelSession.isNativeQuarantined, error))
            } finally {
                try {
                    if (input.nativeSpeculation?.closeAndDrain() == false)
                        throw com.battlesbudz.jarvis.v2.voice.SpeculativeResponseCoordinator.Quarantined(null)
                    capture.mark("request_processing_finished")
                    reply.finishBenchmark()
                    // The resident backend is shared: only the turn that
                    // still owns the sequence may clear its callbacks.
                    if (ownership.isCurrent()) {
                        if (ownsBenchmark) ownedBackend?.onBenchmarkSubmission = {}
                        modelSession.residentBackend?.onInferenceProgress = {}
                        if (input.voiceAudio == null && !input.callOwned) modelSession.residentBackend?.onPromptSubmitted = { _, _ -> }
                    }
                    input.incrementalVoice?.close()
                } finally {
                    // Clear the native-mutation fence only if it is still
                    // this turn's; a replacement turn's fence stays.
                    if (modelSession.beforeNativeMutation === installedFence) modelSession.beforeNativeMutation = {}
                    runCatching { activity?.close() }
                }
                // Start the bounded visible error after cleanup, before releasing admission.
                if (failed && ownership.isCurrent()) runCatching { onTurnFailure(conversationId) }
            }
        }
        job.invokeOnCompletion {
            ConversationWork.activeJobs.decrementAndGet()
            ownership.guard.release(ownership.sequence)
        }
        return job
    }

    private suspend fun finishDraft(draft: ConversationDraft, ownership: TurnOwnership,
                                    emitDiscarded: () -> Unit) {
        // M7: a superseded turn publishes nothing and writes no shared
        // session state; it persisted nothing, so signal the discard.
        if (!ownership.isCurrent()) {
            diagnostics.summary("Superseded turn ${ownership.sequence} draft dropped before publication")
            emitDiscarded()
            return
        }
        val answer = draft.answer
        val input = answer.invocation
        if (!draft.rawControlOutput) modelSession.addCharacters(answer.submittedPrompt.length + draft.generated.text.length)
        val previous = input.history.asReversed().firstOrNull { it.role == "Jarvis" }?.text?.trim()
        val final = ConversationFinalizer.resolve(draft.cleanedResponse, previous, draft.actionResultMessage,
            draft.actionResultMessage == null && actionIntent(input.prompt, input.history), draft.actionName)
        if (final.repeatedFragment) { modelSession.reset(); draft.containsCurrentTurn = false }
        if (answer.prepared.memoryContext?.let(contexts.memory::isCurrent) == false) {
            contexts.memory.deliveryFence.invalidate()
            modelSession.reset()
            modelSession.clearSummary()
            answer.reply.postFinish("Memory changed while I was responding. Please ask again.")
            return
        }
        recordResponse(input.prompt, final.text, answer.routed.plan)
        modelSession.hasContext = draft.containsCurrentTurn
        diagnostics.important("Turn\nuser=${input.prompt.take(1_000)}\nhistoryEntries=${input.history.size}\n" +
            "action=${draft.actionName ?: "none"}\nactionResult=${draft.actionResultMessage ?: "none"}\n" +
            "generatedLength=${draft.generated.text.length}\ncleaned=${draft.cleanedResponse.take(4_000)}\n" +
            "raw=${draft.generated.text.take(4_000)}\nrepeatedFragment=${final.repeatedFragment}\n" +
            "conversationCharacters=${modelSession.characters}")
        answer.reply.postFinish(final.text)
        // A direct-audio turn that produced a native tool plan ends
        // call-session reuse: the next call turn re-establishes a fresh
        // conversation instead of appending behind a tool exchange.
        if (answer.directAudio && draft.actionName != null) modelSession.reset()
    }
}
