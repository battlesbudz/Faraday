package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.code.CodeSampling
import com.battlesbudz.jarvis.v2.code.CodeSystemPrompt
import com.google.ai.edge.litertlm.*

/** Let each bundle use its own template; execution always stays with Jarvis's validated dispatcher. */
internal fun modelConversationConfig(spec: LocalModelSpec, tools: List<OpenApiTool>, enabled: Boolean,
                                     fixedVoicePreface: Boolean = false): ConversationConfig {
    val declarations = if (enabled && spec.supportsTools) tools.map { tool(it) } else emptyList()
    require(!fixedVoicePreface || spec.incrementalGemmaInput && spec.supportsAudio && !enabled) {
        "Fixed voice preface requires tool-disabled Gemma audio"
    }
    return if (spec.incrementalGemmaInput) ConversationConfig(
        tools = declarations, automaticToolCalling = false,
        systemInstruction = if (fixedVoicePreface) Contents.of(GemmaVoicePreface.instructions) else null,
        prefillPrefaceOnInit = fixedVoicePreface
    )
    else ConversationConfig(
        tools = declarations,
        automaticToolCalling = false,
        channels = listOf(Channel("thought", "<think>", "</think>")),
        maxOutputToken = 512,
        thinkingConfig = ThinkingConfig(enableThinking = spec.reasoning, thinkingTokenBudget = 256)
    )
}

/**
 * The code-mode conversation (code-and-export scope section 4.2): tools
 * disabled, the code system prompt as the system instruction, and the
 * code sampler.
 *
 * The bridge sampler check the scope required, settled against the
 * reviewed 0.16 bridge source: `ConversationConfig` DOES expose a
 * per-conversation `samplerConfig` — `Engine.createConversation` passes
 * it to the native create call, and the JNI layer reads topK (int),
 * topP (double), temperature (double) and seed (int) off it. So code
 * mode samples at the scope's Mochu-derived values ([CodeSampling])
 * instead of running at the bridge default, and no bridge patch is
 * involved. The non-Gemma branch keeps its ordinary shape (thought
 * channel, output cap, thinking config) under the same code instruction
 * and sampler.
 */
@OptIn(ExperimentalApi::class)
internal fun codeConversationConfig(spec: LocalModelSpec): ConversationConfig {
    val sampler = SamplerConfig(
        topK = CodeSampling.TOP_K,
        topP = CodeSampling.TOP_P,
        temperature = CodeSampling.TEMPERATURE,
        seed = CodeSampling.SEED
    )
    return if (spec.incrementalGemmaInput) ConversationConfig(
        tools = emptyList(), automaticToolCalling = false,
        systemInstruction = Contents.of(CodeSystemPrompt.TEXT),
        samplerConfig = sampler
    )
    else ConversationConfig(
        tools = emptyList(),
        automaticToolCalling = false,
        systemInstruction = Contents.of(CodeSystemPrompt.TEXT),
        channels = listOf(Channel("thought", "<think>", "</think>")),
        maxOutputToken = 512,
        thinkingConfig = ThinkingConfig(enableThinking = spec.reasoning, thinkingTokenBudget = 256),
        samplerConfig = sampler
    )
}
