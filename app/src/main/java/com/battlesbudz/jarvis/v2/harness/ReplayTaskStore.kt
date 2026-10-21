package com.battlesbudz.jarvis.v2.harness

import java.io.File

/**
 * File-backed store for [ReplayTurnRecord]s (self-improving harness
 * project, Phase 1; plan §4.3). One JSON file per turn in app-private
 * storage, written atomically (temp file, then rename — the same pattern
 * the memory stores use), with a rolling retention cap of the
 * [MAX_TURNS] most recent turns (Battles's decision, 2026-10-21).
 *
 * The store holds conversation content (user text, memory sections,
 * detections) and nothing else ever leaves the phone with it; there is no
 * export path. Recording never throws: a failed capture must not affect
 * the live turn that produced it.
 */
class ReplayTaskStore(
    private val dir: File,
    private val maxTurns: Int = MAX_TURNS
) {
    private val lock = Any()

    /** Persist [turn], then prune to the retention cap. Returns false on any I/O failure. */
    fun record(turn: ReplayTurnRecord): Boolean = synchronized(lock) {
        runCatching {
            dir.mkdirs()
            val name = "%013d-%s.json".format(turn.capturedAtMs, turn.id)
            val target = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.writeText(ReplayTurnCodec.toJson(turn))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            pruneLocked()
            true
        }.getOrDefault(false)
    }

    /** All retained turns, oldest first. Unreadable files are skipped. */
    fun list(): List<ReplayTurnRecord> = synchronized(lock) {
        filesLocked().mapNotNull { file ->
            runCatching { ReplayTurnCodec.fromJson(file.readText()) }.getOrNull()
        }
    }

    fun count(): Int = synchronized(lock) { filesLocked().size }

    private fun filesLocked(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun pruneLocked() {
        val files = filesLocked()
        if (files.size > maxTurns) {
            files.take(files.size - maxTurns).forEach { it.delete() }
        }
    }

    companion object {
        /** Battles's retention decision (2026-10-21): 100 most recent turns, rolling. */
        const val MAX_TURNS = 100
    }
}
