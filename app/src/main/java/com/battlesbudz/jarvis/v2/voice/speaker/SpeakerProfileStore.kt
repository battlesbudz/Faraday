package com.battlesbudz.jarvis.v2.voice.speaker

import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

data class EnrolledProfile(val embedding: FloatArray, val enrolledAtMs: Long, val phraseCount: Int)

/** One private, atomically replaced normalized template. */
class SpeakerProfileStore(private val directory: File) {
    private val profileFile get() = File(directory, "profile.bin")
    fun load(): EnrolledProfile? = runCatching {
        DataInputStream(profileFile.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC)
            val dim = input.readInt(); require(dim in 1..4096)
            val at = input.readLong(); val count = input.readInt(); require(count >= 3)
            val vector = FloatArray(dim) { input.readFloat().also { require(it.isFinite()) } }
            require(input.read() == -1)
            val norm = sqrt(vector.sumOf { it.toDouble() * it })
            require(norm > 0.99 && norm < 1.01)
            EnrolledProfile(vector, at, count)
        }
    }.getOrNull()

    fun save(embeddings: List<FloatArray>, nowMs: Long) {
        require(embeddings.size >= 3)
        val dim = embeddings.first().size
        require(dim > 0 && embeddings.all { it.size == dim })
        val mean = DoubleArray(dim)
        embeddings.forEach { vector ->
            require(vector.all(Float::isFinite))
            val n = sqrt(vector.sumOf { it.toDouble() * it }); require(n > 0)
            vector.indices.forEach { mean[it] += vector[it] / n }
        }
        val n = sqrt(mean.sumOf { it * it }); require(n > 0)
        val normalized = FloatArray(dim) { (mean[it] / n).toFloat() }
        directory.mkdirs()
        val tmp = File(directory, "profile.tmp")
        FileOutputStream(tmp).use { file ->
            DataOutputStream(file.buffered()).use { out ->
                out.writeInt(MAGIC); out.writeInt(dim); out.writeLong(nowMs); out.writeInt(embeddings.size)
                normalized.forEach(out::writeFloat); out.flush(); file.fd.sync()
            }
        }
        val dest = profileFile
        if (dest.exists()) check(dest.delete())
        check(tmp.renameTo(dest)) { "Cannot atomically install speaker profile" }
    }

    fun delete() { profileFile.delete(); File(directory, "profile.tmp").delete() }
    companion object { private const val MAGIC = 0x53475031 }
}
