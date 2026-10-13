package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CallEvidenceZipTest {
    @Before fun reset() { LiveCallAudioEvidence.clear() }
    @After fun cleanup() { LiveCallAudioEvidence.clear() }

    private fun export(): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        writeCallEvidenceZip(CallEvidenceSnapshot("Call report", LiveCallAudioEvidence.snapshot()), output)
        return ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
    }

    @Test fun defaultAndClearedEvidenceNeverAddsAudioToTheZip() {
        LiveCallAudioEvidence.begin("unrecorded")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2), 16000)
        assertEquals(setOf("report.txt"), export().keys)
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("recorded")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(3, 4), 16000)
        LiveCallAudioEvidence.clear()
        assertEquals(setOf("report.txt"), export().keys)
        assertFalse(LiveCallAudioEvidence.armed)
    }

    @Test fun optedInZipContainsExactRetainedSampleAndItsTruncationLimits() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("recorded")
        LiveCallAudioEvidence.record("microphone", ByteArray(800_000) { 1 }, 16000)
        val tail = byteArrayOf(2, 3, 4, 5)
        LiveCallAudioEvidence.record("microphone", tail, 16000)
        LiveCallAudioEvidence.finish()
        val files = export()
        assertEquals(setOf("report.txt", "audio-evidence.txt", "microphone.wav"), files.keys)
        assertEquals("Call report", files.getValue("report.txt").toString(Charsets.UTF_8))
        assertArrayEquals(tail, files.getValue("microphone.wav").drop(44).toByteArray())
        val report = files.getValue("audio-evidence.txt").toString(Charsets.UTF_8)
        assertTrue(report.contains("truncated=true"))
        assertTrue(report.contains("startFrame=400000"))
        assertTrue(report.contains("whole_call_recording=false"))
        assertTrue(report.contains("perStreamLimitBytes=800000"))
        assertTrue(report.contains("memoryLimitBytes=4000000"))
    }
}
