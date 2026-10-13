package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class InterruptionProbeBudgetTest {
    @Test fun capacityRenewsRatherThanDisablingTheReply() {
        val budget = InterruptionProbeBudget()
        for (now in listOf(0L, 500L, 1000L, 1500L)) {
            assertTrue(budget.available(now)); budget.record(now)
        }
        assertFalse(budget.available(11_999))
        assertTrue(budget.available(12_000)); budget.record(12_000)
        assertFalse(budget.available(12_001))
        assertTrue(budget.available(12_500))
    }
    @Test fun repeatedFastEmptyDecodesAreStillRateLimited() {
        val budget = InterruptionProbeBudget()
        budget.record(0)
        assertFalse(budget.available(499)); assertTrue(budget.available(500))
        var accepted = 1
        for (now in 1L until 60_000) if (budget.available(now)) { budget.record(now); accepted++ }
        assertEquals(20, accepted)
    }
}
