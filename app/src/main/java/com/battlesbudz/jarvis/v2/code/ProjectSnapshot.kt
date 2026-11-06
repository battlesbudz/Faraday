package com.battlesbudz.jarvis.v2.code

import java.io.File
import java.time.Instant

/** One persisted round: when it finished, what Battles asked, and the one-line outcome. */
data class RoundLogEntry(val atMillis: Long, val request: String, val outcome: String)

/**
 * Builds the snapshot a code round starts from (code-and-export scope
 * section 3.2): the project's ground truth, re-derived from disk in a
 * fixed order under one character budget —
 *
 * 1. the brief, in full;
 * 2. the file map, one line per content file;
 * 3. `index.html`, capped at the budget's index cap with head and tail
 *    kept and the middle dropped (the snapshot says so, per Mochu's rule);
 * 4. every other file in file-map order, capped per file, until the
 *    budget runs out; files that do not fit are listed by name and size
 *    only, and the model can ask for one in a later round;
 * 5. the recent round log — the last few requests and outcomes, clipped —
 *    which replaces chat history as the continuity carrier.
 *
 * Nothing here touches the chat or the model: given the same bytes on
 * disk and the same budget, the snapshot is byte-identical, which is
 * what lets round fifty start from the same ground truth as round one.
 */
object ProjectSnapshotBuilder {

    /** Round-log entries a snapshot carries. */
    const val MAX_ROUND_LOG_LINES = 5

    /** Requests and outcomes are clipped to this in the snapshot's round log. */
    const val LOG_FIELD_CLIP_CHARS = 120

    fun build(
        store: CodeProjectStore,
        project: CodeProjectStore.CodeProject,
        budget: CodeBudget.Budget
    ): String {
        val dir = store.projectDir(project.id)
        val entries = ProjectFiles.list(dir)
        val out = StringBuilder()
        fun remaining(): Int = budget.snapshotBudgetChars - out.length

        // 1. The brief, in full: it is capped at write time (section 3.3),
        // so this is always possible by construction.
        out.append("## Project brief\n\n")
        out.append(store.readBrief(project)?.render() ?: "(no brief on disk)")
        out.append("\n\n")

        // 2. The file map.
        out.append("## Project files\n\n")
        if (entries.isEmpty()) out.append("(no files)\n")
        for (entry in entries) {
            out.append("- `").append(entry.path).append("` — ")
                .append(entry.sizeBytes).append(" B\n")
        }
        out.append('\n')

        // 3. index.html, head and tail when it exceeds its cap.
        val indexEntry = entries.firstOrNull { it.path == CodeProjectStore.INDEX_FILE_NAME }
        if (indexEntry != null) {
            val header = "## ${indexEntry.path}\n\n"
            val cap = minOf(budget.indexCapChars, remaining() - header.length)
            if (cap > 0) {
                out.append(header)
                out.append(capHeadTail(readText(File(dir, indexEntry.path)), cap))
                out.append("\n\n")
            }
        }

        // 4. Other files, in file-map order, until the budget runs out.
        // The brief's body is step 1, so it is not carried twice. The
        // round log's body is step 5, clipped to the last few entries;
        // carrying it whole here would defeat the clipping and leak the
        // older rounds the snapshot deliberately leaves behind.
        val notShown = mutableListOf<ProjectFileEntry>()
        for (entry in entries) {
            if (entry.path == CodeProjectStore.INDEX_FILE_NAME) continue
            if (entry.path == CodeProjectStore.BRIEF_FILE_NAME) continue
            if (entry.path == CodeProjectStore.ROUND_LOG_FILE_NAME) continue
            val content = readText(File(dir, entry.path))
            val header = "## ${entry.path}\n\n"
            val needed = minOf(content.length, budget.fileCapChars)
            if (remaining() >= header.length + needed + 2) {
                out.append(header)
                out.append(capHeadTail(content, minOf(budget.fileCapChars, remaining() - header.length)))
                out.append("\n\n")
            } else {
                notShown += entry
            }
        }
        if (notShown.isNotEmpty()) {
            val header = "## Not shown (snapshot budget spent)\n\n"
            if (remaining() > header.length) {
                out.append(header)
                for (entry in notShown) {
                    val line = "- `${entry.path}` — ${entry.sizeBytes} B (listed only)\n"
                    if (line.length > remaining()) break
                    out.append(line)
                }
                out.append('\n')
            }
        }

        // 5. The recent round log, most recent entries first when
        // selecting, rendered oldest-to-newest.
        val log = store.readRoundLog(project)
        if (log.isNotEmpty()) {
            val header = "## Recent rounds\n\n"
            var used = out.length + header.length
            val lines = mutableListOf<String>()
            for (entry in log.takeLast(MAX_ROUND_LOG_LINES).asReversed()) {
                val line = "- ${Instant.ofEpochMilli(entry.atMillis)} — " +
                    "${clip(entry.request)} → ${clip(entry.outcome)}\n"
                if (used + line.length > budget.snapshotBudgetChars) break
                lines += line
                used += line.length
            }
            if (lines.isNotEmpty()) {
                out.append(header)
                lines.asReversed().forEach { out.append(it) }
            }
        }

        return out.toString().trimEnd() + "\n"
    }

    /**
     * [text] capped at [cap] chars: whole when it fits; otherwise the
     * head and tail are kept with a note where the middle was dropped,
     * and the result is exactly [cap] chars.
     */
    fun capHeadTail(text: String, cap: Int): String {
        if (cap <= 0) return ""
        if (text.length <= cap) return text
        val note = "\n\n[... middle of the file omitted in this snapshot ...]\n\n"
        if (cap <= note.length) return text.take(cap)
        val available = cap - note.length
        val headLength = available / 2
        val tailLength = available - headLength
        return text.substring(0, headLength) + note + text.substring(text.length - tailLength)
    }

    private fun clip(field: String): String =
        if (field.length <= LOG_FIELD_CLIP_CHARS) field else field.take(LOG_FIELD_CLIP_CHARS - 1) + "…"

    private fun readText(file: File): String = try {
        file.readText()
    } catch (e: Exception) {
        ""
    }
}
