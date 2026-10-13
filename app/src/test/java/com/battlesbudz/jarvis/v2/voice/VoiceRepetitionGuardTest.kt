package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceRepetitionGuardTest {
    private val repeated = "Programming an AI to understand emotions from voice and text could be incredibly powerful for building more empathetic and responsive interactions. " +
        "It could help in many areas, from customer service to mental health support, by allowing systems to better gauge a person's state and respond in a way that is more appropriate and supportive."

    @Test fun logExampleNeverPublishesRepeatedParagraphEvenWithDifferentOpening() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Would this be helpful for Jarvis right now?",
            "That's a fascinating idea. $repeated", spoken::append)
        ("That's a very interesting idea. $repeated").chunked(7).forEach(guard::accept)
        guard.finish()
        assertFalse(spoken.contains("Programming an AI"))
        assertFalse(spoken.contains("customer service"))
        assertEquals(2, guard.suppressedSentences)
        guard.accept("Yes. Jarvis could shorten its replies when you sound frustrated.")
        guard.finish()
        assertTrue(spoken.contains("Jarvis could shorten"))
        assertEquals(spoken.toString(), guard.text)
    }
    @Test fun echoesAreHeldBeforeAnyAudioIsPublished() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Could emotion detection help a Jarvis phone assistant?", null, spoken::append)
        guard.accept("Could emotion detection help")
        assertTrue(spoken.isEmpty())
        guard.accept(" a Jarvis phone assistant?")
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(1, guard.suppressedSentences)
    }
    @Test fun catchesNearDuplicatesAndRepetitionInsideOneReply() {
        assertTrue(VoiceRepetitionGuard.duplicates(
            "Programming an AI to understand emotions from voice and text could be very powerful for building more empathetic and responsive interactions.", repeated))
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Should we build it?", null, spoken::append)
        guard.accept("Start with shorter replies. Start with shorter replies.")
        guard.finish()
        assertEquals("Start with shorter replies.", spoken.toString())
    }
    @Test fun newFactsAndShortSharedPhrasesAreAllowed() {
        assertFalse(VoiceRepetitionGuard.duplicates("Your battery is at 99 percent.", "Your battery is at 100 percent."))
        assertFalse(VoiceRepetitionGuard.duplicates("Jarvis could shorten replies when you sound frustrated.", repeated))
    }
    @Test fun finalOnlyAndDecimalAnswersRemainIntact() {
        val guard = VoiceRepetitionGuard("How long?", null) {}
        guard.accept("About 2.")
        guard.accept("5 seconds.")
        assertEquals("About 2.5 seconds.", guard.finish())
        val finalOnly = VoiceRepetitionGuard("Anything else?", "That is all.") {}
        assertEquals("", finalOnly.finish("THAT IS ALL!"))
        assertEquals(1, finalOnly.suppressedSentences)
    }
    @Test fun shortSentencesCannotLeakBeforeTheFinalReplyCheck() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("What next?", "Good idea. Let's go.", spoken::append)
        "Good idea. Let's go.".chunked(2).forEach(guard::accept)
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(2, guard.suppressedSentences)
    }

    @Test fun multiSentenceUserEchoIsBlockedEvenWhenEachQuestionIsShort() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("What now? Why?", null, spoken::append)
        "What now? Why?".chunked(2).forEach(guard::accept)
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(2, guard.suppressedSentences)
    }

    @Test fun repairOnlyWhenRepetitionLeavesNothingToSay() {
        val guard = VoiceRepetitionGuard("What now?", "Let's go.") {}
        guard.finish("Let's go.")
        assertTrue(guard.needsRepair)
        guard.accept("We can start with the microphone."); guard.finish()
        assertFalse(guard.needsRepair)
        assertEquals(1, guard.suppressedSentences)
    }
    @Test fun decimalDoesNotHoldSubsequentSentencesUntilGenerationEnds() {
        val published = mutableListOf<String>()
        val guard = VoiceRepetitionGuard("How long?", null, published::add)
        guard.accept("It takes 2.")
        assertTrue(published.isEmpty())
        guard.accept("5 seconds. Then continue. More is still generating")
        assertEquals(listOf("It takes 2.5 seconds.", " Then continue."), published)
    }
    @Test fun checkedSentenceIsImmediatelyAvailableToEverySpeechChunker() {
        val phrase = "The old lighthouse was empty."
        for (sentence in listOf(false, true)) {
            val chunks = SpeechChunker(sentenceMode = sentence)
            val guard = VoiceRepetitionGuard("Tell a story", null) {
                chunks.append(VoiceRepetitionGuard.speechReady(it))
            }
            guard.accept(phrase)
            assertEquals(phrase, chunks.take())
        }
    }

    @Test fun nicotineLoopStopsAcrossEveryTokenSplitWithValidOpening() {
        val answer = "Vaping has several considerations. It involves nicotine- " + "nicotine- ".repeat(1000)
        for (split in listOf(1, 2, 7, 31, answer.length)) {
            val spoken = StringBuilder()
            val guard = VoiceRepetitionGuard("Opinion?", null, spoken::append)
            try { answer.chunked(split).forEach(guard::accept); fail("Loop must cancel native stream") }
            catch (_: VoiceRepetitionGuard.RunawayLoop) {}
            assertTrue(guard.loopDetected)
            assertTrue(guard.needsRepair)
            assertEquals("Vaping has several considerations.", guard.finish())
            assertEquals(guard.text, spoken.toString())
            try { guard.accept("Leaked late sentence."); fail("Late broken generation is sealed") }
            catch (_: VoiceRepetitionGuard.RunawayLoop) {}
            guard.beginRepair()
            guard.accept("It is sensible to understand its risks.")
            assertFalse(guard.needsRepair)
            assertEquals("Vaping has several considerations. It is sensible to understand its risks.", guard.finish())
        }
    }

    @Test fun repeatedUnfinishedMultiwordPhraseCannotReachSpeech() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Continue", null, spoken::append)
        try {
            ("Here is a detail " + "heated tobacco and nicotine ".repeat(20)).chunked(3).forEach(guard::accept)
            fail("Repeated phrase must cancel")
        } catch (_: VoiceRepetitionGuard.RunawayLoop) {}
        assertTrue(guard.loopDetected)
        assertTrue(spoken.isEmpty())
        assertEquals("", guard.finish())
    }

    @Test fun legitimateRepetitionListsMathAndLongStoryRemainIntact() {
        val samples = listOf(
            "Very, very, very good. The bell rang again, and again, and again.",
            "First: 1 1 1 1 1 1 1 1. Second: 2 2 2 2 2 2 2 2.",
            "The value is 2.5. Add 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1.",
            (1..200).joinToString(" ") { "At mile $it the traveller found a different landmark." }
        )
        for (sample in samples) {
            val guard = VoiceRepetitionGuard("Explain", null) {}
            sample.chunked(2).forEach(guard::accept)
            assertEquals(sample, guard.finish())
            assertFalse(guard.loopDetected)
        }
    }

    @Test fun formattingPreservingCodeAndDataBypassSpeechLoopHeuristic() {
        val sample = "```kotlin\n" + "println(\"nicotine\")\n".repeat(10) + "```\n" +
            "nicotine- ".repeat(10)
        val guard = VoiceRepetitionGuard("Show code", null) {}
        guard.preserveFormatting = true
        sample.chunked(3).forEach(guard::accept)
        assertEquals(sample, guard.finish())
        assertFalse(guard.loopDetected)
    }

    @Test fun finalUndelimitedLoopWordIsCheckedBeforeFinishPublishes() {
        val guard = VoiceRepetitionGuard("Explain", null) {}
        guard.accept(List(8) { "nicotine" }.joinToString("-"))
        try { guard.finish(); fail("Final undelimited word completes the loop") }
        catch (_: VoiceRepetitionGuard.RunawayLoop) {}
        assertTrue(guard.loopDetected)
        assertEquals("", guard.text)
    }

}
