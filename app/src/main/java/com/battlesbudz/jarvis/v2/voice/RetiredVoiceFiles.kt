package com.battlesbudz.jarvis.v2.voice

import java.io.File

/** Exact installer-owned paths only; saved calls, measurements and Piper files are preserved. */
internal object RetiredVoiceFiles {
    private val directories = listOf("kokoro-en-v0_19", "kokoro-int8-en-v0_19",
        "sherpa-onnx-pocket-tts-int8-2026-01-26", "vits-piper-en_US-lessac-medium",
        "vits-piper-en_GB-alan-medium", "vits-piper-en_US-ryan-high", "vits-piper-en_GB-miro-high")
    fun remove(root: File) {
        for (directory in directories) for (suffix in listOf("", ".staging", ".tar.part", ".tar.bz2.part")) {
            val file = File(root, directory + suffix)
            check(!file.exists() || file.deleteRecursively()) { "Could not remove retired voice files: ${file.name}" }
        }
    }
}
