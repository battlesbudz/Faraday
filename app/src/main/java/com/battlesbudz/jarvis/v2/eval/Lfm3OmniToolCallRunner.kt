package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.Lfm3ToolProtocol
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.google.ai.edge.litertlm.OpenApiTool

/**
 * Read-only eval adapter for Omni text-mode tool calls. The supplied runtime
 * callbacks own text inference and conversation reset; emitted calls are
 * returned as ordinary [ToolCall] values and are never dispatched here.
 */
class Lfm3OmniToolCallRunner(
    private val resetConversation: suspend () -> Unit,
    private val generateRawText: suspend (prompt: String) -> String,
    private val tools: () -> List<OpenApiTool>
) : ToolCallRunner {
    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> {
        resetConversation()
        val declarations = tools()
        val prompt = buildPrompt(utterance, declarations, SYSTEM_INSTRUCTION)
        return Lfm3ToolProtocol.parseCalls(generateRawText(prompt), declarations)
    }

    companion object {
        const val SYSTEM_INSTRUCTION = "You are Faraday, a helpful assistant. Use the available tools when they are needed."

        /** The text-only eval prompt uses the same schemas and LFM3 ChatML roles as production. */
        fun buildPrompt(utterance: String, tools: List<OpenApiTool>, systemInstruction: String): String = buildString {
            append("<|startoftext|><|im_start|>system\n")
            append(systemInstruction).append('\n')
            append(Lfm3ToolProtocol.renderDefinitions(tools))
            append("<|im_end|>\n<|im_start|>user\n")
            append(utterance)
            append("<|im_end|>\n<|im_start|>assistant\n<think></think>")
        }

        /** Production catalog snapshot for an eval runner wired to OmniRuntime. */
        fun mobileActionTools(): List<OpenApiTool> = MobileActionToolDefinitions.all()
    }
}
