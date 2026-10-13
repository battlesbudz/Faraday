package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechChunkerTest {
    @Test fun tokenSplitsAndDecimalsPreserveText() {
        val chunks = SpeechChunker()
        chunks.append("It costs 3.")
        assertNull(chunks.take())
        chunks.append("50 dollars. Next")
        assertEquals("It costs 3.50 dollars.", chunks.take())
        assertNull(chunks.take())
        assertEquals("Next", chunks.take(final = true))
        assertNull(chunks.take(final = true))
    }

    @Test fun shortReplyFlushesWithoutWaitingForAnotherSentence() {
        val chunks = SpeechChunker()
        chunks.append("Hello, sir.")
        assertEquals("Hello, sir.", chunks.take(final = true))
    }

    @Test fun longTokenStreamLosesNoWordsAndStartsWithShorterChunk() {
        val chunks = SpeechChunker()
        val text = (1..150).joinToString(" ") { "word$it" }
        val result = mutableListOf<String>()
        for (character in text) {
            chunks.append(character.toString())
            while (true) result += chunks.take() ?: break
        }
        while (true) result += chunks.take(final = true) ?: break
        assertEquals(text, result.joinToString(" "))
        assertTrue(result.first().length < result[1].length)
        assertTrue(result.all { it.length < 200 })
    }

    @Test fun slowSynthesisReducesFollowingChunkSize() {
        fun next(rtf: Double): String {
            val chunks = SpeechChunker()
            chunks.append("Hello. ")
            chunks.take()
            chunks.observe(rtf)
            chunks.append((1..60).joinToString(" ") { "word" })
            return requireNotNull(chunks.take())
        }
        assertTrue(next(1.7).length < next(0.7).length)
    }

    @Test fun firstClauseCanStartBeforeLongSentenceFinishes() {
        val chunks = SpeechChunker()
        chunks.append("Beyond the quiet harbor and its old lighthouse, the pirate captain watched the distant horizon")
        assertEquals("Beyond the quiet harbor and its old lighthouse,", chunks.take())
    }
    @Test fun varyingAudioBudgetsPreserveAllWordsWhenFlushing() {
        val text = "Here is a longer answer with several natural word boundaries that must survive shrinking audio budgets while the speaker is playing. It ends here."
        val chunker = SpeechChunker()
        chunker.append(text)
        val chunks = mutableListOf<String>()
        for (limit in listOf(180, 40, 70, 40, 40, 40)) {
            chunker.take(final = true, maxChars = limit)?.let { chunks += it }
        }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun shortSentenceTailIsNotAnOrphanNativeCall() {
        val chunker = SpeechChunker()
        chunker.append("He was a keeper of a silence that had a terrible price. Next sentence.")
        assertEquals("He was a keeper of a silence that had a terrible price.", chunker.take(maxChars = 40))
        assertEquals("Next sentence.", chunker.take(final = true))
    }

    @Test fun pocketKeepsSentenceAcrossCommaAndTinyAudioBudget() {
        val c = SpeechChunker(sentenceMode = true)
        c.append("Instead, she became the city's quiet guardian, ")
        assertNull(c.take(maxChars = 40))
        c.append("the keeper of its inner landscape. Next")
        assertEquals("Instead, she became the city's quiet guardian, the keeper of its inner landscape.", c.take(maxChars = 40))
        assertEquals("Next", c.take(final = true))
    }
    @Test fun pocketPreservesQuotedEndsDecimalsAndAbbreviations() {
        val c = SpeechChunker(sentenceMode = true)
        c.append("Dr. Smith said, \"It costs 3.")
        assertNull(c.take())
        c.append("50 dollars.\" Next")
        assertEquals("Dr. Smith said, \"It costs 3.50 dollars.\"", c.take())
        assertEquals("Next", c.take(final = true))
    }
    @Test fun pocketRunOnInputStaysBoundedAndLosesNoText() {
        val text = (1..150).joinToString(" ") { "word$it" }
        val c = SpeechChunker(sentenceMode = true); val result = mutableListOf<String>()
        for (ch in text) {
            c.append(ch.toString())
            while (true) result += c.take() ?: break
        }
        while (true) result += c.take(final = true) ?: break
        assertEquals(text, result.joinToString(" "))
        assertTrue(result.all { it.length <= SpeechChunker.SENTENCE_LIMIT })
    }

    @Test fun kokoroGroupsShortIntroductionsWithoutDroppingText() {
        val chunks = SpeechChunker(60, minPhraseChars = 40)
        chunks.append("Certainly, sir. Here is a joke for you. ")
        assertNull(chunks.take())
        chunks.append("Why did the scarecrow win? Because he was outstanding.")
        val result = mutableListOf<String>()
        while (true) result += chunks.take(final = true) ?: break
        assertEquals("Certainly, sir. Here is a joke for you. Why did the scarecrow win? Because he was outstanding.", result.joinToString(" "))
        assertTrue(result.first().length >= 40)
        val short = SpeechChunker(60, minPhraseChars = 40)
        short.append("Hello, sir.")
        assertEquals("Hello, sir.", short.take(final = true))
    }

}
