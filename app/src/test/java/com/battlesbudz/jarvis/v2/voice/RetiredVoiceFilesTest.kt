package com.battlesbudz.jarvis.v2.voice

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RetiredVoiceFilesTest {
    @Test fun removesOnlyRetiredInstallerFilesAndIsRepeatable() {
        val root = Files.createTempDirectory("voice-retirement").toFile()
        try {
            val retired = listOf("kokoro-en-v0_19", "sherpa-onnx-pocket-tts-int8-2026-01-26")
            for (name in retired) {
                root.resolve(name).mkdirs()
                root.resolve("$name/filler-cache-v3").mkdirs()
                root.resolve("$name/filler-cache-v3/cue.wav").writeText("old cue")
                root.resolve("$name.staging").mkdirs()
                root.resolve("$name.tar.part").writeText("partial")
                root.resolve("$name.tar.bz2.part").writeText("partial")
            }
            root.resolve(TtsEngine.PIPER_NORTHERN.directory).mkdirs()
            val retained = root.resolve("${TtsEngine.PIPER_NORTHERN.directory}/.verified")
            retained.writeText("keep")
            root.resolve("call-history.json").writeText("keep")
            repeat(2) { RetiredVoiceFiles.remove(root) }
            for (name in retired) for (suffix in listOf("", ".staging", ".tar.part", ".tar.bz2.part"))
                assertFalse(root.resolve(name + suffix).exists())
            assertEquals("keep", retained.readText())
            assertEquals("keep", root.resolve("call-history.json").readText())
        } finally { root.deleteRecursively() }
    }
}
