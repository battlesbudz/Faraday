package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Versioned JSON file store. It is intentionally independent from Android; pass noBackupFilesDir from the UI adapter. */
class MemoryStore(
    private val file: File,
    private val cleanupArtifacts: (File) -> Unit = MemoryStore::deleteOwnedTemps,
    private val commitWriter: (File, String) -> Unit = ::atomicWrite,
) : MemoryPersistence {
    data class Read(val snapshot: MemorySnapshot?, val error: String? = null)
    data class Update<T>(val value: T? = null, val error: String? = null)

    override fun read(): Read = synchronized(lockFor(file)) { readLocked() }

    /** Reloads disk while holding a per-canonical-file process lock, then durably commits before returning [value]. */
    override fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): Update<T> = synchronized(lockFor(file)) {
        val read = readLocked()
        val before = read.snapshot ?: return@synchronized Update(error = read.error ?: "Memory store is unavailable.")
        val (after, value) = try { block(before) } catch (e: Exception) { return@synchronized Update(error = e.message ?: "Memory update failed.") }
        // Idempotent/conflict/no-op outcomes must not become write failures merely because storage is unavailable.
        if (after == before) return@synchronized Update(value = value)
        val validity = MemorySnapshotCodec.validate(after)
        if (validity != null) return@synchronized Update(error = validity)
        val encoded = MemorySnapshotCodec.encode(after)
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_STORE_BYTES) return@synchronized Update(error = "Memory store capacity exceeded.")
        return@synchronized try {
            commitWriter(file, encoded)
            Update(value = value)
        } catch (e: Exception) {
            Update(error = "Memory store write failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readLocked(): Read {
        try { cleanupArtifacts(file) } catch (e: Exception) {
            return Read(null, "Memory store cleanup failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!file.exists()) return Read(MemorySnapshot(0, emptyList(), emptyList()))
        return try {
            if (!file.isFile || file.length() > MAX_STORE_BYTES) return Read(null, "Memory store is unreadable or exceeds its size limit.")
            val raw = file.readText(StandardCharsets.UTF_8)
            if (raw.length > MAX_STORE_BYTES) return Read(null, "Memory store exceeds its size limit.")
            MemorySnapshotCodec.decode(raw)
        } catch (e: Exception) { Read(null, "Memory store is corrupt: ${e.message ?: e.javaClass.simpleName}") }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_STORE_BYTES = 1_048_576L
        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(file: File): Any = locks.getOrPut(file.canonicalFile.path) { Any() }
        /** Deletes only files whose name is the store's own atomic-write temp naming family. */
        internal fun deleteOwnedTemps(destination: File) {
            val parent = destination.parentFile ?: return
            if (!parent.exists()) return // A new store has no directory or artifacts yet.
            if (!parent.isDirectory) throw IllegalStateException("memory store parent is not a directory")
            val prefix = ".${destination.name}."
            val candidates = parent.listFiles() ?: throw IllegalStateException("could not inspect memory store artifacts")
            candidates.filter { candidate -> candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".tmp") }
                .forEach { candidate -> if (!candidate.delete()) throw IllegalStateException("could not remove abandoned memory artifact") }
        }

        private fun atomicWrite(destination: File, contents: String) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { out -> out.write(contents.toByteArray(StandardCharsets.UTF_8)); out.fd.sync() }
                if (!temporary.renameTo(destination)) throw IllegalStateException("atomic rename failed")
            } finally { temporary.delete() }
        }
    }
}
