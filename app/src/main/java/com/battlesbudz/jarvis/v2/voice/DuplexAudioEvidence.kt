package com.battlesbudz.jarvis.v2.voice

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, opt-in PCM evidence. Timestamps share CLOCK_MONOTONIC; no disk I/O on capture. */
class DuplexAudioEvidence(private val maxSamples: Int = 192_000) {
    private val microphone = ByteArrayOutputStream()
    private val timeline = StringBuilder("kind,observedNs,pcmStartFrame,pcmEndFrame,hardwareFrame,hardwareNs\n")
    private var rows = 0
    var truncated = false
        private set

    init { require(maxSamples in 1..192_000) }

    @Synchronized fun capture(pcm: ByteArray, count: Int, atNs: Long, hardwareFrame: Long?, hardwareNs: Long?) {
        require(count in 0..pcm.size && count % 2 == 0)
        val start = microphone.size() / 2
        val accepted = minOf(count, maxSamples * 2 - microphone.size())
        microphone.write(pcm, 0, accepted)
        if (accepted < count) truncated = true
        if (accepted > 0) row("capture_read", atNs, start.toLong(), (start + accepted / 2).toLong(), hardwareFrame, hardwareNs)
    }

    @Synchronized fun playback(atNs: Long, head: Long, hardwareFrame: Long?, hardwareNs: Long?) {
        row("playback_head", atNs, head, head, hardwareFrame, hardwareNs)
    }

    private fun row(kind: String, at: Long, start: Long, end: Long, hw: Long?, hwNs: Long?) {
        if (rows++ < 1200) timeline.append("$kind,$at,$start,$end,${hw ?: ""},${hwNs ?: ""}\n")
        else truncated = true
    }

    @Synchronized fun pcm(): ByteArray = microphone.toByteArray()
    @Synchronized fun csv(): String = timeline.toString()

    data class Result(val scenario: String, val microphone: ByteArray, val reference: ByteArray,
                      val referenceRate: Int, val decoder: ByteArray, val timeline: String, val report: String)

    companion object {
        /** Called only following the user's explicit Save action. */
        fun writeArchive(output: OutputStream, results: List<Result>) {
            require(results.isNotEmpty() && results.size <= 3)
            require(results.map { it.scenario }.distinct().size == results.size)
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                results.forEach { result ->
                    require(result.scenario in setOf("jarvis_only", "user_only", "double_talk"))
                    val prefix = result.scenario + "/"
                    entry(prefix + "report.txt", result.report.toByteArray())
                    entry(prefix + "timeline.csv", result.timeline.toByteArray())
                    entry(prefix + "microphone.wav", WavEncoder.pcm16Mono(result.microphone))
                    if (result.reference.isNotEmpty()) entry(prefix + "piper-reference.wav", WavEncoder.pcm16Mono(result.reference, result.referenceRate))
                    if (result.decoder.isNotEmpty()) entry(prefix + "decoder-input.wav", WavEncoder.pcm16Mono(result.decoder))
                }
            }
        }
    }
}
