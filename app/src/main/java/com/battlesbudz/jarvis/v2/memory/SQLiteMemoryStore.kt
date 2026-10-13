package com.battlesbudz.jarvis.v2.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import kotlin.concurrent.withLock

/**
 * Device-local canonical ledger. Records and non-content erase tombstones are separate rows;
 * mutations and generation changes share one SQLite transaction. Indexes are rebuildable later.
 * A committed migration marker prevents a surviving legacy file from resurrecting erased data.
 */
class SQLiteMemoryStore(
    private val file: File,
    private val legacyFile: File? = null,
    private val canReadSourceText: () -> Boolean = { false },
    private val archiveClock: () -> Long = { System.currentTimeMillis() },
) : MemoryPersistence, MemorySourceArchive, AutoCloseable {
    private var connection: SQLiteDatabase? = null
    // Android configures journal mode while opening, before a transaction can serialize writers.
    // All instances for one canonical path therefore share the same in-process boundary.
    private val databaseLock = lockFor(file)

    override fun read(): MemoryStore.Read = databaseLock.withLock { try {
        val db = database()
        transaction(db) { purgeSourceText(db, archiveClock()); MemoryStore.Read(snapshot(db)) }
    } catch (e: Exception) {
        MemoryStore.Read(null, "Memory database is unavailable (${e.javaClass.simpleName}).")
    } }

    override fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot, T>): MemoryStore.Update<T> = databaseLock.withLock { try {
        val db = database()
        transaction(db) {
            purgeSourceText(db, archiveClock())
            val before = snapshot(db)
            val (after, value) = block(before)
            if (after != before) {
                require(after.generation == Math.addExact(before.generation, 1)) { "Invalid memory generation" }
                require(MemorySnapshotCodec.validate(after) == null) { "Invalid memory snapshot" }
                persist(db, before, after)
            }
            MemoryStore.Update(value = value)
        }
    } catch (e: Exception) {
        // Never include SQL/payload text in UI errors or claim a failed transaction succeeded.
        MemoryStore.Update(error = "Memory database update failed (${e.javaClass.simpleName}).")
    } }

    override fun close() { databaseLock.withLock { connection?.close(); connection = null } }

    override fun captureSource(input: FinalMemoryInput): SourceArchiveCapture {
        return databaseLock.withLock { try {
            val prepared = MemoryArchivePolicy.prepare(input, archiveClock())
            prepared.rejection?.let { return SourceArchiveCapture(it) }
            val episode = checkNotNull(prepared.episode)
            val db = database()
            transaction(db) {
                purgeSourceText(db, archiveClock())
                val previous = db.rawQuery("SELECT fingerprint, expires_at_ms FROM source_events WHERE event_key=?", arrayOf(episode.eventKey)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                if (previous != null) return@transaction SourceArchiveCapture(when {
                    previous.second <= archiveClock() -> SourceArchiveOutcome.EXPIRED
                    previous.first == episode.fingerprint -> SourceArchiveOutcome.ALREADY_RECORDED
                    else -> SourceArchiveOutcome.CONFLICT
                })
                val capacity = db.rawQuery("SELECT count(*), coalesce(sum(text_bytes),0) FROM source_events", null).use {
                    check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
                }
                val bytes = episode.text.toByteArray(Charsets.UTF_8).size
                if (capacity.first >= MemoryArchivePolicy.MAX_EVENTS || capacity.second + bytes > MemoryArchivePolicy.MAX_TEXT_BYTES) return@transaction SourceArchiveCapture(SourceArchiveOutcome.FULL)
                if (episode.expiresAtMs <= archiveClock()) return@transaction SourceArchiveCapture(SourceArchiveOutcome.EXPIRED)
                db.insertOrThrow("source_events", null, ContentValues().apply {
                    put("event_key", episode.eventKey); put("conversation_key", episode.conversationKey); put("call_key", episode.callKey)
                    put("source", episode.source.name); put("captured_at_ms", episode.capturedAtMs); put("expires_at_ms", episode.expiresAtMs)
                    put("text", episode.text); put("text_bytes", bytes); put("fingerprint", episode.fingerprint)
                })
                SourceArchiveCapture(SourceArchiveOutcome.STORED)
            }
        } catch (_: Exception) { SourceArchiveCapture(SourceArchiveOutcome.STORAGE_FAILURE) } }
    }

    override fun searchExplicitHistory(query: String, limit: Int): SourceArchiveSearch {
        return databaseLock.withLock { try {
            if (query.isBlank() || query.length > MemoryArchivePolicy.MAX_QUERY_CHARS || limit !in 1..50) return SourceArchiveSearch(SourceArchiveOutcome.INVALID)
            if (!canReadSourceText()) return SourceArchiveSearch(SourceArchiveOutcome.LOCKED)
            val db = database()
            transaction(db) {
                val now = archiveClock()
                purgeSourceText(db, now)
                val rows = db.rawQuery("SELECT event_key, conversation_key, call_key, source, captured_at_ms, expires_at_ms, text, fingerprint FROM source_events WHERE text IS NOT NULL AND expires_at_ms>? AND instr(lower(text),lower(?))>0 ORDER BY captured_at_ms DESC, event_key LIMIT ?", arrayOf(now.toString(), query.trim(), limit.toString())).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(SourceEpisode(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2), ConversationMemorySource.valueOf(cursor.getString(3)), cursor.getLong(4), cursor.getLong(5), cursor.getString(6), cursor.getString(7)))
                        }
                    }
                }
                // Locking/expiry while the query runs must not disclose a now-ineligible result.
                if (!canReadSourceText()) SourceArchiveSearch(SourceArchiveOutcome.LOCKED)
                else {
                    val completedAtMs = archiveClock()
                    SourceArchiveSearch(null, rows.filter { it.expiresAtMs > completedAtMs })
                }
            }
        } catch (_: Exception) { SourceArchiveSearch(SourceArchiveOutcome.STORAGE_FAILURE) } }
    }

    override fun purgeExpiredSources(): Boolean = databaseLock.withLock { try {
        val db = database()
        transaction(db) { purgeSourceText(db, archiveClock()) }
        true
    } catch (_: Exception) { false } }

    private fun purgeSourceText(db: SQLiteDatabase, nowMs: Long) {
        require(nowMs > 0)
        // Retain only opaque event metadata after expiry so retries cannot restart retention.
        db.execSQL("UPDATE source_events SET text=NULL, text_bytes=0 WHERE expires_at_ms<=? AND text IS NOT NULL", arrayOf(nowMs))
    }

    private fun createSourceArchive(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_events (event_key TEXT PRIMARY KEY NOT NULL, conversation_key TEXT NOT NULL, call_key TEXT, source TEXT NOT NULL CHECK(source IN ('TEXT','VOICE')), captured_at_ms INTEGER NOT NULL CHECK(captured_at_ms>0), expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>captured_at_ms), text TEXT, text_bytes INTEGER NOT NULL CHECK(text_bytes>=0), fingerprint TEXT NOT NULL)")
        db.execSQL("CREATE INDEX source_events_expiry ON source_events(expires_at_ms)")
    }

    private fun database(): SQLiteDatabase {
        connection?.let { return it }
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Memory directory unavailable" }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        try {
            // Avoid a long-lived WAL containing old memory payloads; scrub deleted SQLite cells.
            db.disableWriteAheadLogging()
            // This pragma returns a row even when assigning it. Android execSQL rejects
            // statements with results; use a query and verify the requested setting.
            db.rawQuery("PRAGMA secure_delete=ON", null).use {
                require(it.moveToFirst() && it.getInt(0) == 1) { "Memory secure-delete unavailable" }
            }
            db.execSQL("PRAGMA synchronous=FULL")
            transaction(db) {
                when (db.version) {
                    0 -> {
                        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata', 'sqlite_sequence')", null).use {
                            require(!it.moveToFirst()) { "Unrecognized memory database" }
                        }
                        db.execSQL("CREATE TABLE memory_meta (singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL CHECK(generation>=0), legacy_imported INTEGER NOT NULL CHECK(legacy_imported=1))")
                        db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL UNIQUE, review_status TEXT NOT NULL, expires_at_ms INTEGER, payload TEXT NOT NULL)")
                        db.execSQL("CREATE INDEX memories_active ON memories(review_status, expires_at_ms)")
                        db.execSQL("CREATE TABLE tombstones (event_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, erased_at_ms INTEGER NOT NULL)")
                        val imported = if (legacyFile?.exists() == true) {
                            MemoryStore(legacyFile).read().snapshot ?: error("Legacy memory migration failed")
                        } else MemorySnapshot(0, emptyList(), emptyList())
                        db.execSQL("INSERT INTO memory_meta VALUES(1, 0, 1)")
                        persist(db, MemorySnapshot(0, emptyList(), emptyList()), imported)
                        require(snapshot(db) == imported) { "Memory migration validation failed" }
                        createSourceArchive(db)
                        db.version = DATABASE_VERSION
                    }
                    1 -> {
                        snapshot(db)
                        createSourceArchive(db)
                        db.version = DATABASE_VERSION
                    }
                    DATABASE_VERSION -> {
                        snapshot(db) // Missing/malformed schema must fail closed.
                        db.rawQuery("SELECT event_key, conversation_key, call_key, source, captured_at_ms, expires_at_ms, text, text_bytes, fingerprint FROM source_events LIMIT 0", null).use { }
                    }
                    else -> error("Unsupported memory database version")
                }
            }
            // Only retire the old copy after a validated, durable commit. Retry cleanup after a crash
            // or failure; the marker above means this file will never be imported a second time.
            legacyFile?.let { legacy ->
                MemoryStore.deleteOwnedTemps(legacy)
                require(!legacy.exists() || legacy.delete()) { "Legacy memory cleanup failed" }
            }
            connection = db
            return db
        } catch (e: Exception) { db.close(); throw e }
    }

    private fun snapshot(db: SQLiteDatabase): MemorySnapshot {
        val generation = db.rawQuery("SELECT generation, legacy_imported FROM memory_meta WHERE singleton=1", null).use {
            require(it.moveToFirst() && it.getInt(1) == 1) { "Missing memory metadata" }
            it.getLong(0)
        }
        val memories = db.rawQuery("SELECT id, event_id, review_status, expires_at_ms, payload FROM memories ORDER BY rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val record = MemorySnapshotCodec.decodeMemory(JSONObject(cursor.getString(4)))
                    require(record.id == cursor.getString(0) && record.source.eventId == cursor.getString(1) && record.reviewStatus.name == cursor.getString(2)) { "Memory columns disagree" }
                    require(record.expiresAtMs == if (cursor.isNull(3)) null else cursor.getLong(3)) { "Memory expiry disagrees" }
                    add(record)
                }
            }
        }
        val tombstones = db.rawQuery("SELECT event_id, fingerprint, erased_at_ms FROM tombstones ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(MemoryTombstone(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
        }
        return MemorySnapshot(generation, memories, tombstones).also {
            require(MemorySnapshotCodec.validate(it) == null) { "Invalid persisted memory" }
        }
    }

    private fun persist(db: SQLiteDatabase, before: MemorySnapshot, after: MemorySnapshot) {
        val previous = before.memories.associateBy { it.id }
        val current = after.memories.associateBy { it.id }
        previous.keys.filterNot { it in current }.forEach { db.delete("memories", "id=?", arrayOf(it)) }
        after.memories.filter { previous[it.id] != it }.forEach { record ->
            val values = ContentValues().apply {
                put("id", record.id); put("event_id", record.source.eventId); put("review_status", record.reviewStatus.name)
                put("expires_at_ms", record.expiresAtMs); put("payload", MemorySnapshotCodec.encodeMemory(record).toString())
            }
            if (record.id in previous) require(db.update("memories", values, "id=?", arrayOf(record.id)) == 1)
            else db.insertOrThrow("memories", null, values)
        }
        val previousTombstones = before.tombstones.associateBy { it.eventId }
        val currentTombstones = after.tombstones.associateBy { it.eventId }
        previousTombstones.keys.filterNot { it in currentTombstones }.forEach { db.delete("tombstones", "event_id=?", arrayOf(it)) }
        after.tombstones.filter { previousTombstones[it.eventId] != it }.forEach { tombstone ->
            val values = ContentValues().apply { put("event_id", tombstone.eventId); put("fingerprint", tombstone.payloadFingerprint); put("erased_at_ms", tombstone.erasedAtMs) }
            if (tombstone.eventId in previousTombstones) require(db.update("tombstones", values, "event_id=?", arrayOf(tombstone.eventId)) == 1)
            else db.insertOrThrow("tombstones", null, values)
        }
        db.execSQL("UPDATE memory_meta SET generation=? WHERE singleton=1", arrayOf(after.generation))
    }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }

    companion object {
        const val DATABASE_VERSION = 2
        private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
        private fun lockFor(file: File): java.util.concurrent.locks.ReentrantLock {
            val path = try { file.canonicalPath } catch (_: Exception) { file.absoluteFile.normalize().path }
            return pathLocks.computeIfAbsent(path) { java.util.concurrent.locks.ReentrantLock(true) }
        }
    }
}
