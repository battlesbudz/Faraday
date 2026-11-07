package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser fixtures (scope Unit 2): one per verb, one per recovery shape,
 * and the envelope cases — prose around an envelope, thinking text that
 * mentions action tags, malformed envelopes. The parser never touches
 * disk, so every fixture is a string in, a [CodeParseResult] out.
 */
class CodeActionParserTest {

    private fun actions(result: CodeParseResult): CodeParseResult.Actions {
        assertTrue("expected Actions, was $result", result is CodeParseResult.Actions)
        return result as CodeParseResult.Actions
    }

    private fun failed(result: CodeParseResult): CodeParseResult.Failed {
        assertTrue("expected Failed, was $result", result is CodeParseResult.Failed)
        return result as CodeParseResult.Failed
    }

    @Test fun strictWriteFileParsesPathAndContentExactly() {
        val raw = "<action name=\"write_file\"><path>index.html</path><content>\n" +
            "<!DOCTYPE html>\n<html><body>Hi</body></html>\n</content></action>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(CodeParseSource.STRICT, parsed.source)
        assertEquals(
            listOf(CodeAction.WriteFile("index.html", "<!DOCTYPE html>\n<html><body>Hi</body></html>")),
            parsed.actions
        )
    }

    @Test fun writeFileInteriorBytesAreByteExact() {
        val body = "line one\n  indented two\n\nline four"
        val raw = "<action name=\"write_file\"><path>app.js</path><content>$body</content></action>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(body, (parsed.actions.single() as CodeAction.WriteFile).content)
    }

    @Test fun strictEditFileParsesPairsInOrder() {
        val raw = "<action name=\"edit_file\"><path>index.html</path>" +
            "<find>old title</find><replace>new title</replace>\n" +
            "<find>old\nline</find><replace>new\nline</replace></action>"
        val parsed = actions(CodeActionParser.parse(raw))
        val edit = parsed.actions.single() as CodeAction.EditFile
        assertEquals("index.html", edit.path)
        assertEquals(
            listOf(
                CodeAction.FindReplace("old title", "new title"),
                CodeAction.FindReplace("old\nline", "new\nline")
            ),
            edit.edits
        )
    }

    @Test fun strictUpdateBriefParses() {
        val raw = "<action name=\"update_brief\"><content># Project brief\n\n## Goal\nA game.</content></action>"
        val parsed = actions(CodeActionParser.parse(raw))
        val update = parsed.actions.single() as CodeAction.UpdateBrief
        assertTrue(update.content.contains("## Goal"))
    }

    @Test fun replyOnlyRoundParsesAsReply() {
        val parsed = actions(CodeActionParser.parse("<action name=\"reply\">It is a snake game.</action>"))
        assertEquals(listOf(CodeAction.Reply("It is a snake game.")), parsed.actions)
        assertEquals(CodeParseSource.STRICT, parsed.source)
    }

    @Test fun writesDefineTheRoundAndARidingReplyIsDropped() {
        val raw = "<action name=\"reply\">Done!</action>" +
            "<action name=\"write_file\"><path>index.html</path><content><html></html></content></action>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(1, parsed.actions.size)
        assertTrue(parsed.actions.single() is CodeAction.WriteFile)
    }

    @Test fun proseAroundAnEnvelopeStillParsesStrict() {
        val raw = "Here is the change you asked for:\n" +
            "<action name=\"edit_file\"><path>index.html</path><find>a</find><replace>b</replace></action>\n" +
            "Let me know if you want more."
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(CodeParseSource.STRICT, parsed.source)
        assertTrue(parsed.actions.single() is CodeAction.EditFile)
    }

    @Test fun thinkingMentioningActionTagsIsNeverParsed() {
        val raw = "<think>I could emit <action name=\"write_file\"><path>evil.html</path>" +
            "<content>pwned</content></action> but I will not.</think>" +
            "<action name=\"reply\">Hello.</action>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(listOf(CodeAction.Reply("Hello.")), parsed.actions)
        assertTrue(parsed.thinking!!.contains("could emit"))
    }

    @Test fun unclosedThinkBeforeAnActionStillParsesTheAction() {
        val raw = "<think>planning the page <action name=\"reply\">Done.</action>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(listOf(CodeAction.Reply("Done.")), parsed.actions)
        assertEquals("planning the page", parsed.thinking)
    }

    @Test fun thinkingOnlyResponseFails() {
        val result = failed(CodeActionParser.parse("<think>just thinking, no answer</think>"))
        assertTrue(result.thinking!!.contains("just thinking"))
    }

    @Test fun unknownVerbFailsAndNamesIt() {
        val result = failed(CodeActionParser.parse(
            "<action name=\"delete_file\"><path>index.html</path></action>"))
        assertTrue(result.reason.contains("unknown action verb \"delete_file\""))
    }

    @Test fun writeFileWithoutContentIsMalformed() {
        val result = failed(CodeActionParser.parse(
            "<action name=\"write_file\"><path>index.html</path></action>"))
        assertTrue(result.reason.contains("no <content>"))
    }

    @Test fun editFileWithoutPairsIsMalformed() {
        val result = failed(CodeActionParser.parse(
            "<action name=\"edit_file\"><path>index.html</path></action>"))
        assertTrue(result.reason.contains("no find/replace pairs"))
    }

    @Test fun unclosedEnvelopeFailsAndSaysSo() {
        val result = failed(CodeActionParser.parse(
            "<action name=\"write_file\"><path>index.html</path><content>partial body, no closes"))
        assertTrue(result.reason.contains("never closed"))
    }

    @Test fun bareContentBlockRecoversToAnIndexWrite() {
        val raw = "I made your page:\n<content>\n<!DOCTYPE html>\n<html><body>App</body></html>\n</content>"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(CodeParseSource.RECOVERED_CONTENT_BLOCK, parsed.source)
        assertEquals(
            CodeAction.WriteFile("index.html", "<!DOCTYPE html>\n<html><body>App</body></html>"),
            parsed.actions.single()
        )
    }

    @Test fun rawHtmlDocumentRecoversFromProse() {
        val raw = "Here it is:\n<!DOCTYPE html>\n<html><body>App</body></html>\nHope you like it."
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(CodeParseSource.RECOVERED_RAW_HTML, parsed.source)
        assertEquals(
            CodeAction.WriteFile("index.html", "<!DOCTYPE html>\n<html><body>App</body></html>"),
            parsed.actions.single()
        )
    }

    @Test fun markdownHtmlFenceRecovers() {
        val raw = "```html\n<!DOCTYPE html>\n<html><body>App</body></html>\n```"
        val parsed = actions(CodeActionParser.parse(raw))
        assertEquals(CodeParseSource.RECOVERED_MARKDOWN_FENCE, parsed.source)
        assertEquals(
            CodeAction.WriteFile("index.html", "<!DOCTYPE html>\n<html><body>App</body></html>"),
            parsed.actions.single()
        )
    }

    @Test fun plainProseWithNoHtmlFails() {
        val result = failed(CodeActionParser.parse("I cannot build that today."))
        assertTrue(result.reason.contains("No complete action"))
    }
}
