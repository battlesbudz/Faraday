package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DuplexAudioEvidenceTest {
    @Test fun boundsCaptureAndPreservesSignedPcmWithoutMutatingInput() {
        val evidence = DuplexAudioEvidence(maxSamples = 3)
        val pcm = byteArrayOf(-1, -1, 0, -128, 1, 0, 2, 0)
        evidence.capture(pcm, pcm.size, 100, 4, 90)
        assertArrayEquals(pcm.take(6).toByteArray(), evidence.pcm())
        assertTrue(evidence.truncated)
        assertTrue(evidence.csv().contains("capture_read,100,0,3,4,90"))
        pcm[0] = 0
        assertEquals((-1).toByte(), evidence.pcm()[0])
    }
    @Test fun retainsIndependentHardwareAndObservationTimesWithUnavailableTimestampExplicit() {
        val evidence = DuplexAudioEvidence()
        evidence.capture(byteArrayOf(1, 0), 2, 1000, null, null)
        evidence.playback(1200, 440, 400, 1100)
        assertTrue(evidence.csv().contains("capture_read,1000,0,1,,"))
        assertTrue(evidence.csv().contains("playback_head,1200,440,440,400,1100"))
        assertFalse(evidence.truncated)
    }
    @Test fun archiveKeepsScenarioStreamsAndRatesSeparate() {
        val output = ByteArrayOutputStream()
        DuplexAudioEvidence.writeArchive(output, listOf(
            DuplexAudioEvidence.Result("jarvis_only", byteArrayOf(1, 0), byteArrayOf(2, 0), 22050,
                byteArrayOf(3, 0), "timeline", "report")))
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        assertEquals(5, entries.size)
        assertArrayEquals(byteArrayOf(1, 0), entries.getValue("jarvis_only/microphone.wav").drop(44).toByteArray())
        assertArrayEquals(byteArrayOf(2, 0), entries.getValue("jarvis_only/piper-reference.wav").drop(44).toByteArray())
        assertEquals(22050, java.nio.ByteBuffer.wrap(entries.getValue("jarvis_only/piper-reference.wav"), 24, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsArchiveTraversal() {
        DuplexAudioEvidence.writeArchive(ByteArrayOutputStream(), listOf(
            DuplexAudioEvidence.Result("../escape", byteArrayOf(), byteArrayOf(), 16000, byteArrayOf(), "", "")))
    }
    @Test fun boundsTimestampRows() {
        val evidence = DuplexAudioEvidence()
        repeat(5000) { evidence.playback(it.toLong(), it.toLong(), null, null) }
        assertEquals(1202, evidence.csv().split('\n').size)
        assertTrue(evidence.truncated)
    }
}
