package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.harness.HarnessConfig
import com.battlesbudz.jarvis.v2.harness.HarnessTemplate

class ConversationPromptBuilder(
    private val shortTermContext: ShortTermConversationContext,
    /**
     * Editable harness content (self-improving harness project, Phase 1).
     * The instruction wording lives in the harness files now; assembly
     * order, the append-only voice prefix rule, and every structural
     * decision stay in this class. With the shipped v1 content the output
     * is byte-identical to the previously hardcoded strings.
     */
    private val harness: () -> HarnessConfig = { HarnessConfig.DEFAULT }
) {
    // Keep the prompt policy centralized so mobile turns can stay concise.
    fun buildGemmaPrompt(
        userPrompt: String,
        actionResultContext: String?,
        history: List<ChatEntry>,
        seedContext: Boolean,
        voice: Boolean = false,
        compactInstructions: Boolean = false,
        memoryContext: String? = null,
        continuityContext: String? = null,
        captureContext: String? = null
    ): String {
        val instructions = harness().instructions
        val dialogue = DialogueContextPolicy.resolve(userPrompt, history.map { it.role to it.text })
        val dialogueInstruction = if (dialogue.recall)
            instructions.dialogueRecallInstruction
        else dialogue.storyInstruction.orEmpty()
        val actionContext = actionResultContext?.let { "\n\n$it" }.orEmpty()
        // MemoryOS packets are quoted historical evidence, never instructions or tool authority.
        val memorySection = listOfNotNull(memoryContext?.trim()?.takeIf { it.isNotBlank() },
            continuityContext,
            captureContext).filter { it.isNotBlank() }.joinToString("\n\n")
        val sessionContext = if (seedContext) {
            shortTermContext.promptContext(history.map { it.role to it.text }, compact = voice || compactInstructions)
                .let { if (compactInstructions) it.takeLast(instructions.compactContextTailChars) else it }
                .takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        } else ""
        // ASR commits this exact append-only prefix while listening. Context that
        // depends on the final request must follow the current message; inserting
        // it ahead of the message would discard otherwise valid prefetched KV.
        // Memory remains quoted evidence, with the existing delivery/revocation fence.
        if (voice) {
            val supportingContext = memorySection.takeIf { it.isNotBlank() }?.let {
                instructions.supportingContextPrefix + "\n" + it + "\n" + instructions.supportingContextSuffix
            }.orEmpty()
            val suffix = listOf(supportingContext, dialogueInstruction, actionContext.trim())
                .filter { it.isNotBlank() }.joinToString("\n\n")
            return voiceInputPrefix(history, compactInstructions, seedContext) +
                userPrompt + if (suffix.isBlank()) "" else "\n\n$suffix"
        }
        if (compactInstructions) return listOf(
            instructions.compactText,
            sessionContext.trim(), memorySection, "Current user message:\n$userPrompt", dialogueInstruction, actionContext.trim()
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        return HarnessTemplate.render(instructions.mainTemplate, mapOf(
            HarnessConfig.TOKEN_SESSION_CONTEXT to sessionContext,
            HarnessConfig.TOKEN_MEMORY_SECTION to memorySection,
            HarnessConfig.TOKEN_DIALOGUE_INSTRUCTION to dialogueInstruction,
            HarnessConfig.TOKEN_USER_PROMPT to userPrompt,
            HarnessConfig.TOKEN_ACTION_CONTEXT to actionContext
        ))
    }

    fun voiceInputPrefix(history: List<ChatEntry>, compactInstructions: Boolean = false,
                         seedContext: Boolean = true): String = listOf(
        if (compactInstructions) harness().instructions.compactText else com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy.instructions,
        if (seedContext) shortTermContext.promptContext(history.map { it.role to it.text }, compact = true)
            .let { if (compactInstructions) it.takeLast(harness().instructions.compactContextTailChars) else it }.trim() else ""
    ).filter { it.isNotBlank() }.joinToString("\n\n") + "\n\nCurrent user message:\n"

    fun buildToolResultContext(
        userPrompt: String,
        toolName: String,
        resultMessage: String,
        outcome: ExecutionResult.Outcome
    ): String {
        val instructions = harness().instructions
        val statusText = when (outcome) {
            ExecutionResult.Outcome.SUCCEEDED -> instructions.statusSucceeded
            ExecutionResult.Outcome.UNKNOWN_COMPLETION -> instructions.statusUnknownCompletion
            ExecutionResult.Outcome.FAILED -> instructions.statusFailed
            ExecutionResult.Outcome.REJECTED_VALIDATION -> instructions.statusRejectedValidation
            ExecutionResult.Outcome.DENIED_PERMISSION -> instructions.statusDeniedPermission
            ExecutionResult.Outcome.NEEDS_UNLOCK -> instructions.statusNeedsUnlock
        }
        return HarnessTemplate.render(instructions.toolResultTemplate, mapOf(
            HarnessConfig.TOKEN_USER_PROMPT to userPrompt,
            HarnessConfig.TOKEN_TOOL_NAME to toolName,
            HarnessConfig.TOKEN_STATUS_TEXT to statusText,
            HarnessConfig.TOKEN_RESULT_MESSAGE to resultMessage
        ))
    }

    /** Legacy overload: maps the old boolean to SUCCEEDED/FAILED. */
    fun buildToolResultContext(
        userPrompt: String,
        toolName: String,
        resultMessage: String,
        succeeded: Boolean
    ): String = buildToolResultContext(
        userPrompt, toolName, resultMessage,
        if (succeeded) ExecutionResult.Outcome.SUCCEEDED else ExecutionResult.Outcome.FAILED
    )


}
