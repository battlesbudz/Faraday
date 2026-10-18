package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject

/**
 * [ToolCallRunner] adapter for FunctionGemma-family models (M8 comparison path).
 *
 * FunctionGemma emits tool calls in its own control-token format rather than
 * the LiteRT conversation API's structured tool_calls. This adapter lets a
 * FunctionGemma model run through the same reliability benchmark harness as
 * E2B/E4B: it builds the FunctionGemma developer-turn prompt (with the exact
 * trigger phrase the model needs to enter tooling mode), feeds the raw text
 * output through the control-token parser, and returns [ToolCall] entries.
 *
 * Output format (per FunctionGemma spec):
 * ```
 * <start_function_call>call:{name}{arg1:<escape>value1<escape>,arg2:15}<end_function_call>
 * ```
 * - There may be multiple call blocks (parallel calls are trained behavior).
 * - String values are wrapped in `<escape>` delimiters; non-string values
 *   (numbers, booleans) appear unquoted.
 * - No call block means a plain chat reply: returns empty, never an error.
 * - Unknown tool names are rejected (a 270M model occasionally invents one);
 *   malformed blocks are skipped, never dispatched.
 *
 * The [generate] function produces raw model text for a prompt; wiring it to
 * a real LiteRT engine with `llm_model_type: function_gemma` is the
 * production integration step (D-level, physical device).
 */
class FunctionGemmaAdapter(
    private val generate: suspend (prompt: String) -> String,
    private val knownTools: Set<String>
) : ToolCallRunner {

    companion object {
        /** Exact trigger phrase that switches FunctionGemma into tooling mode. */
        const val TRIGGER_PHRASE = "You are a model that can do function calling with the following functions"

        internal const val CALL_START = "<start_function_call>"
        internal const val CALL_END = "<end_function_call>"
        internal const val ESCAPE = "<escape>"

        /**
         * Builds the developer-turn prompt for [utterance]. Uses the `developer`
         * role (not `system` — FunctionGemma ignores tool definitions under
         * system) and the exact trigger phrase.
         */
        fun buildPrompt(utterance: String, toolDescriptions: String = ""): String =
            "<start_of_turn>developer\n$TRIGGER_PHRASE$toolDescriptions<end_of_turn>\n" +
                "<start_of_turn>user\n$utterance<end_of_turn>\n" +
                "<start_of_turn>model\n"

        /**
         * Parses FunctionGemma raw output into [ToolCall] entries.
         *
         * Pure function, JVM-testable without a model. Returns calls in
         * emission order. Unknown tools and malformed blocks are skipped;
         * output with no call blocks yields an empty list.
         */
        fun parseFunctionCalls(output: String, knownTools: Set<String>): List<ToolCall> {
            val calls = mutableListOf<ToolCall>()
            var searchFrom = 0
            while (true) {
                val start = output.indexOf(CALL_START, searchFrom)
                if (start < 0) break
                val end = output.indexOf(CALL_END, start + CALL_START.length)
                if (end < 0) break
                val block = output.substring(start + CALL_START.length, end)
                parseCallBlock(block, knownTools)?.let { calls.add(it) }
                searchFrom = end + CALL_END.length
            }
            return calls
        }

        private fun parseCallBlock(block: String, knownTools: Set<String>): ToolCall? {
            // Expected: call:{name}{args}
            val trimmed = block.trim()
            if (!trimmed.startsWith("call:")) return null
            val afterCall = trimmed.removePrefix("call:")
            val nameEnd = afterCall.indexOf('{')
            if (nameEnd < 0) return null
            val name = afterCall.substring(0, nameEnd).trim()
            if (name.isEmpty() || name !in knownTools) return null
            val argsSection = afterCall.substring(nameEnd)
            if (!argsSection.startsWith("{") || !argsSection.endsWith("}")) return null
            val argsJson = parseArgsObject(argsSection) ?: return null
            return ToolCall(name, argsJson)
        }

        /**
         * Parses `{k1:<escape>v1<escape>,k2:15}` into a JSON object string.
         * Keys are unquoted identifiers; string values are <escape>-wrapped;
         * numbers/booleans are bare. Returns null on malformed input.
         */
        private fun parseArgsObject(section: String): String? {
            return try {
                val inner = section.removePrefix("{").removeSuffix("}").trim()
                val json = JSONObject()
                if (inner.isEmpty()) return json.toString()
                var i = 0
                while (i < inner.length) {
                    // key: read until ':'
                    val colon = inner.indexOf(':', i)
                    if (colon < 0) return null
                    val key = inner.substring(i, colon).trim()
                    if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) return null
                    i = colon + 1
                    // value: <escape>-wrapped string, or bare token until ',' or end
                    val next = if (inner.startsWith(ESCAPE, i)) {
                        val valueStart = i + ESCAPE.length
                        val valueEnd = inner.indexOf(ESCAPE, valueStart)
                        if (valueEnd < 0) return null
                        val raw = inner.substring(valueStart, valueEnd)
                        json.put(key, raw)
                        valueEnd + ESCAPE.length
                    } else {
                        var j = i
                        while (j < inner.length && inner[j] != ',') j++
                        val token = inner.substring(i, j).trim()
                        when {
                            token.equals("true", ignoreCase = true) -> json.put(key, true)
                            token.equals("false", ignoreCase = true) -> json.put(key, false)
                            token.toLongOrNull() != null -> json.put(key, token.toLong())
                            token.toDoubleOrNull() != null -> json.put(key, token.toDouble())
                            token.isEmpty() -> return null
                            else -> json.put(key, token)
                        }
                        j
                    }
                    i = next
                    // expect ',' or end
                    while (i < inner.length && inner[i].isWhitespace()) i++
                    if (i < inner.length) {
                        if (inner[i] != ',') return null
                        i++
                        while (i < inner.length && inner[i].isWhitespace()) i++
                    }
                }
                json.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> {
        val output = generate(buildPrompt(utterance))
        return parseFunctionCalls(output, knownTools)
    }
}
