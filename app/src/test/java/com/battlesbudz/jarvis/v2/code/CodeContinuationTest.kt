package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuation stitching (scope section 4.2): the cut-off signals, the
 * continuation prompt, and the stitch — overlap de-dup and the
 * re-emitted header strip — that turns a truncated response and its
 * continuations into one coherent action stream.
 */
class CodeContinuationTest {

    private val completeWrite =
        "<action name=\"write_file\"><path>index.html</path><content>" +
            "<!DOCTYPE html>\n<html><body>Done</body></html></content></action>"

    @Test fun completeEnvelopeIsNotCutOff() {
        assertFalse(CodeContinuation.looksCutOff(completeWrite))
    }

    @Test fun unclosedEnvelopeIsCutOffAtAnyLength() {
        assertTrue(CodeContinuation.looksCutOff(
            "<action name=\"write_file\"><path>index.html</path><content><!DOCTYPE html>\n<html><bo"))
    }

    @Test fun longTextWithNoClosingActionTagIsCutOff() {
        assertTrue(CodeContinuation.looksCutOff("x".repeat(CodeContinuation.MIN_CUT_OFF_CHARS)))
        assertFalse(CodeContinuation.looksCutOff("x".repeat(CodeContinuation.MIN_CUT_OFF_CHARS - 1)))
    }

    @Test fun htmlWriteMissingItsCloseIsCutOff() {
        val raw = "<action name=\"write_file\"><path>index.html</path><content>" +
            "<!DOCTYPE html>\n<html><body>never closed</content></action>"
        assertTrue(CodeContinuation.looksCutOff(raw))
    }

    @Test fun replyIsNotCutOff() {
        assertFalse(CodeContinuation.looksCutOff("<action name=\"reply\">All done.</action>"))
    }

    @Test fun continuationPromptQuotesTheEnding() {
        val raw = "abcdefghijklmnopqrstuvwxyz"
        val prompt = CodeContinuation.continuationPrompt(raw)
        assertTrue(prompt.startsWith("Continue from the exact next character after this ending: "))
        assertTrue(prompt.endsWith(raw))
    }

    @Test fun appendWithoutOverlapIsPlainConcatenation() {
        assertEquals("abcdef", CodeContinuation.appendContinuation("abc", "def"))
    }

    @Test fun appendDeduplicatesTheOverlap() {
        val stitched = CodeContinuation.appendContinuation("the quick brown", "brown fox jumps")
        assertEquals("the quick brown fox jumps", stitched)
    }

    @Test fun overlapIsCappedAtTheNamedWindow() {
        val existing = "a".repeat(1500)
        val continuation = "a".repeat(1500) + "b"
        assertEquals(CodeContinuation.MAX_OVERLAP_CHARS,
            CodeContinuation.overlapLength(existing, continuation))
        assertEquals(1500 + 500 + 1, CodeContinuation.appendContinuation(existing, continuation).length)
    }

    @Test fun appendStripsAReemittedHeaderAndStitchesOneCoherentAction() {
        val existing = "<action name=\"write_file\"><path>index.html</path><content>" +
            "<!DOCTYPE html>\n<html><body><h1>Sna"
        val continuation = "<action name=\"write_file\"><path>index.html</path><content>" +
            "ke</h1></body></html></content></action>"
        val stitched = CodeContinuation.appendContinuation(existing, continuation)
        val parsed = CodeActionParser.parse(stitched) as CodeParseResult.Actions
        val write = parsed.actions.single() as CodeAction.WriteFile
        assertEquals("<!DOCTYPE html>\n<html><body><h1>Snake</h1></body></html>", write.content)
        assertFalse(CodeContinuation.looksCutOff(stitched))
    }

    @Test fun appendStripsABareReemittedActionTag() {
        val stitched = CodeContinuation.appendContinuation(
            "<action name=\"reply\">Hel", "<action name=\"reply\">lo</action>")
        assertEquals("<action name=\"reply\">Hello</action>", stitched)
    }

    @Test fun stripReemittedHeaderLeavesPlainTextAlone() {
        val plain = "just the next characters, no envelope"
        assertEquals(plain, CodeContinuation.stripReemittedHeader(plain))
    }
}
