package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Installs the pinned Northern English Piper voice under the model-operation gate. */
class TtsModelStore(context: Context) {
    private val root = File(context.filesDir, "voice-models")

    fun isReady(): Boolean {
        val engine = TtsEngine.PIPER_NORTHERN
        val directory = File(root, engine.directory)
        return NorthernPiperSpec.files.all { (name, bytes) -> File(directory, name).isFile && File(directory, name).length() == bytes } &&
            File(directory, ".verified").takeIf { it.isFile }?.readText() == engine.archiveSha256
    }

    suspend fun ensureReady(engine: TtsEngine, status: (String) -> Unit): File = withContext(Dispatchers.IO) {
        RetiredVoiceFiles.remove(root)
        val directory = File(root, engine.directory)
        val verification = engine.archiveSha256
        fun ready(dir: File) = NorthernPiperSpec.files.all { (name, bytes) -> File(dir, name).isFile && File(dir, name).length() == bytes }
        if (ready(directory) && File(directory, ".verified").takeIf { it.isFile }?.readText() == verification)
            return@withContext directory
        root.mkdirs()
        val archive = File(root, "${engine.directory}.tar.bz2.part")
        val staging = File(root, "${engine.directory}.staging")
        archive.delete()
        staging.deleteRecursively()
        try {
            status("Downloading ${engine.label}…")
            val connection = (URL(engine.archiveUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            var nextUpdate = 0L
            try {
                check(connection.responseCode in 200..299) { "Voice download failed: HTTP ${connection.responseCode}" }
                connection.inputStream.use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= engine.archiveBytes) { "Voice archive size did not match." }
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (bytes >= nextUpdate) {
                            status("Downloading ${engine.label}… ${bytes / 1_000_000}/${engine.archiveBytes / 1_000_000} MB")
                            nextUpdate = bytes + 4_000_000
                        }
                    }
                    output.fd.sync()
                } }
            } finally { connection.disconnect() }
            check(bytes == engine.archiveBytes && hash.digest().joinToString("") { "%02x".format(it) } == engine.archiveSha256) {
                "Voice model checksum did not match. Please retry the download."
            }
            status("Installing ${engine.label}…")
            staging.mkdirs()
            TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                var unpacked = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextTarEntry ?: break
                    if (entry.name.trimEnd('/') == engine.directory) continue
                    check(entry.name.startsWith(engine.directory + "/") && !entry.isSymbolicLink && !entry.isLink) {
                        "Voice archive contained an unexpected entry."
                    }
                    val relative = entry.name.removePrefix(engine.directory + "/")
                    // Exclude archive demo recordings.
                    if (relative == "test_wavs" || relative.startsWith("test_wavs/")) continue
                    val target = File(staging, relative)
                    check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe voice archive path." }
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        check(entry.isFile) { "Unsupported voice archive entry." }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = tar.read(buffer)
                                if (count < 0) break
                                unpacked += count
                                check(unpacked <= 250_000_000) { "Voice archive expanded beyond its budget." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            check(ready(staging)) { "The voice archive is incomplete." }
            File(staging, ".verified").writeText(verification)
            directory.deleteRecursively()
            check(staging.renameTo(directory)) { "Could not finish installing the voice model." }
            directory
        } finally { archive.delete(); staging.deleteRecursively() }
    }
}
