package com.battlesbudz.jarvis.v2.code

/**
 * Truncation continuation (code-and-export scope section 4.2), ported
 * from Mochu's proven shape: LiteRT-LM generations on Android can end
 * mid-file, and the round hides that completely. When a raw response
 * looks cut off, the app asks for continuation from the exact next
 * character, up to [MAX_CONTINUATIONS] times, stitching each
 * continuation onto the raw text with an overlap de-dup of up to
 * [MAX_OVERLAP_CHARS] and stripping a re-emitted action header.
 * Continuation calls are part of the same round, on the same
 * conversation; parsing only ever runs on the stitched whole.
 */
object CodeContinuation {

    /** Continuation calls per round, at most (section 4.2). */
    const val MAX_CONTINUATIONS: Int = 6

    /** Overlap de-dup window when stitching a continuation (section 4.2). */
    const val MAX_OVERLAP_CHARS: Int = 1_000

    /** Mochu's length floor for the no-closing-tag cut-off signal. */
    const val MIN_CUT_OFF_CHARS: Int = 5_000

    /** Chars of the current ending quoted back in the continuation prompt. */
    private const val ENDING_QUOTE_CHARS: Int = 120

    private val REEMITTED_HEADER = Regex(
        "^\\s*<action\\b[^>]*>\\s*(<path>.*?</path>\\s*)?(<content>)?",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Whether [raw] looks cut off. Three signals, any one enough:
     *
     * - an action envelope was opened and never closed — cut off at any
     *   length, because a complete round always closes its envelopes;
     * - Mochu's rule: at least [MIN_CUT_OFF_CHARS] long with no closing
     *   action tag anywhere;
     * - a parsed write of an HTML file whose body never closes its
     *   `</html>` tag — the recovered-file completeness signal. The full
     *   completeness gate is Unit 3's; this is only the continuation
     *   trigger, so it checks the one structural fact a truncation
     *   guarantees.
     */
    fun looksCutOff(raw: String): Boolean {
        val body = CodeActionParser.bodyText(raw)
        if (occurrences(body, "<action") > occurrences(body, "</action>")) return true
        if (body.length >= MIN_CUT_OFF_CHARS && !body.contains("</action>")) return true
        val parsed = CodeActionParser.parse(raw)
        if (parsed is CodeParseResult.Actions) {
            return parsed.actions.filterIsInstance<CodeAction.WriteFile>().any { write ->
                write.path.endsWith(".html", ignoreCase = true) &&
                    !write.content.contains("</html>", ignoreCase = true)
            }
        }
        return false
    }

    /** The prompt that asks the model to continue from the exact next character. */
    fun continuationPrompt(raw: String): String =
        "Continue from the exact next character after this ending: " +
            raw.takeLast(ENDING_QUOTE_CHARS)

    /**
     * Stitches [continuation] onto [existing]: a re-emitted action
     * header is stripped first, then the largest overlap (up to
     * [MAX_OVERLAP_CHARS]) between the existing tail and the
     * continuation's head is de-duplicated, and the rest is appended.
     */
    fun appendContinuation(existing: String, continuation: String): String {
        val stripped = stripReemittedHeader(continuation)
        val overlap = overlapLength(existing, stripped)
        return existing + stripped.drop(overlap)
    }

    /**
     * Removes a re-emitted envelope header from the head of a
     * continuation: when a model restarts instead of continuing, it
     * re-opens `<action ...>`, sometimes with its `<path>` and
     * `<content>` tags. Stripping through the `<content>` tag (or just
     * the action tag when there is none) leaves the body the round was
     * already inside, so the stitch lands mid-file where it belongs.
     */
    fun stripReemittedHeader(continuation: String): String {
        val match = REEMITTED_HEADER.find(continuation) ?: return continuation
        return continuation.substring(match.range.last + 1)
    }

    /** The largest k ≤ [MAX_OVERLAP_CHARS] with [existing] ending in [continuation]'s first k chars. */
    fun overlapLength(existing: String, continuation: String): Int {
        val max = minOf(MAX_OVERLAP_CHARS, existing.length, continuation.length)
        for (k in max downTo 1) {
            if (existing.endsWith(continuation.substring(0, k))) return k
        }
        return 0
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1
}
