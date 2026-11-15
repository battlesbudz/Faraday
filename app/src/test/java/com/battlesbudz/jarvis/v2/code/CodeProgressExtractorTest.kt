package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live build view's extraction (code-mode complete fix, item B):
 * the in-flight write's path and partial body, read off the streaming
 * response. What Battles watches while a round generates.
 */
class CodeProgressExtractorTest {

    @Test fun anOpenWriteReportsItsPathAndStreamedContent() {
        val partial = "<action name=\"write_file\">\n" +
            "<path>index.html</path>\n" +
            "<content>\n<!DOCTYPE html>\n<canvas id=\"g"
        val write = CodeProgressExtractor.currentWrite(partial)
        assertEquals("index.html", write?.path)
        // The one line break inside the <content> tag is the
        // envelope's, exactly as the parser treats it.
        assertEquals("<!DOCTYPE html>\n<canvas id=\"g", write?.contentSoFar)
    }

    @Test fun aWriteBeforeItsContentTagReportsEmptyContent() {
        val partial = "<action name=\"write_file\"><path>app.js</path><content>"
        val write = CodeProgressExtractor.currentWrite(partial)
        assertEquals("app.js", write?.path)
        assertEquals("", write?.contentSoFar)
    }

    @Test fun aClosedWriteIsNoLongerInFlight() {
        val partial = "<action name=\"write_file\"><path>index.html</path>" +
            "<content><html></html></content></action>"
        assertNull(CodeProgressExtractor.currentWrite(partial))
    }

    @Test fun theLastOpenWriteWins() {
        val partial = "<action name=\"write_file\"><path>a.html</path>" +
            "<content>done</content></action>" +
            "<action name=\"write_file\"><path>b.js</path><content>let x"
        val write = CodeProgressExtractor.currentWrite(partial)
        assertEquals("b.js", write?.path)
        assertEquals("let x", write?.contentSoFar)
    }

    @Test fun noWriteInFlightReadsAsNull() {
        assertNull(CodeProgressExtractor.currentWrite("thinking out loud"))
        assertNull(CodeProgressExtractor.currentWrite("<action name=\"reply\">hi</action>"))
        assertNull(CodeProgressExtractor.currentWritePath("plain text"))
    }

    @Test fun aPathInsideThinkingNeverShows() {
        val partial = "<think>maybe <action name=\"write_file\"><path>fake.html</path></think>"
        assertNull(CodeProgressExtractor.currentWrite(partial))
    }
}
