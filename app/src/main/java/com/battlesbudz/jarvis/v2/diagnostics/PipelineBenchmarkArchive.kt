package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded, text-free archive. Atomic replacement preserves the last complete snapshot on interruption. */
class PipelineBenchmarkArchive(
    private val file: File,
    val maxRecords: Int = 500,
    val maxBytes: Int = 2 * 1024 * 1024
) {
    init { require(maxRecords in 1..500); require(maxBytes in 1024..2 * 1024 * 1024) }
    data class Loaded(val samples: List<PipelineBenchmarkTurn>, val error: String? = null)

    internal data class Prepared(val sample: PipelineBenchmarkTurn, val json: String, val bytes: Int)
    internal fun prepare(sample: PipelineBenchmarkTurn): Prepared {
        val safe = sample.copy(accuracy = sample.accuracy?.copy(reference = null, hypothesis = null))
        val json = safe.json(includeText = false).withoutDerivedMetricStatus().toString()
        return Prepared(safe, json, json.toByteArray(Charsets.UTF_8).size)
    }
    internal val envelopeBytes: Int get() = PREFIX.toByteArray(Charsets.UTF_8).size + SUFFIX.length

    fun read(): Loaded {
        if (!file.exists()) return Loaded(emptyList())
        return try {
            require(file.length() <= maxBytes) { "Benchmark archive exceeds its size limit" }
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == 1) { "Unsupported benchmark archive version" }
            val array = root.getJSONArray("samples")
            require(array.length() <= maxRecords) { "Benchmark archive exceeds its record limit" }
            Loaded(bound((0 until array.length()).map { PipelineBenchmarkTurn.read(array.getJSONObject(it)) }))
        } catch (_: Exception) {
            // Keep bounded corrupt evidence separate; never echo archive contents into diagnostics.
            if (file.length() <= maxBytes) runCatching { Files.move(file.toPath(), File(file.parentFile, "${file.name}.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Loaded(emptyList(), "Stored benchmark records could not be read. Recording new samples; the bounded archive was retained for diagnosis.")
        }
    }

    fun bound(samples: List<PipelineBenchmarkTurn>): List<PipelineBenchmarkTurn> {
        val buffer = PipelineBenchmarkBuffer(this)
        samples.takeLast(maxRecords).forEach { buffer.append(it) }
        return buffer.samples()
    }

    fun write(samples: List<PipelineBenchmarkTurn>) {
        writePrepared(samples.map(::prepare))
    }

    internal fun writePrepared(samples: List<Prepared>) {
        require(samples.size <= maxRecords && encodedBytes(samples) <= maxBytes) { "Caller must bound the archive before writing" }
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create benchmark storage" }
        val temp = File(parent, "${file.name}.pending")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write((PREFIX + samples.joinToString(",") { it.json } + SUFFIX).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun encodedBytes(samples: List<Prepared>): Long = envelopeBytes.toLong() + samples.sumOf { it.bytes.toLong() } + (samples.size - 1).coerceAtLeast(0)
    companion object {
        private const val PREFIX = "{\"schemaVersion\":1,\"privacy\":\"metrics_only_no_prompt_transcript_reference_or_pcm\",\"samples\":["
        private const val SUFFIX = "]}"
    }
}

/** Exact UTF-8 byte accounting. Only the changed sample is serialized during append or annotation. */
internal class PipelineBenchmarkBuffer(private val archive: PipelineBenchmarkArchive, initial: List<PipelineBenchmarkTurn> = emptyList()) {
    private val retained = linkedMapOf<String, PipelineBenchmarkArchive.Prepared>()
    private var sampleBytes = 0L
    val encodedBytes: Long get() = archive.envelopeBytes.toLong() + sampleBytes + (retained.size - 1).coerceAtLeast(0)
    init { initial.takeLast(archive.maxRecords).forEach(::append) }

    fun samples(): List<PipelineBenchmarkTurn> = retained.values.map { it.sample }
    fun prepared(): List<PipelineBenchmarkArchive.Prepared> = retained.values.toList()
    fun contains(id: String): Boolean = retained.containsKey(id)
    fun clear() { retained.clear(); sampleBytes = 0 }

    fun append(turn: PipelineBenchmarkTurn): Boolean {
        val item = archive.prepare(turn)
        // An oversized single record must not erase the valid history already held.
        if (archive.envelopeBytes.toLong() + item.bytes > archive.maxBytes) return false
        retained.remove(turn.turnId)?.let { sampleBytes -= it.bytes }
        retained[turn.turnId] = item
        sampleBytes += item.bytes
        evict()
        return retained.containsKey(turn.turnId)
    }

    fun update(id: String, transform: (PipelineBenchmarkTurn) -> PipelineBenchmarkTurn): Boolean {
        val previous = retained[id] ?: return false
        val item = archive.prepare(transform(previous.sample))
        require(item.sample.turnId == id) { "Annotations cannot change benchmark identity" }
        if (archive.envelopeBytes.toLong() + item.bytes > archive.maxBytes) return false
        retained[id] = item
        sampleBytes += item.bytes - previous.bytes
        evict()
        return retained.containsKey(id)
    }

    private fun evict() {
        while (retained.size > archive.maxRecords || encodedBytes > archive.maxBytes) {
            val oldest = retained.keys.first()
            sampleBytes -= checkNotNull(retained.remove(oldest)).bytes
        }
    }
}
