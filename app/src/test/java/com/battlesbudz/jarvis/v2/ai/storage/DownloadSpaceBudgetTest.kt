package com.battlesbudz.jarvis.v2.ai.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpaceBudgetTest {
    @Test fun budgetIsModelSizeTimesOneAndAHalf() {
        assertEquals(150L, DownloadSpaceBudget.budgetBytes(100L))
        // Odd sizes round up: the cache half is a budget, not a measurement.
        assertEquals(152L, DownloadSpaceBudget.budgetBytes(101L))
        assertEquals(0L, DownloadSpaceBudget.budgetBytes(0L))
        // Gemma-4-E2B's catalog size, for scale.
        assertEquals(3_882_221_568L, DownloadSpaceBudget.budgetBytes(2_588_147_712L))
    }

    @Test fun shortfallIsZeroWhenAllocatableCoversTheBudget() {
        assertEquals(0L, DownloadSpaceBudget.shortfallBytes(100L, 0L, 150L))
        assertEquals(0L, DownloadSpaceBudget.shortfallBytes(100L, 0L, 10_000L))
    }

    @Test fun shortfallNamesExactlyWhatIsMissing() {
        assertEquals(150L, DownloadSpaceBudget.shortfallBytes(100L, 0L, 0L))
        assertEquals(40L, DownloadSpaceBudget.shortfallBytes(100L, 0L, 110L))
    }

    @Test fun bytesAlreadyOnDiskAreCredited() {
        // A resumed download only pays for what is missing.
        assertEquals(0L, DownloadSpaceBudget.shortfallBytes(100L, 60L, 90L))
        assertEquals(40L, DownloadSpaceBudget.shortfallBytes(100L, 60L, 50L))
        // A model already occupying more than its budget needs nothing.
        assertEquals(0L, DownloadSpaceBudget.shortfallBytes(100L, 200L, 0L))
    }
}
