package com.battlesbudz.jarvis.v2.code

/**
 * The completeness gate (code-and-export scope section 4.4): the
 * structural check a round's written files must pass before anything
 * is saved. It answers one narrow question — is this file whole? — and
 * answers it with specific problems, because the retry loop feeds
 * those problems back to the model verbatim. It is not static
 * analysis and does not judge whether the code is right; runtime
 * behavior is the preview loop's evidence (Unit 4), and logic bugs
 * that do not throw land on Battles's eyes, as section 8 states.
 *
 * Rules, per the scope:
 *
 * - An HTML file passes when it opens with a doctype or an html tag,
 *   closes its html tag, has no unclosed script or style tag, and does
 *   not end with an open bracket or angle character.
 * - A split script file (`.js`) passes when its braces balance. The
 *   scan is string- and comment-aware, so a brace inside a string
 *   literal or a comment does not unbalance it.
 * - Files a round only edited are exempt: they were already whole,
 *   and a surgical edit is not re-judged for the sins of its file.
 *   [CodeRoundRunner] owns that provenance call; this object only
 *   checks content it is handed.
 */
object CodeCompletenessGate {

    /**
     * Problems with [content] as a complete HTML file; empty when it
     * passes. Every problem is one specific, model-actionable clause.
     */
    fun checkHtml(content: String): List<String> {
        val problems = mutableListOf<String>()
        val trimmed = content.trim()
        val opens = trimmed.startsWith("<!doctype", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        if (!opens) problems += "does not open with a doctype or an <html> tag"
        if (!content.contains("</html>", ignoreCase = true)) {
            problems += "never closes its </html> tag"
        }
        if (unclosedTags(content, "script")) problems += "has an unclosed <script> tag"
        if (unclosedTags(content, "style")) problems += "has an unclosed <style> tag"
        val last = trimmed.lastOrNull()
        if (last != null && last in OPEN_ENDINGS) {
            problems += "ends with an open '$last'"
        }
        return problems
    }

    private val OPEN_ENDINGS: Set<Char> = setOf('<', '{', '(', '[')

    private fun unclosedTags(content: String, tag: String): Boolean {
        val opens = Regex("<$tag\\b", RegexOption.IGNORE_CASE).findAll(content).count()
        val closes = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).findAll(content).count()
        return opens > closes
    }

    /**
     * Problems with [content] as a complete JavaScript file; empty
     * when its braces balance. The scan skips line comments, block
     * comments, and string literals (single, double, and template),
     * honoring backslash escapes inside strings.
     */
    fun checkJs(content: String): List<String> {
        var depth = 0
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '/' && i + 1 < content.length && content[i + 1] == '/' -> {
                    val end = content.indexOf('\n', i)
                    i = if (end < 0) content.length else end
                }
                c == '/' && i + 1 < content.length && content[i + 1] == '*' -> {
                    val end = content.indexOf("*/", i + 2)
                    i = if (end < 0) content.length else end + 2
                }
                c == '\'' || c == '"' || c == '`' -> {
                    i = skipString(content, i, c)
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth--
                    if (depth < 0) return listOf("closes a brace it never opened")
                    i++
                }
                else -> i++
            }
        }
        return if (depth == 0) {
            emptyList()
        } else {
            listOf("has unbalanced braces ($depth more '{' than '}')")
        }
    }

    /** The index just past the string that opens at [start] with [quote]. */
    private fun skipString(content: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < content.length) {
            when {
                content[i] == '\\' -> i += 2
                content[i] == quote -> return i + 1
                else -> i++
            }
        }
        return content.length
    }

    /** Whether the gate has any rule for a file at [path] (`.html` / `.htm` / `.js`). */
    fun gatesPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".js")
    }

    /** The gate problems for a written file at [path] with [content]. */
    fun checkWritten(path: String, content: String): List<String> {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> checkHtml(content)
            lower.endsWith(".js") -> checkJs(content)
            else -> emptyList()
        }
    }
}
