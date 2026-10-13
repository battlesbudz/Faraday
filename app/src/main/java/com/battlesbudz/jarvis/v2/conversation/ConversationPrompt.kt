package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext

/** Assembles one bounded request without owning the transcript or the native conversation. */
internal class ConversationPrompt(
    private val builder: ConversationPromptBuilder,
    private val voice: Boolean,
    private val contextTokens: () -> Int?,
    private val memoryContext: () -> MemoryTurnContext?
) {
    var continuityContext = ""
    var captureContext = ""

    fun build(userPrompt: String, actionResultContext: String?, history: List<ChatEntry>,
              seedContext: Boolean): String = builder.buildGemmaPrompt(
        userPrompt, actionResultContext, history, seedContext,
        voice = voice, compactInstructions = (contextTokens() ?: 4096) < 2048,
        memoryContext = memoryContext()?.takeIf { it.isCurrent() }?.promptSection(),
        continuityContext = continuityContext, captureContext = captureContext)

    fun pendingSize(prompt: String, actionContext: String?, history: List<ChatEntry>,
                    referenceContext: String?): Int = maxOf(
        build(prompt, actionContext, history, false).length,
        build(prompt, actionContext, history, true).length
    ) + (referenceContext?.length ?: 0)

    data class Submission(val text: String, val seeded: Boolean)

    fun assemble(prompt: String, actionContext: String?, history: List<ChatEntry>,
                 seedContext: Boolean, activeSubject: String?, resolvedQuestion: String?,
                 reference: String?, limit: Int): Submission {
        fun text(seed: Boolean, entries: List<ChatEntry>) = build(prompt, actionContext, entries, seed) +
            activeSubject?.let { "\n\nResolved subject for this turn: $it" }.orEmpty() +
            resolvedQuestion?.let {
                "\n\nResolved current question: $it\nAnswer this question using relevant reference evidence. " +
                    "The raw speech transcript and earlier assistant claims are not verified facts."
            }.orEmpty() + reference?.let { "\n\n$it" }.orEmpty()
        val first = text(seedContext, if (seedContext) history else emptyList())
        return if (first.length + ConversationPolicy.GENERATION_HEADROOM > limit) {
            // Background context must never crowd out the current request or reference evidence.
            Submission(text(false, emptyList()), false)
        } else Submission(first, seedContext)
    }

    companion object {
        fun contextLimit(contextTokens: Int?, imageAttached: Boolean): Int = contextTokens?.let {
            minOf(ConversationPolicy.CONVERSATION_COMPACTION_LIMIT, it * 3 - if (imageAttached) 1800 else 0)
        } ?: ConversationPolicy.CONVERSATION_COMPACTION_LIMIT
    }
}
