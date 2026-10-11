package com.battlesbudz.jarvis.v2.voice.whisper

import android.content.Context
import com.battlesbudz.jarvis.v2.voice.RecognitionModelStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pinned Qualcomm 8 Gen 3 precompiled graphs, never included in source or APK. */
internal class WhisperQnnModelStore(private val context: Context) {
    suspend fun prepare(status: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val cpu = RecognitionModelStore(context).whisper(status)
        if (!WhisperQnnRuntime.supportedDevice()) {
            status("Snapdragon 8 Gen 3 NPU unavailable on this device; Whisper CPU is ready.")
            return@withContext cpu
        }
        try { lock.withLock {
            WhisperQnnRuntime.prepare(context)
            val root = File(context.filesDir, "voice-models")
            val directory = File(root, DIRECTORY)
            if (models.all { valid(File(directory, it.name), it) }) return@withLock directory
            val staging = File(root, "$DIRECTORY.staging")
            val archive = File(root, "$DIRECTORY.zip.part")
            staging.deleteRecursively(); check(staging.mkdirs())
            try {
                download(ARCHIVE_URL, archive, 180668860, "b4b82da6e8703ad4d284324676ee4e0ed77ebbd5a26cb9cd79baa2531e41324b", status)
                ZipFile(archive).use { zip ->
                    models.filter { it.name != "multilingual.tiktoken" }.forEach { spec ->
                        currentCoroutineContext().ensureActive()
                        val entry = zip.getEntry("whisper_base-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3/${spec.name}")
                        check(entry != null && !entry.isDirectory && entry.size == spec.bytes) { "Unexpected QNN model archive" }
                        val target = File(staging, spec.name)
                        zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(65536); var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= spec.bytes); output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        } }
                        check(valid(target, spec)) { "QNN model integrity failure" }
                    }
                }
                val tokenizer = models.last()
                download(TOKENIZER_URL, File(staging, tokenizer.name), tokenizer.bytes, tokenizer.sha, status)
                currentCoroutineContext().ensureActive(); verifyInstalled(staging)
                // Only this installer-owned incomplete directory, never old Moonshine/user files.
                directory.deleteRecursively()
                check(staging.renameTo(directory)) { "Cannot install QNN Whisper" }
                directory
            } finally { archive.delete(); staging.deleteRecursively() }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            status("Whisper NPU setup unavailable; using verified Whisper CPU fallback.")
            cpu
        }
    }
    private suspend fun download(url: String, file: File, bytes: Long, sha: String, status: (String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 30000; readTimeout = 30000 }
        try {
            check(connection.responseCode == 200) { "NPU model download failed: HTTP ${connection.responseCode}" }
            var count = 0L; var reportAt = 0L
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    count += n; check(count <= bytes) { "NPU download exceeds pinned size" }
                    output.write(buffer, 0, n)
                    if (count >= reportAt) { status("Preparing Whisper NPU: ${count / 1_000_000}/${bytes / 1_000_000} MB"); reportAt = count + 2_000_000 }
                }
                output.fd.sync()
            } }
            check(count == bytes && WhisperQnnRuntime.digest(file) == sha) { "NPU model download integrity failure" }
        } finally { connection.disconnect() }
    }
    companion object {
        const val DIRECTORY = "whisper-base-qnn-v064-sm8650"
        const val ARCHIVE_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_base/releases/v0.64.0/whisper_base-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip"
        const val TOKENIZER_URL = "https://raw.githubusercontent.com/openai/whisper/839639a223b92ad61851baae9ad8a695ccb41ce5/whisper/assets/multilingual.tiktoken"
        private val lock = Mutex()
        private data class Model(val name: String, val bytes: Long, val sha: String)
        private val models = listOf(
            Model("encoder.onnx", 1542, "6a35006a70c5731f84e6c921484e9ffdadc99b5088d2867a2af8ee86d455b968"),
            Model("encoder.bin", 49317256, "2d51e930c18a31642cde4eaeaf5e37a7ad0f98299c440804765fe5aede9fad70"),
            Model("decoder.onnx", 4469, "f8f5620b3e5f15f3e752c1b816f804ae85412adec8ebf4bfbf18c77f8192a753"),
            Model("decoder.bin", 152158512, "786ddc0fbbbb406b0d8ef6e4dfba7f52cabd25e52c8ff27bbe9669e598e702ac"),
            Model("metadata.json", 14585, "35d8a35b5c9f6cd766223fc6ef400a3703716985f48f2a4bea66642dc71db229"),
            Model("multilingual.tiktoken", 816730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
        )
        internal fun verifyInstalled(directory: File) { check(models.all { valid(File(directory, it.name), it) }) { "QNN Whisper model integrity failure" } }
        private fun valid(file: File, model: Model): Boolean = file.isFile && file.length() == model.bytes && WhisperQnnRuntime.digest(file) == model.sha
    }
}
