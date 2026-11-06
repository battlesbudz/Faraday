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

    fun currentWritePath(partial: String): String? {
        val body = CodeActionParser.bodyText(partial)
        val last = WRITE_OPEN.findAll(body).lastOrNull() ?: return null
        val tail = body.substring(last.range.last + 1)
        if (tail.contains("</action>")) return null
        val path = PATH_TAG.find(tail)?.groupValues?.get(1)?.trim()
        return path?.ifEmpty { null }
    }
}
