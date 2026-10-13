package com.battlesbudz.jarvis.v2.ui

import org.junit.Assert.*
import org.junit.Test

class BenchmarkExportStateTest {
    @Test fun repeatedClicksAreRejectedBeforeRecompositionAndCancelAllowsAnotherExport() {
        val state = BenchmarkExportState()
        assertTrue(state.tryBegin())
        assertFalse(state.tryBegin())
        assertFalse(state.tryBegin())
        state.pendingSave = "frozen first report"
        assertFalse(state.tryBegin())
        // Picker cancellation clears pending payload and releases the synchronous gate.
        state.pendingSave = null
        state.busy = false
        assertTrue(state.tryBegin())
        assertNull(state.pendingSave)
    }

    @Test fun openClipboardPartsCannotBeReplacedByAnotherExport() {
        val state = BenchmarkExportState()
        state.parts = listOf("report A part 1", "report A part 2")
        state.partIndex = 1
        assertFalse(state.tryBegin())
        assertEquals("report A part 2", state.parts[state.partIndex])
        state.parts = emptyList()
        assertTrue(state.tryBegin())
    }
}
