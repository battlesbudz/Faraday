package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class TranscriptDisagreementTest {
    // Build 1771, call 83164c01: on one barge turn Whisper produced the
    // English line below while Gemma's final caption produced the Hindi
    // line. Battles confirmed he said neither — both engines invented
    // words on the same audio — yet the caption was committed as his
    // words into dialogue history and recent-request evidence.
    private val specimenWhisper = "and it does not matter."
    private val specimenGemma = "प्रस्थान मेरा है"

    @Test fun specimenTotalDisagreementAcrossScriptsIsHardBlocked() {
        assertTrue(TranscriptDisagreement.hardBlock(specimenWhisper, specimenGemma))
        // The block is symmetric: neither recognizer is trusted over the other.
        assertTrue(TranscriptDisagreement.hardBlock(specimenGemma, specimenWhisper))
    }

    @Test fun agreementIsUntouched() {
        assertFalse(TranscriptDisagreement.hardBlock(
            "what is the battery level", "What is the battery level?"))
    }

    @Test fun partialOverlapIsUntouched() {
        assertFalse(TranscriptDisagreement.hardBlock(
            "turn on the kitchen light", "turn on the light please"))
        // ASR noise on one word still leaves the shared words standing.
        assertFalse(TranscriptDisagreement.hardBlock(
            "weather tomorrow", "whether tomorrow"))
        // A shared token across scripts is still agreement.
        assertFalse(TranscriptDisagreement.hardBlock(
            "open youtube", "youtube खोलो"))
    }

    @Test fun sameScriptTotalDisagreementIsOrdinaryNoiseAndUntouched() {
        // Zero shared words, one script: a rephrase or a mishear, never
        // the two-inventions signature this block exists for.
        assertFalse(TranscriptDisagreement.hardBlock(
            "turn it up", "increase the volume"))
        assertFalse(TranscriptDisagreement.hardBlock(
            "call mom", "phone mother"))
    }

    @Test fun singleRecognizerTurnsAreUntouched() {
        // Only one side produced words: nothing to disagree with. The
        // empty side is the follow-up confirmation gate's territory.
        assertFalse(TranscriptDisagreement.hardBlock("", specimenGemma))
        assertFalse(TranscriptDisagreement.hardBlock("hello there", ""))
        assertFalse(TranscriptDisagreement.hardBlock("", ""))
        assertFalse(TranscriptDisagreement.hardBlock("   ", "  "))
    }

    @Test fun markersAndSoundCaptionsAreNotWords() {
        assertFalse(TranscriptDisagreement.hardBlock("[NO_SPEECH]", specimenGemma))
        assertFalse(TranscriptDisagreement.hardBlock("(coughing)", specimenGemma))
        assertFalse(TranscriptDisagreement.hardBlock(specimenWhisper, "[NO_SPEECH]"))
    }

    @Test fun mostlyOneScriptStaysThatScript() {
        // No shared words here, so only the script test can decide: a
        // stray Hindi word inside an otherwise English caption does not
        // change its dominant script, so the pair is same-script and
        // stays unblocked.
        assertFalse(TranscriptDisagreement.hardBlock(
            "battery percentage now", "प्रस्थान check remaining charge"))
    }
}
