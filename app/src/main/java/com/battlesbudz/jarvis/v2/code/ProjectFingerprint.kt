package com.battlesbudz.jarvis.v2.code

import java.io.File
import java.security.MessageDigest

/**
 * One project content file: its project-relative path with '/' separators
 * (for example `index.html` or `css/style.css`) and its size in bytes.
 * The file map in the brief, the snapshot, and the fingerprint all speak
 * in these entries.
 */
data class ProjectFileEntry(val path: String, val sizeBytes: Long)

/**
 * Walks a project folder into its content files (code-and-export scope
 * section 3.5): every regular file under the project, excluding the
 * `versions/` bookkeeping tree and the `project.json` machine state.
 * Everything else — the brief included — is project content. Entries are
 * sorted by path so every consumer sees the same order.
 */
internal object ProjectFiles {

    fun list(projectDir: File): List<ProjectFileEntry> {
        val entries = mutableListOf<ProjectFileEntry>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory -> {
                        // versions/ is bookkeeping, never content (section 3.4).
                        if (prefix.isEmpty() && child.name == CodeProjectStore.VERSIONS_DIR_NAME) continue
                        walk(child, relative)
                    }
                    child.isFile -> {
                        if (prefix.isEmpty() && child.name == CodeProjectStore.PROJECT_JSON_FILE_NAME) continue
                        entries += ProjectFileEntry(relative, child.length())
                    }
                }
            }
        }
        walk(projectDir, "")
        return entries.sortedBy { it.path }
    }
}

/**
 * The project fingerprint (section 3.5): SHA-256 over the sorted list of
 * (path, size, SHA-256 of contents) for every project file, the brief
 * included, `versions/` and `project.json` excluded. Content, never
 * timestamps — Android timestamp granularity makes mtime checks fragile
 * and these files are small. Two snapshots of the same bytes always hash
 * the same; one changed byte anywhere changes the fingerprint. Unit 2's
 * session reuse rule keys on this value.
 */
object ProjectFingerprint {

    /** The fingerprint of the project rooted at [projectDir], lowercase hex. */
    fun compute(projectDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in ProjectFiles.list(projectDir)) {
            val contents = File(projectDir, entry.path).readBytes()
            val contentHash = MessageDigest.getInstance("SHA-256").digest(contents)
            val line = "${entry.path}\u0000${entry.sizeBytes}\u0000${toHex(contentHash)}\n"
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return toHex(digest.digest())
    }

    internal fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
