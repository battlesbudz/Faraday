package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
class LiveCallAudioEvidenceTest {
    @Before fun reset() = LiveCallAudioEvidence.clear()
    @After fun clear() = LiveCallAudioEvidence.clear()
    @Test fun defaultCallsDoNotRecordAndArmingIsOneCallOnly() {
        LiveCallAudioEvidence.clear(); LiveCallAudioEvidence.begin("call-a")
        LiveCallAudioEvidence.record("microphone", ByteArray(3200), 16000)
        assertNull(LiveCallAudioEvidence.snapshot())
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("call-b")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2, 3, 4), 16000, 42)
        LiveCallAudioEvidence.finish()
        LiveCallAudioEvidence.record("microphone", ByteArray(3200), 16000)
        val result = LiveCallAudioEvidence.snapshot()!!
        assertEquals("call-b", result.call)
        assertEquals(48, result.files.getValue("microphone.wav").size)
        assertTrue(result.report.contains("atMs=42"))
        LiveCallAudioEvidence.begin("call-c")
        assertNull(LiveCallAudioEvidence.snapshot())
    }
    @Test fun rollingAudioAndEventsStayBoundedWithHonestOffsets() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("call")
        repeat(300) { LiveCallAudioEvidence.record("microphone", ByteArray(3200), 16000, it.toLong()) }
        repeat(2000) { LiveCallAudioEvidence.event("test=$it") }
        val result = LiveCallAudioEvidence.snapshot()!!
        assertEquals(800044, result.files.getValue("microphone.wav").size)
        assertTrue(result.report.contains("startFrame=80000"))
        assertTrue(result.report.contains("truncated=true"))
        assertFalse(result.report.contains("test=0\n"))
    }
    @Test fun sourceReferencesPreserveSignedPcmAndSnapshotIsImmutable() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("call")
        val stream = LiveCallAudioEvidence.newStream("answer")
        LiveCallAudioEvidence.recordOutput(stream, shortArrayOf(-1, 32767, -32768), 0, 3, 22050)
        val snapshot = LiveCallAudioEvidence.snapshot()!!
        assertArrayEquals(byteArrayOf(-1, -1, -1, 127, 0, -128), snapshot.files.getValue("$stream.wav").drop(44).toByteArray())
        LiveCallAudioEvidence.clear()
        assertEquals(50, snapshot.files.getValue("$stream.wav").size)
        assertTrue(snapshot.report.contains("output=written_reference_not_acoustic_recording"))
    }

    @Test fun clearErasesAudioEventsAndDisarmsTheNextCall() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("private-call")
        val stream = LiveCallAudioEvidence.newStream("reply")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2), 16000)
        LiveCallAudioEvidence.record(stream, byteArrayOf(3, 4), 22050)
        LiveCallAudioEvidence.event("private-event")
        assertNotNull(LiveCallAudioEvidence.snapshot())
        LiveCallAudioEvidence.clear()
        assertFalse(LiveCallAudioEvidence.active)
        assertFalse(LiveCallAudioEvidence.armed)
        assertNull(LiveCallAudioEvidence.snapshot())
        assertEquals("", LiveCallAudioEvidence.newStream("reply"))
        LiveCallAudioEvidence.record(stream, byteArrayOf(5, 6), 22050)
        LiveCallAudioEvidence.begin("next-call")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(7, 8), 16000)
        assertNull(LiveCallAudioEvidence.snapshot())
    }
    @Test fun armingAgainClearsPreviousEvidenceWithoutRecordingBeforeTheNextCall() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("first-call")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2), 16000)
        LiveCallAudioEvidence.finish()
        LiveCallAudioEvidence.arm()
        assertTrue(LiveCallAudioEvidence.armed)
        assertFalse(LiveCallAudioEvidence.active)
        assertNull(LiveCallAudioEvidence.snapshot())
        LiveCallAudioEvidence.begin("second-call")
        assertFalse(LiveCallAudioEvidence.armed)
        assertTrue(LiveCallAudioEvidence.active)
        assertTrue(LiveCallAudioEvidence.snapshot()!!.files.isEmpty())
    }
    @Test fun exportDescribesBoundedAudioAndReferenceOutputRatherThanAWholeCallRecording() {
        LiveCallAudioEvidence.arm(); LiveCallAudioEvidence.begin("call")
        repeat(6) {
            val stream = LiveCallAudioEvidence.newStream("answer")
            LiveCallAudioEvidence.record(stream, ByteArray(800_000), 22050)
        }
        val result = LiveCallAudioEvidence.snapshot()!!
        assertTrue(result.files.values.sumOf { it.size - 44 } <= 4_000_000)
        assertTrue(result.report.contains("truncated=true"))
        assertTrue(result.report.contains("memoryLimitBytes=4000000"))
        assertTrue(result.report.contains("perStreamLimitBytes=800000"))
        assertTrue(result.report.contains("retention=rolling_tail"))
        assertTrue(result.report.contains("whole_call_recording=false"))
        assertTrue(result.report.contains("output=written_reference_not_acoustic_recording"))
        assertTrue(result.report.contains("tone_cues=not_captured"))
    }
}
