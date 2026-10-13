package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** The 52 KB microWakeWord model is bundled; wake readiness never depends on a download. */
class WakeWordModelStore(private val context: Context) {
    suspend fun ensureReady(report: (String) -> Unit): File = withContext(Dispatchers.IO) {
        report("Preparing microWakeWord…")
        val root = File(context.filesDir, "voice-models")
        val destination = File(root, "microwakeword-v2").apply { mkdirs() }
        val model = File(destination, "hey_jarvis.tflite")
        val bytes = context.assets.open("microwakeword/hey_jarvis.tflite").use { it.readBytes() }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
            "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77") { "microWakeWord model integrity check failed" }
        if (!model.isFile || !model.readBytes().contentEquals(bytes)) model.writeBytes(bytes)
        // Remove only the two obsolete keyword models, preserving ASR and voices.
        listOf("hey-jarvis-kws-v1", "hey-jarvis-kws-v2").forEach { File(root, it).deleteRecursively() }
        destination
    }
}
