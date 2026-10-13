package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PiperTextStreamTest {
    private val earlySentence = "A civilization capable of that would need to harness energy on a stellar scale."

    @Test fun firstCompletedSentenceReleasesWithoutWaitingForFullReply() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("$earlySentence The next explanation")
        assertEquals(earlySentence, stream.take())
        assertNull(stream.openingWaitMs())
        stream.append(" continues here.")
        clock = 5000
        assertNull(stream.take()) // Later passages retain the 320-character policy.
        assertEquals("The next explanation continues here.", stream.take(final = true))
    }

    @Test fun immediateOpeningPreservesAbbreviationsAndSplitDecimals() {
        val stream = PiperTextStream()
        stream.append("Dr. Smith measured 3.")
        assertNull(stream.take())
        stream.append("14 volts before giving a complete explanation.")
        assertEquals("Dr. Smith measured 3.14 volts before giving a complete explanation.", stream.take())
    }

    @Test fun immediateShortOpeningStillNeverReleasesIncompleteSentence() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("Yes, sir. " + "This explanation is not finished yet ".repeat(3))
        assertEquals("Yes, sir.", stream.take())
        clock = 5000
        assertNull(stream.take())
        stream.append("and now it is. ")
        assertNotNull(stream.take(final = true))
    }

    @Test fun fullReplyPolicyStillWaitsWhileDefaultStreamsOpening() {
        var clock = 0L
        val normal = PiperTextStream(nowMs = { clock })
        val full = PiperTextStream(waitForEnd = true, openingTargetChars = 160, nowMs = { clock })
        for (stream in listOf(normal, full)) stream.append("$earlySentence Another sentence is ready. ")
        clock = 10_000
        assertEquals(earlySentence, normal.take())
        assertEquals("Another sentence is ready.", normal.take(final = true))
        assertNull(full.openingWaitMs()); assertNull(full.take())
        assertEquals("$earlySentence Another sentence is ready.", full.take(final = true))
    }

    @Test fun waitingForOpeningDoesNotMistakeAnAbbreviationForASentence() {
        var clock = 0L
        val stream = PiperTextStream(openingTargetChars = 160, nowMs = { clock })
        stream.append("The physician who will explain all the details to you today is Dr. ")
        clock = 1000
        assertNull(stream.take()); assertNull(stream.openingWaitMs())
        stream.append("Smith. ")
        assertTrue(requireNotNull(stream.take()).endsWith("Dr. Smith."))
    }
    @Test fun optionalSmallerOpeningReturnsToLongPassagesWithoutLosingWords() {
        val sentence = "We can continue with this explanation for a little longer. "
        val text = sentence.repeat(20).trim()
        val stream = PiperTextStream(openingTargetChars = 160)
        val parts = mutableListOf<String>()
        text.chunked(4).forEach { stream.append(it); while (true) parts += stream.take() ?: break }
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.first().length in 1 until 320)
        assertTrue(parts[1].length in 320..640)
        assertTrue(parts.all { it.endsWith(".") && it.length <= 640 })
    }
    @Test fun shortRepliesStayTogetherUntilGemmaFinishes() {
        val text = "Good evening, sir. Your appointment begins in twenty minutes. There is time for tea."
        val stream = PiperTextStream()
        val parts = mutableListOf<String>()
        text.chunked(4).forEach { stream.append(it); stream.take()?.let(parts::add) }
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(text, parts.joinToString(" "))
        assertNull(stream.take(final = true))
    }

    @Test fun storyProducesLargerBoundedPassagesWithoutLosingWords() {
        val text = "Kiko stood on the deck as the moon rose above the island. The little monkey had found a map inside an old brass compass, and tonight his crew would follow it. Beyond the reef, a blue light flickered beneath the waves. Kiko lowered a lantern and discovered the roof of a sunken library. Its windows were still glowing. He smiled, tied a rope around his waist, and handed the other end to his first mate. Gold could wait. Somewhere below them was a story that no pirate had ever heard, and Kiko intended to bring it home."
        val parts = collect(text)
        assertTrue(parts.size >= 2)
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
        assertTrue(parts.first().endsWith("."))
    }

    @Test fun fullReplyModeWaitsForEndButStillBoundsNativeWork() {
        val text = (1..80).joinToString(" ") { "Sentence number $it is ready." }
        val stream = PiperTextStream(waitForEnd = true)
        text.chunked(4).forEach { stream.append(it); assertNull(stream.take()) }
        val parts = mutableListOf<String>()
        while (true) parts += stream.take(final = true) ?: break
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
    }

    @Test fun runOnTextAndUnbrokenTokensAreNotTruncated() {
        val words = (1..500).joinToString(" ") { "word$it" }
        assertEquals(words, collect(words).joinToString(" "))
        val token = "x".repeat(1800)
        val parts = collect(token)
        assertEquals(token, parts.joinToString(""))
        assertTrue(parts.all { it.length <= PiperTextStream.MAX_CHARS })
    }

    @Test fun quotedSentenceAndAbbreviationsKeepTheirBoundaries() {
        val prefix = "We can continue with this explanation for a little longer. ".repeat(5)
        val text = prefix + "Dr. Smith replied, ‘That is correct.’ Then we finished."
        assertEquals(text.trim(), collect(text).joinToString(" "))
        assertFalse(collect(text).any { it.endsWith("Dr.") })
    }

    @Test fun realAnswerTextMayUseOldCueWordsWithoutFiltering() {
        val text = "One moment, please, sir. Just a moment, sir. Those words belong to this actual answer."
        assertEquals(text, collect(text).joinToString(" "))
    }

    @Test fun waitingWithoutAnswerTextNeverCreatesWords() {
        var clock = 0L
        val stream = PiperTextStream(nowMs = { clock })
        repeat(10) {
            clock += 5_000
            assertNull(stream.take())
            assertNull(stream.openingWaitMs())
        }
        assertNull(stream.take(final = true))
    }

    private fun collect(text: String): List<String> {
        val stream = PiperTextStream()
        val parts = mutableListOf<String>()
        text.chunked(4).forEach { token ->
            stream.append(token)
            while (true) parts += stream.take() ?: break
        }
        while (true) parts += stream.take(final = true) ?: break
        return parts
    }
}
