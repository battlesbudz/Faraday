package com.battlesbudz.jarvis.v2.code

/**
 * The normalizers (code-and-export scope section 4.3), ported from
 * Mochu's repair layer as the scope adapts it. They run on every parsed
 * or recovered write before the edit applier and the completeness gate:
 * the model's common sloppiness shapes are repaired deterministically
 * here instead of failing the round. Pure text in, pure text out —
 * nothing in this file touches disk.
 *
 * Four repairs, in the order [CodeRoundRunner] applies them:
 *
 * 1. **Path cleanup** ([cleanPath]): separators normalized, leading
 *    slashes stripped, `index.htm` given its `.html` extension. Escape
 *    attempts are NOT cleaned away — a `..` segment survives cleanup so
 *    the store's `resolvePath` refuses it and the round fails with that
 *    reason, per the scope ("escapes rejected").
 * 2. **Body cleaning** ([cleanBody]): a Markdown fence or a CDATA
 *    wrapper around the whole body is stripped. Only whole-body
 *    wrappers are touched; a fence shown inside a page as an example
 *    is interior content and survives.
 * 3. **CSS/JS un-mix** ([splitCssJs]): JavaScript that landed inside a
 *    css file is cut out to the script side. Detection is Mochu's
 *    line-based shape (declarations, `document.`, event wiring), plus
 *    brace tracking so a function body's plain lines travel with the
 *    line that opened it, plus whole `<script>` blocks lifted out.
 * 4. **Single-file consolidation** ([consolidateSplit]): when the
 *    round wrote `index.html` plus css/js files it references, and
 *    Battles did not ask for a split, the css and js are inlined back
 *    into the html and the separate writes are dropped — the contract's
 *    house rule is one self-contained file unless asked. Mochu keyed
 *    "unrequested" off the generated HTML; the runner here holds
 *    Battles's actual request, which is the better signal, so
 *    [splitWasRequested] reads the request text.
 */
object CodeNormalizer {

    /** The script file an un-mix extraction lands in (Mochu's name). */
    const val SCRIPT_FILE_NAME: String = "app.js"

    /**
     * Cleans a model-supplied path: trimmed, backslashes become
     * slashes, leading slashes stripped, empty and `.` segments dropped,
     * and a final `index.htm` segment gains its `.html` extension. `..`
     * segments are deliberately preserved for the store to refuse.
     */
    fun cleanPath(raw: String): String {
        val segments = raw.trim().replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return ""
        val last = segments.last()
        val fixedLast = if (last.equals("index.htm", ignoreCase = true)) "index.html" else last
        return (segments.dropLast(1) + fixedLast).joinToString("/")
    }

    /**
     * Strips whole-body wrappers: a Markdown fence (an opening ``` line
     * and a closing ``` line) or a CDATA section, repeatedly while the
     * body is still wrapped (a fence around CDATA sheds both). A body
     * that merely contains a fence or a CDATA section is returned
     * untouched.
     */
    fun cleanBody(body: String): String {
        var out = body
        repeat(MAX_WRAPPER_PASSES) {
            val next = stripOneWrapper(out)
            if (next == out) return out
            out = next
        }
        return out
    }

    private const val MAX_WRAPPER_PASSES: Int = 3

    private fun stripOneWrapper(body: String): String {
        val trimmed = body.trim()
        // CDATA wrapper around the whole body.
        if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
            return trimmed.removePrefix("<![CDATA[").removeSuffix("]]>")
        }
        // Markdown fence: first line is ``` or ```lang, last line is ```.
        val firstBreak = trimmed.indexOf('\n')
        if (firstBreak > 0) {
            val firstLine = trimmed.substring(0, firstBreak).trim()
            val lastLine = trimmed.substringAfterLast('\n').trim()
            if (firstLine.startsWith("```") && lastLine == "```") {
                return trimmed.substring(firstBreak + 1, trimmed.length - lastLine.length)
                    .trimEnd('\n', '\r')
            }
        }
        return body
    }

    /** The two halves of a css file after un-mixing: what stays css, what was JavaScript. */
    data class CssJsSplit(val css: String, val js: String)

    /**
     * Splits JavaScript out of a css body. A line is JavaScript when it
     * carries one of Mochu's signals (a declaration keyword, an arrow
     * function, `document.` / `window.` access, event or DOM wiring) or
     * when it sits inside a brace block a JavaScript line opened.
     * Whole `<script>` blocks are lifted out first, tags and all.
     * Blank lines follow the region they are inside. Returns the input
     * as the css half and an empty js half when no JavaScript is found.
     */
    fun splitCssJs(css: String): CssJsSplit {
        val jsBlocks = mutableListOf<String>()
        var rest = css
        while (true) {
            val open = SCRIPT_BLOCK_OPEN.find(rest) ?: break
            val afterOpen = rest.substring(open.range.last + 1)
            val close = SCRIPT_BLOCK_CLOSE.find(afterOpen) ?: break
            jsBlocks += afterOpen.substring(0, close.range.first).trim('\n')
            rest = rest.substring(0, open.range.first) +
                afterOpen.substring(close.range.last + 1)
        }

        val cssLines = mutableListOf<String>()
        val jsLines = mutableListOf<String>()
        var jsDepth = 0
        for (line in rest.split('\n')) {
            val isJs = jsDepth > 0 || (line.isNotBlank() && looksLikeJsLine(line))
            if (isJs) {
                jsLines += line
                jsDepth = (jsDepth + braceDelta(line)).coerceAtLeast(0)
            } else {
                cssLines += line
            }
        }
        val extracted = (jsBlocks + jsLines.joinToString("\n")).filter { it.isNotBlank() }
        return CssJsSplit(
            css = cssLines.joinToString("\n"),
            js = extracted.joinToString("\n")
        )
    }

    private val SCRIPT_BLOCK_OPEN = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_BLOCK_CLOSE = Regex("</script\\s*>", RegexOption.IGNORE_CASE)

    private val JS_LINE_SIGNALS: List<(String) -> Boolean> = listOf(
        { it.startsWith("const ") || it.startsWith("let ") || it.startsWith("var ") },
        { it.startsWith("function ") || it.startsWith("function(") },
        { it.startsWith("class ") || it.startsWith("import ") || it.startsWith("export ") },
        { it.contains("=>") },
        { it.contains("document.") || it.contains("window.") || it.contains("console.") },
        { it.contains("addEventListener(") || it.contains("querySelector") },
        { it.contains("getElementById(") || it.contains("createElement(") }
    )

    private fun looksLikeJsLine(line: String): Boolean {
        val trimmed = line.trim()
        return JS_LINE_SIGNALS.any { signal -> signal(trimmed) }
    }

    private fun braceDelta(line: String): Int = line.count { it == '{' } - line.count { it == '}' }

    /**
     * Whether Battles's request asked for split files, in which case
     * consolidation must not merge them back. Reads the request text
     * for the plain ways of asking: naming a stylesheet or script file,
     * or asking for separate/split/external files.
     */
    fun splitWasRequested(request: String): Boolean {
        val text = request.lowercase()
        return SPLIT_REQUEST_PHRASES.any { phrase -> text.contains(phrase) }
    }

    private val SPLIT_REQUEST_PHRASES: List<String> = listOf(
        "separate file", "separate files", "split", "external file", "multiple files",
        "two files", "three files", "stylesheet", ".css", ".js",
        "style.css", "styles.css", "app.js", "script.js"
    )

    /**
     * Inlines the round's split files into [indexHtml]: every `<link>`
     * whose href names a path in [cssByPath] is replaced by a `<style>`
     * block holding that css, and every `<script src="...">` naming a
     * path in [jsByPath] is replaced by an inline `<script>` block.
     * Returns the rewritten html and the set of paths that were inlined
     * (so the caller can drop those separate writes). References to
     * files not in the maps are left exactly as they are.
     */
    fun consolidateSplit(
        indexHtml: String,
        cssByPath: Map<String, String>,
        jsByPath: Map<String, String>
    ): Pair<String, Set<String>> {
        var html = indexHtml
        val inlined = mutableSetOf<String>()
        html = LINK_TAG.replace(html) { match ->
            val href = attrValue(HREF_ATTR, match.value)
            val path = href?.let { referencePath(it) }
            val css = path?.let { cssByPath[it] }
            if (path != null && css != null) {
                inlined += path
                "<style>\n$css\n</style>"
            } else {
                match.value
            }
        }
        html = SCRIPT_SRC_TAG.replace(html) { match ->
            val src = attrValue(SRC_ATTR, match.value)
            val path = src?.let { referencePath(it) }
            val js = path?.let { jsByPath[it] }
            if (path != null && js != null) {
                inlined += path
                "<script>\n$js\n</script>"
            } else {
                match.value
            }
        }
        return html to inlined
    }

    /** Injects a `<script src="[scriptPath]"></script>` reference into [html] when absent. */
    fun injectScriptReference(html: String, scriptPath: String): String {
        val already = SCRIPT_SRC_TAG.findAll(html).any { match ->
            attrValue(SRC_ATTR, match.value)
                ?.let { referencePath(it) } == scriptPath
        }
        if (already) return html
        val tag = "<script src=\"$scriptPath\"></script>"
        val bodyClose = html.indexOf("</body>", ignoreCase = true)
        return if (bodyClose >= 0) {
            html.substring(0, bodyClose) + tag + "\n" + html.substring(bodyClose)
        } else {
            html + "\n" + tag
        }
    }

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("href\\s*=\\s*\"([^\"]+)\"|href\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE)
    private val SCRIPT_SRC_TAG =
        Regex("<script\\b[^>]*\\bsrc\\s*=\\s*(?:\"[^\"]*\"|'[^']*')[^>]*>\\s*</script\\s*>", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("src\\s*=\\s*\"([^\"]+)\"|src\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE)

    /** An href/src value as a project-relative path: `./` and leading slashes stripped. */
    private fun referencePath(reference: String): String =
        reference.trim().removePrefix("./").trimStart('/')

    /** The value an attribute regex matched, from whichever quote-style branch fired. */
    private fun attrValue(attr: Regex, tag: String): String? =
        attr.find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
}
