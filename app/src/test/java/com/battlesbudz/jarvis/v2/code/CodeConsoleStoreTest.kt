package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console store (scope Unit 4): its bound, its error filtering,
 * and the watermark that hands each error to the fix loop exactly
 * once.
 */
class CodeConsoleStoreTest {

    private fun error(text: String) =
        CodeConsoleMessage(CodeConsoleLevel.ERROR, text, "https://appassets.androidplatform.net/index.html", 3)

    private fun log(text: String) =
        CodeConsoleMessage(CodeConsoleLevel.LOG, text, null, 0)

    @Test fun keepsAtMostTheBoundDroppingTheOldest() {
        val store = CodeConsoleStore()
        for (i in 0..CodeConsoleStore.MAX_ENTRIES + 4) store.add(log("m$i"))
        assertEquals(CodeConsoleStore.MAX_ENTRIES, store.size)
        val entries = store.entries()
        assertEquals("m5", entries.first().message)
        assertEquals("m${CodeConsoleStore.MAX_ENTRIES + 4}", entries.last().message)
    }

    @Test fun errorsFiltersToTheErrorLevelOnly() {
        val store = CodeConsoleStore()
        store.add(log("a log"))
        store.add(error("boom"))
        store.add(CodeConsoleMessage(CodeConsoleLevel.WARNING, "careful", null, 1))
        store.add(error("bang"))
        assertEquals(listOf("boom", "bang"), store.errors().map { it.message })
    }

    @Test fun newErrorsHandsEachErrorOverExactlyOnce() {
        val store = CodeConsoleStore()
        store.add(error("first"))
        store.add(log("noise between errors"))
        store.add(error("second"))
        assertEquals(listOf("first", "second"), store.newErrors().map { it.message })
        assertTrue(store.newErrors().isEmpty())
        store.add(error("third"))
        assertEquals(listOf("third"), store.newErrors().map { it.message })
        assertTrue(store.newErrors().isEmpty())
    }

    @Test fun newErrorsDoesNotResurrectAnEvictedError() {
        val store = CodeConsoleStore()
        store.add(error("evicted before the check"))
        for (i in 0..CodeConsoleStore.MAX_ENTRIES + 4) store.add(log("filler $i"))
        assertTrue(store.errors().isEmpty())
        assertTrue(store.newErrors().isEmpty())
    }

    @Test fun clearEmptiesTheStoreAndPendingErrorsStayCleared() {
        val store = CodeConsoleStore()
        store.add(error("before clear"))
        store.clear()
        assertEquals(0, store.size)
        assertTrue(store.newErrors().isEmpty())
        store.add(error("after clear"))
        assertEquals(listOf("after clear"), store.newErrors().map { it.message })
    }

    @Test fun addStampsArrivalSequencesInOrder() {
        val store = CodeConsoleStore()
        val first = store.add(log("one"))
        val second = store.add(error("two"))
        assertTrue(second.sequence > first.sequence)
        assertEquals(listOf(first, second), store.entries())
    }
}
