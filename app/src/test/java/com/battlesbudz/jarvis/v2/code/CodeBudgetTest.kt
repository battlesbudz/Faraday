package com.battlesbudz.jarvis.v2.code

import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy
import com.battlesbudz.jarvis.v2.conversation.ConversationPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The budget constants are pinned against the chat layer (scope section
 * 3.2): if `ConversationPolicy` or `ConversationPrompt.contextLimit`
 * changes, these tests fail here instead of code mode silently shrinking.
 */
class CodeBudgetTest {

    @Test fun fallbackLimitIsConversationPolicysCompactionLimit() {
        val budget = CodeBudget.compute(null, 0)!!
        assertEquals(ConversationPolicy.CONVERSATION_COMPACTION_LIMIT, budget.limitChars)
        assertEquals(ConversationPrompt.contextLimit(null, false), budget.limitChars)
    }

    @Test fun declaredTokensFlowThroughTheChatLayersFormula() {
        assertEquals(
            ConversationPrompt.contextLimit(2048, false),
            CodeBudget.compute(2048, 0)!!.limitChars
        )
        assertEquals(
            ConversationPrompt.contextLimit(8192, false),
            CodeBudget.compute(8192, 0)!!.limitChars
        )
    }

    @Test fun replyReserveIsHalfTheLimitCapped() {
        val budget = CodeBudget.compute(null, 0)!!
        assertEquals(
            minOf(budget.limitChars / 2, CodeBudget.REPLY_RESERVE_CAP_CHARS),
            budget.replyReserveChars
        )
    }

    @Test fun snapshotBudgetIsLimitMinusReserveMinusPrompt() {
        // On the fallback limit (10,000): 10,000 - 5,000 - prompt.
        assertEquals(5_000, CodeBudget.compute(null, 0)!!.snapshotBudgetChars)
        assertEquals(4_500, CodeBudget.compute(null, 500)!!.snapshotBudgetChars)
    }

    @Test fun snapshotBudgetIsFlooredAtTheMinimum() {
        // The raw formula gives 3,500 here; the floor guarantees 4,000,
        // and 4,000 + the 1,500-char prompt still fits the 10,000 limit.
        val budget = CodeBudget.compute(null, 1_500)!!
        assertEquals(CodeBudget.MIN_SNAPSHOT_BUDGET_CHARS, budget.snapshotBudgetChars)
        // A small declared window hits the same floor.
        assertEquals(
            CodeBudget.MIN_SNAPSHOT_BUDGET_CHARS,
            CodeBudget.compute(2048, 0)!!.snapshotBudgetChars
        )
    }

    @Test fun roundIsRefusedWhenEvenTheFloorCannotFit() {
        // 512 declared tokens: the chat limit is 1,536 chars, under the floor.
        assertNull(CodeBudget.compute(512, 0))
        // The floored snapshot plus a 6,001-char prompt exceeds the fallback limit by one.
        assertNull(CodeBudget.compute(null, 6_001))
        // Exactly at the boundary the floor still fits.
        assertNotNull(CodeBudget.compute(null, 6_000))
    }

    @Test fun capsAreFractionsOfTheSnapshotBudget() {
        val budget = CodeBudget.compute(null, 0)!!
        assertEquals(budget.snapshotBudgetChars / 4, budget.briefCapChars)
        assertEquals(budget.snapshotBudgetChars / 2, budget.indexCapChars)
        assertEquals(budget.snapshotBudgetChars / 4, budget.fileCapChars)
    }
}
