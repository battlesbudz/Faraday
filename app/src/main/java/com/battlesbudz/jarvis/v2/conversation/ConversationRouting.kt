package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.actions.PhoneActionStatus
import com.battlesbudz.jarvis.v2.actions.PhoneActionStatusReply
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput

/** Routing inputs are explicit; captions/direct audio never acquire tool or lookup authority. */
internal data class ConversationTurnRequest(
    val prompt: String,
    val history: List<ChatEntry>,
    val conversationId: String,
    val imageAttached: Boolean,
    val audioAttached: Boolean,
    val voiceAttached: Boolean,
    val directVoiceAudio: Boolean,
    val comparisonActive: Boolean,
    val incrementalVoice: IncrementalVoiceInput?,
    val frozenActionPlan: ActionTurnPlan.Ready?,
    val frozenVoiceFinal: Boolean
)

internal data class RoutedConversation(
    val history: List<ChatEntry>,
    val plan: TurnPlan,
    val actionPlan: ActionTurnPlan,
    val textInput: IncrementalVoiceInput?
)

/** Authoritative literal-action routing before approved memory, reference lookup or inference. */
internal class ConversationRouting(
    private val historyAfterCutoff: () -> List<ChatEntry>,
    private val turnOrchestrator: TurnOrchestrator,
    private val modelSession: ConversationModelSession,
    private val readPhoneStatus: () -> PhoneActionStatus?,
    private val savePhoneStatus: (PhoneActionStatus) -> Unit,
    private val diagnostics: ConversationDiagnostics
) {
    /** Null means this stage already published a terminal shortcut/rejection. */
    suspend fun route(request: ConversationTurnRequest, reply: ConversationReply,
                      phoneActions: ConversationActions,
                      onPhonePlanFinished: (ActionTurnRunner.Outcome) -> Unit): RoutedConversation? {
        val prompt = request.prompt
        val history = request.history
        val benchmark = reply.benchmark
        val postFinish = reply::postFinish
        // All routing, recall, and summaries must observe the persistent post-memory
        // boundary. Visible transcript stays intact; only prompt context is filtered.
        var safeHistory = historyAfterCutoff().filterNot {
            request.directVoiceAudio && GemmaAudioInputPolicy.isPendingTranscript(it.role, it.text)
        }
        val turnPlan = if (request.directVoiceAudio || request.comparisonActive || request.imageAttached || request.audioAttached) TurnPlan(TurnKind.NORMAL_CHAT)
            else turnOrchestrator.plan(prompt, safeHistory.map { it.role to it.text })
        // Parse the completed request before any direct shortcut, lookup, or model side effect.
        val requestedActionPlan = request.frozenActionPlan ?: turnPlan.actionPlan
        diagnostics.record("Action route plan=${requestedActionPlan.javaClass.simpleName} " +
            "steps=${(requestedActionPlan as? ActionTurnPlan.Ready)?.steps?.map { it.request.name } ?: emptyList<String>()} " +
            "lookup=${turnPlan.lookupQuery != null} " +
            "build=${BuildConfig.VERSION_CODE} user=${prompt.take(1000)} " +
            "condition=${(requestedActionPlan as? ActionTurnPlan.Ready)?.batteryCondition} " +
            "reason=${(requestedActionPlan as? ActionTurnPlan.Rejected)?.reason}")
        if (requestedActionPlan is ActionTurnPlan.Rejected) {
            reply.outcome = PipelineBenchmarkOutcome.REJECTED
            reply.failure = "action_plan_rejected"
            request.incrementalVoice?.close()
            modelSession.reset()
            val rejection = requestedActionPlan.reason
            turnOrchestrator.recordResponse(prompt, rejection, turnPlan)
            postFinish(rejection)
            return null
        }
        val guardedFrozenVoicePlan = requestedActionPlan as? ActionTurnPlan.Ready
        if (request.frozenVoiceFinal && guardedFrozenVoicePlan != null &&
            !guardedFrozenVoicePlan.steps.all { step ->
                FinalVoiceToolGuard.allows(
                    step.sourceClause, step.request.name, step.request.arguments)
            }) {
            reply.outcome = PipelineBenchmarkOutcome.REJECTED
            reply.failure = "final_voice_guard_rejected"
            request.incrementalVoice?.close()
            modelSession.reset()
            val rejection = "I couldn't verify that final spoken phone request. Please say it again."
            diagnostics.important("Voice action rejected: final source-clause guard failed")
            postFinish(rejection)
            return null
        }
        var preparedTextInput = request.incrementalVoice?.takeIf { !request.imageAttached && requestedActionPlan !is ActionTurnPlan.Ready }
        if (preparedTextInput == null) request.incrementalVoice?.close()
        // The whole literal request is validated before any effect. Text and final voice
        // use the same native path, including models which do not emit function calls.
        if (guardedFrozenVoicePlan != null) {
            val plan = guardedFrozenVoicePlan
            if ((request.voiceAttached || request.frozenVoiceFinal) && !plan.steps.all { step ->
                    FinalVoiceToolGuard.allows(
                        step.sourceClause, step.request.name, step.request.arguments)
                }) {
                postFinish("I couldn't verify that final spoken phone request. Please say it again.")
                return null
            }
            modelSession.reset()
            val outcome = phoneActions.runValidated(plan, benchmark)
            if (!outcome.completed) {
                reply.outcome = PipelineBenchmarkOutcome.ERROR
                reply.failure = "tool_execution_incomplete"
            }
            savePhoneStatus(PhoneActionStatus(
                request.conversationId, prompt, outcome.message))
            onPhonePlanFinished(outcome)
            diagnostics.important("Action turn\nbuild=${BuildConfig.VERSION_CODE} user=${prompt.take(1000)}\n" +
                "steps=${plan.steps.map { it.request }} condition=${plan.batteryCondition} matched=${outcome.conditionMatched}\n" +
                "conditionResult=${outcome.conditionResult?.message}\n" +
                "receipts=${outcome.receipts.map { it.request to (it.result.outcome to it.result.message) }}\n" +
                "completed=${outcome.completed} result=${outcome.message}")
            turnOrchestrator.recordResponse(prompt, outcome.message, turnPlan)
            postFinish(outcome.message)
            return null
        }
        val phoneStatusReply = if (!request.directVoiceAudio && !request.imageAttached && !request.audioAttached && !request.comparisonActive)
            PhoneActionStatusReply.resolve(prompt, request.conversationId,
                history.map { it.role to it.text }, readPhoneStatus()) else null
        if (phoneStatusReply != null) {
            request.incrementalVoice?.close()
            modelSession.reset()
            diagnostics.important("Phone status follow-up: source=executor_receipt modelInvoked=false")
            postFinish(phoneStatusReply)
            return null
        }
        if (!modelSession.verifyIntegrity()) {
            request.incrementalVoice?.close()
            modelSession.close()
            error("The selected model file changed or failed integrity verification. Re-import it.")
        }
        return RoutedConversation(safeHistory, turnPlan, requestedActionPlan, preparedTextInput)
    }
}
