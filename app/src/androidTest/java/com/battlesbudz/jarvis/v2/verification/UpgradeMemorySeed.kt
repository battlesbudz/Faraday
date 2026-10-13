package com.battlesbudz.jarvis.v2.verification

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Frozen, framework-only schema-2 fixture. This executes in the previous APK;
 * candidate storage classes (including their obfuscated names) are not available.
 * Startup may initialize the empty store before instrumentation gets here. The
 * write transaction serializes admission and seeding with that initialization.
 */
internal object UpgradeMemorySeed {
    private const val META = "CREATE TABLE memory_meta (singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL CHECK(generation>=0), legacy_imported INTEGER NOT NULL CHECK(legacy_imported=1))"
    private const val MEMORIES = "CREATE TABLE memories (id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL UNIQUE, review_status TEXT NOT NULL, expires_at_ms INTEGER, payload TEXT NOT NULL)"
    private const val MEMORIES_INDEX = "CREATE INDEX memories_active ON memories(review_status, expires_at_ms)"
    private const val TOMBSTONES = "CREATE TABLE tombstones (event_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, erased_at_ms INTEGER NOT NULL)"
    private const val SOURCES = "CREATE TABLE source_events (event_key TEXT PRIMARY KEY NOT NULL, conversation_key TEXT NOT NULL, call_key TEXT, source TEXT NOT NULL CHECK(source IN ('TEXT','VOICE')), captured_at_ms INTEGER NOT NULL CHECK(captured_at_ms>0), expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>captured_at_ms), text TEXT, text_bytes INTEGER NOT NULL CHECK(text_bytes>=0), fingerprint TEXT NOT NULL)"
    private const val SOURCES_INDEX = "CREATE INDEX source_events_expiry ON source_events(expires_at_ms)"
    private const val ANDROID_META = "CREATE TABLE android_metadata (locale TEXT)"
    private val schema = listOf(META, MEMORIES, MEMORIES_INDEX, TOMBSTONES, SOURCES, SOURCES_INDEX)
    private val dataTables = listOf("memories", "tombstones", "source_events")

    private data class SchemaObject(val type: String, val name: String, val table: String, val sql: String?)
    private fun sql(sql: String) = sql.trim().replace(Regex("\\s+"), " ")
    private val expectedObjects = setOf(
        SchemaObject("table", "memory_meta", "memory_meta", META),
        SchemaObject("table", "memories", "memories", MEMORIES),
        SchemaObject("index", "sqlite_autoindex_memories_1", "memories", null),
        SchemaObject("index", "sqlite_autoindex_memories_2", "memories", null),
        SchemaObject("index", "memories_active", "memories", MEMORIES_INDEX),
        SchemaObject("table", "tombstones", "tombstones", TOMBSTONES),
        SchemaObject("index", "sqlite_autoindex_tombstones_1", "tombstones", null),
        SchemaObject("table", "source_events", "source_events", SOURCES),
        SchemaObject("index", "sqlite_autoindex_source_events_1", "source_events", null),
        SchemaObject("index", "source_events_expiry", "source_events", SOURCES_INDEX),
    )
    private data class Column(val name: String, val type: String, val notNull: Int = 0, val primaryKey: Int = 0)
    private val expectedColumns = mapOf(
        "memory_meta" to listOf(Column("singleton", "INTEGER", primaryKey = 1), Column("generation", "INTEGER", 1), Column("legacy_imported", "INTEGER", 1)),
        "memories" to listOf(Column("id", "TEXT", 1, 1), Column("event_id", "TEXT", 1), Column("review_status", "TEXT", 1), Column("expires_at_ms", "INTEGER"), Column("payload", "TEXT", 1)),
        "tombstones" to listOf(Column("event_id", "TEXT", 1, 1), Column("fingerprint", "TEXT", 1), Column("erased_at_ms", "INTEGER", 1)),
        "source_events" to listOf(Column("event_key", "TEXT", 1, 1), Column("conversation_key", "TEXT", 1), Column("call_key", "TEXT"), Column("source", "TEXT", 1), Column("captured_at_ms", "INTEGER", 1), Column("expires_at_ms", "INTEGER", 1), Column("text", "TEXT"), Column("text_bytes", "INTEGER", 1), Column("fingerprint", "TEXT", 1)),
    )
    private val expectedIndexes = mapOf(
        "memories" to mapOf("sqlite_autoindex_memories_1" to listOf("id"), "sqlite_autoindex_memories_2" to listOf("event_id"), "memories_active" to listOf("review_status", "expires_at_ms")),
        "tombstones" to mapOf("sqlite_autoindex_tombstones_1" to listOf("event_id")),
        "source_events" to mapOf("sqlite_autoindex_source_events_1" to listOf("event_key"), "source_events_expiry" to listOf("expires_at_ms")),
        "memory_meta" to emptyMap(),
    )

    fun seed(file: File, now: Long) = open(file).use { seed(it, now) }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun seed(db: SQLiteDatabase, now: Long) {
        db.beginTransaction()
        try {
            check(integerPragma(db, "application_id") == 0L) { "Unrecognized memory application ID" }
            val objects = applicationObjects(db)
            when (db.version) {
                0 -> {
                    check(objects.isEmpty()) { "Previous APK fixture requires a pristine new memory database" }
                    createEmptySchema(db)
                }
                2 -> Unit
                else -> error("Unsupported previous memory schema")
            }
            check(applicationObjects(db) == expectedObjects) { "Previous memory schema differs from the frozen schema-2 fixture" }
            expectedColumns.forEach { (table, columns) -> checkColumns(db, table, columns) }
            checkIndexes(db)
            check(pristineMetadata(db)) { "Previous memory metadata is not pristine" }
            dataTables.forEach { table ->
                db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use {
                    check(!it.moveToFirst()) { "Previous memory database contains $table" }
                }
            }
            insertFixture(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun applicationObjects(db: SQLiteDatabase): Set<SchemaObject> {
        val objects = db.rawQuery("SELECT type, name, tbl_name, sql FROM sqlite_master", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(SchemaObject(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    if (cursor.isNull(3)) null else sql(cursor.getString(3))))
            }
        }
        objects.find { it.name == "android_metadata" }?.let { metadata ->
            check(metadata == SchemaObject("table", "android_metadata", "android_metadata", ANDROID_META)) { "Unknown Android metadata schema" }
            checkColumns(db, "android_metadata", listOf(Column("locale", "TEXT")))
        }
        // Only the known Android locale table is optional. Do not ignore sqlite_*
        // objects, extra indexes, views, triggers, virtual tables or other extensions.
        return objects.filterNot { it.name == "android_metadata" }.toSet()
    }

    private fun checkColumns(db: SQLiteDatabase, table: String, expected: List<Column>) {
        val actual = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(cursor.getInt(0) == size && cursor.isNull(4)) { "Unexpected memory column/default" }
                    add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getInt(5)))
                }
            }
        }
        check(actual == expected) { "Unexpected columns in $table" }
    }

    private fun checkIndexes(db: SQLiteDatabase) {
        expectedIndexes.forEach { (table, expected) ->
            val actual = db.rawQuery("PRAGMA index_list($table)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        val automatic = name.startsWith("sqlite_autoindex_")
                        check(cursor.getInt(2) == if (automatic) 1 else 0) { "Unexpected index uniqueness" }
                        val origin = if (!automatic) "c" else if (name == "sqlite_autoindex_memories_2") "u" else "pk"
                        check(cursor.getString(3) == origin && cursor.getInt(4) == 0) { "Unexpected index origin/predicate" }
                        add(name)
                    }
                }
            }
            check(actual == expected.keys) { "Unexpected indexes in $table" }
            expected.forEach { (name, columns) ->
                val actualColumns = db.rawQuery("PRAGMA index_info($name)", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                }
                check(actualColumns == columns) { "Unexpected columns in $name" }
            }
        }
    }

    private fun pristineMetadata(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT singleton, generation, legacy_imported, typeof(singleton), typeof(generation), typeof(legacy_imported) FROM memory_meta", null,
    ).use {
        it.moveToFirst() && it.getLong(0) == 1L && it.getLong(1) == 0L && it.getLong(2) == 1L &&
            (3..5).all { column -> it.getString(column) == "integer" } && !it.moveToNext()
    }

    private fun createEmptySchema(db: SQLiteDatabase, statements: List<String> = schema) {
        statements.forEach { db.execSQL(it) }
        db.execSQL("INSERT INTO memory_meta VALUES(1, 0, 1)")
        db.version = 2
    }

    private fun insertFixture(db: SQLiteDatabase, now: Long) {
        val payload = JSONObject().put("id", "12345678-1234-4123-8123-123456789012")
            .put("content", "Upgrade fixture prefers apricots").put("category", "PREFERENCE")
            .put("tier", "LONG_TERM").put("type", "SEMANTIC").put("confidence", 90)
            .put("reviewStatus", "APPROVED").put("createdAtMs", now).put("updatedAtMs", now).put("revision", 1)
            .put("source", JSONObject().put("eventId", "a".repeat(32)).put("eventSource", "text")
                .put("createdAtMs", now).put("sensitivity", "NORMAL").put("provenance", JSONArray()))
        db.execSQL("UPDATE memory_meta SET generation=2 WHERE singleton=1")
        db.execSQL("INSERT INTO memories VALUES(?, ?, 'APPROVED', NULL, ?)", arrayOf("12345678-1234-4123-8123-123456789012", "a".repeat(32), payload.toString()))
        db.execSQL("INSERT INTO tombstones VALUES(?, ?, ?)", arrayOf<Any>("b".repeat(32), "c".repeat(64), now))
        val text = "Upgrade fixture source text"
        db.execSQL("INSERT INTO source_events VALUES(?, ?, NULL, 'TEXT', ?, ?, ?, ?, ?)",
            arrayOf<Any>("d".repeat(32), "f".repeat(32), now, now + 86_400_000L, text, text.toByteArray().size, "e".repeat(64)))
    }

    /** Called by the named seed journey, so every hosted upgrade executes these checks. */
    fun verifyContract(cacheDir: File) {
        val directory = File(cacheDir, "upgrade-memory-contract-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Could not create isolated memory contract directory" }
        val now = 1_800_000_000_000L
        var checks = 0
        fun passed(name: String) { checks++; Log.i("UpgradeSeedContract", "PASS $name") }
        fun accepted(name: String, initialize: (SQLiteDatabase) -> Unit = {}) {
            val file = File(directory, "$checks.db")
            open(file).use { db ->
                initialize(db)
                seed(db, now)
                assertFixture(db, now)
            }
            passed(name)
        }
        fun rejected(name: String, initialize: (SQLiteDatabase) -> Unit) {
            val file = File(directory, "$checks.db")
            open(file).use { db ->
                initialize(db)
                val before = snapshot(db)
                val failure = runCatching { seed(db, now) }.exceptionOrNull()
                check(failure is IllegalStateException) { "$name did not fail closed: $failure" }
                check(snapshot(db) == before) { "$name changed a rejected memory database" }
            }
            passed(name)
        }
        fun rejectedInitialized(name: String, alter: (SQLiteDatabase) -> Unit) = rejected(name) { db ->
            createEmptySchema(db); alter(db)
        }
        try {
            val newFile = File(directory, "not-created.db")
            check(!newFile.exists())
            seed(newFile, now)
            open(newFile).use { assertFixture(it, now) }
            passed("new database")
            accepted("empty schema 0")
            accepted("empty schema 0 with Android locale") { db ->
                db.execSQL(ANDROID_META); db.execSQL("INSERT INTO android_metadata VALUES('en_US')")
            }
            accepted("initialized schema 2") { createEmptySchema(it) }
            accepted("initialized schema 2 with Android locale") { db ->
                createEmptySchema(db); db.execSQL(ANDROID_META); db.execSQL("INSERT INTO android_metadata VALUES('en_US')")
            }
            rejectedInitialized("nonempty memories") { it.execSQL("INSERT INTO memories VALUES('keep', 'event', 'APPROVED', NULL, 'preserve memory')") }
            rejectedInitialized("nonempty tombstones") { it.execSQL("INSERT INTO tombstones VALUES('keep', 'fingerprint', 1)") }
            rejectedInitialized("nonempty source events") { it.execSQL("INSERT INTO source_events VALUES('keep', 'conversation', NULL, 'TEXT', 1, 2, 'preserve source', 15, 'fingerprint')") }
            rejectedInitialized("nonzero generation") { it.execSQL("UPDATE memory_meta SET generation=1") }
            rejectedInitialized("missing metadata") { it.execSQL("DELETE FROM memory_meta") }
            rejectedInitialized("unimported metadata") {
                it.execSQL("PRAGMA ignore_check_constraints=ON"); it.execSQL("UPDATE memory_meta SET legacy_imported=0")
            }
            rejectedInitialized("multiple metadata rows") {
                it.execSQL("PRAGMA ignore_check_constraints=ON"); it.execSQL("INSERT INTO memory_meta VALUES(2, 0, 1)")
            }
            rejectedInitialized("text generation masquerading as zero") { it.execSQL("UPDATE memory_meta SET generation='not a number'") }
            rejected("unknown schema 0 table") { it.execSQL("CREATE TABLE keep (value TEXT)"); it.execSQL("INSERT INTO keep VALUES('preserve')") }
            rejected("unknown schema 0 view") { it.execSQL("CREATE VIEW keep AS SELECT 1") }
            rejected("unknown Android metadata") { it.execSQL("CREATE TABLE android_metadata (locale TEXT, extra TEXT)") }
            rejectedInitialized("unsupported schema 1") { it.version = 1 }
            rejectedInitialized("unsupported schema 3") { it.version = 3 }
            rejectedInitialized("foreign application ID") { it.execSQL("PRAGMA application_id=123") }
            rejectedInitialized("extra table") { it.execSQL("CREATE TABLE keep (id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT)"); it.execSQL("INSERT INTO keep(value) VALUES('preserve')") }
            rejectedInitialized("extra index") { it.execSQL("CREATE INDEX keep ON memories(payload)") }
            rejectedInitialized("extra view") { it.execSQL("CREATE VIEW keep AS SELECT * FROM memory_meta") }
            rejectedInitialized("extra trigger") { it.execSQL("CREATE TRIGGER keep AFTER UPDATE ON memory_meta BEGIN UPDATE memory_meta SET generation=99; END") }
            rejectedInitialized("extra column") { it.execSQL("ALTER TABLE memories ADD COLUMN keep TEXT") }
            rejected("different known index") { db -> createEmptySchema(db, schema.map { if (it == MEMORIES_INDEX) "CREATE INDEX memories_active ON memories(review_status)" else it }) }
            rejected("missing known index") { db -> createEmptySchema(db, schema.filterNot { it == SOURCES_INDEX }) }
            rejected("changed constraint") { db -> createEmptySchema(db, schema.map { if (it == META) it.replace("CHECK(generation>=0)", "CHECK(generation>=-1)") else it }) }
            concurrentInitialization(File(directory, "concurrent-empty.db"), now, populated = false)
            passed("concurrent initialization then seed")
            concurrentInitialization(File(directory, "concurrent-populated.db"), now, populated = true)
            passed("concurrent populated initialization preserved")
            Log.i("UpgradeSeedContract", "Passed $checks memory seed contract checks")
        } finally {
            // Only this invocation's uniquely owned scratch files are removed.
            // The previous APK's actual memory-os.db is never deleted or reset.
            check(directory.deleteRecursively()) { "Could not clean isolated memory contract files" }
        }
    }

    private fun concurrentInitialization(file: File, now: Long, populated: Boolean) {
        open(file).use { initializer -> open(file).use { seeder ->
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val thread = Thread({
                entered.countDown()
                try { seed(seeder, now) } catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }, "upgrade-memory-seed-contract")
            var initialized: List<String>? = null
            initializer.beginTransaction()
            try {
                createEmptySchema(initializer)
                if (populated) initializer.execSQL("INSERT INTO tombstones VALUES('keep', 'fingerprint', 1)")
                initialized = snapshot(initializer)
                thread.start()
                check(entered.await(5, TimeUnit.SECONDS)) { "Concurrent seeder did not start" }
                check(!finished.await(100, TimeUnit.MILLISECONDS)) { "Seeder bypassed the initialization transaction" }
                initializer.setTransactionSuccessful()
            } finally {
                initializer.endTransaction()
                thread.join(10_000)
            }
            check(!thread.isAlive) { "Concurrent seed did not finish after initialization committed" }
            if (populated) {
                check(failure.get() is IllegalStateException) { "Concurrent populated database was admitted: ${failure.get()}" }
                check(snapshot(initializer) == initialized) { "Concurrent populated database was changed" }
            } else {
                check(failure.get() == null) { "Concurrent empty initialization failed: ${failure.get()}" }
                assertFixture(initializer, now)
            }
        } }
    }

    private fun assertFixture(db: SQLiteDatabase, now: Long) {
        check(db.version == 2 && applicationObjects(db) == expectedObjects)
        check(integerQuery(db, "SELECT generation FROM memory_meta WHERE singleton=1 AND legacy_imported=1") == 2L)
        (listOf("memory_meta") + dataTables).forEach { check(integerQuery(db, "SELECT count(*) FROM $it") == 1L) }
        db.rawQuery("SELECT id, event_id, review_status, expires_at_ms, payload FROM memories", null).use {
            check(it.moveToFirst() && it.getString(0) == "12345678-1234-4123-8123-123456789012" && it.getString(1) == "a".repeat(32))
            check(it.getString(2) == "APPROVED" && it.isNull(3))
            val payload = JSONObject(it.getString(4))
            check(payload.getString("content") == "Upgrade fixture prefers apricots" && payload.getLong("createdAtMs") == now)
            check(payload.getInt("revision") == 1 && payload.getJSONObject("source").getString("eventSource") == "text")
        }
        db.rawQuery("SELECT event_id, fingerprint, erased_at_ms FROM tombstones", null).use {
            check(it.moveToFirst() && it.getString(0) == "b".repeat(32) && it.getString(1) == "c".repeat(64) && it.getLong(2) == now)
        }
        db.rawQuery("SELECT event_key, conversation_key, call_key, source, captured_at_ms, expires_at_ms, text, text_bytes, fingerprint FROM source_events", null).use {
            check(it.moveToFirst() && it.getString(0) == "d".repeat(32) && it.getString(1) == "f".repeat(32) && it.isNull(2) && it.getString(3) == "TEXT")
            check(it.getLong(4) == now && it.getLong(5) == now + 86_400_000L && it.getString(6) == "Upgrade fixture source text")
            check(it.getInt(7) == "Upgrade fixture source text".toByteArray().size && it.getString(8) == "e".repeat(64))
        }
    }

    private fun integerQuery(db: SQLiteDatabase, query: String) = db.rawQuery(query, null).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun integerPragma(db: SQLiteDatabase, name: String) = integerQuery(db, "PRAGMA $name")

    /** Logical schema/content snapshot, including unknown tables and SQLite metadata. */
    private fun snapshot(db: SQLiteDatabase): List<String> = buildList {
        add("version=${db.version};schema=${integerPragma(db, "schema_version")};application=${integerPragma(db, "application_id")}")
        db.rawQuery("SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY name", null).use {
            while (it.moveToNext()) add("schema:${row(it)}")
        }
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        tables.forEach { table ->
            val quoted = "\"${table.replace("\"", "\"\"")}\""
            db.rawQuery("SELECT * FROM $quoted", null).use { cursor ->
                val rows = buildList { while (cursor.moveToNext()) add(row(cursor)) }.sorted()
                add("$table:$rows")
            }
        }
    }

    private fun row(cursor: Cursor): String = (0 until cursor.columnCount).joinToString("|") { column ->
        val type = cursor.getType(column)
        val value = when (type) {
            Cursor.FIELD_TYPE_NULL -> ""
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
            else -> cursor.getString(column)
        }
        "$type:${value.length}:$value"
    }
}
