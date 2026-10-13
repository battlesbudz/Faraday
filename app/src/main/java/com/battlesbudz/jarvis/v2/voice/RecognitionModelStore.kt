package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Pinned files, bounded downloads and atomic installs. No models enlarge the APK. */
class RecognitionModelStore(context: Context) {
    private val root = File(context.filesDir, "voice-models")
    suspend fun whisper(status: (String) -> Unit): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val dir = File(root, "sherpa-onnx-whisper-base.en")
            if (WHISPER.all { valid(File(dir, it.name), it) }) return@withLock dir
            root.mkdirs()
            val part = File(root, "whisper-base.part")
            val staging = File(root, "whisper-base.staging")
            staging.deleteRecursively(); staging.mkdirs()
            try {
                download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2", part,
                    208576005, "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542", status)
                status("Installing Whisper base.en…")
                TarArchiveInputStream(BZip2CompressorInputStream(part.inputStream().buffered())).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        val spec = WHISPER.firstOrNull { entry.name == "${dir.name}/${it.name}" } ?: continue
                        check(entry.isFile && !entry.isLink && !entry.isSymbolicLink && entry.size == spec.bytes)
                        val dest = File(staging, spec.name)
                        dest.outputStream().use { tar.copyTo(it) }
                        check(valid(dest, spec)) { "Whisper model integrity check failed" }
                    }
                }
                check(WHISPER.all { valid(File(staging, it.name), it) })
                dir.deleteRecursively()
                check(staging.renameTo(dir)) { "Cannot install Whisper" }
            } finally { part.delete(); staging.deleteRecursively() }
            dir
        }
    }
    private suspend fun download(url: String, target: File, bytes: Long, hash: String, status: (String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 30_000; readTimeout = 30_000 }
        try {
            check(connection.responseCode == 200) { "Speech model download failed: HTTP ${connection.responseCode}" }
            var copied = 0L
            var reportAt = 0L
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    copied += n; check(copied <= bytes) { "Speech model exceeds expected size" }
                    output.write(buffer, 0, n)
                    if (copied >= reportAt) { status("Preparing voice input: ${copied / 1_000_000}/${bytes / 1_000_000} MB"); reportAt = copied + 2_000_000 }
                }
                output.fd.sync()
            } }
            check(copied == bytes && digest(target) == hash) { "Speech model integrity check failed; retry download" }
        } finally { connection.disconnect() }
    }
    private fun valid(file: File, spec: Spec): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val stamp = "${file.length()}:${file.lastModified()}:${spec.hash}"
        if (verified[file.path] == stamp) return true
        if (digest(file) != spec.hash) return false
        verified[file.path] = stamp
        return true
    }
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private data class Spec(val name: String, val bytes: Long, val hash: String)
    companion object {
        private val lock = Mutex()
        private val verified = mutableMapOf<String, String>()
        private val WHISPER = listOf(
            Spec("base.en-encoder.int8.onnx", 29120534, "ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823"),
            Spec("base.en-decoder.int8.onnx", 130669978, "f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41"),
            Spec("base.en-tokens.txt", 835554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"))
    }
}
