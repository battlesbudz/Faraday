package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class CaptureRecognitionBudgetTest {
    @Test fun expensivePartialGetsRecoveryTimeAndBacklogAlwaysDefers() {
        val budget = CaptureRecognitionBudget()
        assertTrue(budget.allows(0, 0))
        budget.completed(0, 1500)
        assertFalse(budget.allows(2999, 0))
        assertTrue(budget.allows(3000, 0))
        assertFalse(budget.allows(3000, 200))
        assertEquals(1500L, budget.largestWorkMs)
    }
    @Test fun CheapFeedingDoesNotExtendTheOptionalDecodeCooldown() {
        val budget = CaptureRecognitionBudget()
        budget.completed(0, 1)
        budget.completed(100, 101)
        assertTrue(budget.allows(101, 0))
    }
    @Test fun segmentFinalizationWorkAlsoGetsChargedButCooldownIsBounded() {
        val budget = CaptureRecognitionBudget()
        budget.completed(0, 8000)
        assertFalse(budget.allows(10999, 0))
        assertTrue(budget.allows(11000, 0))
    }
}
