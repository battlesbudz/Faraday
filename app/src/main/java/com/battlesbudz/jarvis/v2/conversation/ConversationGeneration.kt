package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.NativeActionDecoder
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.chat.AnswerQualityPolicy
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.vision.NativeVision
import com.battlesbudz.jarvis.v2.vision.VisionContextFormatter
import com.battlesbudz.jarvis.v2.voice.AudioToolGuard
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import java.io.InputStream
import org.json.JSONObject

/** Owns draft streaming, native input selection and strictly journaled compatibility tool calls. */
internal class ConversationGeneration(
    private val modelSession: ConversationModelSession,
    private val references: ConversationReferences,
    private val promptBuilder: ConversationPromptBuilder,
    private val openAttachment: (Uri) -> InputStream?,
    private val diagnostics: ConversationDiagnostics,
    /**
     * M1d: model-proposed screen mutations never auto-dispatch (D23); the
     * runtime parks them for the user's explicit approval instead.
     */
    private val onNeedsApproval: (suspend (ActionRequest) -> ExecutionResult)? = null,
    /**
     * App assets for the native on-device vision models. Null disables the
     * vision context injection (existing behavior, e.g. unit tests).
     */
    private val appAssets: android.content.res.AssetManager? = null,
) {
    suspend fun generate(invocation: ConversationInvocation, contextLimit: Int,
                         routed: RoutedConversation, prepared: PreparedConversation,
                         promptHistory: List<ChatEntry>, turnPrompt: ConversationPrompt,
                         engine: ConversationBackend, reply: ConversationReply,
                         phoneActions: ConversationActions,
                         telemetry: ConversationInferenceTelemetry): ConversationDraft {
        telemetry.attach(engine, prepared.history, prepared.captureReceipt)
        val allowTools = invocation.comparison == null && modelSession.selectedModel().supportsTools &&
            (routed.actionPlan is ActionTurnPlan.Ready || invocation.directVoiceAudio)
        // Split the tool-policy transition. The accounting half applies now:
        // when the policy will flip, the native conversation is about to reset,
        // so this turn's prompt must re-seed history (matching the speculation
        // preview, which always seeds). The native half — engine.setToolsEnabled,
        // whose reset would mutate the conversation under an in-flight
        // speculative draft — is deferred to the generation step below, after
        // the speculative owner has drained via promote/settle.
        if (engine.toolsEnabled != allowTools) modelSession.clearContextAccounting()
        val groundedVoice = invocation.voiceAudio != null && !prepared.referenceContext.isNullOrBlank()
        val guard = repetitionGuard(invocation, routed, prepared, reply, groundedVoice)
        if (invocation.voiceAudio != null) diagnostics.record("Voice sentence streaming: " +
            "suppliedReference=$groundedVoice actionGuard=$allowTools")
        val stream = AssistantStreamFilter { safe ->
            if ((routed.plan.kind == TurnKind.NORMAL_CHAT || groundedVoice) && routed.actionPlan !is ActionTurnPlan.Ready) {
                if (guard != null) guard.accept(safe) else reply.postToken(safe)
            }
        }
        val attachments = readConversationAttachments(invocation.imageUri, invocation.audioUri, reply.benchmark, openAttachment)
        // Native on-device vision: when the turn carries an image, run all
        // four models in-process and fuse the structured detections into
        // Gemma's prompt as authoritative context (replaces the old Termux
        // HTTP path). One message combines detection + Gemma's understanding.
        val visionAssets = appAssets
        val visionImageBytes = attachments.image
        val visionContext = if (visionAssets != null && visionImageBytes != null) {
            runCatching {
                val snapshot = NativeVision.analyze(visionImageBytes, visionAssets)
                reply.benchmark.metric("native_vision_ms", snapshot.totalMs.toLong())
                VisionContextFormatter.format(snapshot)
            }.getOrElse { error ->
                diagnostics.summary("Native vision failed: ${error.message?.take(200)}")
                // Surface the failure instead of swallowing it: the model (and
                // user) should know the structured on-device analysis did not
                // run. The raw image remains attached as fallback.
                VisionContextFormatter.failureNote(error)
            }
        } else null
        val submission = turnPrompt.assemble(invocation.prompt, visionContext, promptHistory, !modelSession.hasContext,
            routed.plan.activeSubject, routed.plan.resolvedQuestion, prepared.referenceContext, contextLimit)
        validatePrompt(invocation, routed, prepared, promptHistory, turnPrompt, submission, contextLimit)
        val directAudio = invocation.directVoiceAudio || invocation.comparison?.request?.path == LiveComparison.Path.GEMMA_DIRECT
        check(invocation.sealedVoiceAudio == null || invocation.voiceAudioIsComplete) { "Incomplete native audio cannot be submitted" }
        val input = ConversationInput(invocation.voiceAudio, directAudio, prepared.textInput, attachments.image, attachments.audio, invocation.sealedVoiceAudio, invocation.nativeSpeculation)
        if (invocation.sealedVoiceAudio != null) {
            reply.benchmark.configuration("native_audio_input_path", "incremental_encoder_sealed_conversation_v1")
            reply.benchmark.metric("native_audio_pcm_samples", invocation.sealedVoiceAudio.pcmSampleCount)
            reply.benchmark.metric("native_audio_token_rows", invocation.sealedVoiceAudio.audioTokenCount)
        }
        telemetry.begin(engine)
        diagnostics.summary("Inference input: mode=" +
            (if (directAudio) if (invocation.directVoiceAudio) "gemma_direct_audio" else "diagnostic_direct_audio"
                else if (prepared.textInput != null) "incremental_text" else if (invocation.voiceAudio != null) "voice_text"
                else if (attachments.image != null) "image_text" else if (attachments.audio != null) "audio_file_text" else "text") +
            " audioBytes=${if (directAudio) invocation.voiceAudio?.size ?: 0 else attachments.audio?.size ?: 0} retainedAudioBytes=${invocation.voiceAudio?.size ?: 0} promptChars=${submission.text.length}" +
            " nativeAudioEncodeMs=${if (directAudio || attachments.audio != null) "unavailable" else "not_used"} queueMs=unavailable")
        var stoppedLoop = false
        val generated = try {
            // Promote-first: promote drains the speculative owner (join +
            // rollback) without any native mutation, so the tool-policy
            // transition below can never reset the conversation under an
            // in-flight speculative draft. A non-direct-audio turn can never
            // promote, so settle its speculation explicitly first. Only once
            // the owner has drained is the native policy transition safe.
            // The fallback then runs with the full ordinary audio input and
            // the correct tool policy, never tool-disabled by a spent draft.
            val onToken: (String) -> Unit = { token -> telemetry.token(token); stream.accept(token) }
            val promoted = if (directAudio) input.tryPromote(submission.text, onToken) else null
            if (promoted == null && !directAudio) invocation.nativeSpeculation?.beforeNativeMutation()
            if (engine.setToolsEnabled(allowTools)) modelSession.clearContextAccounting()
            diagnostics.record("Generation tool policy allowed=$allowTools source=current_action_intent")
            promoted ?: input.generateFallback(engine, submission.text, onToken) { error ->
                diagnostics.summary("Voice incremental fallback: reason=${error.javaClass.simpleName} " +
                    "message=${error.message?.take(300)} policy=final_text_once audioBytes=0")
            }
        } catch (loop: VoiceRepetitionGuard.RunawayLoop) {
            // Native cancellation/join completes before this boundary; retain only accepted opening.
            stoppedLoop = true
            reply.benchmark.configuration("generation_recovery_reason", "runaway_repetition")
            diagnostics.important("Runaway repetition cancelled before speech publication; preserving valid opening.")
            GenerationResult(guard?.text.orEmpty(), -1L, null)
        }
        if (!stoppedLoop) telemetry.record("answer", generated, submission.text.length)
        telemetry.terminal(generated)
        val answer = ConversationAnswer(invocation, routed, prepared, promptHistory, turnPrompt,
            engine, reply, submission.text, attachments.image, directAudio, stream, guard, telemetry)
        val draft = ConversationDraft(answer, generated, input.nativeConversationContainsTurn,
            runawayLoopStopped = stoppedLoop)
        executeNativeActions(draft, phoneActions)
        retryInvalidToolDraft(draft, input)
        return draft
    }

    private fun repetitionGuard(invocation: ConversationInvocation, routed: RoutedConversation,
                                prepared: PreparedConversation, reply: ConversationReply,
                                groundedVoice: Boolean): VoiceRepetitionGuard? {
        if (routed.actionPlan !is ActionTurnPlan.NotAction || invocation.imageUri != null || invocation.audioUri != null) return null
        val previous = prepared.history.lastOrNull { it.role == "Jarvis" }?.text
        return VoiceRepetitionGuard(invocation.prompt, previous, emit = { safe ->
            reply.postToken(if (invocation.voiceAudio != null) VoiceRepetitionGuard.speechReady(safe) else safe)
        }).also { guard ->
            guard.preserveFormatting = invocation.voiceAudio == null
            guard.isPublishable = { candidate ->
                val reason = AnswerQualityPolicy.rejection(invocation.prompt, candidate, previous)
                if (reason != null) diagnostics.summary("Answer draft suppressed reason=$reason turn=${reply.id}")
                reason == null && (!groundedVoice || !references.isInsufficientAnswer(candidate))
            }
        }
    }

    private fun validatePrompt(invocation: ConversationInvocation, routed: RoutedConversation,
                               prepared: PreparedConversation, history: List<ChatEntry>, prompt: ConversationPrompt,
                               submission: ConversationPrompt.Submission, limit: Int) {
        val text = submission.text
        if (text.length + ConversationPolicy.GENERATION_HEADROOM > limit) {
            check(modelSession.selectedModel().contextTokens == null) {
                "This request exceeds ${modelSession.selectedModel().id}'s small context budget. Please send a shorter request."
            }
            diagnostics.record("Turn context remains near budget\\n" +
                "userLength=${invocation.prompt.length}\\nsubmittedPromptLength=${text.length}\\naction=continue with concise response")
        }
        if (invocation.voiceAudio == null) return
        val base = prompt.build(invocation.prompt, null, emptyList(), false)
        val subjectChars = routed.plan.activeSubject?.let { ("\n\nResolved subject for this turn: $it").length } ?: 0
        val referenceChars = prepared.referenceContext?.let { it.length + 2 } ?: 0
        val contextChars = (text.length - base.length - subjectChars - referenceChars).coerceAtLeast(0)
        diagnostics.summary("Voice prompt parts totalChars=${text.length} baseAndRequestChars=${base.length} " +
            "contextAndDialogueChars=$contextChars subjectChars=$subjectChars referenceChars=$referenceChars " +
            "audioComplete=${invocation.voiceAudioIsComplete} scope=assembled_answer_prompt prepared=false")
        val latestReply = history.lastOrNull { it.role == "Jarvis" }?.text?.trim()?.take(450)
        val latestUser = history.lastOrNull { it.role == "You" }?.text?.trim()?.take(300)
        diagnostics.summary("Voice context evidence policy=newest_first_v1 seeded=${submission.seeded} historyEntries=${history.size} " +
            "latestUserIncluded=${latestUser?.takeIf { it.isNotBlank() }?.let(text::contains)} " +
            "latestReplyIncluded=${latestReply?.takeIf { it.isNotBlank() }?.let(text::contains)} " +
            "latestReplyChars=${latestReply?.length ?: 0} scope=assembled_prompt nativeContext=${modelSession.hasContext}")
    }

    /**
     * Builds an action plan from the model's native tool calls in direct-audio
     * mode, where no transcript exists for the deterministic router. Each call
     * is strictly decoded and audio-guard validated; invalid calls are dropped.
     * Returns null when no valid calls remain.
     */
    private fun audioPlanFromCalls(draft: ConversationDraft): ActionTurnPlan.Ready? {
        val invocation = draft.answer.invocation
        if (!invocation.directVoiceAudio || draft.generated.toolCalls.isEmpty()) return null
        val steps = draft.generated.toolCalls.mapNotNull { call ->
            val request = NativeActionDecoder.decodeStrict(call) ?: return@mapNotNull null
            if (!AudioToolGuard.allows(request)) return@mapNotNull null
            ActionTurnPlan.Step(request, "[audio input]")
        }.take(3)
        if (steps.isEmpty()) return null
        diagnostics.summary("Audio-native tool plan steps=${steps.map { it.request.name }}")
        return ActionTurnPlan.Ready(steps)
    }

    private suspend fun executeNativeActions(draft: ConversationDraft, phoneActions: ConversationActions) {
        val answer = draft.answer
        val invocation = answer.invocation
        // Settle outstanding speculation before the tool phase can mutate
        // native state: a still-running speculative draft invalidates and
        // drains first. Harmless after a successful promote (already drained).
        invocation.nativeSpeculation?.beforeNativeMutation()
        val plan = answer.routed.actionPlan as? ActionTurnPlan.Ready
            ?: audioPlanFromCalls(draft) ?: return
        if (draft.generated.toolCalls.isEmpty()) {
            draft.actionResultMessage = "I couldn't verify the requested phone actions."
            return
        }
        val allowed = if (invocation.directVoiceAudio) {
            // Audio-native path: no transcript exists, so FinalVoiceToolGuard's
            // spoken-argument verification cannot run. AudioToolGuard applies
            // the allowlist + argument sanity policy instead.
            plan.steps.all { AudioToolGuard.allows(it.request) }
        } else {
            (invocation.voiceAudio == null && !invocation.frozenVoiceFinal) || plan.steps.all { step ->
                FinalVoiceToolGuard.allows(step.sourceClause, step.request.name, step.request.arguments)
            }
        }
        if (!allowed) { draft.actionResultMessage = "I couldn't verify the requested phone actions."; return }
        val outcome = phoneActions.runNative(plan, draft.generated.toolCalls, nextCalls = { batch ->
            val results = batch.map { receipt ->
                ToolCall(receipt.request.name, JSONObject(receipt.request.arguments).toString()) to
                    promptBuilder.buildToolResultContext(invocation.prompt, receipt.request.name, receipt.result.message, receipt.result.succeeded)
            }
            draft.generated = answer.engine.sendToolResults(results, answer.streamFilter::accept)
            answer.telemetry.record("tool response", draft.generated, answer.submittedPrompt.length)
            draft.generated.toolCalls
        }, onNeedsApproval = onNeedsApproval)
        draft.actionName = plan.steps.joinToString(",") { it.request.name }
        draft.actionResultMessage = outcome.message
        diagnostics.important("Action turn\nuser=${invocation.prompt.take(500)}\nsteps=${plan.steps.map { it.request }}\n" +
            "receipts=${outcome.receipts}\ncompleted=${outcome.completed} result=${outcome.message}")
    }

    private suspend fun retryInvalidToolDraft(draft: ConversationDraft, input: ConversationInput) {
        if (draft.generated.toolCalls.isEmpty() || draft.actionResultMessage != null ||
            AssistantText.forDisplay(draft.generated.text).isNotBlank()) return
        val answer = draft.answer
        modelSession.reset()
        answer.engine.setToolsEnabled(false)
        diagnostics.summary("Rejected tool response reason=does_not_match_current_intent retryToolsEnabled=false")
        val prompt = answer.submittedPrompt + "\nThe previous output contained an invalid tool call. Answer the user's current message directly as normal text. Do not call a tool."
        answer.engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        draft.generated = input.retry(answer.engine, prompt, answer.streamFilter::accept)
        answer.telemetry.record("invalid tool retry", draft.generated, answer.submittedPrompt.length)
    }
}
