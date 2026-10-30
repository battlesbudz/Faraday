package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.LastReplyRecall
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.chat.TurnContinuity
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult
import com.battlesbudz.jarvis.v2.memory.MemoryCaptureAcknowledgment
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput


internal data class PreparedConversation(
    val history: List<ChatEntry>,
    val memoryContext: MemoryTurnContext?,
    val captureReceipt: ConversationMemoryResult?,
    val referenceContext: String?,
    val personalMemoryRecall: Boolean,
    val memoryHistoryInvalidated: Boolean,
    val textInput: IncrementalVoiceInput?
)

/** Reads approved memory and reference evidence at the persistent transcript boundary. */
internal class ConversationContextPreparation(
    private val historyAfterCutoff: () -> List<ChatEntry>,
    val memory: ConversationMemoryAccess,
    private val turnOrchestrator: TurnOrchestrator,
    private val modelSession: ConversationModelSession,
    private val references: ConversationReferences,
    private val diagnostics: ConversationDiagnostics
) {
    /** Pure preview for direct audio only: no routing effects, token adoption,
     * receipt consumption, history cutoff, memory capture, lookup or native reset. */
    fun previewDirectAudio(builder: com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder):
        com.battlesbudz.jarvis.v2.voice.NativeVoicePromptPreview? {
        val limit = ConversationPrompt.contextLimit(modelSession.selectedModel().contextTokens, false)
        val prompt = GemmaAudioInputPolicy.REQUEST
        val context = memory.approvedSnapshot(prompt, (limit / 5).coerceIn(240, 1_200)) ?: return null
        if (!context.isCurrent()) return null
        val history = historyAfterCutoff().filterNot { GemmaAudioInputPolicy.isPendingTranscript(it.role, it.text) }
        val preview = ConversationPrompt(builder, true, { modelSession.selectedModel().contextTokens }, { context })
        preview.continuityContext = TurnContinuity.section(prompt, history.map { it.role to it.text },
            maxChars = (limit / 6).coerceIn(300, 1600))
        // Direct audio does not capture provisional text into memory. Preserve
        // the ordinary null-receipt instruction without consuming any receipt.
        preview.captureContext = MemoryCaptureAcknowledgment.section(null)
        val exact = preview.assemble(prompt, null, history, true, null, null, null, limit).text
        return com.battlesbudz.jarvis.v2.voice.NativeVoicePromptPreview(exact, context::isCurrent)
    }

    suspend fun prepare(request: ConversationTurnRequest, routed: RoutedConversation,
                        reply: ConversationReply, turnPrompt: ConversationPrompt, contextLimit: Int,
                        onMemoryBound: (MemoryDeliveryFence.Ticket, MemoryTurnContext) -> Unit): PreparedConversation? {
        val prompt = request.prompt
        val benchmark = reply.benchmark
        val postFinish = reply::postFinish
        var memoryTurnContext: MemoryTurnContext? = null
        var safeHistory = routed.history
        val turnPlan = routed.plan
        val requestedActionPlan = routed.actionPlan
        var preparedTextInput = routed.textInput
        // Read one approved snapshot only for an ordinary answer, after raw action routing
        // and before lookup or any prompt-size/retry calculation. Pending proposals never
        // invalidate it; an approved mutation fences later tokens and forces a fresh context.
        if (requestedActionPlan !is ActionTurnPlan.Ready) {
            val memoryBudget = (contextLimit / 5).coerceIn(240, 1_200)
            val memoryStarted = System.nanoTime()
            benchmark.mark("memory_retrieval_started")
            memoryTurnContext = memory.approvedSnapshot(prompt, memoryBudget)
            benchmark.mark("memory_retrieval_finished")
            benchmark.metric("memory_retrieval_ms", (System.nanoTime() - memoryStarted) / 1_000_000)
            if (memoryTurnContext == null) {
                // A failed store read must not reuse a resident native conversation seeded
                // with an earlier approved packet. Keep the user-visible failure explicit.
                preparedTextInput?.close()
                preparedTextInput = null
                memory.deliveryFence.invalidate()
                memory.clearNativeToken()
                modelSession.reset()
                postFinish("Memory context is unavailable right now; please try again after storage recovers.")
                return null
            }
            if (memory.adopt(memoryTurnContext!!)) {
                preparedTextInput?.close()
                preparedTextInput = null
                modelSession.reset()
                // Expiry-token adoption can publish a fresh persistent cutoff. Reload
                // before any recall/retry prompt observes retained dialogue.
                safeHistory = historyAfterCutoff().filterNot {
                    request.directVoiceAudio && GemmaAudioInputPolicy.isPendingTranscript(it.role, it.text)
                }
            }
            val memoryDeliveryTicket = memory.deliveryFence.ticket(memoryTurnContext!!.expiresAtMs)
            reply.bindMemory(memoryTurnContext!!, memoryDeliveryTicket)
            onMemoryBound(memoryDeliveryTicket, memoryTurnContext!!)
            val repeatReply = if (!request.imageAttached && !request.audioAttached && !request.voiceAttached)
                LastReplyRecall.resolve(prompt, safeHistory.map { it.role to it.text }) else null
            if (repeatReply != null) {
                modelSession.reset()
                turnOrchestrator.recordResponse(prompt, repeatReply, turnPlan)
                diagnostics.record("Dialogue recall: source=latest_visible_reply chars=${repeatReply.length}")
                postFinish(repeatReply)
                return null
            }
        }
        turnPrompt.continuityContext = TurnContinuity.section(prompt, safeHistory.map { it.role to it.text },
            maxChars = (contextLimit / 6).coerceIn(300, 1600))
        val captureReceipt = memory.takeCaptureReceipt(prompt)
        turnPrompt.captureContext = MemoryCaptureAcknowledgment.section(captureReceipt)
        if (MemoryCaptureAcknowledgment.explicitRequest(prompt) &&
            requestedActionPlan !is ActionTurnPlan.Ready && !request.imageAttached && !request.audioAttached) {
            preparedTextInput?.close()
            modelSession.reset()
            val acknowledgment = captureReceipt?.let(MemoryCaptureAcknowledgment::reply)
                ?: "I couldn't verify that a memory proposal was saved. Please try again."
            turnOrchestrator.recordResponse(prompt, acknowledgment, turnPlan)
            postFinish(acknowledgment)
            return null
        }
        if (TurnContinuity.isCorrection(prompt)) {
            preparedTextInput?.close(); preparedTextInput = null
            modelSession.reset()
        }
        diagnostics.summary("Turn continuity entries=${safeHistory.size} chars=${turnPrompt.continuityContext.length} correction=${TurnContinuity.isCorrection(prompt)} capture=${captureReceipt?.outcome ?: "unavailable"}")
        // Preserve the destination branch's local personal-recall route without
        // suppressing explicit lookup/confirmation or granting tools memory authority.
        val personalMemoryRecall = MemoryTurnContext.shouldUseLocalRecall(
            prompt, memoryTurnContext?.hasApprovedMemories == true,
            explicitLookup = turnPlan.kind == TurnKind.EXPLICIT_LOOKUP ||
                turnPlan.kind == TurnKind.LOOKUP_CONFIRMATION,
            phoneAction = requestedActionPlan is ActionTurnPlan.Ready
        )
        // FACTUAL_LOCAL_FIRST means what it says: the local model drafts
        // first, and the model-driven factuality pass decides afterwards
        // whether a lookup is warranted. Do not prefetch (or dead-end on an
        // empty prefetch) before that draft exists. A supplied source URL is
        // different: its contents are an input to the turn, so fetch it now.
        val suppliedSourceLookup = turnPlan.lookupQuery?.contains("https://") == true
        val automaticLocalFirst = turnPlan.kind == TurnKind.FACTUAL_LOCAL_FIRST && !suppliedSourceLookup
        val lookupAttempted = turnPlan.lookupQuery != null && !personalMemoryRecall && !automaticLocalFirst
        val lookupStarted = System.nanoTime()
        if (lookupAttempted) benchmark.mark("reference_lookup_started")
        val referenceContext = if (lookupAttempted) {
            references.fetch(turnPlan.lookupQuery!!)
        } else null
        // Private mode: an attempted lookup was skipped (no network call).
        // Say so honestly instead of inventing a retrieval failure.
        if (lookupAttempted && references.isPrivateLookupBlocked()) {
            diagnostics.record(
                "Private reference lookup blocked\\n" +
                    "user=${prompt.take(1_000)}\\n" +
                    "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
            )
            postFinish("Web search, Wikipedia, and Wikidata lookups are turned off in private chats to keep this conversation fully offline.")
            return null
        }
        if (lookupAttempted) diagnostics.summary(
            "Voice lookup durationMs=${(System.nanoTime() - lookupStarted) / 1_000_000} success=${!referenceContext.isNullOrBlank()}")

        if (lookupAttempted) {
            benchmark.mark("reference_lookup_finished")
            benchmark.metric("reference_lookup_ms", (System.nanoTime() - lookupStarted) / 1_000_000)
            benchmark.metric("reference_lookup_evidence_found", if (referenceContext.isNullOrBlank()) 0 else 1)
        }
        reply.lookupMs += if (lookupAttempted) (System.nanoTime() - lookupStarted) / 1_000_000 else 0L

        // An attempted lookup that returns no usable evidence splits by
        // turn kind: a supplied source is a retrieval failure (below),
        // while an ordinary question still answers from local knowledge.
        if (lookupAttempted && referenceContext.isNullOrBlank()) {
            if (suppliedSourceLookup) {
                // A supplied source is the turn's input, not a fact the
                // model can recall instead: with nothing retrieved there
                // is no document to summarize or answer from, and falling
                // through would let generation invent its contents. End
                // the turn with an honest retrieval failure, the same
                // shape as the private-mode lookup block above.
                diagnostics.record(
                    "Supplied source lookup returned no evidence; retrieval failed\\n" +
                        "user=${prompt.take(1_000)}\\n" +
                        "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
                )
                postFinish("I couldn't retrieve the page at that link, so I don't have its contents to work from. Please check the link and try again.")
                return null
            }
            // An attempted lookup that returns no usable evidence is not a
            // dead end for an ordinary question. Generation proceeds and
            // answers from the model's own knowledge; the assembled prompt
            // handles how (and whether) the empty lookup may be mentioned
            // for this kind of turn.
            diagnostics.record(
                "Reference lookup returned no evidence; answering locally\\n" +
                    "user=${prompt.take(1_000)}\\n" +
                    "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
            )
        }

        val memoryHistoryInvalidated = requestedActionPlan !is ActionTurnPlan.Ready &&
            memory.consumeHistoryCutoff()
        if (memoryHistoryInvalidated) {
            preparedTextInput?.close()
            preparedTextInput = null
            modelSession.reset()
        }
        val textInput = preparedTextInput
        val effectiveHistory = safeHistory
        return PreparedConversation(effectiveHistory, memoryTurnContext, captureReceipt, referenceContext,
            personalMemoryRecall, memoryHistoryInvalidated, textInput)
    }
}
