package com.battlesbudz.jarvis.v2.chat

/**
 * The legacy text form of a tool call: `call:<name>{<key>:<value>,...}`,
 * optionally wrapped in `<|tool_call>` / `<start_function_call>` tags.
 * Native tool calls arrive structurally from the engine; this form only
 * ever appears when a model emits the call as ordinary text (a text turn
 * with tools disabled, or a malformed generation). It is protocol text,
 * never answer content: the prompt instructs the model never to emit it,
 * and the display boundary already strips the wrapped forms. These
 * helpers give every layer — generation, recovery, display — one shared
 * test for the bare form as well.
 */
internal object ToolCallMarkup {
    private const val CALL_PREFIX = "call:"

    /**
     * The first legacy call span in [text] — the exact `call:<name>{...}`
     * substring, braces balanced — or null when the text carries no
     * call-shaped span. A span whose braces never balance runs to the end
     * of the text, mirroring the wrapped forms' unterminated handling in
     * [AssistantText.forDisplay]. A span is only located, never validated:
     * strict parsing lives in the salvage/schema layer.
     */
    fun callBlock(text: String): String? {
        var searchFrom = 0
        while (true) {
            val start = text.indexOf(CALL_PREFIX, searchFrom)
            if (start < 0) return null
            // A name character immediately before the prefix means this is
            // a longer word ("recall:"), not the call marker.
            if (start == 0 || !isNameChar(text[start - 1])) {
                var index = start + CALL_PREFIX.length
                val nameStart = index
                while (index < text.length && isNameChar(text[index])) index++
                if (index > nameStart) {
                    var brace = index
                    while (brace < text.length && text[brace].isWhitespace()) brace++
                    if (brace < text.length && text[brace] == '{') {
                        return text.substring(start, balancedEnd(text, brace) ?: text.length)
                    }
                }
            }
            searchFrom = start + CALL_PREFIX.length
        }
    }

    /** True when [text] carries any tool-call markup, wrapped or bare. */
    fun contains(text: String): Boolean =
        text.contains("tool_call>") || text.contains("start_function_call") || callBlock(text) != null

    /** [text] with every bare call span removed; the wrapped forms are [AssistantText]'s regexes' job. */
    fun strip(text: String): String {
        var result = text
        while (true) {
            val block = callBlock(result) ?: return result
            val start = result.indexOf(block)
            result = result.removeRange(start, start + block.length)
        }
    }

    private fun isNameChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == ':' || char == '.'

    /** Index just past the `}` balancing the `{` at [open], or null when it never balances. */
    private fun balancedEnd(text: String, open: Int): Int? {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return null
    }
}
