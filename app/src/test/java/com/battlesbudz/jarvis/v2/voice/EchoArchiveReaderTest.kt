package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EchoArchiveReaderTest {
    private fun archive(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    @Test fun roundTripPreservesAllScenarioEvidence() {
        val originals = listOf("jarvis_only", "user_only", "double_talk").map {
            DuplexAudioEvidence.Result(it, byteArrayOf(1, 0), byteArrayOf(2, 0), 22050,
                byteArrayOf(3, 0), "timeline", "report")
        }
        val out = ByteArrayOutputStream()
        DuplexAudioEvidence.writeArchive(out, originals)
        val loaded = EchoArchiveReader.read(out.toByteArray().inputStream())
        assertEquals(originals.map { it.scenario }, loaded.map { it.scenario })
        loaded.zip(originals).forEach { (actual, expected) ->
            assertArrayEquals(expected.microphone, actual.microphone)
            assertArrayEquals(expected.reference, actual.reference)
            assertArrayEquals(expected.decoder, actual.decoder)
            assertEquals(expected.report, actual.report)
            assertEquals(expected.timeline, actual.timeline)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() {
        EchoArchiveReader.read(archive(mapOf("../report.txt" to byteArrayOf())).inputStream())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedEntry() {
        EchoArchiveReader.read(archive(mapOf("user_only/report.txt" to ByteArray(200001))).inputStream())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedWave() {
        EchoArchiveReader.read(archive(mapOf("user_only/microphone.wav" to ByteArray(46))).inputStream())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyArchive() {
        EchoArchiveReader.read(archive(emptyMap()).inputStream())
    }
}
