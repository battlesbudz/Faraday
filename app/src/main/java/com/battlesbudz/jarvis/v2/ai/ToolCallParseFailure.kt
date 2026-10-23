package com.battlesbudz.jarvis.v2.ai

import org.json.JSONObject

/**
 * Classification of native function-call parse failures.
 *
 * LiteRT-LM parses the model's tool-call block inside the native engine. A
 * malformed block (for example a duplicated argument value) never reaches
 * app code as a [ToolCall]: the SDK aborts the generation and delivers a
 * LiteRtLmJniException through the message callback's onError, with a
 * message of the form "Failed to parse tool calls from code block:
 * call:<name>{...}". That exception is a generation failure, not a model
 * load failure, and callers must be able to tell the two apart from the
 * exception chain alone, without depending on native classes.
 */
object ToolCallParseFailure {
    private const val PARSE_MARKER = "Failed to parse tool calls"

    /** True when any exception in the cause chain is the SDK's tool-call parse rejection. */
    fun isFailure(error: Throwable?): Boolean {
        var current = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current.message?.contains(PARSE_MARKER) == true) return true
            current = current.cause
        }
        return false
    }

    /** The raw `call:...` block the SDK rejected, when its message carries one. */
    fun rawCallText(error: Throwable?): String? {
        var current = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null && message.contains(PARSE_MARKER)) {
                val start = message.indexOf("call:")
                if (start >= 0) return message.substring(start).trim()
            }
            current = current.cause
        }
        return null
    }
}

/**
 * Tolerant salvage for the one malformation class observed on device: a
 * string argument value emitted twice in a row inside a single call, e.g.
 * `call:open_app{app:<|"|>Facebook<|"|>Facebook<|"|>}`.
 *
 * The parser accepts exactly the Gemma function-call shape
 * `call:<name>{<key>:<value>,...}` where string values are wrapped in the
 * `<|"|>` delimiter and other values are bare literals. The single tolerated
 * defect is one duplicated string segment: after a complete
 * `<|"|>value<|"|>`, an immediate repeat of the same value followed by the
 * closing delimiter is dropped. Anything else malformed — truncation,
 * unknown structure, a different duplication, extra trailing text — yields
 * null, and the caller falls back to regenerating without tools. A salvaged
 * call is only a candidate: the caller must still validate it against the
 * tool schema (strict decode plus the mode's tool guard) before it may run
 * through the normal approval/execution path.
 */
object MalformedToolCallSalvage {
    private const val DELIMITER = "<|\"|>"

    fun salvage(error: Throwable?): ToolCall? {
        val raw = ToolCallParseFailure.rawCallText(error) ?: return null
        return parse(raw)
    }

    /** Parses one raw `call:...` block, or null when it is not salvageable. */
    fun parse(raw: String): ToolCall? {
        val text = raw.trim()
        if (!text.startsWith("call:")) return null
        var index = "call:".length
        val nameStart = index
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
        if (index == nameStart) return null
        val name = text.substring(nameStart, index)
        index = skipWhitespace(text, index)
        if (index >= text.length || text[index] != '{') return null
        index++
        val arguments = JSONObject()
        index = skipWhitespace(text, index)
        if (index < text.length && text[index] == '}') {
            index++
            return if (skipWhitespace(text, index) == text.length) ToolCall(name, arguments.toString()) else null
        }
        while (true) {
            index = skipWhitespace(text, index)
            val keyStart = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
            if (index == keyStart) return null
            val key = text.substring(keyStart, index)
            if (arguments.has(key)) return null
            index = skipWhitespace(text, index)
            if (index >= text.length || text[index] != ':') return null
            index++
            index = skipWhitespace(text, index)
            if (text.startsWith(DELIMITER, index)) {
                index += DELIMITER.length
                val valueEnd = text.indexOf(DELIMITER, index)
                if (valueEnd < 0) return null
                val value = text.substring(index, valueEnd)
                index = valueEnd + DELIMITER.length
                // The tolerated defect: the same value repeated once as its
                // own segment immediately after the complete value. Drop the
                // repeat; a second repeat or a different segment is not
                // salvageable and fails at the separator check below.
                if (value.isNotEmpty() && text.startsWith(value, index) &&
                    text.startsWith(DELIMITER, index + value.length)) {
                    index += value.length + DELIMITER.length
                }
                arguments.put(key, value)
            } else {
                val valueStart = index
                while (index < text.length && text[index] != ',' && text[index] != '}') index++
                val literal = text.substring(valueStart, index).trim()
                if (literal.isEmpty() || literal.any { it == '{' || it == '"' }) return null
                arguments.put(key, literal)
            }
            index = skipWhitespace(text, index)
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index++
                '}' -> {
                    index++
                    return if (skipWhitespace(text, index) == text.length) {
                        ToolCall(name, arguments.toString())
                    } else null
                }
                else -> return null
            }
        }
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }
}
