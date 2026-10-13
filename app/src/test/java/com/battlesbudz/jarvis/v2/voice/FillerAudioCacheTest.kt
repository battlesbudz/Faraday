package com.battlesbudz.jarvis.v2.voice
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class FillerAudioCacheTest {
    @Test fun survivesNewCacheInstanceAndKeepsVoicesSeparate() {
        val dir = Files.createTempDirectory("fillers").toFile()
        try {
            val audio = SpeechAudio("Um.",24000,ShortArray(4800) { if (it % 2 == 0) 6000 else -6000 },123)
            FillerAudioCache(dir).write("voice10:Um",audio)
            val restored = FillerAudioCache(dir).read("voice10:Um","Um.")!!
            assertArrayEquals(audio.pcm,restored.pcm); assertEquals(24000,restored.sampleRate)
            assertNull(FillerAudioCache(dir).read("voice11:Um","Um."))
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptFileIsCacheMiss() {
        val dir = Files.createTempDirectory("fillers").toFile()
        try {
            val cache = FillerAudioCache(dir)
            cache.write("test",SpeechAudio("Um.",24000,shortArrayOf(1),0))
            dir.listFiles()!!.single().writeBytes(byteArrayOf(1,2,3))
            assertNull(cache.read("test","Um."))
        } finally { dir.deleteRecursively() }
    }
}
