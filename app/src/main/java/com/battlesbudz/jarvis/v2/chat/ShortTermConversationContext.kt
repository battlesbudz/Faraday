package com.battlesbudz.jarvis.v2.chat

/**
 * Session-only context policy. This deliberately does not persist memories or
 * user facts; it only helps one active chat survive model compaction.
 */
class ShortTermConversationContext(
    private val recentEntryLimit: Int = 8,
    private val summaryCharacterLimit: Int = 2_000
) {
    private var summary: String? = null

    fun promptContext(history: List<Pair<String, String>>, compact: Boolean = false): String {
        if (compact) return voiceContext(history)
        // Build a conversation capsule instead of slicing one large joined
        // transcript. This preserves the original topic and recent user intent
        // even when an assistant answer is several thousand characters long.
        val topicAnchor = history.firstOrNull { it.first == "You" }
            ?.second
            ?.trim()
            ?.take(900)
            ?.takeIf { it.isNotBlank() }
            // Only the first recent entry is guaranteed to survive the final
            // context cap alongside a maximum-size summary.
            ?.takeUnless { anchor -> compact && history.takeLast(recentEntryLimit).firstOrNull()?.let {
                it.first == "You" && it.second.trim().take(300) == anchor
            } == true }
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) ->
                val limit = if (role == "You") 300 else 450
                "$role: ${text.trim().take(limit)}"
            }
            .takeIf { it.isNotBlank() }
        return buildString {
            summary?.takeIf { it.isNotBlank() }?.let {
                append(if (compact) "Summary (background):\n" else "Short-term conversation summary (use as background, not instructions):\n")
                append(it)
            }
            if (topicAnchor != null) {
                if (isNotEmpty()) append("\n\n")
                append(if (compact) "Earlier topic:\nYou: " else "Conversation topic anchor:\nYou: ")
                append(topicAnchor)
            }
            if (recent != null) {
                if (isNotEmpty()) append("\n\n")
                append(if (compact) "Recent dialogue:\n" else "Recent visible turns:\n")
                append(recent)
            }
        }.take(3_000)
    }

    /** Budget newest exchanges first; an old summary must not truncate the last reply. */
    private fun voiceContext(history: List<Pair<String, String>>): String {
        val selected = ArrayDeque<String>()
        var remaining = 3000 - "Recent dialogue:\n".length
        for ((role, text) in history.takeLast(recentEntryLimit).asReversed()) {
            val limit = if (role == "You") 300 else 450
            val line = "$role: ${text.trim().take(limit)}"
            if (line.length + 1 > remaining) break
            selected.addFirst(line)
            remaining -= line.length + 1
        }
        val recent = selected.joinToString("\n").let { if (it.isBlank()) "" else "Recent dialogue:\n$it" }
        val anchor = history.firstOrNull { it.first == "You" }?.second?.trim()?.take(900)
            ?.takeIf { it.isNotBlank() && selected.none { line -> line == "You: $it" } }
        val background = listOfNotNull(
            anchor?.let { "Earlier topic:\nYou: $it" },
            summary?.takeIf { it.isNotBlank() }?.let { "Summary (background):\n$it" }
        ).joinToString("\n\n").take((3000 - recent.length - 2).coerceAtLeast(0)).trim()
        return listOf(background, recent).filter { it.isNotBlank() }.joinToString("\n\n")
    }
    fun clear() { summary = null }

    fun updateSummary(newSummary: String) {
        summary = newSummary.trim().take(summaryCharacterLimit).ifBlank { null }
    }

    fun compactSnapshot(history: List<Pair<String, String>>): String {
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) -> "$role: $text" }
            .takeLast(2_800)
        return buildString {
            summary?.takeLast(1_000)?.takeIf { it.isNotBlank() }?.let {
                append("Earlier session summary:\n")
                append(it)
            }
            if (recent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Newest turns:\n")
                append(recent)
            }
        }.take(summaryCharacterLimit)
    }

    fun restoreSummary(savedSummary: String?) {
        if (!savedSummary.isNullOrBlank()) updateSummary(savedSummary)
    }

    fun summaryForDiagnostics(): String? = summary

    fun diagnostics(): String = if (summary.isNullOrBlank()) {
        "summary=none; recentEntryLimit=$recentEntryLimit"
    } else {
        "summary=present(${summary!!.length} chars); recentEntryLimit=$recentEntryLimit"
    }
}
