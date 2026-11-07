package com.battlesbudz.jarvis.v2.code

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Owns code-mode projects on disk (code-and-export scope section 3.1):
 * one folder per project under the sandbox workspace —
 * `files/sandbox/workspace/projects/<projectId>/` — holding the app
 * itself (`index.html` plus any split files), `BRIEF.md`, `project.json`
 * machine state, the persisted round log, and `versions/` snapshots.
 * Host path and guest path are the same folder (`/workspace/projects/<id>`
 * inside the sandbox) because the workspace bind is the same directory.
 *
 * All of it is plain host-side file IO: the store takes the workspace
 * directory (in the app, `SandboxStore.workspaceDir`) and never touches
 * the sandbox engine, so code mode's memory core works whether or not
 * the sandbox rootfs is installed. The workspace's settled lifecycle is
 * inherited, not reimplemented: a rootfs Reset leaves projects untouched
 * and Clear workspace deletes them with everything else (decision D2).
 *
 * Project ids are app-generated slugs; a folder name is never derived
 * unchecked from model output, and [resolvePath] resolves model-supplied
 * paths inside the project folder with the escape check the scope takes
 * from Mochu's `safeResolve()`.
 *
 * This surface is what Units 2 to 6 consume: no later unit reaches
 * around it into raw files.
 */
class CodeProjectStore(private val workspaceDir: File) {

    /** One project's machine state, persisted as `project.json`. */
    data class CodeProject(
        val id: String,
        val name: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
        /** Accepted rounds so far; Unit 3 bumps it as rounds land. */
        val rounds: Int,
        /** The deterministic export package name, derived at creation (section 5.2 rule, applied early so it is stable in state). */
        val exportPackageName: String
    )

    /** The folder holding every project: `<workspace>/projects`. */
    val projectsDir: File get() = File(workspaceDir, PROJECTS_DIR_NAME)

    /** The folder of the project with [id]. Ids are slugs; anything else is rejected. */
    fun projectDir(id: String): File {
        require(isSafeId(id)) { "Unsafe project id: $id" }
        return File(projectsDir, id)
    }

    /**
     * Creates a project: folder, the working starter page (so round one
     * is an edit of something that already runs, section 4.1), a seeded
     * brief whose Goal is Battles's first request, and `project.json`.
     * The id is the slug of [name], suffixed until unique.
     */
    fun createProject(name: String, seedGoal: String, nowMillis: Long): CodeProject {
        projectsDir.mkdirs()
        val base = slugify(name)
        var id = base
        var suffix = 2
        while (File(projectsDir, id).exists()) {
            id = "$base-$suffix"
            suffix++
        }
        val dir = File(projectsDir, id)
        check(dir.mkdirs()) { "Could not create the project folder for $id" }
        File(dir, INDEX_FILE_NAME).writeText(STARTER_HTML)
        val project = CodeProject(
            id = id,
            name = name,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            rounds = 0,
            exportPackageName = exportPackageName(id)
        )
        saveProject(project)
        writeBrief(project, ProjectBrief.seeded(seedGoal, ProjectFiles.list(dir)))
        return project
    }

    /** All projects with readable state, oldest first. */
    fun listProjects(): List<CodeProject> {
        val dirs = projectsDir.listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }
            .mapNotNull { loadProject(it.name) }
            .sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
    }

    /** The project with [id], or null when it does not exist or its state is unreadable. */
    fun loadProject(id: String): CodeProject? {
        if (!isSafeId(id)) return null
        val file = File(File(projectsDir, id), PROJECT_JSON_FILE_NAME)
        if (!file.isFile) return null
        return try {
            CodeProjectJson.parse(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Persists [project]'s state atomically (write to temp, rename over). */
    fun saveProject(project: CodeProject) {
        val dir = projectDir(project.id)
        dir.mkdirs()
        writeAtomically(File(dir, PROJECT_JSON_FILE_NAME), CodeProjectJson.render(project))
    }

    /** Deletes a project's whole folder. Returns false when there was nothing to delete. */
    fun deleteProject(id: String): Boolean {
        if (!isSafeId(id)) return false
        val dir = File(projectsDir, id)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    /**
     * Resolves a model-supplied [modelPath] inside [projectDir], or null
     * when the path must be refused: empty, absolute, carrying a `..`
     * segment anywhere, or escaping the project folder once canonicalized.
     * Backslashes are treated as separators, dot segments are dropped,
     * and the result is never the project folder itself. The target does
     * not need to exist (writes create it), but it always lands inside.
     */
    fun resolvePath(projectDir: File, modelPath: String): File? {
        val normalized = modelPath.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        val root = projectDir.canonicalFile
        val target = File(root, segments.joinToString("/")).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }

    // -- The brief --------------------------------------------------------

    /** The project's brief, or null when there is none on disk or it does not parse. */
    fun readBrief(project: CodeProject): ProjectBrief? {
        val file = File(projectDir(project.id), BRIEF_FILE_NAME)
        if (!file.isFile) return null
        return try {
            ProjectBrief.parse(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Writes [brief] as the project's `BRIEF.md`, atomically. */
    fun writeBrief(project: CodeProject, brief: ProjectBrief) {
        val dir = projectDir(project.id)
        dir.mkdirs()
        writeAtomically(File(dir, BRIEF_FILE_NAME), brief.render())
    }

    /**
     * Regenerates the brief's machine-owned Files section from the real
     * folder and writes the brief back (section 3.3): whatever was in
     * that section is overwritten. Returns the updated brief, or null
     * when the project has no readable brief.
     */
    fun regenerateBriefFilesSection(project: CodeProject): ProjectBrief? {
        val brief = readBrief(project) ?: return null
        val updated = brief.withFilesSection(ProjectFiles.list(projectDir(project.id)))
        writeBrief(project, updated)
        return updated
    }

    // -- The round log ------------------------------------------------------

    /**
     * Appends one finished round to the persisted round log: the user's
     * request and the one-line outcome. This log, not chat history, is
     * the continuity carrier the snapshot replays (section 3.2 step 5).
     */
    fun appendRoundLog(project: CodeProject, request: String, outcome: String, nowMillis: Long) {
        val dir = projectDir(project.id)
        dir.mkdirs()
        val line = nowMillis.toString() + '\t' + escapeLogField(request) + '\t' +
            escapeLogField(outcome) + '\n'
        File(dir, ROUND_LOG_FILE_NAME).appendText(line)
    }

    /** The round log in append order; malformed lines are skipped, never fatal. */
    fun readRoundLog(project: CodeProject): List<RoundLogEntry> {
        val file = File(projectDir(project.id), ROUND_LOG_FILE_NAME)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                RoundLogEntry(at, unescapeLogField(parts[1]), unescapeLogField(parts[2]))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -- Versions and rollback (section 3.4) --------------------------------

    /**
     * Snapshots the project's current content files and brief into
     * `versions/vNNN/` (NNN one past the highest existing version) and
     * prunes to the last [MAX_KEPT_VERSIONS]. Returns the new version
     * number. Called before an accepted round writes, so a bad round
     * always has a way back.
     */
    fun snapshotVersion(project: CodeProject): Int {
        val dir = projectDir(project.id)
        val versionsDir = File(dir, VERSIONS_DIR_NAME)
        versionsDir.mkdirs()
        val number = (listVersions(project.id).maxOrNull() ?: 0) + 1
        val target = File(versionsDir, versionDirName(number))
        check(target.mkdirs()) { "Could not create version folder ${target.path}" }
        copyProjectContents(dir, target)
        pruneVersions(project.id)
        return number
    }

    /** The version numbers present for a project, ascending. */
    fun listVersions(id: String): List<Int> {
        if (!isSafeId(id)) return emptyList()
        val versionsDir = File(File(projectsDir, id), VERSIONS_DIR_NAME)
        val children = versionsDir.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory }
            .mapNotNull { dir -> VERSION_DIR_NAME.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull() }
            .sorted()
    }

    /**
     * Restores version [version]'s files and brief over the project. A
     * rollback first takes a version snapshot of the state it replaces,
     * so a rollback can itself be rolled back. `project.json` is
     * restored from the version too, with the update stamp moved to
     * [nowMillis]; the restored state is returned.
     */
    fun rollback(project: CodeProject, version: Int, nowMillis: Long): CodeProject {
        val dir = projectDir(project.id)
        val source = File(File(dir, VERSIONS_DIR_NAME), versionDirName(version))
        require(source.isDirectory) { "Project ${project.id} has no version $version" }
        snapshotVersion(project)
        dir.listFiles()?.forEach { child ->
            if (child.name != VERSIONS_DIR_NAME) child.deleteRecursively()
        }
        copyProjectContents(source, dir)
        val restored = loadProject(project.id) ?: project
        val updated = restored.copy(updatedAtMillis = nowMillis)
        saveProject(updated)
        return updated
    }

    private fun pruneVersions(id: String) {
        val versions = listVersions(id)
        if (versions.size <= MAX_KEPT_VERSIONS) return
        val versionsDir = File(projectDir(id), VERSIONS_DIR_NAME)
        for (number in versions.dropLast(MAX_KEPT_VERSIONS)) {
            File(versionsDir, versionDirName(number)).deleteRecursively()
        }
    }

    private fun copyProjectContents(from: File, to: File) {
        val children = from.listFiles() ?: return
        for (child in children) {
            if (child.name == VERSIONS_DIR_NAME) continue
            copyEntry(child, File(to, child.name))
        }
    }

    private fun copyEntry(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyEntry(it, File(target, it.name)) }
        } else if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Same-directory rename is atomic on Android's filesystems;
            // this fallback only fires on exotic storage.
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun escapeLogField(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescapeLogField(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> {
                        out.append(c)
                        out.append(value[i + 1])
                    }
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        const val PROJECTS_DIR_NAME = "projects"
        const val BRIEF_FILE_NAME = "BRIEF.md"
        const val PROJECT_JSON_FILE_NAME = "project.json"
        const val ROUND_LOG_FILE_NAME = "rounds.log"
        const val VERSIONS_DIR_NAME = "versions"
        const val INDEX_FILE_NAME = "index.html"

        /** Version snapshots kept per project (section 3.4). */
        const val MAX_KEPT_VERSIONS = 10

        /** The longest slug a project id carries; the rest is cut. */
        const val MAX_SLUG_CHARS = 32

        private val VERSION_DIR_NAME = Regex("v(\\d+)")
        private val PACKAGE_PREFIX = "com.battlesbudz.faraday.app."

        /** The page a new project starts with: small, complete, and already working. */
        const val STARTER_HTML: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>New app</title>
<style>
  body { font-family: sans-serif; margin: 2em auto; max-width: 32em; padding: 0 1em; text-align: center; }
</style>
</head>
<body>
<h1>New app</h1>
<p>Describe what you want and Faraday will build it here.</p>
</body>
</html>
"""

        /** Ids are slugs: lowercase ASCII letters, digits, dashes, underscores. */
        fun isSafeId(id: String): Boolean =
            id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

        /** The id a project name maps to before uniqueness suffixing. */
        fun slugify(name: String): String {
            val slug = name.lowercase(Locale.ROOT)
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(MAX_SLUG_CHARS)
                .trim('-')
            return slug.ifEmpty { "app" }
        }

        /**
         * The export package name for a project id (section 5.2's
         * derivation, computed at creation so `project.json` carries it):
         * the fixed prefix, the id slug (at most 24 chars), an
         * underscore, and 8 hex digits of the id's SHA-256. At most 61
         * chars — inside the exporter's 64-char placeholder.
         */
        fun exportPackageName(id: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            val hash = ProjectFingerprint.toHex(digest).substring(0, 8)
            return PACKAGE_PREFIX + id.take(24) + "_" + hash
        }

        /** The `versions/` folder name for a version number: v001, v002, … */
        fun versionDirName(number: Int): String = "v" + number.toString().padStart(3, '0')
    }
}
