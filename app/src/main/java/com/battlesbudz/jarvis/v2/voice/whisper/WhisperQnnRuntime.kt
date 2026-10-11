package com.battlesbudz.jarvis.v2.voice.whisper

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** App-packaged vendor code only; no executable download or shared ORT replacement. */
internal object WhisperQnnRuntime {
    const val DIRECTORY = "whisper-qnn-runtime-2.50-v75"
    private data class Library(val name: String, val bytes: Long, val sha: String)
    private val files = listOf(
        Library("libonnxruntime_providers_qnn.so", 4281832, "21556616b76a82a9c5d3226c2b35f275263447235f356a13b5f208e5fa469fae"),
        Library("libQnnSystem.so", 4068296, "ca9e5429c77380e1c3f5a401f00288915ec2f6cc478979bafd57c665391d432f"),
        Library("libQnnHtp.so", 3979248, "30e2981965e687d71626c91397867da81247c639e598f49fa5d139cbb21255d0"),
        Library("libQnnHtpV75Stub.so", 791696, "eca06fdf5bba9d9a291eae6d53ca46c7432e57be6c8e0565a628f2acc353b0a1"),
        Library("libQnnHtpV75Skel.so", 18693300, "3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af"),
    )
    fun supportedDevice(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        Build.SUPPORTED_ABIS.contains("arm64-v8a") && supportedSoc(Build.SOC_MODEL)
    internal fun supportedSoc(soc: String): Boolean = soc.uppercase().matches(Regex("SM8650(?:[-_ ].*)?"))
    @Synchronized fun prepare(context: Context) {
        check(supportedDevice()) { "This NPU bundle supports Snapdragon 8 Gen 3 only" }
        val directory = File(context.filesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs())
        files.forEach { spec ->
            val file = File(directory, spec.name)
            if (!valid(file, spec)) {
                val part = File(directory, spec.name + ".part")
                try {
                    GZIPInputStream(context.assets.open("whisper-qnn-runtime/${spec.name}.gz")).use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(65536); var count = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= spec.bytes); output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                    }
                    check(valid(part, spec)) { "QNN packaged runtime integrity failure" }
                    check(part.setReadOnly())
                    check(part.renameTo(file)) { "QNN runtime installation failed" }
                } finally { part.delete() }
            }
        }
    }
    @Synchronized fun load(directory: File) {
        check(supportedDevice()) { "Unsupported NPU target" }
        check(files.all { valid(File(directory, it.name), it) }) { "QNN runtime not verified" }
        // Same app linker namespace; the Hexagon skeleton is opened only by FastRPC.
        listOf("libQnnSystem.so", "libQnnHtpV75Stub.so", "libQnnHtp.so", "libonnxruntime_providers_qnn.so")
            .forEach { System.load(File(directory, it).absolutePath) }
    }
    private fun valid(file: File, spec: Library): Boolean = file.isFile && file.length() == spec.bytes && digest(file) == spec.sha
    internal fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) {
            val n = input.read(b); if (n < 0) break; md.update(b, 0, n)
        } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
