package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class BargeInGateTest {
    @Test fun soundCaptionsAndPunctuationNeverBecomeWordsEvenWithOwnerOverlapEnabled() {
        for (text in listOf("(buzzer)", "(buzzing)", "[beeping]", "[cough]", "(sneezing)", "[wind]", "[car noise]", "[yelling]", "...", "'''", "♪")) {
            val gate = BargeInGate(stableMs = 0, allowShortEchoOverlap = true)
            repeat(20) { assertEquals(text, BargeInGate.Action.WAIT, gate.update(true, true, it * 100L, text, "The sky is blue")) }
        }
    }
    @Test fun wordsBesideBuzzingCaptionsStillInterrupt() {
        for (word in listOf("No", "Yes", "I", "stop", "buzzing")) {
            val gate = BargeInGate(stableMs = 0, allowShortEchoOverlap = true)
            assertEquals(word, BargeInGate.Action.CONFIRM,
                gate.update(true, true, 0, "(buzzing) $word", "The sky is blue"))
            assertEquals(word.lowercase(), gate.requestText)
        }
    }
    @Test fun build709PlaybackSubstitutionsCannotBecomeNewUserWords() {
        val reference = "One moment please sir. The sky appears blue because of Rayleigh scattering of sunlight. " +
            "This phenomenon occurs when sunlight interacts with the Earth's atmosphere. " +
            "The shorter blue wavelengths of light are scattered more effectively by the tiny molecules of air."
        for (text in listOf("tracks with the Earth's atmosphere. The shorter, blue wavelengths of light.",
            "This guy is blue due to Rayleigh'", "The sky at the")) {
            val ref = if (text.startsWith("This guy")) "The sky is blue due to Rayleigh scattering of sunlight." else reference
            val gate = BargeInGate(stableMs = 0, allowShortEchoOverlap = true)
            repeat(5) { assertEquals(text, BargeInGate.Action.WAIT, gate.update(true, true, it * 100L, text, ref)) }
        }
    }
    @Test fun acousticNoiseAloneNeverCancelsPreparingOrPlayingReply() {
        for (playing in listOf(false, true)) {
            val gate = BargeInGate()
            repeat(100) { assertEquals(BargeInGate.Action.WAIT, gate.update(true, playing, it * 100L)) }
            assertFalse(gate.confirmed)
        }
    }
    @Test fun anyUserWordsTakeTheFloor() {
        for (playing in listOf(false, true)) {
            for (text in listOf("No", "Yes", "I", "squirrel", "It is", "That's what we need", "I can tell them", "I can ask that", "We should know")) {
                val gate = BargeInGate()
                assertEquals(BargeInGate.Action.WAIT, gate.update(true, playing, 0, text))
                assertEquals(text, BargeInGate.Action.CONFIRM, gate.update(true, playing, 300, text))
            }
        }
    }
    @Test fun singleNoSurvivesAcknowledgementEcho() {
        for (word in listOf("No", "Yes", "I")) {
            val gate = BargeInGate()
            val mixed = "$word. One moment, please, sir."
            val reference = "One moment, please, sir. A brave squirrel found a lost acorn."
            gate.update(true, true, 0, mixed, reference)
            assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, true, 300, mixed, reference))
            assertEquals(word.lowercase(), gate.requestText)
        }
    }
    @Test fun deliberateCorrectionsAndQuestionsWorkInBothPhases() {
        for (playing in listOf(false, true)) {
            for (text in listOf("actually open settings", "I meant tomorrow", "what is my battery", "stop")) {
                val gate = BargeInGate()
                assertEquals(BargeInGate.Action.WAIT, gate.update(true, playing, 0, text))
                assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, playing, 300, text))
            }
        }
    }
    @Test fun repeatedSpeakerEchoNeverInterruptsOrRequestsAPause() {
        val gate = BargeInGate()
        repeat(100) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(true, true, it * 100L, "The garden gate is open", "The garden gate is open, and the breeze is cool.")) }
        assertFalse(gate.confirmed)
    }
    @Test fun stableDifferentWordsConfirmExactlyOnce() {
        val gate = BargeInGate()
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 0, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 299, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(false, true, 300, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(false, true, 301))
    }
    @Test fun changingWordsRestartConfirmation() {
        val gate = BargeInGate()
        gate.update(true, true, 0, "open instagram", "Hello there")
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 300, "open youtube", "Hello there"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, true, 600, "open youtube", "Hello there"))
    }
    @Test fun hallucinatedWordsWithoutAcousticSpeechCannotInterrupt() {
        val gate = BargeInGate()
        repeat(20) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(false, true, it * 100L, "open youtube", "The sky is blue")) }
    }
    @Test fun stopCanInterruptButJarvisSayingStopCannot() {
        val gate = BargeInGate()
        gate.update(true, true, 0, "stop", "The sky is blue")
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, true, 300, "stop", "The sky is blue"))
        val echo = BargeInGate()
        repeat(10) { assertEquals(BargeInGate.Action.WAIT,
            echo.update(true, true, it * 100L, "stop", "You can say stop to interrupt me")) }
    }
    @Test fun isolatedRecognitionErrorInEchoIsRejected() {
        val gate = BargeInGate()
        repeat(10) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(true, true, it * 100L, "The garden grate is open", "The garden gate is open")) }
    }
    @Test fun requestInsideEchoSurvivesMoreStoryWordsAndPlaybackGaps() {
        val spoken = "He wasn't just a keeper of the light. He was a keeper of a silence that had a terrible price."
        for (playing in listOf(true, false)) {
            val gate = BargeInGate()
            val early = "He wasn't just a keeper of the light. Can you open up Facebook?"
            val later = early + " He was a keeper of a silence that had a terror."
            assertEquals(BargeInGate.Action.WAIT, gate.update(true, playing, 0, early, spoken))
            assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, playing, 300, later, spoken))
        }
    }
    @Test fun echoedQuestionCannotInterruptEvenDuringAnUnderrun() {
        val text = "Can you open up Facebook?"
        val gate = BargeInGate()
        repeat(20) { assertEquals(BargeInGate.Action.WAIT, gate.update(true, false, it * 100L, text, text)) }
    }
    @Test fun growingRequestDoesNotRestartStablePrefix() {
        val gate = BargeInGate()
        gate.update(true, true, 0, "Can you open up", "The old lighthouse keeper")
        assertEquals(BargeInGate.Action.CONFIRM,
            gate.update(true, true, 300, "Can you open up Facebook?", "The old lighthouse keeper"))
    }

}
