package com.battlesbudz.jarvis.v2.code

/**
 * One parsed code action (code-and-export scope section 4.1): the whole
 * output vocabulary of a code round. The parser produces these; Unit 3's
 * gate and apply step consume them; nothing here touches disk.
 *
 * File bodies are carried exactly as the model emitted them, apart from
 * the envelope rule documented on [CodeActionParser]: one line break
 * directly inside a `<content>`, `<find>` or `<replace>` tag belongs to
 * the envelope, not the file.
 */
sealed interface CodeAction {

    /** `write_file`: replace [path]'s whole content with [content]. */
    data class WriteFile(val path: String, val content: String) : CodeAction

    /** One find/replace pair of an [EditFile]. */
    data class FindReplace(val find: String, val replace: String)

    /** `edit_file`: apply [edits], in order, to the file at [path]. */
    data class EditFile(val path: String, val edits: List<FindReplace>) : CodeAction

    /** `update_brief`: replace the brief with [content] (validated by Unit 3 against section 3.3). */
    data class UpdateBrief(val content: String) : CodeAction

    /** `reply`: a plain answer; the round changes nothing. */
    data class Reply(val text: String) : CodeAction
}

/** How a parse found its actions: the strict envelope, or one recovery shape. */
enum class CodeParseSource {
    STRICT,
    RECOVERED_CONTENT_BLOCK,
    RECOVERED_RAW_HTML,
    RECOVERED_MARKDOWN_FENCE
}

/** The outcome of parsing one raw code response. */
sealed interface CodeParseResult {

    /**
     * A usable action set. [thinking] is the model's thinking text,
     * split off before parsing (section 4.3), kept for diagnostics only.
     */
    data class Actions(
        val actions: List<CodeAction>,
        val source: CodeParseSource,
        val thinking: String?
    ) : CodeParseResult

    /**
     * No usable actions. [reason] names what was wrong in one line; the
     * retry loop (Unit 3) feeds it back to the model.
     */
    data class Failed(val reason: String, val thinking: String?) : CodeParseResult
}
