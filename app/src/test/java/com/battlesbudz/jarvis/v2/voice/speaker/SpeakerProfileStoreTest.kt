package com.battlesbudz.jarvis.v2.voice.speaker

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SpeakerProfileStoreTest {
    @Test fun meanNormalizedRoundTripAndDelete() {
        val dir = Files.createTempDirectory("speaker").toFile(); val store = SpeakerProfileStore(dir)
        store.save(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 0f)), 42)
        val p = requireNotNull(store.load()); assertEquals(42, p.enrolledAtMs); assertEquals(3, p.phraseCount)
        assertEquals(0.8944f, p.embedding[0], .001f); assertEquals(0.4472f, p.embedding[1], .001f)
        FileCorruption.write(dir.toPath().resolve("profile.bin")); assertNull(store.load())
        store.delete(); assertNull(store.load()); dir.deleteRecursively()
    }
    private object FileCorruption { fun write(file: java.nio.file.Path) { Files.write(file, byteArrayOf(1, 2, 3)) } }
}
