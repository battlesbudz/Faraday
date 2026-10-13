package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PreparedSpeechOpeningTest {
    private fun audio(text: String = "A short opening.") = SpeechAudio(text, 24000, shortArrayOf(1, 2, 3), 80)

    @Test fun synthesisAndFinalTranscriptAloneDoNotAuthorizePlayback() {
        val opening = PreparedSpeechOpening("A short opening.")
        opening.complete(audio())
        assertNull(opening.takeFor("A short opening."))
        opening.authorize()
        assertNotNull(opening.takeFor("A short opening."))
        assertNull(opening.takeFor("A short opening."))
    }

    @Test fun changedOutputAndCancelledDraftCannotReusePcm() {
        val opening = PreparedSpeechOpening("A short opening.")
        opening.complete(audio()); opening.authorize()
        assertNull(opening.takeFor("A corrected opening."))
        opening.discard()
        opening.complete(audio()); opening.authorize()
        assertNull(opening.takeFor("A short opening."))
    }

    @Test fun cancellationDuringNativeSynthesisCannotPublishLateAudio() {
        val opening = PreparedSpeechOpening("A short opening.")
        opening.discard() // Native generate may still finish after cancellation.
        opening.complete(audio())
        opening.authorize()
        assertTrue(opening.isDiscarded())
        assertNull(opening.takeFor("A short opening."))
    }

    @Test fun oversizedSpeculativeAudioIsNotRetained() {
        val opening = PreparedSpeechOpening("A short opening.")
        opening.complete(SpeechAudio(opening.text, 24000, ShortArray(24000 * 13), 80))
        opening.authorize()
        assertNull(opening.takeFor(opening.text))
    }
}
