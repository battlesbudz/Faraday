package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class WordErrorRateTest {
    @Test fun countsDeletedOpeningWordsAndMisheardNouns() {
        val score = WordErrorRate.score("Can you tell me a story about astronauts", "a story about ants")!!
        assertEquals(5, score.edits)
        assertEquals(8, score.referenceWords)
        assertEquals(62.5, score.percent, 0.001)
    }
    @Test fun ignoresCasePunctuationAndApostropheStyle() {
        assertEquals(0, WordErrorRate.score("Hey, Jarvis! I’m here.", "HEY JARVIS I'M HERE")!!.edits)
    }
    @Test fun preservesSpokenCorrectionsAndCanExceedOneHundredPercent() {
        assertEquals(0, WordErrorRate.score("pirates no I mean astronauts", "pirates no I mean astronauts")!!.edits)
        assertEquals(300.0, WordErrorRate.score("hello", "a b c")!!.percent, 0.001)
    }
    @Test fun noReferenceIsUnscoredAndEmptyRecognitionCountsOmissions() {
        assertNull(WordErrorRate.score("...", "hello"))
        assertEquals(100.0, WordErrorRate.score("hello Jarvis", "")!!.percent, 0.001)
    }
}
