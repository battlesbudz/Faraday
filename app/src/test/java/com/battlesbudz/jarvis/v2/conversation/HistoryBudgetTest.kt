package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 (reliability epic item 5, part 3): the explicit history budget and
 * heavy-payload windowing enforced on the history a persistent
 * conversation (re)build would seed.
 */
class HistoryBudgetTest {

    private fun entries(count: Int, charsEach: Int = 10): List<ChatEntry> =
        (1..count).map { ChatEntry(if (it % 2 == 1) "You" else "Jarvis", "entry-$it".padEnd(charsEach, 'x')) }

    @Test fun withinBudgetReturnsTheSameInstance() {
        val history = entries(4)
        assertSame(history, HistoryBudget.window(history))
    }

    @Test fun emptyHistoryReturnsTheSameInstance() {
        val history = emptyList<ChatEntry>()
        assertSame(history, HistoryBudget.window(history))
    }

    @Test fun messageBudgetKeepsTheNewestEntriesInOrder() {
        val history = entries(HistoryBudget.MAX_MESSAGES + 8)
        val windowed = HistoryBudget.window(history)
        assertEquals(HistoryBudget.MAX_MESSAGES, windowed.size)
        assertEquals(history.takeLast(HistoryBudget.MAX_MESSAGES), windowed)
        assertEquals(history.last(), windowed.last())
    }

    @Test fun charBudgetKeepsNewestFirstAndDropsTheOldestOverflow() {
        // 10 entries of 1_000 chars against a 6_000-char budget: the
        // newest six survive, in original order.
        val history = entries(10, charsEach = 1_000)
        val windowed = HistoryBudget.window(history)
        assertEquals(6, windowed.size)
        assertEquals(history.takeLast(6), windowed)
        assertTrue(HistoryBudget.estimatedTokens(windowed) <= HistoryBudget.MAX_ESTIMATED_TOKENS)
    }

    @Test fun newestEntryIsAlwaysKeptEvenWhenItAloneExceedsTheBudget() {
        val huge = ChatEntry("You", "x".repeat(HistoryBudget.MAX_CHARS + 5_000))
        assertEquals(listOf(huge), HistoryBudget.window(listOf(huge)))
        val windowed = HistoryBudget.window(listOf(ChatEntry("You", "old"), huge))
        assertEquals(listOf(huge), windowed)
    }

    @Test fun payloadMarkerSurvivesOnlyOnTheNewestPayloadEntry() {
        val marker = "\n[image attached to this message]"
        val history = listOf(
            ChatEntry("You", "first photo$marker"),
            ChatEntry("Jarvis", "I see a cat."),
            ChatEntry("You", "second photo$marker"),
            ChatEntry("Jarvis", "I see a dog."),
            ChatEntry("You", "third photo$marker")
        )
        val windowed = HistoryBudget.window(history)
        assertEquals(5, windowed.size)
        assertEquals("first photo", windowed[0].text)
        assertEquals("second photo", windowed[2].text)
        assertEquals("third photo$marker", windowed[4].text)
        assertEquals("I see a cat.", windowed[1].text)
        assertEquals("I see a dog.", windowed[3].text)
    }

    @Test fun audioMarkerIsAlsoAHeavyPayload() {
        val marker = "\n[audio attached to this message]"
        val history = listOf(
            ChatEntry("You", "first clip$marker"),
            ChatEntry("You", "second clip$marker")
        )
        val windowed = HistoryBudget.window(history)
        assertEquals("first clip", windowed[0].text)
        assertEquals("second clip$marker", windowed[1].text)
    }

    @Test fun imageUriSurvivesOnlyOnTheNewestPayloadEntry() {
        val history = listOf(
            ChatEntry("You", "older", imageUri = "content://older"),
            ChatEntry("Jarvis", "reply"),
            ChatEntry("You", "newer", imageUri = "content://newer")
        )
        val windowed = HistoryBudget.window(history)
        assertNull(windowed[0].imageUri)
        assertEquals("older", windowed[0].text)
        assertEquals("content://newer", windowed[2].imageUri)
    }

    @Test fun singlePayloadEntryIsLeftUntouched() {
        val marker = "\n[image attached to this message]"
        val history = listOf(ChatEntry("You", "only photo$marker"), ChatEntry("Jarvis", "Nice."))
        assertSame(history, HistoryBudget.window(history))
    }

    @Test fun estimatedTokensUseTheCodebaseCharRatio() {
        val history = listOf(ChatEntry("You", "x".repeat(900)), ChatEntry("Jarvis", "y".repeat(300)))
        assertEquals(400, HistoryBudget.estimatedTokens(history))
        assertEquals(HistoryBudget.MAX_CHARS / HistoryBudget.CHARS_PER_TOKEN,
            HistoryBudget.MAX_ESTIMATED_TOKENS)
    }
}
