package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One atomic metrics-only record per attempt. Capacity rejects new data, never silently evicts history. */
class PipelineBenchmarkJournal(
    private val directory: File,
    private val legacy: PipelineBenchmarkArchive? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 128L * 1024 * 1024,
    private val maxRecords: Int = 100_000
) {
    private val retained = linkedMapOf<String, PipelineBenchmarkTurn>()
    private val encoded = linkedMapOf<String, String>()
    private val persisted = linkedMapOf<String, String>()
    private var migrationReady = true
    private var encodedBytes = 0L
    var status: String? = null; private set
    var expiredRecords: Int = 0; private set
    init {
        require(maxBytes > 0 && maxRecords > 0)
        if (directory.exists()) {
            directory.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    require(file.length() <= MAX_RECORD_BYTES)
                    val root = JSONObject(file.readText())
                    require(root.getInt("schemaVersion") == 2)
                    val turn = PipelineBenchmarkTurn.read(root.getJSONObject("turn"))
                    if (turn.capturedAtEpochMs < cutoff()) { check(file.delete()); expiredRecords++ }
                    else { retained[turn.turnId] = turn; encoded[turn.turnId] = encode(turn) }
                } catch (_: Exception) { status = "Some benchmark records could not be read. Files remain available for diagnosis; exports may be incomplete." }
            }
        }
        encodedBytes = encoded.values.sumOf { it.toByteArray().size.toLong() }
        persisted.putAll(encoded)
        // The v1 file stays intact as migration evidence. Existing durable records take precedence.
        val previous = if (File(directory, "migration.complete").exists()) null else legacy?.read()
        previous?.error?.let { status = it }
        previous?.samples?.forEach {
            if (it.turnId !in retained && it.capturedAtEpochMs >= cutoff() && !append(it)) migrationReady = false
        }
        if (previous?.error != null) migrationReady = false
        if (expiredRecords > 0 && status == null) status = "$expiredRecords benchmark attempts expired under the visible 90-day retention policy."
    }
    private fun cutoff() = now() - RETENTION_MS
    private fun encode(turn: PipelineBenchmarkTurn) = JSONObject().put("schemaVersion", 2)
        .put("privacy", "metrics_only_no_prompt_transcript_reference_or_pcm")
        .put("turn", turn.copy(accuracy = turn.accuracy?.copy(reference = null, hypothesis = null)).json(false).withoutDerivedMetricStatus()).toString()
    private fun file(id: String): File = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    fun samples(): List<PipelineBenchmarkTurn> = retained.values.sortedBy { it.capturedAtEpochMs }
    fun prepared(): Map<String, String> = encoded.toMap()
    fun clear() { retained.clear(); encoded.clear(); encodedBytes = 0L; migrationReady = true; status = null; expiredRecords = 0 }
    fun append(turn: PipelineBenchmarkTurn): Boolean {
        prune()
        val safe = turn.copy(accuracy = turn.accuracy?.copy(reference = null, hypothesis = null))
        val json = encode(safe)
        if (turn.capturedAtEpochMs < cutoff()) { status = "An expired benchmark attempt was not stored (90-day retention)."; return false }
        val newBytes = json.toByteArray().size
        val total = encodedBytes - (encoded[turn.turnId]?.toByteArray()?.size ?: 0) + newBytes
        if (newBytes > MAX_RECORD_BYTES || total > maxBytes || (turn.turnId !in retained && retained.size >= maxRecords)) {
            status = "Benchmark storage capacity reached. This attempt was not retained. Export and reset measurements to make room; existing attempts were preserved."
            return false
        }
        retained[turn.turnId] = safe; encoded[turn.turnId] = json; encodedBytes = total
        return true
    }
    fun update(id: String, transform: (PipelineBenchmarkTurn) -> PipelineBenchmarkTurn): Boolean {
        val previous = retained[id] ?: return false
        val next = transform(previous); require(next.turnId == id)
        return append(next)
    }
    fun prune() {
        val expired = retained.values.filter { it.capturedAtEpochMs < cutoff() }.map { it.turnId }
        expired.forEach { retained.remove(it); encoded.remove(it)?.let { json -> encodedBytes -= json.toByteArray().size } }
        if (expired.isNotEmpty()) { expiredRecords += expired.size; status = "$expiredRecords benchmark attempts expired under the visible 90-day retention policy." }
    }
    /** Caller serializes writes. Snapshot identity prevents queued writes from resurrecting deleted records. */
    fun writePrepared(snapshot: Map<String, String>) {
        check(directory.isDirectory || directory.mkdirs())
        snapshot.forEach { (id, json) -> if (persisted[id] != json) {
            val target = file(id); val temp = File(directory, target.name + ".pending")
            try {
                FileOutputStream(temp).use { it.write(json.toByteArray()); it.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                // Track each completed atomic replacement even if a later record fails.
                persisted[id] = json
            } finally { temp.delete() }
        } }
        (persisted.keys - snapshot.keys).toList().forEach { id ->
            check(!file(id).exists() || file(id).delete())
            persisted.remove(id)
        }
        val marker = File(directory, "migration.complete")
        if (migrationReady && !marker.exists()) FileOutputStream(marker).use { it.write("v1 imported; original preserved".toByteArray()); it.fd.sync() }
    }
    companion object {
        const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        const val MAX_RECORD_BYTES = 256 * 1024
    }
}
