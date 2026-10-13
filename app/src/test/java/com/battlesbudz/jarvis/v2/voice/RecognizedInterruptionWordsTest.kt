package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class RecognizedInterruptionWordsTest {
    @Test fun soundAnnotationsAndRustleNeverSupplyInterruptionWords() {
        for (noise in listOf("[cough]", "(sneezing)", "[rustling]", "[cloth rustle]", "[wind]", "..."))
            assertTrue(RecognizedInterruptionWords.words(noise).isEmpty())
    }

    @Test fun literalWordsRemainEligibleWithoutSpeakerIdentity() {
        assertEquals(listOf("no"), RecognizedInterruptionWords.words("No"))
        assertEquals(listOf("actually", "wait"), RecognizedInterruptionWords.words("Actually wait"))
    }

    @Test fun keywordDetectionNeedsFreshNonEchoAsrWords() {
        assertFalse(RecognizedInterruptionWords.confirmsKeyword("Hey_Jarvis", "[rustling]", "The sky is blue"))
        assertFalse(RecognizedInterruptionWords.confirmsKeyword("Hey_Jarvis", "Hey Jarvis", "You can say Hey Jarvis"))
        assertFalse(RecognizedInterruptionWords.confirmsKeyword("Hey_Jarvis", "Hey Jarvis", "Hey Jarvis"))
        assertTrue(RecognizedInterruptionWords.confirmsKeyword("Hey_Jarvis", "Hey Jarvis", "The sky is blue"))
    }
}
