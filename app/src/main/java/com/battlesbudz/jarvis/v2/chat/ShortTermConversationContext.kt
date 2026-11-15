package com.battlesbudz.jarvis.v2.chat

import com.battlesbudz.jarvis.v2.harness.HarnessConfig

/**
 * Session-only context policy. This deliberately does not persist memories or
 * user facts; it only helps one active chat survive model compaction.
 *
 * The shaping numbers (entry limits, truncation lengths, caps) are editable
 * harness content (self-improving harness project, Phase 1): they come from
 * [policyProvider], whose shipped v1 values are exactly the constants this
 * class used to hardcode.
 */
class ShortTermConversationContext(
    private val policyProvider: () -> HarnessConfig.HistoryPolicyConfig =
        { HarnessConfig.DEFAULT.historyPolicy },
    private val recentEntryLimitOverride: Int? = null,
    private val summaryCharacterLimitOverride: Int? = null
) {
    private val recentEntryLimit: Int
        get() = recentEntryLimitOverride ?: policyProvider().recentEntryLimit
    private val summaryCharacterLimit: Int
        get() = summaryCharacterLimitOverride ?: policyProvider().summaryCharacterLimit

    private var summary: String? = null

    fun promptContext(history: List<Pair<String, String>>, compact: Boolean = false): String {
        val policy = policyProvider()
        if (compact) return voiceContext(history, policy)
        // Build a conversation capsule instead of slicing one large joined
        // transcript. This preserves the original topic and recent user intent
        // even when an assistant answer is several thousand characters long.
        val topicAnchor = history.firstOrNull { it.first == "You" }
            ?.second
            ?.trim()
            ?.take(policy.topicAnchorChars)
            ?.takeIf { it.isNotBlank() }
            // Only the first recent entry is guaranteed to survive the final
            // context cap alongside a maximum-size summary.
            ?.takeUnless { anchor -> compact && history.takeLast(recentEntryLimit).firstOrNull()?.let {
                it.first == "You" && it.second.trim().take(policy.userEntryChars) == anchor
            } == true }
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) ->
                val limit = if (role == "You") policy.userEntryChars else policy.assistantEntryChars
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
        }.take(policy.contextCapChars)
    }

    /** Budget newest exchanges first; an old summary must not truncate the last reply. */
    private fun voiceContext(history: List<Pair<String, String>>,
                             policy: HarnessConfig.HistoryPolicyConfig): String {
        val selected = ArrayDeque<String>()
        var remaining = policy.contextCapChars - "Recent dialogue:\n".length
        for ((role, text) in history.takeLast(recentEntryLimit).asReversed()) {
            val limit = if (role == "You") policy.userEntryChars else policy.assistantEntryChars
            val line = "$role: ${text.trim().take(limit)}"
            if (line.length + 1 > remaining) break
            selected.addFirst(line)
            remaining -= line.length + 1
        }
        val recent = selected.joinToString("\n").let { if (it.isBlank()) "" else "Recent dialogue:\n$it" }
        val anchor = history.firstOrNull { it.first == "You" }?.second?.trim()?.take(policy.topicAnchorChars)
            ?.takeIf { it.isNotBlank() && selected.none { line -> line == "You: $it" } }
        val background = listOfNotNull(
            anchor?.let { "Earlier topic:\nYou: $it" },
            summary?.takeIf { it.isNotBlank() }?.let { "Summary (background):\n$it" }
        ).joinToString("\n\n").take((policy.contextCapChars - recent.length - 2).coerceAtLeast(0)).trim()
        return listOf(background, recent).filter { it.isNotBlank() }.joinToString("\n\n")
    }
    fun clear() { summary = null }

    fun updateSummary(newSummary: String) {
        summary = newSummary.trim().take(summaryCharacterLimit).ifBlank { null }
    }

    fun compactSnapshot(history: List<Pair<String, String>>): String {
        val policy = policyProvider()
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) -> "$role: $text" }
            .takeLast(policy.compactSnapshotTailChars)
        return buildString {
            summary?.takeLast(policy.compactSnapshotSummaryTailChars)?.takeIf { it.isNotBlank() }?.let {
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
