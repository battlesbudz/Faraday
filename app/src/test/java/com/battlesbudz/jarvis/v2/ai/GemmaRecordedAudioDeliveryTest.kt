package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.RecordedSpeechFixture
import com.google.ai.edge.litertlm.Content
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaRecordedAudioDeliveryTest {
    @Test fun instructionPrecedesCompleteHumanRecordingInNativeContent() {
        val recording = RecordedSpeechFixture.wav
        val contents = audioMessageContents(GemmaAudioInputPolicy.REQUEST, recording).contents
        assertEquals(2, contents.size)
        assertEquals(GemmaAudioInputPolicy.REQUEST, (contents[0] as Content.Text).text)
        val delivered = (contents[1] as Content.AudioBytes).bytes
        assertArrayEquals(recording, delivered)
        assertEquals("0b1785dba56f22af426ccb25d318f7e103558fd40e1c3ab064b455dba2afae12",
            RecordedSpeechFixture.sha256(delivered))
        // Inspect the known recording boundaries independently of WavEncoder.
        assertArrayEquals(recording.copyOfRange(44, 16044), delivered.copyOfRange(44, 16044))
        assertArrayEquals(recording.takeLast(16000).toByteArray(), delivered.takeLast(16000).toByteArray())
    }
}
