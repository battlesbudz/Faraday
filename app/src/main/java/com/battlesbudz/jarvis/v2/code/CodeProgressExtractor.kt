package com.battlesbudz.jarvis.v2.code

/**
 * Live extraction of the file currently being written, for round
 * progress display (code-and-export scope section 4.2): while a round
 * generates, the UI shows the file being written, read straight off the
 * partial response. Cosmetic and cheap — it is how Battles sees that a
 * long round is alive.
 *
 * The answer is the `<path>` of the last `write_file` action that has
 * opened but not yet closed in the partial text, or null when no write
 * is in flight (a reply streaming, a finished write, a path tag that
 * has not completed yet). Thinking text is split off first, like the
 * parser does, so a path mentioned in reasoning never shows.
 */
object CodeProgressExtractor {

    private val WRITE_OPEN = Regex("<action\\s+name=\"write_file\"\\s*>")
    private val PATH_TAG = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL)
    private val CONTENT_OPEN = Regex("<content>")

    /** The write in flight: its [path] and the file body streamed so far. */
    data class WriteProgress(val path: String, val contentSoFar: String)

    fun currentWritePath(partial: String): String? = currentWrite(partial)?.path

    /**
     * The last opened-but-unclosed `write_file` in [partial], with the
     * content streamed into it so far — the live build view renders
     * this, so Battles watches the file's code arrive instead of a
     * bare status line. Null when no write is in flight. The content
     * is the raw text after the `<content>` tag (minus the one line
     * break the envelope rule assigns to the tag itself, mirroring
     * the parser); it is a display value, never parsed or applied.
     */
    fun currentWrite(partial: String): WriteProgress? {
        val body = CodeActionParser.bodyText(partial)
        val last = WRITE_OPEN.findAll(body).lastOrNull() ?: return null
        val tail = body.substring(last.range.last + 1)
        if (tail.contains("</action>")) return null
        val path = PATH_TAG.find(tail)?.groupValues?.get(1)?.trim()
            ?.ifEmpty { null } ?: return null
        val contentStart = CONTENT_OPEN.find(tail)?.range?.last?.plus(1)
        val content = if (contentStart == null) {
            ""
        } else {
            tail.substring(contentStart).removePrefix("\r\n").removePrefix("\n")
        }
        return WriteProgress(path, content)
    }
}
