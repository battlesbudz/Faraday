package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Path cleanup, body cleaning, the CSS/JS un-mix, and consolidation (scope section 4.3). */
class CodeNormalizerTest {

    // -- Path cleanup ---------------------------------------------------------

    @Test fun cleanPathFixesIndexHtmExtension() {
        assertEquals("index.html", CodeNormalizer.cleanPath("index.htm"))
        assertEquals("pages/index.html", CodeNormalizer.cleanPath("pages/index.htm"))
    }

    @Test fun cleanPathNormalizesSeparatorsAndLeadingSlashes() {
        assertEquals("css/style.css", CodeNormalizer.cleanPath("css\\style.css"))
        assertEquals("index.html", CodeNormalizer.cleanPath("/index.html"))
        assertEquals("css/style.css", CodeNormalizer.cleanPath("./css//style.css"))
        assertEquals("app.js", CodeNormalizer.cleanPath("  app.js  "))
    }

    @Test fun cleanPathPreservesEscapesForTheStoreToRefuse() {
        assertEquals("../evil.html", CodeNormalizer.cleanPath("../evil.html"))
        assertEquals("a/../../evil.html", CodeNormalizer.cleanPath("a/../../evil.html"))
    }

    // -- Body cleaning --------------------------------------------------------

    @Test fun cleanBodyStripsAWrappingMarkdownFence() {
        val body = "```html\n<!DOCTYPE html>\n<html><body>x</body></html>\n```"
        assertEquals("<!DOCTYPE html>\n<html><body>x</body></html>", CodeNormalizer.cleanBody(body))
    }

    @Test fun cleanBodyStripsAWrappingCdataSection() {
        val body = "<![CDATA[<!DOCTYPE html>\n<html><body>x</body></html>]]>"
        assertEquals("<!DOCTYPE html>\n<html><body>x</body></html>", CodeNormalizer.cleanBody(body))
    }

    @Test fun cleanBodyStripsAFenceAroundCdata() {
        val body = "```\n<![CDATA[let a = 1;]]>\n```"
        assertEquals("let a = 1;", CodeNormalizer.cleanBody(body))
    }

    @Test fun cleanBodyLeavesInteriorFencesAndPlainBodiesAlone() {
        val page = "<!DOCTYPE html>\n<html><body><pre>```\ncode\n```</pre></body></html>"
        assertEquals(page, CodeNormalizer.cleanBody(page))
        val plain = "body { color: red; }"
        assertEquals(plain, CodeNormalizer.cleanBody(plain))
    }

    // -- CSS/JS un-mix ----------------------------------------------------------

    @Test fun splitCssJsLeavesPureCssAlone() {
        val css = "body { color: red; }\nh1 { margin: 0; }"
        val split = CodeNormalizer.splitCssJs(css)
        assertEquals(css, split.css)
        assertEquals("", split.js)
    }

    @Test fun splitCssJsExtractsABlockOfJavaScript() {
        val css = listOf(
            "body { margin: 0; }",
            "const el = document.getElementById('x');",
            "el.addEventListener('click', () => {",
            "  el.textContent = 'hi';",
            "});"
        ).joinToString("\n")
        val split = CodeNormalizer.splitCssJs(css)
        assertEquals("body { margin: 0; }", split.css)
        assertTrue(split.js.contains("const el = document.getElementById('x');"))
        assertTrue(split.js.contains("el.textContent = 'hi';"))
        assertTrue(split.js.endsWith("});"))
    }

    @Test fun splitCssJsExtractsAPureJavaScriptFile() {
        val css = "const a = 1;\nfunction f() {\n  return a;\n}"
        val split = CodeNormalizer.splitCssJs(css)
        assertEquals("", split.css)
        assertEquals(css, split.js)
    }

    @Test fun splitCssJsLiftsOutAScriptBlock() {
        val css = "body { color: blue; }\n<script>\nlet x = 1;\n</script>"
        val split = CodeNormalizer.splitCssJs(css)
        assertTrue(split.css.contains("color: blue"))
        assertFalse(split.css.contains("let x"))
        assertEquals("let x = 1;", split.js)
    }

    // -- Consolidation ------------------------------------------------------------

    private val indexWithSplitRefs =
        "<!DOCTYPE html>\n<html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><h1>x</h1><script src=\"app.js\"></script></body></html>"

    @Test fun consolidateSplitInlinesReferencedFiles() {
        val (html, inlined) = CodeNormalizer.consolidateSplit(
            indexWithSplitRefs,
            cssByPath = mapOf("style.css" to "body { color: red; }"),
            jsByPath = mapOf("app.js" to "let a = 1;")
        )
        assertEquals(setOf("style.css", "app.js"), inlined)
        assertTrue(html.contains("<style>\nbody { color: red; }\n</style>"))
        assertTrue(html.contains("<script>\nlet a = 1;\n</script>"))
        assertFalse(html.contains("href=\"style.css\""))
        assertFalse(html.contains("src=\"app.js\""))
    }

    @Test fun consolidateSplitLeavesUnreferencedFilesAlone() {
        val (html, inlined) = CodeNormalizer.consolidateSplit(
            indexWithSplitRefs,
            cssByPath = mapOf("other.css" to "p { margin: 0; }"),
            jsByPath = emptyMap()
        )
        assertTrue(inlined.isEmpty())
        assertEquals(indexWithSplitRefs, html)
    }

    @Test fun consolidateSplitNormalizesDotSlashReferences() {
        val html = indexWithSplitRefs.replace("href=\"style.css\"", "href=\"./style.css\"")
        val (rewritten, inlined) = CodeNormalizer.consolidateSplit(
            html,
            cssByPath = mapOf("style.css" to "body { color: red; }"),
            jsByPath = emptyMap()
        )
        assertEquals(setOf("style.css"), inlined)
        assertTrue(rewritten.contains("<style>"))
    }

    // -- Script reference injection ----------------------------------------------

    @Test fun injectScriptReferenceAddsTheTagBeforeBodyClose() {
        val html = "<!DOCTYPE html>\n<html><body><h1>x</h1></body></html>"
        val out = CodeNormalizer.injectScriptReference(html, "app.js")
        assertTrue(out.contains("<script src=\"app.js\"></script>\n</body>"))
    }

    @Test fun injectScriptReferenceIsANoOpWhenAlreadyReferenced() {
        assertEquals(
            indexWithSplitRefs,
            CodeNormalizer.injectScriptReference(indexWithSplitRefs, "app.js")
        )
    }

    // -- Split detection -----------------------------------------------------------

    @Test fun splitWasRequestedReadsTheRequestText() {
        assertFalse(CodeNormalizer.splitWasRequested("build a snake game"))
        assertFalse(CodeNormalizer.splitWasRequested("make it faster and add a score"))
        assertTrue(CodeNormalizer.splitWasRequested("put the css in a separate file"))
        assertTrue(CodeNormalizer.splitWasRequested("split it into style.css and app.js"))
        assertTrue(CodeNormalizer.splitWasRequested("use an external stylesheet"))
    }
}
