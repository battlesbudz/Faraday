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
import com.battlesbudz.jarvis.v2.vision.PhoneVisionClient
import com.battlesbudz.jarvis.v2.vision.VisionContextFormatter
import com.battlesbudz.jarvis.v2.vision.VisionServerSettings
import com.battlesbudz.jarvis.v2.voice.AudioToolGuard
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import com.battlesbudz.jarvis.v2.work.TurnOwnership
import com.battlesbudz.jarvis.v2.work.TurnSupersededException
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
    /**
     * Vision-server config snapshot for this turn. When it reports
     * enabled with a non-blank token, chat-photo vision goes to the
     * Phone Inference server instead of the in-process models. Read
     * fresh per turn so a Settings change applies without restart.
     */
    private val visionServerConfig: () -> VisionServerSettings.Config? = { null },
) {
    suspend fun generate(invocation: ConversationInvocation, contextLimit: Int,
                         routed: RoutedConversation, prepared: PreparedConversation,
                         promptHistory: List<ChatEntry>, turnPrompt: ConversationPrompt,
                         engine: ConversationBackend, reply: ConversationReply,
                         phoneActions: ConversationActions,
                         telemetry: ConversationInferenceTelemetry,
                         turnOwnership: TurnOwnership? = null): ConversationDraft {
        // M7 turn guard: a turn superseded before generation starts must
        // not touch shared session state at all.
        turnOwnership?.checkCurrent()
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
        if (engine.toolsEnabled != allowTools) {
            turnOwnership?.checkCurrent()
            modelSession.clearContextAccounting()
        }
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
        val visionContext = if (visionImageBytes != null) {
            val serverConfig = visionServerConfig()
            if (serverConfig?.enabled == true) {
                // Phone Inference server: when enabled, the server is
                // authoritative for this turn. A failure surfaces as a
                // failure note instead of silently falling back to
                // in-process vision — otherwise a user testing the server
                // path could never tell whether the server was exercised.
                runCatching {
                    require(serverConfig.token.isNotBlank()) {
                        "Phone Inference server is enabled but no X-Vision-Token is saved. " +
                            "Paste the token from the Phone Inference app into Settings."
                    }
                    val snapshot = PhoneVisionClient().detect(visionImageBytes, serverConfig.token)
                    reply.benchmark.metric("phone_vision_server_ms", snapshot.totalMs.toLong())
                    VisionContextFormatter.formatServerDetections(snapshot.detections, snapshot.totalMs)
                }.getOrElse { error ->
                    diagnostics.summary("Phone Inference server vision failed: ${error.message?.take(200)}")
                    VisionContextFormatter.failureNote(error)
                }
            } else if (visionAssets != null) {
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
        } else null
        // Persistent voice-call conversation: a direct-audio turn whose
        // native conversation already holds this call's persona, call-start
        // memory packet and dialogue submits only the current request; the
        // resident context is not re-prefilled. The tool-policy accounting
        // above has already cleared hasContext when anything this turn reset
        // or flipped the conversation, so hasContext here means the resident
        // context is exactly this call's own.
        val reuseNativeContext = invocation.directVoiceAudio && invocation.comparison == null &&
            modelSession.hasContext
        // Guard and retry paths below reset before regenerating, so they
        // must re-seed from the full prompt; assemble it before switching
        // this turn's assembly to resident mode.
        val fullPromptText = if (reuseNativeContext) {
            turnPrompt.nativeContextResident = false
            turnPrompt.assemble(invocation.prompt, visionContext, promptHistory, true,
                routed.plan.activeSubject, routed.plan.resolvedQuestion, prepared.referenceContext, contextLimit).text
        } else null
        turnPrompt.nativeContextResident = reuseNativeContext
        val submission = turnPrompt.assemble(invocation.prompt, visionContext, promptHistory, !modelSession.hasContext,
            routed.plan.activeSubject, routed.plan.resolvedQuestion, prepared.referenceContext, contextLimit)
        if (reuseNativeContext) diagnostics.summary("Voice session reuse: resident_native_context=true " +
            "promptChars=${submission.text.length} fullPromptChars=${fullPromptText?.length ?: submission.text.length} " +
            "policy=delta_only")
        validatePrompt(invocation, routed, prepared, promptHistory, turnPrompt, submission, contextLimit)
        val directAudio = invocation.directVoiceAudio || invocation.comparison?.request?.path == LiveComparison.Path.GEMMA_DIRECT
        check(invocation.sealedVoiceAudio == null || invocation.voiceAudioIsComplete) { "Incomplete native audio cannot be submitted" }
        val input = ConversationInput(invocation.voiceAudio, directAudio, prepared.textInput, attachments.image, attachments.audio, invocation.sealedVoiceAudio, invocation.nativeSpeculation)
        // Visual input for the no-vision honesty guard: an attached image
        // or an injected visual-context block. Checked against the general
        // turn inputs so the guard ports to the video-call branch's live
        // visual context unchanged.
        val hasVisualInput = input.imageBytes != null || visionContext != null
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
            // Promotion is eligible only when this turn will NOT run the
            // tools-enabled authoritative pass: speculative drafts are
            // tool-free by construction, so promoting one on a tools-enabled
            // turn would publish prose and skip the only pass that can emit
            // the tool calls a spoken action needs. A rejected promote still
            // drains the owner, so the fallback below is safe.
            val onToken: (String) -> Unit = { token -> telemetry.token(token); stream.accept(token) }
            val promoted = if (directAudio) input.tryPromote(submission.text, onToken,
                ordinaryReplyAccepted = !allowTools) else null
            if (promoted == null && !directAudio) invocation.nativeSpeculation?.beforeNativeMutation()
            // Last fence before the native policy transition mutates the
            // shared conversation: a superseded turn aborts here instead.
            turnOwnership?.checkCurrent()
            if (engine.setToolsEnabled(allowTools)) modelSession.clearContextAccounting()
            diagnostics.record("Generation tool policy allowed=$allowTools source=current_action_intent")
            promoted ?: try {
                input.generateFallback(engine, submission.text, onToken) { error ->
                    diagnostics.summary("Voice incremental fallback: reason=${error.javaClass.simpleName} " +
                        "message=${error.message?.take(300)} policy=final_text_once audioBytes=0")
                }
            } catch (error: Throwable) {
                // A malformed native tool call aborts the SDK generation
                // before any app parsing runs. It must never kill the turn:
                // recover here, where the tool policy and the input routes
                // are owned, instead of surfacing a generation failure.
                if (!com.battlesbudz.jarvis.v2.ai.ToolCallParseFailure.isFailure(error)) throw error
                recoverFromToolCallParseFailure(engine, input, fullPromptText ?: submission.text, onToken, error,
                    routedPlanSteps = (routed.actionPlan as? ActionTurnPlan.Ready)?.steps?.size ?: 0,
                    turnOwnership = turnOwnership)
            }
        } catch (loop: VoiceRepetitionGuard.RunawayLoop) {
            // Native cancellation/join completes before this boundary; retain only accepted opening.
            stoppedLoop = true
            reply.benchmark.configuration("generation_recovery_reason", "runaway_repetition")
            diagnostics.important("Runaway repetition cancelled before speech publication; preserving valid opening.")
            GenerationResult(guard?.text.orEmpty(), -1L, null)
        } catch (superseded: TurnSupersededException) {
            throw superseded
        } catch (error: Throwable) {
            // M7: a failure that lands after this turn was superseded is
            // dropped silently (as a supersession abort) instead of
            // surfacing over the replacement turn. Real failures of the
            // current turn propagate unchanged. Cancellation keeps its own
            // type so the coordinator can tell a user cancel from an error.
            if (error !is kotlinx.coroutines.CancellationException &&
                turnOwnership != null && !turnOwnership.isCurrent()) {
                throw TurnSupersededException(turnOwnership.sequence)
            }
            throw error
        }
        if (!stoppedLoop) telemetry.record("answer", generated, submission.text.length)
        telemetry.terminal(generated)
        val answer = ConversationAnswer(invocation, routed, prepared, promptHistory, turnPrompt,
            engine, reply, submission.text, attachments.image, directAudio, stream, guard, telemetry,
            visualContextInjected = visionContext != null, turnOwnership = turnOwnership)
        val draft = ConversationDraft(answer, generated, input.nativeConversationContainsTurn,
            runawayLoopStopped = stoppedLoop)
        // M7: a superseded turn dispatches no actions and runs no
        // reset-and-retry guard against its replacement's session.
        turnOwnership?.checkCurrent()
        executeNativeActions(draft, phoneActions)
        val retryPromptBase = fullPromptText ?: submission.text
        retryInvalidToolDraft(draft, input, retryPromptBase)
        guardAudioAccessRefusal(draft, input, retryPromptBase)
        guardVisionPerceptionClaim(draft, input, hasVisualInput, retryPromptBase)
        return draft
    }

    private fun repetitionGuard(invocation: ConversationInvocation, routed: RoutedConversation,
                                prepared: PreparedConversation, reply: ConversationReply,
                                groundedVoice: Boolean): VoiceRepetitionGuard? {
        if (routed.actionPlan !is ActionTurnPlan.NotAction || invocation.imageUri != null || invocation.audioUri != null) return null
        val previous = prepared.history.lastOrNull { it.role == "Jarvis" }?.text
        return VoiceRepetitionGuard(invocation.prompt, previous, emit = { safe ->
            if (invocation.voiceAudio != null && safe.isNotBlank()) reply.benchmark.mark("first_guard_release")
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

    /**
     * Salvage ladder for a native tool-call parse failure, in order:
     * 1. A tolerant salvage of the specific observed malformation (a string
     *    argument value duplicated inside the call) that still validates
     *    against the strict tool schema and the direct-audio tool guard
     *    stands as this turn's tool calls, so [executeNativeActions] runs it
     *    through the normal approval/execution path — but only when the
     *    salvaged call completes the turn's plan by itself. The failed
     *    generation disposed the native conversation, so the session is
     *    reset first; a plan with further steps would then continue by
     *    submitting tool results into that rebuilt conversation, where
     *    neither the user turn nor the assistant tool-call message exists
     *    for the result to answer (the manual tool-calling flow sends a
     *    tool result only after a message that produced the call), so a
     *    validated salvage on a multi-step plan falls through to step 2
     *    instead of executing.
     * 2. Otherwise the malformed call is discarded and the turn regenerates
     *    once with tools disabled, mirroring [retryInvalidToolDraft], so it
     *    completes as a normal text answer.
     * A failure of the regeneration itself propagates: tools are disabled
     * there, so it cannot be another parse failure of the same call.
     */
    private suspend fun recoverFromToolCallParseFailure(engine: ConversationBackend, input: ConversationInput,
                                                        prompt: String, onToken: (String) -> Unit,
                                                        error: Throwable, routedPlanSteps: Int,
                                                        turnOwnership: TurnOwnership? = null): GenerationResult {
        // M7: recovery resets the shared native conversation; a superseded
        // turn must never run it against its replacement's session.
        turnOwnership?.checkCurrent()
        val salvaged = com.battlesbudz.jarvis.v2.ai.MalformedToolCallSalvage.salvage(error)
        val request = salvaged?.let { NativeActionDecoder.decodeStrict(it) }
        if (salvaged != null && request != null && AudioToolGuard.allows(request)) {
            if (routedPlanSteps >= 2) {
                diagnostics.summary("Tool call parse failure salvaged call=${salvaged.name} " +
                    "policy=regenerate_without_tools reason=multi_step_plan_tool_results_need_call_context")
            } else {
                // The failed generation's turn was not accepted: the lifecycle
                // disposed its conversation and marked the history for rebuild,
                // so no native submission can run until the conversation is
                // rebuilt from confirmed history. Reset first, exactly as the
                // discard branch below does before regenerating. The salvaged
                // call completes the plan alone, so no tool-results submission
                // follows its execution and the rebuilt conversation needs no
                // turn context replayed into it.
                modelSession.reset()
                diagnostics.summary("Tool call parse failure salvaged call=${salvaged.name} " +
                    "policy=strict_schema_and_audio_guard_validated nativeConversation=rebuilt")
                return GenerationResult("", -1L, null, toolCalls = listOf(salvaged))
            }
        }
        diagnostics.summary("Tool call parse failure discarded call=${salvaged?.name ?: "unparseable"} " +
            "policy=regenerate_without_tools")
        modelSession.reset()
        engine.setToolsEnabled(false)
        engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        val retryPrompt = prompt + "\nThe previous output contained a tool call that could not be read. " +
            "Answer the user's current message directly as normal text. Do not call a tool."
        return input.retry(engine, retryPrompt, onToken)
    }

    private suspend fun retryInvalidToolDraft(draft: ConversationDraft, input: ConversationInput,
                                              fullPrompt: String) {
        draft.answer.turnOwnership?.checkCurrent()
        if (draft.generated.toolCalls.isEmpty() || draft.actionResultMessage != null ||
            AssistantText.forDisplay(draft.generated.text).isNotBlank()) return
        val answer = draft.answer
        modelSession.reset()
        answer.engine.setToolsEnabled(false)
        diagnostics.summary("Rejected tool response reason=does_not_match_current_intent retryToolsEnabled=false")
        val prompt = fullPrompt + "\nThe previous output contained an invalid tool call. Answer the user's current message directly as normal text. Do not call a tool."
        answer.engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        draft.generated = input.retry(answer.engine, prompt, answer.streamFilter::accept)
        answer.telemetry.record("invalid tool retry", draft.generated, fullPrompt.length)
    }

    /**
     * Final generation-stage check on an audio turn. The draft — whether it
     * came from the first generation or from a tool-failure regeneration
     * above — may claim the audio is inaccessible ("I cannot respond to the
     * audio as I do not have access to the audio content"). That claim is
     * always false here: [ConversationInput.retry] and the first generation
     * alike hand the engine the audio itself, so this is the model refusing
     * despite attached audio, and no downstream stage recognises the text as
     * a failure. Regenerate once, mirroring [retryInvalidToolDraft]'s reset
     * and tool policy, with a correction that names the false claim; the
     * regeneration goes through [ConversationInput.retry], so it carries the
     * same authoritative audio. If the model refuses again, substitute the
     * honest fallback rather than let the turn be answered with the claim.
     */
    private suspend fun guardAudioAccessRefusal(draft: ConversationDraft, input: ConversationInput,
                                                fullPrompt: String) {
        draft.answer.turnOwnership?.checkCurrent()
        if (!input.carriesAudioInput || draft.actionResultMessage != null) return
        if (!AudioAccessRefusal.isRefusal(AssistantText.forDisplay(draft.generated.text))) return
        val answer = draft.answer
        modelSession.reset()
        answer.engine.setToolsEnabled(false)
        answer.engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        val prompt = fullPrompt + AudioAccessRefusal.CORRECTION
        val regenerated = input.retry(answer.engine, prompt, answer.streamFilter::accept)
        answer.telemetry.record("audio refusal retry", regenerated, fullPrompt.length)
        draft.generated = if (AudioAccessRefusal.isRefusal(AssistantText.forDisplay(regenerated.text))) {
            diagnostics.summary("Audio access refusal guard outcome=fallback stage=generation " +
                "reason=regeneration_refused_again")
            GenerationResult(AudioAccessRefusal.FALLBACK, -1L, null)
        } else {
            diagnostics.summary("Audio access refusal guard outcome=regenerated stage=generation")
            regenerated
        }
    }

    /**
     * No-vision honesty guard, mirroring [guardAudioAccessRefusal]. A
     * vision-directed question on a turn with no visual input must never be
     * answered with an invented perception claim: on 31 October 2026 a
     * device build with no vision input at all answered "Can you tell me
     * what you see?" with "I see a room with a window and a desk. This is
     * the primary visual data I am currently processing." Regenerate once,
     * mirroring the refusal guard's reset and tool policy, with a
     * correction that names the false claim; the regeneration goes through
     * [ConversationInput.retry], so it carries the same authoritative
     * inputs. If the model claims perception again, substitute the honest
     * fallback rather than let the turn be answered with the claim. A turn
     * with an image or an injected visual-context block is never guarded —
     * bad recognition is a different problem — and an answer that is
     * honest about not seeing passes untouched. Admission is
     * [VisionPerceptionClaim.triggerFor]: on a direct-audio turn the
     * spoken question is not text the guard can read, so a concrete
     * visual claim in the draft admits the guard on its own (the
     * build-1697 failure mode); this stage runs after
     * [retryInvalidToolDraft], so a claim produced by the tool-failure
     * regeneration is checked here exactly like a first-generation one.
     */
    private suspend fun guardVisionPerceptionClaim(
        draft: ConversationDraft,
        input: ConversationInput,
        hasVisualInput: Boolean,
        fullPrompt: String
    ) {
        draft.answer.turnOwnership?.checkCurrent()
        if (hasVisualInput || draft.actionResultMessage != null) return
        val answer = draft.answer
        val displayText = AssistantText.forDisplay(draft.generated.text)
        // Admission is the detector's shared test: a vision-directed
        // question whose draft asserts perception, or — on a direct-audio
        // turn, where the invocation prompt is the audio boilerplate and
        // the spoken question can never be read as text — a draft that
        // asserts a concrete visual perception. The build-1697 call
        // 443f75e8 slipped both specimen claims past the old question-only
        // gate on exactly that boilerplate. A claim this stage declines
        // to act on is still recorded, so a diagnostics export shows the
        // evaluation instead of the old silence.
        val trigger = VisionPerceptionClaim.triggerFor(
            answer.invocation.prompt, answer.directAudio, displayText)
        if (trigger == null) {
            if (VisionPerceptionClaim.assertsPerception(displayText)) {
                diagnostics.summary("Vision perception claim guard outcome=not_fired stage=generation " +
                    "reason=${if (answer.directAudio) "claim_not_concrete" else "question_not_vision_directed"}")
            }
            return
        }
        val triggerName = VisionPerceptionClaim.triggerName(trigger)
        modelSession.reset()
        answer.engine.setToolsEnabled(false)
        answer.engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        val prompt = fullPrompt + VisionPerceptionClaim.CORRECTION
        val regenerated = input.retry(answer.engine, prompt, answer.streamFilter::accept)
        answer.telemetry.record("vision claim retry", regenerated, fullPrompt.length)
        draft.generated = if (VisionPerceptionClaim.assertsPerception(
                AssistantText.forDisplay(regenerated.text))) {
            diagnostics.summary("Vision perception claim guard outcome=fallback stage=generation " +
                "trigger=$triggerName reason=regeneration_claimed_again")
            GenerationResult(VisionPerceptionClaim.FALLBACK, -1L, null)
        } else {
            diagnostics.summary("Vision perception claim guard outcome=regenerated stage=generation " +
                "trigger=$triggerName")
            regenerated
        }
    }
}
