package com.battlesbudz.jarvis.v2.ai.audio

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/** App-private derived graph storage; caller already owns the global model lease.
 * No model/network download, permission changes or raw speech storage. */
internal object GemmaStreamingArtifactStore {
    const val ASSET_DIRECTORY = "gemma_streaming"
    const val RECIPE_ASSET_NAME = "source-copy-recipe.bin"
    // Android packaging treats a final .gz suffix specially. Preserve the
    // audited gzip bytes under a literal name and verify them in the final APK.
    const val LITERALS_ASSET_NAME = "structural-literals.bin.gzip"
    private val gate = Any()
    private var activeLeases = 0

    class Lease internal constructor(
        val file: File,
        private val modelLeaseHeld: () -> Boolean,
        private val length: Long,
        private val modified: Long,
    ) : Closeable {
        private val live = AtomicBoolean(true)
        fun checkImmutableAndLive() {
            check(live.get() && modelLeaseHeld()) { "native_audio_artifact_lease_expired" }
            check(file.isFile && file.length() == length && file.lastModified() == modified) {
                "native_audio_artifact_changed"
            }
        }
        /** Call only after checked native encoder drain. */
        override fun close() = synchronized(gate) {
            if (live.compareAndSet(true, false)) { check(activeLeases > 0); activeLeases-- }
        }
    }

    /** Blocking disk/hash work; invoke on a preparation worker, never UI/capture. */
    fun acquire(
        officialBundle: File,
        modelCacheDirectory: File,
        readAsset: (String) -> InputStream,
        exactSourceVerified: () -> Boolean,
        modelLeaseHeld: () -> Boolean,
        cancelled: () -> Boolean,
    ): Lease = synchronized(gate) {
        check(modelLeaseHeld()) { "native_audio_requires_model_lease" }
        check(officialBundle.length() == WeightlessEncoderRecipe.SOURCE_BYTES && exactSourceVerified()) {
            "native_audio_requires_exact_pinned_e2b_bundle"
        }
        check(!cancelled()) { "native_audio_preparation_cancelled" }
        val folder = File(modelCacheDirectory, "native_audio_stream_v1").canonicalFile
        check(folder.isDirectory || folder.mkdirs()) { "native_audio_cache_unavailable" }
        val model = File(folder, "gemma4_e2b_d5c50b14.tflite")
        if (!model.exists()) {
            check(activeLeases == 0) { "native_audio_artifact_in_use" }
            val recipe = copyAsset(folder, RECIPE_ASSET_NAME, WeightlessEncoderRecipe.RECIPE_BYTES, WeightlessEncoderRecipe.RECIPE_SHA256, readAsset)
            val literals = copyAsset(folder, LITERALS_ASSET_NAME, WeightlessEncoderRecipe.LITERALS_BYTES, WeightlessEncoderRecipe.LITERALS_SHA256, readAsset)
            WeightlessEncoderRecipe.reconstruct(officialBundle, recipe, literals, model) {
                cancelled() || !modelLeaseHeld()
            }
        }
        check(model.length() == WeightlessEncoderRecipe.OUTPUT_BYTES &&
            sha256(model, cancelled) == WeightlessEncoderRecipe.OUTPUT_SHA256) { "native_audio_cache_integrity_failed" }
        check(modelLeaseHeld() && !cancelled()) { "native_audio_preparation_cancelled" }
        // Allocate the lease before publishing its count, including OOM paths.
        val lease = Lease(model, modelLeaseHeld, model.length(), model.lastModified())
        activeLeases++
        lease
    }

    /** Exercises the actual packaged asset reader without a model or model lease. */
    fun verifyPackagedAssets(readAsset: (String) -> InputStream): Map<String, String> {
        val recipePath = "$ASSET_DIRECTORY/$RECIPE_ASSET_NAME"
        val literalsPath = "$ASSET_DIRECTORY/$LITERALS_ASSET_NAME"
        readAsset(recipePath).use {
            transferVerifiedAsset(it, null, WeightlessEncoderRecipe.RECIPE_BYTES, WeightlessEncoderRecipe.RECIPE_SHA256)
        }
        readAsset(literalsPath).use {
            transferVerifiedAsset(it, null, WeightlessEncoderRecipe.LITERALS_BYTES, WeightlessEncoderRecipe.LITERALS_SHA256)
        }
        readAsset(literalsPath).use { asset ->
            GZIPInputStream(asset).use {
                transferVerifiedAsset(it, null, WeightlessEncoderRecipe.LITERALS_DECODED_BYTES, WeightlessEncoderRecipe.LITERALS_DECODED_SHA256)
            }
        }
        return mapOf(recipePath to WeightlessEncoderRecipe.RECIPE_SHA256,
            literalsPath to WeightlessEncoderRecipe.LITERALS_SHA256,
            "$literalsPath:decoded" to WeightlessEncoderRecipe.LITERALS_DECODED_SHA256)
    }

    private fun transferVerifiedAsset(input: InputStream, output: OutputStream?, expectedBytes: Int, expectedSha: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(16_384)
        var count = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            check(n > 0) { "native_audio_recipe_read_stalled" }
            check(n <= expectedBytes - count) { "native_audio_recipe_oversized" }
            count += n
            digest.update(chunk, 0, n)
            output?.write(chunk, 0, n)
        }
        check(count == expectedBytes) { "native_audio_recipe_truncated" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expectedSha) { "native_audio_recipe_integrity_failed" }
    }

    private fun copyAsset(folder: File, name: String, expectedBytes: Int, expectedSha: String,
                          readAsset: (String) -> InputStream): File {
        val output = File(folder, name)
        if (output.exists()) {
            check(output.length() == expectedBytes.toLong() && sha256(output) == expectedSha) {
                "native_audio_recipe_integrity_failed"
            }
            return output
        }
        val temporary = File.createTempFile(".recipe-", ".tmp", folder)
        var installed = false
        try {
            readAsset("$ASSET_DIRECTORY/$name").use { input ->
                temporary.outputStream().buffered().use { target ->
                    transferVerifiedAsset(input, target, expectedBytes, expectedSha)
                }
            }
            check(sha256(temporary) == expectedSha) { "native_audio_recipe_integrity_failed" }
            check(!output.exists() && temporary.renameTo(output)) { "native_audio_recipe_install_failed" }
            installed = true
            return output
        } finally {
            if (!installed) check(!temporary.exists() || temporary.delete()) { "native_audio_recipe_cleanup_failed" }
        }
    }

    private fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(65_536)
            while (true) {
                check(!cancelled()) { "native_audio_preparation_cancelled" }
                val n = input.read(chunk)
                if (n < 0) break
                digest.update(chunk, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
