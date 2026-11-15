package com.battlesbudz.jarvis.v2.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.withLock

/**
 * One memory's embedding projection row. The canonical record lives in the memory
 * ledger (memory-os.db); this row is a rebuildable projection of it. The stamp —
 * content fingerprint, model id and stored dimension — is what makes staleness
 * detectable: any row whose stamp no longer matches the canonical record and the
 * active model is ignored and reconciled away by the indexer.
 */
data class MemoryVectorEntry(
    val memoryId: String,
    val contentFingerprint: String,
    val modelId: String,
    val dimension: Int,
    val vector: FloatArray,
    val updatedAtMs: Long,
) {
    init {
        require(memoryId.isNotBlank()) { "Vector rows require a memory id." }
        require(modelId.isNotBlank()) { "Vector rows require a model id." }
        require(dimension > 0 && vector.size == dimension) { "Vector width must match its stamped dimension." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVectorEntry) return false
        return memoryId == other.memoryId && contentFingerprint == other.contentFingerprint &&
            modelId == other.modelId && dimension == other.dimension &&
            updatedAtMs == other.updatedAtMs && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + contentFingerprint.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dimension
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

/**
 * Storage boundary for the embedding projection. Reads are fail-closed, mirroring the
 * canonical store: a storage failure is reported as an error, never as an empty index.
 */
interface MemoryVectorStore {
    data class Read(val entries: List<MemoryVectorEntry>?, val error: String? = null)

    /** Rows stamped with [modelId] only; vectors from another model are never mixed into a result. */
    fun read(modelId: String): Read
    fun put(entry: MemoryVectorEntry): Boolean
    fun remove(memoryIds: Collection<String>): Boolean
    /** Bytes held by the backing file (0 when it does not exist yet). */
    fun storedBytes(): Long
}

/**
 * SQLite implementation over a sibling database file, following the canonical store's
 * conventions: per-path in-process lock, no write-ahead log (deleted cells must not
 * linger in a journal), secure_delete verified on, synchronous FULL, and schema or
 * payload disagreement fails the read closed instead of returning partial data.
 */
class SQLiteMemoryVectorStore(private val file: File) : MemoryVectorStore, AutoCloseable {
    private var connection: SQLiteDatabase? = null
    private val databaseLock = lockFor(file)

    override fun read(modelId: String): MemoryVectorStore.Read = databaseLock.withLock {
        try {
            val db = database()
            transaction(db) {
                val entries = db.rawQuery(
                    "SELECT memory_id, content_fingerprint, model_id, dimension, vector, updated_at_ms FROM memory_vectors WHERE model_id=? ORDER BY memory_id",
                    arrayOf(modelId),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val dimension = cursor.getInt(3)
                            val blob = cursor.getBlob(4)
                            require(blob.size == dimension * Float.SIZE_BYTES) { "Memory vector width disagrees" }
                            add(MemoryVectorEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), dimension, decode(blob), cursor.getLong(5)))
                        }
                    }
                }
                MemoryVectorStore.Read(entries)
            }
        } catch (e: Exception) {
            MemoryVectorStore.Read(null, "Memory vector database is unavailable (${e.javaClass.simpleName}).")
        }
    }

    override fun put(entry: MemoryVectorEntry): Boolean = databaseLock.withLock {
        try {
            val db = database()
            transaction(db) {
                val values = ContentValues().apply {
                    put("memory_id", entry.memoryId)
                    put("content_fingerprint", entry.contentFingerprint)
                    put("model_id", entry.modelId)
                    put("dimension", entry.dimension)
                    put("vector", encode(entry.vector))
                    put("updated_at_ms", entry.updatedAtMs)
                }
                db.insertWithOnConflict("memory_vectors", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            true
        } catch (_: Exception) { false }
    }

    override fun remove(memoryIds: Collection<String>): Boolean = databaseLock.withLock {
        if (memoryIds.isEmpty()) return@withLock true
        try {
            val db = database()
            transaction(db) {
                memoryIds.distinct().forEach { id -> db.delete("memory_vectors", "memory_id=?", arrayOf(id)) }
            }
            true
        } catch (_: Exception) { false }
    }

    override fun storedBytes(): Long = if (file.isFile) file.length() else 0L

    override fun close() { databaseLock.withLock { connection?.close(); connection = null } }

    private fun database(): SQLiteDatabase {
        connection?.let { return it }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Memory vector directory unavailable" }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            db.disableWriteAheadLogging()
            db.rawQuery("PRAGMA secure_delete=ON", null).use {
                require(it.moveToFirst() && it.getInt(0) == 1) { "Memory vector secure-delete unavailable" }
            }
            db.execSQL("PRAGMA synchronous=FULL")
            transaction(db) {
                when (db.version) {
                    0 -> {
                        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata', 'sqlite_sequence')", null).use {
                            require(!it.moveToFirst()) { "Unrecognized memory vector database" }
                        }
                        db.execSQL("CREATE TABLE memory_vectors (memory_id TEXT PRIMARY KEY NOT NULL, content_fingerprint TEXT NOT NULL, model_id TEXT NOT NULL, dimension INTEGER NOT NULL CHECK(dimension>0), vector BLOB NOT NULL, updated_at_ms INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX memory_vectors_model ON memory_vectors(model_id)")
                        db.version = DATABASE_VERSION
                    }
                    DATABASE_VERSION -> {
                        db.rawQuery("SELECT memory_id, content_fingerprint, model_id, dimension, vector, updated_at_ms FROM memory_vectors LIMIT 0", null).use { }
                    }
                    else -> error("Unsupported memory vector database version")
                }
            }
            connection = db
            return db
        } catch (e: Exception) { db.close(); throw e }
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    private fun decode(blob: ByteArray): FloatArray {
        val out = FloatArray(blob.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    companion object {
        const val DATABASE_VERSION = 1
        private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
        private fun lockFor(file: File): java.util.concurrent.locks.ReentrantLock {
            val path = try { file.canonicalPath } catch (_: Exception) { file.absoluteFile.normalize().path }
            return pathLocks.computeIfAbsent(path) { java.util.concurrent.locks.ReentrantLock(true) }
        }
    }
}
