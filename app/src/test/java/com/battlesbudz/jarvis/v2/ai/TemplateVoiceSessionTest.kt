package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemplateVoiceSessionTest {
    @Test fun buffersWithoutGeneratingUntilFinalAndUsesPlainText() = runBlocking {
        val submissions = mutableListOf<String>()
        val visible = StringBuilder()
        val session = TemplateVoiceSession { prompt, emit ->
            submissions += prompt
            emit("Answer")
            GenerationResult("Answer", 1, null)
        }
        session.append("Instructions\n")
        session.append("User words")
        assertTrue(submissions.isEmpty())
        assertEquals("Answer", session.decode { visible.append(it) }.text)
        assertEquals(listOf("Instructions\nUser words"), submissions)
        assertEquals("Answer", visible.toString())
        session.close()
    }

    @Test fun abandoningRevisedInputDoesNotSubmitIt() = runBlocking {
        var calls = 0
        val session = TemplateVoiceSession { _, _ -> calls++; error("Should not generate") }
        session.append("incorrect partial")
        session.close()
        assertEquals(0, calls)
        try { session.decode {}; fail("Closed session accepted decode") }
        catch (_: IllegalStateException) { }
    }
}
