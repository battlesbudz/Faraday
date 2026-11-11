package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared legacy-markup detector: bare `call:<name>{...}` spans are
 * located, counted as markup, and stripped, while ordinary prose that
 * merely talks about calls is left alone.
 */
class ToolCallMarkupTest {

    @Test fun callBlockFindsTheBareLegacyForm() {
        val text = "call:run_in_sandbox{command:<|\"|>cat note.txt<|\"|>}"
        assertEquals(text, ToolCallMarkup.callBlock(text))
    }

    @Test fun callBlockFindsTheSpanInsideWrappedMarkup() {
        // The build-1771 specimen shape: a hallucinated namespaced tool
        // name inside the wrapped form. The span is located verbatim;
        // strict parsing rejects it later, which is the salvage layer's
        // job, not the locator's.
        val text = "<|tool_call>call:sandbox:read_file{file_path:note.txt}<tool_call|>"
        assertEquals("call:sandbox:read_file{file_path:note.txt}", ToolCallMarkup.callBlock(text))
    }

    @Test fun callBlockRunsToTheEndWhenBracesNeverBalance() {
        val text = "call:run_in_sandbox{command:<|\"|>cat note.txt"
        assertEquals(text, ToolCallMarkup.callBlock(text))
    }

    @Test fun callBlockIgnoresOrdinaryProseAboutCalls() {
        assertNull(ToolCallMarkup.callBlock("I'll call the office tomorrow and ask."))
        assertNull(ToolCallMarkup.callBlock("Use the format call: name {args} in the docs."))
        assertNull(ToolCallMarkup.callBlock("recall: the meeting moved to Friday"))
        assertNull(ToolCallMarkup.callBlock("A plain answer with {braces} but no call."))
    }

    @Test fun containsCoversWrappedAndBareForms() {
        assertTrue(ToolCallMarkup.contains("<|tool_call>call:open_app{}<tool_call|>"))
        assertTrue(ToolCallMarkup.contains("<start_function_call>call:open_app{}"))
        assertTrue(ToolCallMarkup.contains("call:run_in_sandbox{command:<|\"|>ls<|\"|>}"))
        assertFalse(ToolCallMarkup.contains("The sandbox is installed and ready."))
    }

    @Test fun stripRemovesBareBlocksAndKeepsSurroundingProse() {
        val text = "Checking now. call:run_in_sandbox{command:<|\"|>ls<|\"|>} Done."
        assertEquals("Checking now.  Done.", ToolCallMarkup.strip(text))
        assertEquals("No markup here.", ToolCallMarkup.strip("No markup here."))
    }

    @Test fun forDisplayNeverPublishesABareCallBlock() {
        val markup = "call:run_in_sandbox{command:<|\"|>cat note.txt<|\"|>}"
        assertEquals("", AssistantText.forDisplay(markup))
        assertEquals("Here is the result.",
            AssistantText.forDisplay("Here is the result. $markup"))
    }
}
