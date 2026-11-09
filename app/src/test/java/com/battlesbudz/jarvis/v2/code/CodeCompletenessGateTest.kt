package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One fixture per gate rule (scope section 4.4), plus the JS brace check. */
class CodeCompletenessGateTest {

    private val wholePage =
        "<!DOCTYPE html>\n<html lang=\"en\">\n<head><style>body { margin: 0; }</style></head>\n" +
            "<body><h1>Snake</h1><script>let a = 1;</script></body>\n</html>"

    @Test fun aWholePagePasses() {
        assertEquals(emptyList<String>(), CodeCompletenessGate.checkHtml(wholePage))
    }

    @Test fun aPageOpeningWithTheHtmlTagPasses() {
        val page = "<html><body>x</body></html>"
        assertEquals(emptyList<String>(), CodeCompletenessGate.checkHtml(page))
    }

    @Test fun aPageWithNoDoctypeOrHtmlOpeningFails() {
        val problems = CodeCompletenessGate.checkHtml("<div>hello</div>")
        assertTrue(problems.any { it.contains("does not open") })
    }

    @Test fun aPageMissingItsClosingHtmlTagFails() {
        val problems = CodeCompletenessGate.checkHtml("<!DOCTYPE html>\n<html><body>x</body>")
        assertEquals(listOf("never closes its </html> tag"), problems)
    }

    @Test fun anUnclosedScriptTagFails() {
        val page = "<!DOCTYPE html>\n<html><body><script>let a = 1;</body></html>"
        val problems = CodeCompletenessGate.checkHtml(page)
        assertEquals(listOf("has an unclosed <script> tag"), problems)
    }

    @Test fun anUnclosedStyleTagFails() {
        val page = "<!DOCTYPE html>\n<html><head><style>body { margin: 0; }</head><body>x</body></html>"
        val problems = CodeCompletenessGate.checkHtml(page)
        assertEquals(listOf("has an unclosed <style> tag"), problems)
    }

    @Test fun aPageEndingWithAnOpenBracketFails() {
        val page = "<!DOCTYPE html>\n<html><head><style>body {"
        val problems = CodeCompletenessGate.checkHtml(page)
        assertTrue(problems.any { it.contains("ends with an open '{'") })
    }

    @Test fun aPageEndingWithAnOpenAngleFails() {
        val page = wholePage + "<"
        val problems = CodeCompletenessGate.checkHtml(page)
        assertEquals(listOf("ends with an open '<'"), problems)
    }

    @Test fun balancedJavaScriptPasses() {
        val js = "function f() { const o = { a: 1 }; return o; }\nf();"
        assertEquals(emptyList<String>(), CodeCompletenessGate.checkJs(js))
    }

    @Test fun unbalancedJavaScriptFailsWithTheCount() {
        val problems = CodeCompletenessGate.checkJs("function f() { if (x) { return 1; }")
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("unbalanced braces"))
    }

    @Test fun anExtraClosingBraceFails() {
        val problems = CodeCompletenessGate.checkJs("let a = 1; }")
        assertEquals(listOf("closes a brace it never opened"), problems)
    }

    @Test fun bracesInsideStringsAndCommentsDoNotCount() {
        val js = "let s = \"}\"; // a } in a comment\n/* { */ function f() { return s; }"
        assertEquals(emptyList<String>(), CodeCompletenessGate.checkJs(js))
    }

    @Test fun checkWrittenRoutesByExtension() {
        assertEquals(emptyList<String>(), CodeCompletenessGate.checkWritten("index.html", wholePage))
        assertTrue(CodeCompletenessGate.checkWritten("app.js", "function f() {").isNotEmpty())
        assertEquals(
            emptyList<String>(),
            CodeCompletenessGate.checkWritten("style.css", "body {")
        )
    }
}
