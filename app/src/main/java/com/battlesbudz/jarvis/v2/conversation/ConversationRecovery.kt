package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ai.FactualityVerifier
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.chat.AnswerQualityPolicy
import com.battlesbudz.jarvis.v2.chat.AssistantText
import com.battlesbudz.jarvis.v2.chat.ToolCallMarkup
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard
import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionRepair
import kotlinx.coroutines.CancellationException

/** Bounded read-only factuality/reference/repetition recovery; never admits or dispatches actions. */
internal class ConversationRecovery(
    private val resetNative: suspend () -> Unit,
    private val references: ConversationReferences,
    private val factualityVerifier: FactualityVerifier,
    private val automaticFallbackQuery: (String) -> String,
    private val diagnostics: ConversationDiagnostics
) {
    suspend fun recover(draft: ConversationDraft) {
        // M7 turn guard: every stage below can reset the shared native
        // conversation (resetNative) or regenerate into it. Each stage
        // re-checks ownership at entry, so a turn superseded mid-recovery
        // aborts silently before its next reset instead of tearing down
        // its replacement's session.
        draft.answer.turnOwnership?.checkCurrent()
        recoverLocalFactuality(draft)
        discardControlContext(draft)
        recoverReferenceGap(draft)
        recoverRepetition(draft)
        discardAudioAccessRefusal(draft)
        discardVisionPerceptionClaim(draft)
        discardMemoryDisregard(draft)
        noteMemoryCompleteness(draft)
    }

    private suspend fun recoverLocalFactuality(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        val answer = draft.answer
        val prompt = answer.invocation.prompt
        val engine = answer.engine
        val reply = answer.reply
        val turnPrompt = answer.turnPrompt
        val promptHistory = answer.promptHistory
        val streamFilter = answer.streamFilter
        val imageBytes = answer.imageBytes
        val directAudio = answer.directAudio
        val voiceAudio = answer.invocation.voiceAudio
        fun recordInference(label: String, result: GenerationResult) =
            answer.telemetry.record(label, result, answer.submittedPrompt.length)
        val referenceContext = answer.prepared.referenceContext
        val personalMemoryRecall = answer.prepared.personalMemoryRecall
        val turnPlan = answer.routed.plan
        val actionName = draft.actionName
        val localAnswer = AssistantText.forDisplay(draft.generated.text)
        val isFactualQuestion =
            referenceContext == null &&
                actionName == null &&
                turnPlan.kind == TurnKind.FACTUAL_LOCAL_FIRST && !personalMemoryRecall
        var verifierRequestsLookup = false
        if (isFactualQuestion &&
            !references.isInsufficientAnswer(localAnswer)
        ) {
            // This pass is internal and never reaches the user. It
            // catches confident-looking entity or historical errors
            // that phrase matching cannot detect.
            resetNative()
            engine.benchmarkPurpose = PipelineBenchmarkPurpose.DRAFT
            val verdict = engine.generate(
                prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                onToken = {}
            )
            recordInference("factuality check", verdict)
            verifierRequestsLookup = factualityVerifier.requestsLookup(verdict.text)
            // The verifier is an isolated internal pass. Do not leave
            // its prompt in the user conversation.
            resetNative()
            draft.containsCurrentTurn = false
        }
        val shouldUseAutomaticFallback =
            isFactualQuestion &&
                (references.isInsufficientAnswer(localAnswer) ||
                    verifierRequestsLookup)
        if (shouldUseAutomaticFallback) {
            val fallbackQuery = automaticFallbackQuery(prompt)
            val fallbackStarted = System.nanoTime()
            val fallbackContext = references.fetch(fallbackQuery)
            reply.lookupMs += (System.nanoTime() - fallbackStarted) / 1_000_000
            if (!fallbackContext.isNullOrBlank()) {
                resetNative()
                val fallbackPrompt = turnPrompt.build(
                    prompt,
                    null,
                    promptHistory,
                    seedContext = true
                ) + "\n\n" + fallbackContext
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
                draft.generated = if (directAudio) {
                    // The question can be about the retained audio itself.
                    // Resubmit it, as the original generation's retry does,
                    // instead of asking for an answer about audio the model
                    // can no longer hear.
                    engine.generateAudio(
                        fallbackPrompt,
                        requireNotNull(voiceAudio),
                        streamFilter::accept
                    )
                } else if (imageBytes != null) {
                    engine.generate(
                        prompt = fallbackPrompt,
                        imageBytes = imageBytes,
                        onToken = streamFilter::accept
                    )
                } else {
                    engine.generate(
                        prompt = fallbackPrompt,
                        onToken = streamFilter::accept
                    )
                }
                recordInference("reference fallback", draft.generated)
                draft.containsCurrentTurn = true
            }
        }
    }

    private suspend fun discardControlContext(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        draft.rawControlOutput = draft.generated.toolCalls.isNotEmpty() ||
            ToolCallMarkup.contains(draft.generated.text)
        if (draft.rawControlOutput) {
            // Do not carry protocol text into the next turn.
            resetNative()
            draft.containsCurrentTurn = false
        }
    }

    private suspend fun recoverReferenceGap(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        val answer = draft.answer
        val prompt = answer.invocation.prompt
        val engine = answer.engine
        val reply = answer.reply
        val turnPrompt = answer.turnPrompt
        val promptHistory = answer.promptHistory
        val streamFilter = answer.streamFilter
        val imageBytes = answer.imageBytes
        fun recordInference(label: String, result: GenerationResult) =
            answer.telemetry.record(label, result, answer.submittedPrompt.length)
        val personalMemoryRecall = answer.prepared.personalMemoryRecall
        val turnPlan = answer.routed.plan
        val voiceRepetitionGuard = answer.repetitionGuard
        val directAudio = answer.directAudio
        val voiceAudio = answer.invocation.voiceAudio
        draft.cleanedResponse = AssistantText.forDisplay(draft.generated.text)
        val requiresReference = (!personalMemoryRecall && turnPlan.kind == TurnKind.FACTUAL_LOCAL_FIRST) ||
            turnPlan.kind == TurnKind.EXPLICIT_LOOKUP ||
            turnPlan.kind == TurnKind.LOOKUP_CONFIRMATION
        if (requiresReference && references.isInsufficientAnswer(draft.cleanedResponse) &&
            (voiceRepetitionGuard?.acceptedSentences ?: 0) == 0) {
            // Never expose a local knowledge-base disclaimer for a
            // person/entity question. Re-query the reference APIs once
            // and regenerate from the fresh evidence before replying.
            val retryQuery = turnPlan.lookupQuery ?: prompt
            val retryLookupStarted = System.nanoTime()
            val retryContext = references.fetch(retryQuery)
            reply.lookupMs += (System.nanoTime() - retryLookupStarted) / 1_000_000
            if (!retryContext.isNullOrBlank()) {
                diagnostics.record(
                    "Reference retry after knowledge-gap draft\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "lookupQuery=${retryQuery.take(1_000)}"
                )
                resetNative()
                voiceRepetitionGuard?.discardPending()
                val retryPrompt = turnPrompt.build(
                    prompt,
                    null,
                    promptHistory,
                    seedContext = true
                ) + "\n\n" + retryContext + "\n\n" +
                    "The previous draft was not acceptable. Answer from the reference evidence above. Do not mention your knowledge base or ask whether to search."
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
                draft.generated = if (directAudio) {
                    // The question can be about the retained audio itself.
                    // Resubmit it, as the original generation's retry does,
                    // instead of asking for an answer about audio the model
                    // can no longer hear.
                    engine.generateAudio(
                        retryPrompt,
                        requireNotNull(voiceAudio),
                        streamFilter::accept
                    )
                } else if (imageBytes != null) {
                    engine.generate(
                        prompt = retryPrompt,
                        imageBytes = imageBytes,
                        onToken = streamFilter::accept
                    )
                } else {
                    engine.generate(
                        prompt = retryPrompt,
                        onToken = streamFilter::accept
                    )
                }
                draft.containsCurrentTurn = true
                recordInference("reference retry", draft.generated)
                draft.cleanedResponse = AssistantText.forDisplay(draft.generated.text)
            }
        }
        if (requiresReference && references.isInsufficientAnswer(draft.cleanedResponse) &&
            (voiceRepetitionGuard?.acceptedSentences ?: 0) == 0) {
            // The lookup came back empty (or the retry did). That is not a
            // dead end: regenerate once from the model's own knowledge, with
            // shaping that forbids turning the empty lookup into the answer.
            diagnostics.record(
                "Reference evidence unavailable; regenerating from local knowledge\\n" +
                    "user=${prompt.take(1_000)}"
            )
            resetNative()
            voiceRepetitionGuard?.discardPending()
            val knowledgePrompt = turnPrompt.build(
                prompt,
                null,
                promptHistory,
                seedContext = true
            ) + "\n\n" +
                "The previous draft was not acceptable because it declined to answer. " +
                "Answer the current question directly from your own knowledge now. " +
                "Do not mention reference material, sources, lookup, searching, or being unable to verify an answer. " +
                "Do not ask whether to search and do not call tools."
            engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
            draft.generated = if (directAudio) {
                // The question can be about the retained audio itself.
                // Resubmit it, as the original generation's retry does,
                // instead of asking for an answer about audio the model
                // can no longer hear.
                engine.generateAudio(
                    knowledgePrompt,
                    requireNotNull(voiceAudio),
                    streamFilter::accept
                )
            } else if (imageBytes != null) {
                engine.generate(
                    prompt = knowledgePrompt,
                    imageBytes = imageBytes,
                    onToken = streamFilter::accept
                )
            } else {
                engine.generate(
                    prompt = knowledgePrompt,
                    onToken = streamFilter::accept
                )
            }
            draft.containsCurrentTurn = true
            recordInference("knowledge fallback", draft.generated)
            draft.cleanedResponse = AssistantText.forDisplay(draft.generated.text)
            // A regenerated draft that still only talks about the missing
            // evidence has not answered. Fall back to a plain knowledge
            // limit, never to a reference-material dead end.
            if (references.isInsufficientAnswer(draft.cleanedResponse) &&
                Regex("(?i)reference|sources?|lookup|search|verif|knowledge base").containsMatchIn(draft.cleanedResponse)) {
                draft.cleanedResponse = "I don't know the answer to that yet."
            }
        }
    }

    private suspend fun recoverRepetition(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        val answer = draft.answer
        val prompt = answer.invocation.prompt
        val engine = answer.engine
        val reply = answer.reply
        val turnPrompt = answer.turnPrompt
        val promptHistory = answer.promptHistory
        val streamFilter = answer.streamFilter
        val imageBytes = answer.imageBytes
        fun recordInference(label: String, result: GenerationResult) =
            answer.telemetry.record(label, result, answer.submittedPrompt.length)
        val benchmark = reply.benchmark
        val referenceContext = answer.prepared.referenceContext
        val voiceRepetitionGuard = answer.repetitionGuard
        val actionResultMessage = draft.actionResultMessage
        val directAudioComparison = answer.directAudio
        val voiceAudio = answer.invocation.voiceAudio
        if (voiceRepetitionGuard != null && actionResultMessage == null) {
            draft.cleanedResponse = try { voiceRepetitionGuard.finish(draft.cleanedResponse) }
            catch (loop: VoiceRepetitionGuard.RunawayLoop) {
                draft.runawayLoopStopped = true
                benchmark.configuration("generation_recovery_reason", "runaway_repetition")
                voiceRepetitionGuard.text
            }
            if (voiceRepetitionGuard.needsRepair) {
                diagnostics.important("Voice repetition blocked: sentences=${voiceRepetitionGuard.suppressedSentences}; streaming one bounded read-only repair.")
                val repairStarted = System.nanoTime()
                val beforeRepair = voiceRepetitionGuard.text.length
                resetNative()
                draft.containsCurrentTurn = false
                try {
                    val repairPrompt = turnPrompt.build(prompt, null, promptHistory, seedContext = true) +
                        "\n" + referenceContext.orEmpty() + "\n" +
                        "\nYour previous draft repeated the user or an earlier reply and was suppressed. " +
                        "Give a NEW direct answer to the CURRENT question in one or two sentences. " +
                        "Already delivered opening: ${voiceRepetitionGuard.text.take(600)}. Continue without repeating it. " +
                        "Do not recap, apologize, quote earlier sentences, or call tools. " +
                        "Resolve follow-ups using the dialogue above. " + AnswerQualityPolicy.repairInstruction("repetition_or_unhelpful_draft")
                    // Disable native tool production as well as keeping repair outside dispatch.
                    engine.setToolsEnabled(false)
                    engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
                    val repair = VoiceRepetitionRepair.run(voiceRepetitionGuard) { emit ->
                        if (directAudioComparison) engine.generateAudio(repairPrompt, requireNotNull(voiceAudio), emit)
                        else engine.generate(prompt = repairPrompt, onToken = emit)
                    }
                    repair.generation?.let { recordInference("repetition repair", it) }
                    diagnostics.important("Voice repetition repair ended reason=${repair.reason} " +
                        "budgetMs=10000 nativeCancellationWaitSeparate=true")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    diagnostics.record("Voice repetition repair failed: ${error.message}")
                } finally {
                    resetNative()
                    draft.containsCurrentTurn = false
                }
                if (voiceRepetitionGuard.text.length == beforeRepair) {
                    voiceRepetitionGuard.beginRepair()
                    for (fallback in listOf("I couldn't produce a fresh answer to that.",
                        "I'm stuck on that question at the moment.", "I don't have a useful new answer yet.")) {
                        voiceRepetitionGuard.accept(fallback)
                        voiceRepetitionGuard.finish()
                        if (voiceRepetitionGuard.text.length > beforeRepair) break
                    }
                }
                draft.cleanedResponse = voiceRepetitionGuard.text
                diagnostics.important("Voice repetition guard: suppressed=${voiceRepetitionGuard.suppressedSentences} " +
                    "acceptedChars=${draft.cleanedResponse.length} repairMs=${(System.nanoTime() - repairStarted) / 1_000_000}")
            }
        }
    }

    /**
     * Publication backstop for direct-audio turns, mirroring the generation
     * stage's guard in [ConversationGeneration]: a cleaned response that
     * claims the audio is inaccessible is always false — every regeneration
     * in this class resubmits the retained audio — so regenerate once with
     * the correction before the finalizer can publish the claim. Runs last,
     * so it also catches a refusal produced by one of the recovery
     * regenerations above. Like the reference stages, it never replaces a
     * reply whose sentences were already accepted for publication.
     */
    private suspend fun discardAudioAccessRefusal(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        val answer = draft.answer
        val voiceAudio = answer.invocation.voiceAudio
        if (!answer.directAudio || voiceAudio == null || draft.actionResultMessage != null) return
        if (!AudioAccessRefusal.isRefusal(draft.cleanedResponse)) return
        if ((answer.repetitionGuard?.acceptedSentences ?: 0) > 0) return
        val engine = answer.engine
        resetNative()
        answer.repetitionGuard?.discardPending()
        val prompt = answer.turnPrompt.build(answer.invocation.prompt, null, answer.promptHistory,
            seedContext = true) + AudioAccessRefusal.CORRECTION
        engine.setToolsEnabled(false)
        engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        // Resubmit the retained audio itself, as the original generation's
        // retry does, instead of asking for an answer about audio the model
        // supposedly cannot hear.
        val regenerated = engine.generateAudio(prompt, voiceAudio, answer.streamFilter::accept)
        answer.telemetry.record("audio refusal guard", regenerated, answer.submittedPrompt.length)
        draft.generated = regenerated
        draft.containsCurrentTurn = true
        val cleaned = AssistantText.forDisplay(regenerated.text)
        draft.cleanedResponse = if (AudioAccessRefusal.isRefusal(cleaned)) {
            diagnostics.record("Audio access refusal guard outcome=fallback stage=recovery " +
                "reason=regeneration_refused_again")
            AudioAccessRefusal.FALLBACK
        } else {
            diagnostics.record("Audio access refusal guard outcome=regenerated stage=recovery")
            cleaned
        }
    }

    /**
     * Publication backstop for the no-vision honesty guard, mirroring
     * [discardAudioAccessRefusal]: a cleaned response that asserts
     * perception on a vision-directed turn whose inputs carried no image
     * and no injected visual-context block is always false, so regenerate
     * once with the correction before the finalizer can publish the
     * claim. Admission is the generation stage's shared test
     * ([VisionPerceptionClaim.triggerFor]), so a direct-audio turn —
     * whose spoken question exists only as audio — is admitted by a
     * concrete visual claim in the response instead. Runs last, so it
     * also catches a claim produced by one of the recovery regenerations
     * above. Like the reference stages, it never
     * replaces a reply whose sentences were already accepted for
     * publication; that ordering is also what keeps the honest fallback
     * from becoming a repetition-guard casualty — a substituted fallback
     * is final text, published by the finalizer rather than re-streamed
     * through the guard, and on the next vision question the generation
     * stage substitutes it before recovery's repetition handling runs.
     * The regeneration resubmits the retained audio on direct-audio
     * turns, exactly as the other recovery stages do; an image turn
     * cannot reach here because the presence check returns first.
     */
    private suspend fun discardVisionPerceptionClaim(draft: ConversationDraft) {
        draft.answer.turnOwnership?.checkCurrent()
        val answer = draft.answer
        if (draft.actionResultMessage != null) return
        if (answer.imageBytes != null || answer.visualContextInjected) return
        // Same shared admission as the generation stage: a vision-directed
        // question with a perception-claiming response, or a direct-audio
        // turn — whose invocation prompt is the audio boilerplate, so the
        // spoken question can never be read here — whose response asserts
        // a concrete visual perception. A claim this stage declines to
        // act on is still recorded, so the evaluation is visible in a
        // diagnostics export instead of the old silence.
        val trigger = VisionPerceptionClaim.triggerFor(
            answer.invocation.prompt, answer.directAudio, draft.cleanedResponse)
        if (trigger == null) {
            if (VisionPerceptionClaim.assertsPerception(draft.cleanedResponse)) {
                diagnostics.record("Vision perception claim guard outcome=not_fired stage=recovery " +
                    "reason=${if (answer.directAudio) "claim_not_concrete" else "question_not_vision_directed"}")
            }
            return
        }
        val triggerName = VisionPerceptionClaim.triggerName(trigger)
        if ((answer.repetitionGuard?.acceptedSentences ?: 0) > 0) {
            diagnostics.record("Vision perception claim guard outcome=not_fired stage=recovery " +
                "reason=sentences_already_accepted trigger=$triggerName")
            return
        }
        val engine = answer.engine
        resetNative()
        answer.repetitionGuard?.discardPending()
        val prompt = answer.turnPrompt.build(answer.invocation.prompt, null, answer.promptHistory,
            seedContext = true) + VisionPerceptionClaim.CORRECTION
        engine.setToolsEnabled(false)
        engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
        val voiceAudio = answer.invocation.voiceAudio
        val regenerated = if (answer.directAudio && voiceAudio != null) {
            engine.generateAudio(prompt, voiceAudio, answer.streamFilter::accept)
        } else {
            engine.generate(prompt = prompt, onToken = answer.streamFilter::accept)
        }
        answer.telemetry.record("vision claim guard", regenerated, answer.submittedPrompt.length)
        draft.generated = regenerated
        draft.containsCurrentTurn = true
        val cleaned = AssistantText.forDisplay(regenerated.text)
        draft.cleanedResponse = if (VisionPerceptionClaim.assertsPerception(cleaned)) {
            diagnostics.record("Vision perception claim guard outcome=fallback stage=recovery " +
                "trigger=$triggerName reason=regeneration_claimed_again")
            VisionPerceptionClaim.FALLBACK
        } else {
            diagnostics.record("Vision perception claim guard outcome=regenerated stage=recovery " +
                "trigger=$triggerName")
            cleaned
        }
    }
    /**
     * Publication backstop for memory turns, mirroring the generation
     * stage's guard in [ConversationGeneration]: a cleaned response that
     * disregards records this turn actually injected (see [MemoryDisregard])
     * is always false, so it must never be published. Recovery cannot
     * resubmit the turn's input the way the generation stage can — the
     * generation guard already had its one regeneration — so substitute the
     * honest fallback rendered from the injected records. Runs last, so it
     * also catches a disregard produced by one of the recovery regenerations
     * above. Like the reference stages, it never replaces a reply whose
     * sentences were already accepted for publication.
     */
    private fun discardMemoryDisregard(draft: ConversationDraft) {
        val answer = draft.answer
        if (draft.actionResultMessage != null) return
        val contents = answer.turnPrompt.injectedMemoryContents()
        if (contents.isEmpty()) return
        if ((answer.repetitionGuard?.acceptedSentences ?: 0) > 0) return
        if (!MemoryDisregard.isDisregard(draft.cleanedResponse, answer.invocation.prompt)) return
        draft.cleanedResponse = MemoryDisregard.fallback(contents)
        diagnostics.record("Memory disregard guard outcome=fallback stage=recovery " +
            "reason=publication_backstop")
    }

    /**
     * Publication-stage companion to the generation stage's completeness
     * guard in [ConversationGeneration]: a draft that became partial only
     * through one of the recovery regenerations above is still published
     * as-is — a partial but honest answer beats a stitched one — but the
     * miss is journaled, so a diagnostics paste shows the guard saw it.
     * Runs after the disregard backstop, so a response that disregards the
     * records is replaced by the records fallback first and this check
     * then sees the fallback, which covers the records by construction.
     * Like the disregard backstop, it never touches a reply whose
     * sentences were already accepted for publication.
     */
    private fun noteMemoryCompleteness(draft: ConversationDraft) {
        val answer = draft.answer
        if (draft.actionResultMessage != null) return
        val contents = answer.turnPrompt.injectedMemoryContents()
        if (contents.isEmpty()) return
        if ((answer.repetitionGuard?.acceptedSentences ?: 0) > 0) return
        val uncovered = MemoryCompleteness.uncoveredRecords(answer.invocation.prompt, contents,
            draft.cleanedResponse)
        if (uncovered.isEmpty()) return
        diagnostics.record("Memory completeness guard outcome=still_partial stage=recovery " +
            "reason=publication_backstop uncovered=${uncovered.size}")
    }
}
