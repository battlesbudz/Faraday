package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.Content
import com.battlesbudz.jarvis.v2.voice.WavEncoder
import org.junit.Assert.*
import org.junit.Test

class AudioMessageInputTest {
    @Test fun gemmaFourInstructionPrecedesUnmodifiedRecordingInOneNativeMessage() {
        val wav = WavEncoder.pcm16Mono(ByteArray(3200) { (it % 127).toByte() })
        val prompt = "Current user message: What is nuclear fusion?"
        val message = audioMessageContents(prompt, wav)
        assertEquals(2, message.contents.size)
        assertArrayEquals(wav, (message.contents[1] as Content.AudioBytes).bytes)
        assertEquals(prompt, (message.contents[0] as Content.Text).text)
    }
    @Test(expected = IllegalArgumentException::class)
    fun emptyRecordingCannotBecomeTextOnlySubmission() {
        audioMessageContents("What is nuclear fusion?", byteArrayOf())
    }
}
