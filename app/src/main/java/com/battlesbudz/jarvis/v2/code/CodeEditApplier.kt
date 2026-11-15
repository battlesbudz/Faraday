package com.battlesbudz.jarvis.v2.code

/**
 * The edit applier (code-and-export scope section 4.3), Mochu's
 * protocol: edits are applied in memory first and are all-or-nothing.
 * Each find/replace pair tries an exact substring match first (first
 * occurrence wins — the contract tells the model to give enough
 * context to be unique), then falls back to a line-by-line match that
 * ignores indentation, because a small model's most common edit
 * failure is re-indenting the snippet it copied. One unmatched find
 * fails the whole edit with the missing snippet recorded, and the
 * retry prompt (see [CodeRoundRunner]) turns it into Mochu's targeted
 * feedback: return a full write instead. Nothing here touches disk.
 */
object CodeEditApplier {

    /** The outcome of applying a list of pairs to one file's content. */
    sealed interface Result {
        /** Every pair matched; [content] is the file's new content. */
        data class Applied(val content: String) : Result

        /** A pair matched neither way; [find] is the snippet that was not found. */
        data class Unmatched(val find: String) : Result
    }

    /**
     * Applies [edits] to [content], in order, each pair against the
     * result of the previous one. The first pair that matches neither
     * way makes the whole application [Result.Unmatched] — partial
     * application is never returned.
     */
    fun apply(content: String, edits: List<CodeAction.FindReplace>): Result {
        var current = content
        for (edit in edits) {
            current = applyOne(current, edit) ?: return Result.Unmatched(edit.find)
        }
        return Result.Applied(current)
    }

    private fun applyOne(content: String, edit: CodeAction.FindReplace): String? {
        if (edit.find.isEmpty()) return null
        val exact = content.indexOf(edit.find)
        if (exact >= 0) {
            return content.replaceRange(exact, exact + edit.find.length, edit.replace)
        }
        return applyByLines(content, edit)
    }

    /**
     * The indentation-insensitive fallback: finds the first window of
     * file lines whose trimmed forms equal the find's trimmed lines,
     * and replaces that window with the replace text's lines as given.
     */
    private fun applyByLines(content: String, edit: CodeAction.FindReplace): String? {
        val findLines = edit.find.split('\n').dropLastWhile { it.isEmpty() }
        if (findLines.isEmpty() || findLines.all { it.isBlank() }) return null
        val fileLines = content.split('\n')
        if (findLines.size > fileLines.size) return null
        val replaceLines = edit.replace.split('\n')
        for (start in 0..fileLines.size - findLines.size) {
            var matches = true
            for (offset in findLines.indices) {
                if (fileLines[start + offset].trim() != findLines[offset].trim()) {
                    matches = false
                    break
                }
            }
            if (matches) {
                val out = fileLines.subList(0, start) + replaceLines +
                    fileLines.subList(start + findLines.size, fileLines.size)
                return out.joinToString("\n")
            }
        }
        return null
    }
}
