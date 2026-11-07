package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fix-round prompt (scope section 4.5): the error text itself, up
 * to 4 distinct errors, each clipped, with its source position.
 */
class CodeConsolePromptTest {

    private fun error(text: String, line: Int = 1, source: String? = "https://appassets.androidplatform.net/index.html") =
        CodeConsoleMessage(CodeConsoleLevel.ERROR, text, source, line)

    @Test fun promptCarriesTheErrorTextAndItsSourcePosition() {
        val prompt = CodeConsolePrompt.compose(listOf(error("Uncaught TypeError: x is not a function", line = 42)))
        assertTrue(prompt.contains("Uncaught TypeError: x is not a function"))
        assertTrue(prompt.contains("(index.html:42)"))
        assertFalse(prompt.contains("appassets.androidplatform.net"))
    }

    @Test fun duplicatesCollapseAndTheCapIsFourDistinctErrors() {
        val errors = listOf(
            error("first", line = 1),
            error("first", line = 1), // the same throw, reported twice
            error("second", line = 2),
            error("third", line = 3),
            error("fourth", line = 4),
            error("fifth", line = 5)
        )
        val prompt = CodeConsolePrompt.compose(errors)
        val numbered = prompt.lines().count { it.matches(Regex("^\\d+\\. .*")) }
        assertEquals(CodeConsolePrompt.MAX_ERRORS, numbered)
        assertTrue(prompt.contains("fourth"))
        assertFalse(prompt.contains("fifth"))
    }

    @Test fun aLongErrorIsClippedAndFlattenedToOneLine() {
        val long = "boom\n" + "x".repeat(600)
        val prompt = CodeConsolePrompt.compose(listOf(error(long)))
        assertFalse(prompt.contains("x".repeat(CodeConsolePrompt.MAX_ERROR_CHARS + 1)))
        assertFalse(prompt.lines().any { it.startsWith("x") })
    }

    @Test fun anErrorWithoutASourceShowsNoPosition() {
        val prompt = CodeConsolePrompt.compose(listOf(error("plain failure", source = null)))
        assertTrue(prompt.contains("plain failure"))
        assertFalse(prompt.contains("(:"))
    }
}
