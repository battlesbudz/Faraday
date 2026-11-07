package com.battlesbudz.jarvis.v2.code

/**
 * The project brief (code-and-export scope section 3.3): `BRIEF.md`, the
 * short-term memory that always fits. A plain Markdown file with five
 * fixed sections — Goal, Files, Decisions, Known problems, Next steps —
 * rendered and parsed by this class, so the on-disk format has exactly
 * one owner.
 *
 * The Files section is machine-owned: it is rendered from the real folder
 * (see [withFilesSection] and [CodeProjectStore.regenerateBriefFilesSection])
 * and whatever a model writes there is overwritten on regeneration. The
 * other four sections are prose the model may update through the
 * `update_brief` action (Unit 2 applies it; the validation below is the
 * gate it must pass).
 *
 * A parsed brief always carries all five sections — [parse] returns null
 * when any is missing — and [validate] enforces the brief cap from
 * section 3.2: an update that would exceed the cap fails with the reason,
 * so the model compresses instead of the app truncating silently.
 */
data class ProjectBrief(
    val goal: String,
    val files: List<ProjectFileEntry>,
    val decisions: List<String>,
    val knownProblems: List<String>,
    val nextSteps: List<String>
) {

    /** This brief with its Files section replaced by the real folder's map. */
    fun withFilesSection(entries: List<ProjectFileEntry>): ProjectBrief = copy(files = entries)

    /** The canonical on-disk form: title, then the five sections in order. */
    fun render(): String = buildString {
        append("# Project brief\n\n")
        append("## Goal\n")
        if (goal.isNotBlank()) append(goal.trim()).append('\n')
        append("\n## Files\n")
        for (entry in files) {
            append("- `").append(entry.path).append("` — ").append(entry.sizeBytes).append(" B\n")
        }
        append("\n## Decisions\n")
        for (item in decisions) append("- ").append(item.trim()).append('\n')
        append("\n## Known problems\n")
        for (item in knownProblems) append("- ").append(item.trim()).append('\n')
        append("\n## Next steps\n")
        for (item in nextSteps) append("- ").append(item.trim()).append('\n')
    }.trimEnd()

    /**
     * Problems with this brief as an `update_brief` candidate, empty when
     * it may be applied. Section presence is structural (every instance
     * carries all five); the cap is the check with teeth.
     */
    fun validate(budget: CodeBudget.Budget): List<String> {
        val problems = mutableListOf<String>()
        val length = render().length
        if (length > budget.briefCapChars) {
            problems += "The brief is $length chars, over the ${budget.briefCapChars}-char cap. " +
                "Compress it: keep the goal, the decisions that still bind, and what is broken now."
        }
        return problems
    }

    companion object {

        /** The five section titles, in render order. */
        val SECTION_TITLES: List<String> =
            listOf("Goal", "Files", "Decisions", "Known problems", "Next steps")

        /** The brief a new project starts with (section 3.3). */
        fun seeded(goal: String, files: List<ProjectFileEntry>): ProjectBrief =
            ProjectBrief(goal.trim(), files, emptyList(), emptyList(), emptyList())

        /**
         * Parses a brief from its Markdown form, or null when any of the
         * five sections is missing. List sections take one item per line
         * (a leading "- " is stripped when present); the Files section
         * takes only well-formed `- \`path\` — size B` lines and ignores
         * anything else written there.
         */
        fun parse(text: String): ProjectBrief? {
            val sections = LinkedHashMap<String, MutableList<String>>()
            var current: String? = null
            for (raw in text.replace("\r\n", "\n").split('\n')) {
                val line = raw.trimEnd()
                if (line.startsWith("## ")) {
                    val title = line.removePrefix("## ").trim()
                    current = SECTION_TITLES.firstOrNull { it.equals(title, ignoreCase = true) }
                    if (current != null) sections.putIfAbsent(current!!, mutableListOf())
                    continue
                }
                if (line.startsWith("# ")) continue
                val key = current ?: continue
                sections.getValue(key).add(line)
            }
            if (!sections.keys.containsAll(SECTION_TITLES)) return null

            fun body(title: String): List<String> {
                val lines = sections.getValue(title)
                return lines.subList(
                    lines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0),
                    (lines.indexOfLast { it.isNotBlank() } + 1).coerceAtLeast(0)
                )
            }
            fun items(title: String): List<String> = body(title).mapNotNull { line ->
                val item = line.trim().removePrefix("- ").trim()
                item.ifEmpty { null }
            }
            return ProjectBrief(
                goal = body("Goal").joinToString("\n").trim(),
                files = body("Files").mapNotNull { parseFileLine(it) },
                decisions = items("Decisions"),
                knownProblems = items("Known problems"),
                nextSteps = items("Next steps")
            )
        }

        private val FILE_LINE = Regex("^- `(.+)` — (\\d+) B$")

        private fun parseFileLine(line: String): ProjectFileEntry? {
            val match = FILE_LINE.matchEntire(line.trim()) ?: return null
            val size = match.groupValues[2].toLongOrNull() ?: return null
            return ProjectFileEntry(match.groupValues[1], size)
        }
    }
}
