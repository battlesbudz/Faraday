package com.battlesbudz.jarvis.v2.code

/**
 * The strict action parser and the write recovery chain (code-and-export
 * scope section 4.3). Pure text in, [CodeParseResult] out: the parser
 * never touches disk, and the store is only ever written from accepted
 * actions — by Unit 3's apply step, not by anything here.
 *
 * Order of business, exactly as the scope states:
 *
 * 1. The thinking channel is split off first, so reasoning text that
 *    mentions an action tag is never parsed as an action.
 * 2. Strict parse: complete, well-formed action envelopes are taken
 *    literally. When any write action (`write_file`, `edit_file`,
 *    `update_brief`) is present, those actions define the round and a
 *    `reply` riding alongside them is dropped — the actions speak for
 *    themselves. A round of replies only is a reply round.
 * 3. The recovery chain, only when the strict parse found no action at
 *    all: a bare `<content>` block containing HTML, then a Markdown
 *    html fence, then a raw HTML document anywhere in the text. Any of
 *    these becomes a `write_file` for `index.html`. This chain is the
 *    Mochu finding that matters most for a small model: its common
 *    sloppiness shapes each have a dedicated recovery, so malformation
 *    is cheap instead of fatal. The fence is checked before the raw
 *    document scan even though the scope lists it last: a fence
 *    wrapping a complete document is the more specific shape, and
 *    checked after the raw scan it could never fire for exactly the
 *    input it names — the scan would claim every fenced document
 *    first. A fence without HTML in it falls through to the scan.
 *
 * Envelope rule: file bodies are carried byte-exact, except that one
 * line break directly inside a `<content>`, `<find>` or `<replace>` tag
 * (and one directly before its close) belongs to the envelope, not the
 * file — models put tag and body on separate lines, and the break is
 * the tag's, never the content's. Interior bytes are never touched.
 * Repair of what a body contains (fences, CDATA, misplaced scripts) is
 * Unit 3's normalizers, not this parser's.
 */
object CodeActionParser {

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    private val ACTION_BLOCK =
        Regex("<action\\s+name=\"([A-Za-z_]+)\"\\s*>(.*?)</action>", RegexOption.DOT_MATCHES_ALL)
    private val FIND_REPLACE =
        Regex("<find>(.*?)</find>\\s*<replace>(.*?)</replace>", RegexOption.DOT_MATCHES_ALL)
    private val CONTENT_BLOCK =
        Regex("<content>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
    private val HTML_FENCE =
        Regex("```html\\s*\\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun parse(raw: String): CodeParseResult {
        val (thinking, body) = splitThinking(raw)
        val strict = mutableListOf<CodeAction>()
        var malformedNote: String? = null
        for (match in ACTION_BLOCK.findAll(body)) {
            val verb = match.groupValues[1]
            val inner = match.groupValues[2]
            val outcome = when (verb) {
                "write_file" -> parseWriteFile(inner)
                "edit_file" -> parseEditFile(inner)
                "update_brief" -> parseUpdateBrief(inner)
                "reply" -> VerbOutcome.Parsed(CodeAction.Reply(inner.trim()))
                else -> VerbOutcome.Malformed("unknown action verb \"$verb\"")
            }
            when (outcome) {
                is VerbOutcome.Parsed -> strict += outcome.action
                is VerbOutcome.Malformed -> if (malformedNote == null) malformedNote = outcome.note
            }
        }
        val writes = strict.filter { it !is CodeAction.Reply }
        if (writes.isNotEmpty()) {
            return CodeParseResult.Actions(writes, CodeParseSource.STRICT, thinking)
        }
        if (strict.isNotEmpty()) {
            return CodeParseResult.Actions(strict, CodeParseSource.STRICT, thinking)
        }
        return recover(body, thinking, malformedNote)
    }

    private sealed interface VerbOutcome {
        data class Parsed(val action: CodeAction) : VerbOutcome
        data class Malformed(val note: String) : VerbOutcome
    }

    private fun parseWriteFile(inner: String): VerbOutcome {
        val path = tagBody(inner, "path")?.trim()
        if (path.isNullOrEmpty()) return VerbOutcome.Malformed("a write_file action had no <path>")
        val content = tagBody(inner, "content")
            ?: return VerbOutcome.Malformed("a write_file action had no <content>")
        return VerbOutcome.Parsed(CodeAction.WriteFile(path, envelopeBody(content)))
    }

    private fun parseEditFile(inner: String): VerbOutcome {
        val path = tagBody(inner, "path")?.trim()
        if (path.isNullOrEmpty()) return VerbOutcome.Malformed("an edit_file action had no <path>")
        val pairs = FIND_REPLACE.findAll(inner).map { match ->
            CodeAction.FindReplace(
                envelopeBody(match.groupValues[1]),
                envelopeBody(match.groupValues[2])
            )
        }.toList()
        if (pairs.isEmpty()) {
            return VerbOutcome.Malformed("an edit_file action had no find/replace pairs")
        }
        return VerbOutcome.Parsed(CodeAction.EditFile(path, pairs))
    }

    private fun parseUpdateBrief(inner: String): VerbOutcome {
        val content = tagBody(inner, "content")
            ?: return VerbOutcome.Malformed("an update_brief action had no <content>")
        return VerbOutcome.Parsed(CodeAction.UpdateBrief(envelopeBody(content)))
    }

    private fun recover(body: String, thinking: String?, malformedNote: String?): CodeParseResult {
        val bare = CONTENT_BLOCK.find(body)?.groupValues?.get(1)
        if (bare != null && looksLikeHtml(bare)) {
            return recovered(CodeAction.WriteFile(
                CodeProjectStore.INDEX_FILE_NAME, envelopeBody(bare)),
                CodeParseSource.RECOVERED_CONTENT_BLOCK, thinking)
        }
        val fenced = HTML_FENCE.find(body)?.groupValues?.get(1)
        if (fenced != null && looksLikeHtml(fenced)) {
            return recovered(CodeAction.WriteFile(
                CodeProjectStore.INDEX_FILE_NAME, envelopeBody(fenced)),
                CodeParseSource.RECOVERED_MARKDOWN_FENCE, thinking)
        }
        val docStart = htmlDocumentStart(body)
        if (docStart >= 0) {
            val docEnd = body.lastIndexOf("</html>", ignoreCase = true)
            if (docEnd > docStart) {
                return recovered(CodeAction.WriteFile(
                    CodeProjectStore.INDEX_FILE_NAME,
                    body.substring(docStart, docEnd + "</html>".length)),
                    CodeParseSource.RECOVERED_RAW_HTML, thinking)
            }
        }
        val reason = when {
            malformedNote != null -> "Malformed action output: $malformedNote."
            body.contains("<action") ->
                "An action envelope was opened but never closed, and no HTML could be recovered."
            else -> "No complete action and no recoverable HTML document in the response."
        }
        return CodeParseResult.Failed(reason, thinking)
    }

    private fun recovered(
        action: CodeAction.WriteFile,
        source: CodeParseSource,
        thinking: String?
    ): CodeParseResult = CodeParseResult.Actions(listOf(action), source, thinking)

    private fun tagBody(inner: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)

    /** The envelope rule from the class doc: one line break just inside the tag is the tag's. */
    internal fun envelopeBody(body: String): String {
        var out = body
        out = when {
            out.startsWith("\r\n") -> out.substring(2)
            out.startsWith("\n") || out.startsWith("\r") -> out.substring(1)
            else -> out
        }
        out = when {
            out.endsWith("\r\n") -> out.substring(0, out.length - 2)
            out.endsWith("\n") || out.endsWith("\r") -> out.substring(0, out.length - 1)
            else -> out
        }
        return out
    }

    private fun looksLikeHtml(text: String): Boolean =
        text.contains("<html", ignoreCase = true) ||
            text.contains("<!doctype", ignoreCase = true) ||
            (text.contains("<head", ignoreCase = true) && text.contains("<body", ignoreCase = true))

    private fun htmlDocumentStart(body: String): Int {
        val doctype = body.indexOf("<!doctype", ignoreCase = true)
        val html = body.indexOf("<html", ignoreCase = true)
        return when {
            doctype < 0 -> html
            html < 0 -> doctype
            else -> minOf(doctype, html)
        }
    }

    /**
     * Splits the thinking channel off [text] (section 4.3): returns the
     * thinking text (null when there was none worth keeping) and the
     * body that remains for parsing. A `<think>` block that is never
     * closed swallows the rest of the text — unless an action envelope
     * starts later, in which case the body resumes there, because a
     * truncated think tag must not eat a complete answer.
     */
    fun splitThinking(text: String): Pair<String?, String> {
        val thinking = StringBuilder()
        val body = StringBuilder()
        var rest = text
        while (true) {
            val open = rest.indexOf(THINK_OPEN)
            if (open < 0) {
                body.append(rest)
                break
            }
            body.append(rest.substring(0, open))
            val afterOpen = rest.substring(open + THINK_OPEN.length)
            val close = afterOpen.indexOf(THINK_CLOSE)
            if (close >= 0) {
                thinking.append(afterOpen.substring(0, close))
                rest = afterOpen.substring(close + THINK_CLOSE.length)
            } else {
                val actionStart = afterOpen.indexOf("<action")
                if (actionStart >= 0) {
                    thinking.append(afterOpen.substring(0, actionStart))
                    rest = afterOpen.substring(actionStart)
                } else {
                    thinking.append(afterOpen)
                    rest = ""
                }
            }
        }
        val thought = thinking.toString().trim()
        return (if (thought.isEmpty()) null else thought) to body.toString()
    }

    /** The parseable body of [raw]: [raw] with the thinking channel split off. */
    fun bodyText(raw: String): String = splitThinking(raw).second
}
