package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.conversation.ConversationPrompt

/**
 * The code-mode character budgets (code-and-export scope section 3.2). All
 * counts are chars at the chat layer's 3 chars per token, and every number
 * derives from the same limit the chat layer plans against —
 * [ConversationPrompt.contextLimit], whose fallback for a model with no
 * declared `contextTokens` is `ConversationPolicy.CONVERSATION_COMPACTION_LIMIT`
 * — never from Mochu's 8,192-token numbers.
 *
 * - `limitChars` = `contextLimit(selectedModel.contextTokens, false)`.
 * - `replyReserveChars` = `min(limitChars / 2, 12_000)`: room held back for
 *   the model's reply.
 * - `snapshotBudgetChars` = `limitChars - replyReserveChars -
 *   codeSystemPromptChars`, floored at [MIN_SNAPSHOT_BUDGET_CHARS]. The
 *   scope states the floor with its refusal in one breath: "floored at
 *   4,000; if the floor cannot be met, code mode refuses the round …
 *   instead of sending a snapshot it knows will not fit." The floor is
 *   therefore a guarantee, not a gate on the arithmetic: the snapshot gets
 *   at least 4,000 chars whenever that floored snapshot plus the code
 *   system prompt itself still fits under the limit, and the round is
 *   refused (this returns null) only when even that minimum cannot fit —
 *   a snapshot that would not fit is never sent. The reply reserve is a
 *   planning target already spent inside the formula; it is not charged a
 *   second time in the refusal check, or the floor would refuse every
 *   round on the current Gemma bundles, whose fallback limit is 10,000.
 * - The brief cap and the per-file caps are fractions of the snapshot
 *   budget (brief at most one quarter, `index.html` at most one half, any
 *   one other file at most one quarter), so the proportions hold on any
 *   model.
 *
 * The constants are pinned against `ConversationPolicy` by
 * `CodeBudgetTest`, so a future change to the chat layer's limit fails a
 * test here instead of silently shrinking code mode.
 */
object CodeBudget {

    /** The smallest snapshot budget a code round may run with (section 3.2). */
    const val MIN_SNAPSHOT_BUDGET_CHARS = 4_000

    /** The reply reserve never exceeds this, however large the limit is. */
    const val REPLY_RESERVE_CAP_CHARS = 12_000

    /** One computed budget set for one round on one loaded model. */
    data class Budget(
        val limitChars: Int,
        val replyReserveChars: Int,
        val snapshotBudgetChars: Int,
        val briefCapChars: Int,
        val indexCapChars: Int,
        val fileCapChars: Int
    )

    /**
     * Computes the budget for a round on a model declaring [contextTokens]
     * (null when the bundle declares none, the current Gemma case) with a
     * code system prompt of [codeSystemPromptChars] chars. Returns null
     * when the round must be refused: even the floored snapshot plus the
     * system prompt would exceed the limit.
     */
    fun compute(contextTokens: Int?, codeSystemPromptChars: Int): Budget? {
        val limit = ConversationPrompt.contextLimit(contextTokens, false)
        val reserve = minOf(limit / 2, REPLY_RESERVE_CAP_CHARS)
        val computed = limit - reserve - codeSystemPromptChars
        val snapshot = maxOf(computed, MIN_SNAPSHOT_BUDGET_CHARS)
        if (snapshot + codeSystemPromptChars > limit) return null
        return Budget(
            limitChars = limit,
            replyReserveChars = reserve,
            snapshotBudgetChars = snapshot,
            briefCapChars = snapshot / 4,
            indexCapChars = snapshot / 2,
            fileCapChars = snapshot / 4
        )
    }
}
