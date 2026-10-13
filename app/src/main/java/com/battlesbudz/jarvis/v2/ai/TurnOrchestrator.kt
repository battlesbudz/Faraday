package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan

enum class TurnKind {
    NORMAL_CHAT,
    FACTUAL_LOCAL_FIRST,
    EXPLICIT_LOOKUP,
    LOOKUP_CONFIRMATION
}

data class TurnPlan(
    val kind: TurnKind,
    val lookupQuery: String? = null,
    val activeSubject: String? = null,
    val actionPlan: ActionTurnPlan = ActionTurnPlan.NotAction,
    val resolvedQuestion: String? = null
)

class TurnOrchestrator(
    private val grounding: ReferenceGroundingClient
) {
    private var pendingLookupSubject: String? = null
    private var activeSubjectQuestion: String? = null
    private var activeSubject: String? = null

    fun reset() {
        pendingLookupSubject = null
        activeSubjectQuestion = null
        activeSubject = null
    }

    fun plan(prompt: String, history: List<Pair<String, String>> = emptyList()): TurnPlan {
        val actionPlan = ActionTurnPlan.parse(prompt, history.map { ChatEntry(it.first, it.second) })
        if (actionPlan !is ActionTurnPlan.NotAction) {
            pendingLookupSubject = null; activeSubject = null; activeSubjectQuestion = null
            return TurnPlan(TurnKind.NORMAL_CHAT, actionPlan = actionPlan)
        }
        if (Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).matches(prompt.trim())) {
            val preceding = com.battlesbudz.jarvis.v2.chat.TurnContinuity.precedingSubstantiveRequest(prompt, history)
            val question = preceding ?: "Summarize the supplied document"
            val domain = com.battlesbudz.jarvis.v2.chat.TurnContinuity.lookupContext(question, history)
            return TurnPlan(TurnKind.FACTUAL_LOCAL_FIRST,
                lookupQuery = question + (domain?.let { "\nConversation domain: $it" } ?: "") + "\n" + prompt,
                activeSubject = question)
        }
        val confirmation = grounding.isLookupConfirmation(prompt)
        val explicit = grounding.isExplicitLookupRequest(prompt)
        val dialogue = DialogueContextPolicy.resolve(prompt, history)
        if (!explicit && (dialogue.recall || dialogue.storyInstruction != null)) {
            pendingLookupSubject = null
            activeSubject = null
            activeSubjectQuestion = null
            return TurnPlan(TurnKind.NORMAL_CHAT)
        }
        // Rebuild from user text after the transcript's privacy/topic boundary.
        // Voice intake may plan the same utterance before generation; planning
        // twice must not overwrite its correction with the correction itself.
        var context = TurnQuestionPolicy.Context(activeSubjectQuestion, activeSubject)
        if (history.isNotEmpty()) {
            context = TurnQuestionPolicy.Context()
            val prior = if (history.last().first == "You" && history.last().second == prompt) history.dropLast(1) else history
            for ((role, text) in com.battlesbudz.jarvis.v2.chat.TurnContinuity.currentTopicHistory(prior).takeLast(128)) {
                if (role == "You" && !com.battlesbudz.jarvis.v2.memory.MemoryPolicy.containsRawRestrictedContent(text)) {
                    context = TurnQuestionPolicy.resolve(text, context, grounding::shouldAutomaticallyLookup, grounding::isExplicitLookupRequest)
                }
            }
        }
        if (confirmation && pendingLookupSubject != null && context.question != null) {
            return TurnPlan(TurnKind.LOOKUP_CONFIRMATION, pendingLookupSubject,
                context.subject, resolvedQuestion = context.question)
        }
        val resolved = TurnQuestionPolicy.resolve(prompt, context, grounding::shouldAutomaticallyLookup, grounding::isExplicitLookupRequest)
        if (!confirmation && resolved.question != context.question) pendingLookupSubject = null
        activeSubject = resolved.subject
        activeSubjectQuestion = resolved.question
        val factualPrompt = resolved.question
        if (explicit) {
            val question = factualPrompt ?: com.battlesbudz.jarvis.v2.chat.TurnContinuity.precedingSubstantiveRequest(prompt, history)
            val query = question?.let { queryFor(it, resolved.subject, history) }
                ?: grounding.buildExplicitLookupQuery(prompt, null) ?: prompt
            return TurnPlan(TurnKind.EXPLICIT_LOOKUP, query, activeSubject,
                resolvedQuestion = factualPrompt)
        }
        val kind = if (factualPrompt != null && grounding.shouldAutomaticallyLookup(factualPrompt)) {
            TurnKind.FACTUAL_LOCAL_FIRST
        } else TurnKind.NORMAL_CHAT
        val query = if (kind == TurnKind.FACTUAL_LOCAL_FIRST) queryFor(factualPrompt!!, activeSubject, history) else null
        return TurnPlan(kind, query, activeSubject, resolvedQuestion = factualPrompt)
    }

    private fun queryFor(question: String, subject: String?, history: List<Pair<String, String>>): String {
        val factualQuestion = question.replace(Regex("(?i)^no[,!]?\\s+"), "")
        val entityQuestion = Regex("(?i)\\b(?:who is|who was|who|what is|what was|tell me about|information about)\\b").containsMatchIn(factualQuestion.trim())
        val query = if (entityQuestion && factualQuestion.length <= 100) subject ?: factualQuestion else factualQuestion
        return com.battlesbudz.jarvis.v2.chat.TurnContinuity.lookupContext(factualQuestion, history)?.let {
            query + "\nConversation domain: " + it
        } ?: query
    }

    fun automaticFallbackQuery(prompt: String): String =
        activeSubject?.let { it + "\n" + prompt } ?: prompt

    fun recordResponse(prompt: String, response: String, plan: TurnPlan) {
        val normalized = response.lowercase()
        if (plan.kind == TurnKind.EXPLICIT_LOOKUP ||
            plan.kind == TurnKind.LOOKUP_CONFIRMATION
        ) {
            pendingLookupSubject = null
            return
        }
        if (grounding.isInsufficientAnswer(response) ||
            normalized.contains("would you like me to search wikipedia") ||
            normalized.contains("would you like me to search wikidata")
        ) {
            pendingLookupSubject = plan.lookupQuery ?: activeSubject ?: prompt
        } else {
            pendingLookupSubject = null
        }
    }

    /** An offer that was generated but never spoken cannot arm a later "yes". */
    fun reconcileVoiceDelivery(spoken: String) {
        val normalized = spoken.lowercase()
        pendingLookupSubject = if (spoken.isNotBlank() && (grounding.isInsufficientAnswer(spoken) ||
            normalized.contains("would you like me to search wikipedia") ||
            normalized.contains("would you like me to search wikidata"))) activeSubjectQuestion ?: activeSubject else null
    }

    fun pendingSubjectForDiagnostics(): String? = pendingLookupSubject

}
