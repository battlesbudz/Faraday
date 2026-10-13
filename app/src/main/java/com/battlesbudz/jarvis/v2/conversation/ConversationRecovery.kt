package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ai.FactualityVerifier
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.chat.AnswerQualityPolicy
import com.battlesbudz.jarvis.v2.chat.AssistantText
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
        recoverLocalFactuality(draft)
        discardControlContext(draft)
        recoverReferenceGap(draft)
        recoverRepetition(draft)
    }

    private suspend fun recoverLocalFactuality(draft: ConversationDraft) {
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
                draft.generated = if (imageBytes != null) {
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
        draft.rawControlOutput = draft.generated.toolCalls.isNotEmpty() || draft.generated.text.contains("tool_call>") ||
            draft.generated.text.contains("start_function_call")
        if (draft.rawControlOutput) {
            // Do not carry protocol text into the next turn.
            resetNative()
            draft.containsCurrentTurn = false
        }
    }

    private suspend fun recoverReferenceGap(draft: ConversationDraft) {
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
                draft.generated = if (imageBytes != null) {
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
            draft.cleanedResponse = "I couldn't produce a verified answer from the available reference evidence. Please try again."
        }
    }

    private suspend fun recoverRepetition(draft: ConversationDraft) {
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
}
