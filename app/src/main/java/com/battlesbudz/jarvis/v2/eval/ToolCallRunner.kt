package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.ToolCall

/**
 * Contract for executing one reliability-suite utterance against a model and
 * returning the model's raw tool calls in emission order.
 *
 * Slice 1 shipped the interface plus a deterministic fake for JVM tests. Slice 2
 * provides the on-device implementation backed by LiteRtLmEngine: it must use
 * the same conversation path production uses (Conversation API with the
 * catalog's OpenApiTool declarations, automaticToolCalling=false) and return
 * the collected response.toolCalls as [ToolCall] entries. The runner performs
 * no validation, gating, or dispatch. The scorer only compares emitted calls
 * with fixtures; production admission, permissions and dispatch remain outside
 * this measurement runner and are never bypassed by a score.
 */
interface ToolCallRunner {
    /** Runs [utterance] against [modelId] and returns the model's tool calls in order. */
    suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall>
}

/** Deterministic fake for JVM tests: returns canned tool calls per utterance. */
class FakeToolCallRunner(
    private val script: Map<String, List<ToolCall>> = emptyMap()
) : ToolCallRunner {
    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> =
        script[utterance] ?: emptyList()
}
