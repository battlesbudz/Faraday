package com.battlesbudz.jarvis.v2.voice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class SpeechAudioTraceTest {
    @Test fun ringKeepsNewestSamplesAndTrimsUnplayedQueue() {
        val dir = Files.createTempDirectory("speech-trace").toFile()
        try {
            val file = java.io.File(dir, "reply.wav"); val trace = SpeechAudioTrace(file, "turn", 1)
            trace.append(shortArrayOf(1,2,3,4), 0, 4, 4)
            trace.append(shortArrayOf(9,5,6,9), 1, 2, 4)
            trace.finish(5)
            val bytes = file.readBytes(); assertEquals("RIFF", String(bytes.copyOfRange(0,4)))
            assertEquals(50, bytes.size)
            val pcm = ByteBuffer.wrap(bytes, 44, 6).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(3, pcm.short.toInt()); assertEquals(4, pcm.short.toInt()); assertEquals(5, pcm.short.toInt())
        } finally { dir.deleteRecursively() }
    }
    @Test fun noPlaybackDoesNotOverwriteLastReplyAndNewReplyReplacesIt() {
        val dir = Files.createTempDirectory("speech-trace").toFile()
        try {
            val file = java.io.File(dir, "reply.wav"); file.writeText("previous")
            SpeechAudioTrace(file, "empty").finish(0)
            assertEquals("previous", file.readText())
            val trace = SpeechAudioTrace(file, "next")
            trace.append(shortArrayOf(-32768,32767), 0, 2, 24000); trace.finish(2)
            val pcm = ByteBuffer.wrap(file.readBytes(),44,4).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(-32768, pcm.short.toInt()); assertEquals(32767, pcm.short.toInt())
        } finally { dir.deleteRecursively() }
    }
}
