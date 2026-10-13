package com.battlesbudz.jarvis.v2.ai

/** Session callbacks are raw tokens; unlike Conversation, they do not remove thought channels. */
internal class GemmaSessionText(private val emit: (String) -> Unit) {
    private val pending = StringBuilder()
    private var hidden = false
    private var ended = false
    private val markers = listOf("<|channel>", "<channel|>", "<turn|>", "<|turn>",
        "<|tool_call>", "<eos>", "<|think|>")

    fun accept(chunk: String) {
        if (ended) return
        pending.append(chunk)
        while (pending.isNotEmpty()) {
            val current = pending.toString()
            val marker = markers.firstOrNull { current.startsWith(it) }
            if (marker != null) {
                pending.delete(0, marker.length)
                when (marker) {
                    "<|channel>" -> hidden = true
                    "<channel|>" -> hidden = false
                    "<|think|>" -> Unit
                    else -> { ended = true; pending.clear(); return }
                }
                continue
            }
            if (markers.any { it.startsWith(current) }) return
            // Hold only a possible split control marker, including during hidden content.
            val next = current.indexOf('<', startIndex = 1).takeIf { it >= 0 } ?: current.length
            if (!hidden) emit(current.substring(0, next))
            pending.delete(0, next)
        }
    }

    fun finish() {
        if (!ended && !hidden && pending.isNotEmpty() && markers.none { it.startsWith(pending.toString()) }) {
            emit(pending.toString())
        }
        pending.clear()
    }
}
