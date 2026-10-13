package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceInterruptionTestTest {
    @Test fun onlyExplicitTestCommandOverridesGeneration() {
        assertTrue(VoiceInterruptionTest.requested("Start the interruption test."))
        assertTrue(VoiceInterruptionTest.requested("start interruption test"))
        for (text in listOf("do not start interruption test", "what is the interruption test", "start interruption test then open settings", "tell me a story")) {
            assertFalse(VoiceInterruptionTest.requested(text))
        }
        assertTrue(VoiceInterruptionTest.passage.split(Regex("\\s+")).size >= 100)
    }
}
