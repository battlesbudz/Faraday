package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryFactSegmenterTest {
    @Test fun acceptanceMessageSplitsIntoThreeFacts() {
        assertEquals(
            listOf("My name is Justin Lee Battles", "I have traveled to 27 countries", "I like ice cream"),
            MemoryFactSegmenter.segment("My name is Justin Lee Battles and I have traveled to 27 countries and I like ice cream"),
        )
    }

    @Test fun singleFactMessageIsReturnedUnchanged() {
        assertEquals(listOf("I also love ice cream."), MemoryFactSegmenter.segment("I also love ice cream."))
        assertEquals(listOf("I live in Watertown, New York"), MemoryFactSegmenter.segment("I live in Watertown, New York"))
    }

    @Test fun conjunctionWithoutANewClauseDoesNotSplit() {
        assertEquals(listOf("I like fish and chips"), MemoryFactSegmenter.segment("I like fish and chips"))
        assertEquals(listOf("I used to skateboard and swim"), MemoryFactSegmenter.segment("I used to skateboard and swim"))
    }

    @Test fun sentencesSplitAndQuestionsStaySeparateSegments() {
        assertEquals(
            listOf("I like ice cream.", "What is the weather today?"),
            MemoryFactSegmenter.segment("I like ice cream. What is the weather today?"),
        )
    }

    @Test fun semicolonsSplitStatements() {
        assertEquals(
            listOf("I live in Austin", "I work as a nurse"),
            MemoryFactSegmenter.segment("I live in Austin; I work as a nurse"),
        )
    }

    @Test fun leadInConjunctionsSplitLikeStatements() {
        // The ", and" after a discourse lead-in opens a clause of its own.
        assertEquals(
            listOf("Yeah", "I was an average traveler", "I've been to 27 different countries."),
            MemoryFactSegmenter.segment("Yeah, and I was an average traveler and I've been to 27 different countries."),
        )
    }

    @Test fun blankInputSegmentsToNothing() {
        assertEquals(emptyList<String>(), MemoryFactSegmenter.segment("   "))
    }
}
