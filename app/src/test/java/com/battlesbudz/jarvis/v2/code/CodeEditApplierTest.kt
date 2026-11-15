package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact match, the indentation-insensitive fallback, and all-or-nothing (scope section 4.3). */
class CodeEditApplierTest {

    private fun applied(result: CodeEditApplier.Result): String {
        assertTrue("expected Applied, was $result", result is CodeEditApplier.Result.Applied)
        return (result as CodeEditApplier.Result.Applied).content
    }

    @Test fun exactMatchReplacesTheFirstOccurrence() {
        val out = applied(
            CodeEditApplier.apply(
                "a = 1;\na = 1;\n",
                listOf(CodeAction.FindReplace("a = 1;", "a = 2;"))
            )
        )
        assertEquals("a = 2;\na = 1;\n", out)
    }

    @Test fun pairsApplyInOrderAgainstTheRunningResult() {
        val out = applied(
            CodeEditApplier.apply(
                "<h1>Old</h1><p>draft</p>",
                listOf(
                    CodeAction.FindReplace("<h1>Old</h1>", "<h1>New</h1>"),
                    CodeAction.FindReplace("<p>draft</p>", "<p>final</p>")
                )
            )
        )
        assertEquals("<h1>New</h1><p>final</p>", out)
    }

    @Test fun indentationInsensitiveLineMatchApplies() {
        val content = "<style>\n" +
            "  body { font-family: sans-serif; }\n" +
            "</style>"
        val find = "<style>\nbody { font-family: sans-serif; }\n</style>"
        val replace = "<style>\nbody { font-family: monospace; }\n</style>"
        val out = applied(CodeEditApplier.apply(content, listOf(CodeAction.FindReplace(find, replace))))
        assertEquals("<style>\nbody { font-family: monospace; }\n</style>", out)
    }

    @Test fun unmatchedFindFailsAndCarriesTheSnippet() {
        val result = CodeEditApplier.apply(
            "let a = 1;",
            listOf(CodeAction.FindReplace("let b = 2;", "let b = 3;"))
        )
        assertTrue(result is CodeEditApplier.Result.Unmatched)
        assertEquals("let b = 2;", (result as CodeEditApplier.Result.Unmatched).find)
    }

    @Test fun oneUnmatchedPairFailsTheWholeEdit() {
        val result = CodeEditApplier.apply(
            "<h1>Old</h1>",
            listOf(
                CodeAction.FindReplace("<h1>Old</h1>", "<h1>New</h1>"),
                CodeAction.FindReplace("not present", "anything")
            )
        )
        assertTrue(result is CodeEditApplier.Result.Unmatched)
        assertEquals("not present", (result as CodeEditApplier.Result.Unmatched).find)
    }

    @Test fun emptyFindNeverMatches() {
        val result = CodeEditApplier.apply(
            "anything",
            listOf(CodeAction.FindReplace("", "replacement"))
        )
        assertTrue(result is CodeEditApplier.Result.Unmatched)
    }
}
