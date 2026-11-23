package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptOfRecordTest {
    @Test fun recognizerWinsOverCommentaryLadenCaption() {
        assertEquals("So what you been up to today?", resolve(
            "So what you been up to today?",
            "It's what you've been up to today. This is a question about your recent activities."
        ))
        assertEquals("I said what have you been up to today?", resolve(
            "I said what have you been up to today?",
            "I said what have you been up to today. This is a question about your recent activities."
        ))
    }

    @Test fun recognizerWinsWhenCaptionIsEmpty() {
        assertEquals("Turn on the light", resolve("Turn on the light", ""))
    }

    @Test fun recognizerWinsWhenCaptionEmittedToolCalls() {
        assertEquals("Turn on the light", resolve("Turn on the light", "Turn on the light", captionEmittedToolCalls = true))
    }

    @Test fun cleanCaptionIsFallbackWhenRecognizerProducedNothing() {
        assertEquals("Turn on the light", resolve("", "Turn on the light"))
    }

    @Test fun noSpeechCaptionIsNotARecord() {
        assertNull(resolve("", "[NO_SPEECH]"))
    }

    @Test fun soundOnlyCaptionIsNotARecord() {
        assertNull(resolve("", "[music]"))
    }

    @Test fun disagreementBlockRejectsEvenRecognizerText() {
        assertNull(resolve("Hello there", "", disagreementBlocked = true))
    }

    @Test fun untranscribedRecognizerUsesCleanCaptionFallback() {
        assertEquals("Turn on the light", resolve(VoiceTranscriptResolver.UNTRANSCRIBED, "Turn on the light"))
    }

    @Test fun finalCaptionSessionIsAlwaysIsolated() {
        assertEquals(FinalCaptionSessionPolicy.Session.ISOLATED, FinalCaptionSessionPolicy.choose(true))
        assertEquals(FinalCaptionSessionPolicy.Session.ISOLATED, FinalCaptionSessionPolicy.choose(false))
    }

    private fun resolve(
        asr: String,
        caption: String,
        captionEmittedToolCalls: Boolean = false,
        disagreementBlocked: Boolean = false
    ) = TranscriptOfRecord.resolve(asr, caption, captionEmittedToolCalls, disagreementBlocked)
}
