package com.battlesbudz.jarvis.v2.memory

import org.json.JSONArray
import org.json.JSONObject

/** Shared strict codec for legacy migration and canonical per-record SQLite payloads. */
internal object MemorySnapshotCodec {
    fun decode(raw: String): MemoryStore.Read {
        val root = JSONObject(raw)
        if (root.optInt("schemaVersion", -1) !in 1..MemoryStore.SCHEMA_VERSION) return MemoryStore.Read(null, "Memory store schema is not supported.")
        if (!root.has("generation") || !root.has("memories") || !root.has("tombstones")) return MemoryStore.Read(null, "Memory store is incomplete.")
        val generation = root.getLong("generation").also { require(it >= 0) }
        val memories = root.getJSONArray("memories").map { decodeMemory(it as JSONObject) }
        val tombstones = root.getJSONArray("tombstones").map { o ->
            val j = o as JSONObject
            MemoryTombstone(j.requiredString("eventId", MemoryPolicy.MAX_EVENT_ID_CHARS), j.requiredString("payloadFingerprint", 64), j.getLong("erasedAtMs"))
        }
        val snapshot = MemorySnapshot(generation, memories, tombstones)
        return validate(snapshot)?.let { MemoryStore.Read(null, it) } ?: MemoryStore.Read(snapshot)
    }

    fun decodeMemory(j: JSONObject): MemoryRecord {
        val sourceJson = j.getJSONObject("source")
        val provenance = sourceJson.getJSONArray("provenance").map { item ->
            val p = item as JSONObject
            MemoryProvenance(p.requiredString("kind", 96), p.requiredString("id", 160), p.optString("label").takeIf { it.isNotBlank() }, p.optBoolean("restricted", false))
        }
        return MemoryRecord(
            id = j.requiredString("id", 80), content = j.requiredString("content", MemoryPolicy.MAX_CONTENT_CHARS),
            category = enumValue(j.requiredString("category", 32)), tier = enumValue(j.requiredString("tier", 32)), type = enumValue(j.requiredString("type", 32)),
            confidence = j.getInt("confidence"), source = MemorySource(sourceJson.requiredString("eventId", MemoryPolicy.MAX_EVENT_ID_CHARS), sourceJson.requiredString("eventSource", MemoryPolicy.MAX_EVENT_SOURCE_CHARS), sourceJson.getLong("createdAtMs"), enumValue(sourceJson.requiredString("sensitivity", 32)), provenance),
            reviewStatus = enumValue(j.requiredString("reviewStatus", 32)), createdAtMs = j.getLong("createdAtMs"), updatedAtMs = j.getLong("updatedAtMs"), revision = j.getLong("revision"),
            expiresAtMs = j.optLongOrNull("expiresAtMs"), correctsMemoryId = j.optString("correctsMemoryId").takeIf { it.isNotBlank() },
            wikiAssignment = j.optJSONObject("wikiAssignment")?.let { a -> MemoryWikiAssignment(enumValue(a.requiredString("category", 32)), a.requiredString("topic", 120)) },
            payloadFingerprint = j.optString("payloadFingerprint").takeIf { it.isNotBlank() },
        )
    }

    fun encode(snapshot: MemorySnapshot): String = JSONObject().apply {
        put("schemaVersion", MemoryStore.SCHEMA_VERSION); put("generation", snapshot.generation)
        put("memories", JSONArray(snapshot.memories.map(::encodeMemory))); put("tombstones", JSONArray(snapshot.tombstones.map { t -> JSONObject().put("eventId", t.eventId).put("payloadFingerprint", t.payloadFingerprint).put("erasedAtMs", t.erasedAtMs) }))
    }.toString()

    fun encodeMemory(m: MemoryRecord): JSONObject = JSONObject().apply {
        put("id", m.id); put("content", m.content); put("category", m.category.name); put("tier", m.tier.name); put("type", m.type.name); put("confidence", m.confidence); put("reviewStatus", m.reviewStatus.name)
        put("createdAtMs", m.createdAtMs); put("updatedAtMs", m.updatedAtMs); put("revision", m.revision); put("expiresAtMs", m.expiresAtMs); put("correctsMemoryId", m.correctsMemoryId); put("wikiAssignment", m.wikiAssignment?.let { a -> JSONObject().put("category", a.category.name).put("topic", a.topic) }); put("payloadFingerprint", m.payloadFingerprint)
        put("source", JSONObject().put("eventId", m.source.eventId).put("eventSource", m.source.eventSource).put("createdAtMs", m.source.createdAtMs).put("sensitivity", m.source.sensitivity.name).put("provenance", JSONArray(m.source.provenance.map { p -> JSONObject().put("kind", p.kind).put("id", p.id).put("label", p.label).put("restricted", p.restricted) })))
    }

    fun validate(snapshot: MemorySnapshot): String? {
        if (snapshot.generation < 0 || snapshot.memories.size > MemoryPolicy.MAX_MEMORIES || snapshot.tombstones.size > MemoryPolicy.MAX_TOMBSTONES || snapshot.memories.size + snapshot.tombstones.size > MemoryPolicy.MAX_TOMBSTONES) return "Memory store capacity exceeded."
        if (snapshot.memories.map { it.id }.toSet().size != snapshot.memories.size || snapshot.memories.any { !MemoryPolicy.isGeneratedMemoryId(it.id) }) return "Memory store has invalid memory ids."
        if ((snapshot.memories.map { it.source.eventId } + snapshot.tombstones.map { it.eventId }).toSet().size != snapshot.memories.size + snapshot.tombstones.size) return "Memory store has duplicate source events."
        if (snapshot.tombstones.any { !MemoryPolicy.isOpaqueEventKey(it.eventId) || !MemoryPolicy.isFingerprint(it.payloadFingerprint) || it.erasedAtMs <= 0 }) return "Memory store contains invalid tombstones."
        val ids = snapshot.memories.map { it.id }.toSet()
        for (m in snapshot.memories) {
            if (!MemoryPolicy.validatePersisted(m) || (m.payloadFingerprint != null && !MemoryPolicy.isFingerprint(m.payloadFingerprint)) || (m.correctsMemoryId != null && (!MemoryPolicy.isGeneratedMemoryId(m.correctsMemoryId) || m.correctsMemoryId !in ids || m.correctsMemoryId == m.id))) return "Memory store contains invalid records."
        }
        // Correction references must form finite lineages rather than cycles.
        for (m in snapshot.memories) {
            val seen = mutableSetOf<String>(); var current: MemoryRecord? = m
            while (current?.correctsMemoryId != null) {
                if (!seen.add(current.id)) return "Memory store contains cyclic corrections."
                current = snapshot.memories.firstOrNull { it.id == current.correctsMemoryId } ?: return "Memory store contains invalid correction references."
            }
        }
        return null
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("Unknown enum")
    private fun JSONObject.requiredString(key: String, max: Int): String = getString(key).also { require(it.isNotBlank() && it.length <= max) }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun <T> JSONArray.map(block: (Any) -> T): List<T> = (0 until length()).map { block(get(it)) }

}
