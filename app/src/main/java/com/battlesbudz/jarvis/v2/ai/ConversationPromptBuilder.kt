package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.harness.HarnessConfig
import com.battlesbudz.jarvis.v2.harness.HarnessTemplate

class ConversationPromptBuilder(
    private val shortTermContext: ShortTermConversationContext,
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
        captureContext: String? = null,
        nativeContextResident: Boolean = false
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
            if (nativeContextResident) {
                // The resident native conversation already holds the persona,
                // the call-start supporting context and the dialogue itself:
                // submit only the current message, plus any conditional
                // dialogue instruction or action-result context for this turn.
                val extras = listOf(dialogueInstruction, actionContext.trim())
                    .filter { it.isNotBlank() }
                return if (extras.isEmpty()) userPrompt
                else userPrompt + "\n\n" + extras.joinToString("\n\n")
            }
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
        val defaultMainTemplate = HarnessConfig.DEFAULT.instructions.mainTemplate
        val mainTemplate = if (instructions.mainTemplate == defaultMainTemplate) {
            MAIN_PROMPT_TEMPLATE
        } else instructions.mainTemplate
        return HarnessTemplate.render(mainTemplate, mapOf(
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
        outcome: ExecutionResult.Outcome,
        voice: Boolean = false
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
        if (voice) return """
            Authoritative phone action result:
            Request: $userPrompt
            Tool: $toolName
            Status: $statusText
            Android result: $resultMessage
            Report this status faithfully. For "sent but unconfirmed", say it was sent but unconfirmed. Never turn it into a blanket background refusal. For "not attempted" because the device is locked, suggest trying in the foreground or setting Jarvis as the system assistant.
        """.trimIndent()
        return HarnessTemplate.render(instructions.toolResultTemplate, mapOf(
            HarnessConfig.TOKEN_USER_PROMPT to userPrompt,
            HarnessConfig.TOKEN_TOOL_NAME to toolName,
            HarnessConfig.TOKEN_STATUS_TEXT to statusText,
            HarnessConfig.TOKEN_RESULT_MESSAGE to resultMessage
        ))
    }

    private companion object {
        // Main's current prompt policy supersedes the October v1 wording.
        // If the harness template is still the bundled v1 default, render this
        // current policy; any deliberate template edit remains configurable.
        val MAIN_PROMPT_TEMPLATE = """
            You are Jarvis, a private local assistant. Answer the current
            user message directly and naturally. Do not list your capabilities,
            describe your tools, or discuss the routing system unless the user
            explicitly asks about them. Use a phone action only when the current
            request actually asks you to inspect or change the phone. Tool calls
            are handled internally by the app. Never emit <|tool_call>,
            <start_function_call>, call:, or any other tool-call markup in your
            user-facing answer. If a verified tool result is included below,
            treat it as authoritative and explain it naturally. Do not claim that
            you accessed or verified sources unless reference evidence is included
            in this prompt.
            For factual and historical questions, answer from your own
            knowledge when no reference evidence is included. Reference
            evidence is an aid when it is included, not a prerequisite for
            answering. Do not say that you could not verify an answer merely
            because no reference evidence is included, and do not mention
            reference material, sources, or lookup unless the user asked
            about them. When reference evidence is included, use it as the
            factual basis for the claims it covers and do not claim that you
            accessed or verified any other source. Never ask the user whether
            to search Wikipedia; the app handles factual lookup automatically.
            Prefer a concise answer that fits the current mobile turn budget.
            For broad requests, give the key points first and avoid unnecessary
            repetition or a long preamble.

            {{SESSION_CONTEXT}}

            {{MEMORY_SECTION}}

            {{DIALOGUE_INSTRUCTION}}

            Current user message:
            {{USER_PROMPT}}
            {{ACTION_CONTEXT}}
        """.trimIndent()
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
