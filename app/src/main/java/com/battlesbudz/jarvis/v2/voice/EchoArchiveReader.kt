package com.battlesbudz.jarvis.v2.voice

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Imports only our bounded evidence format; no archive entry is written to disk. */
internal object EchoArchiveReader {
    private val scenarios = setOf("jarvis_only", "user_only", "double_talk")
    private val limits = mapOf("microphone.wav" to 384044, "decoder-input.wav" to 384044,
        "piper-reference.wav" to 529244, "report.txt" to 200000, "timeline.csv" to 200000)

    fun read(input: InputStream): List<DuplexAudioEvidence.Result> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val parts = entry.name.split('/')
                require(parts.size == 2 && parts[0] in scenarios && parts[1] in limits && !entry.isDirectory) {
                    "Choose a Jarvis echo evidence ZIP."
                }
                require(files.size < 15 && entry.name !in files) { "Duplicate or excessive archive entries." }
                val limit = limits.getValue(parts[1])
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= limit) { "Echo archive entry exceeds its recording limit." }
                }
                val bytes = output.toByteArray()
                files[entry.name] = bytes
                zip.closeEntry()
            }
        }
        val present = files.keys.map { it.substringBefore('/') }.distinct()
        require(present.isNotEmpty()) { "The archive contains no echo recordings." }
        return present.map { scenario ->
            fun required(name: String) = requireNotNull(files["$scenario/$name"]) { "Missing $scenario/$name" }
            val mic = pcm(required("microphone.wav"), 16000)
            val decoder = files["$scenario/decoder-input.wav"]?.let { pcm(it, 16000) } ?: byteArrayOf()
            val reference = files["$scenario/piper-reference.wav"]?.let { pcm(it, 22050) } ?: byteArrayOf()
            DuplexAudioEvidence.Result(scenario, mic, reference, 22050, decoder,
                required("timeline.csv").toString(Charsets.UTF_8), required("report.txt").toString(Charsets.UTF_8))
        }
    }

    /** WavEncoder emits this exact 44-byte PCM16 mono header. Reject other formats. */
    private fun pcm(wav: ByteArray, rate: Int): ByteArray {
        require(wav.size >= 46) { "Empty or truncated WAV." }
        fun label(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(label(0) == "RIFF" && label(8) == "WAVE" && label(12) == "fmt " && label(36) == "data" &&
            b.getInt(4) == wav.size - 8 && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 &&
            b.getShort(22).toInt() == 1 && b.getInt(24) == rate && b.getInt(28) == rate * 2 &&
            b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == 16 && b.getInt(40) == wav.size - 44 &&
            (wav.size - 44) % 2 == 0 && wav.size - 44 <= rate * 2 * 12) { "Unsupported echo WAV format or length." }
        return wav.copyOfRange(44, wav.size)
    }
}
