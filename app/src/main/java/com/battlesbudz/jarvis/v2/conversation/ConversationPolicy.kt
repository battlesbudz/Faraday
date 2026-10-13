package com.battlesbudz.jarvis.v2.conversation

/** App context budgets, not the native model's advertised context maximum. */
internal object ConversationPolicy {
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    const val SHORT_TERM_SUMMARY_KEY = "short_term_summary"
    const val CONVERSATION_COMPACTION_LIMIT = 10_000
    const val GENERATION_HEADROOM = 2_000
    const val MAX_USER_PROMPT_CHARS = 12_000
}
