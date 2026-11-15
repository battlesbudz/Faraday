package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemplateVoiceSessionTest {
    @Test fun buffersWithoutGeneratingUntilFinalAndUsesPlainText() = runBlocking {
        val submissions = mutableListOf<String>()
        val visible = StringBuilder()
        val session = TemplateVoiceSession { prompt, image, emit ->
            assertNull(image)
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
        val session = TemplateVoiceSession { _, _, _ -> calls++; error("Should not generate") }
        session.append("incorrect partial")
        session.close()
        assertEquals(0, calls)
        try { session.decode {}; fail("Closed session accepted decode") }
        catch (_: IllegalStateException) { }
    }

    @Test fun appendedImageIsBufferedAndHandedToGenerateAtDecode() = runBlocking {
        var receivedImage: ByteArray? = null
        var receivedPrompt: String? = null
        val session = TemplateVoiceSession { prompt, image, _ ->
            receivedPrompt = prompt
            receivedImage = image
            GenerationResult("Answer", 1, null)
        }
        session.append("User words")
        val image = byteArrayOf(5, 6, 7)
        session.appendImage(image)
        session.decode {}
        assertEquals("User words", receivedPrompt)
        assertTrue(image.contentEquals(receivedImage!!))
        try {
            session.appendImage(byteArrayOf(8))
            fail("Second image in one turn accepted")
        } catch (_: IllegalStateException) { }
        session.close()
    }
}
