package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.eval.ConversationAdmission
import com.battlesbudz.jarvis.v2.eval.admitConversationTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
        val job = scope.launch(Dispatchers.Default) {
            var failed = false
            var ownedBackend: ConversationBackend? = null
            var telemetry: ConversationInferenceTelemetry? = null
            val activity = runCatching { beginActivity(conversationId) }.getOrNull()
            fun progress(message: String, sequence: Long) { runCatching { activity?.progress(message, sequence) } }
            // Only this admitted invocation may install the native-mutation fence.
            // Unchanged context can promote a running draft; any reset/close must
            // invalidate and drain it before touching the same native owner.
            modelSession.beforeNativeMutation = { input.nativeSpeculation?.beforeNativeMutation() }
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
                    backend, reply, actions, timings)
                progress("Checking your reply", 4)
                recovery.recover(draft)
                finishDraft(draft)
            } catch (cancelled: CancellationException) {
                reply.recordOutcome(PipelineBenchmarkOutcome.CANCELLED, cancelled.javaClass.simpleName)
                actions.cancel()
                telemetry?.finishProgress()
                throw cancelled
            } catch (error: Throwable) {
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
                reply.postFinish(if (modelSession.isNativeQuarantined)
                    "The local model did not stop safely. Restart Jarvis before starting another request."
                else "I could not load the local model: ${error.message ?: "unknown error"}")
            } finally {
                try {
                    if (input.nativeSpeculation?.closeAndDrain() == false)
                        throw com.battlesbudz.jarvis.v2.voice.SpeculativeResponseCoordinator.Quarantined(null)
                    capture.mark("request_processing_finished")
                    reply.finishBenchmark()
                    if (ownsBenchmark) ownedBackend?.onBenchmarkSubmission = {}
                    modelSession.residentBackend?.onInferenceProgress = {}
                    if (input.voiceAudio == null && !input.callOwned) modelSession.residentBackend?.onPromptSubmitted = { _, _ -> }
                    input.incrementalVoice?.close()
                } finally {
                    modelSession.beforeNativeMutation = {}
                    runCatching { activity?.close() }
                }
                // Start the bounded visible error after cleanup, before releasing admission.
                if (failed) runCatching { onTurnFailure(conversationId) }
            }
        }
        job.invokeOnCompletion { ConversationWork.activeJobs.decrementAndGet() }
        return job
    }

    private suspend fun finishDraft(draft: ConversationDraft) {
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
    }
}
