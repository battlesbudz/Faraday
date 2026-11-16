package com.battlesbudz.jarvis.v2.ai.storage

import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelCompanionFile
import com.battlesbudz.jarvis.v2.ai.bundleFileNames
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/**
 * The file-level semantics a multi-file bundle's install state is built
 * from: a bundle is present only when every companion is on disk as its
 * exact pinned artifact, verified only when its bytes hash to the pin,
 * and deleted only when every one of its files is gone.
 */
class ModelBundleStorageTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun companion(name: String, bytes: ByteArray): ModelCompanionFile =
        ModelCompanionFile(name, "https://example.invalid/$name", sha256(bytes), bytes.size.toLong())

    private fun bundleDirectory(root: File): File = root.resolve("models").apply { mkdirs() }

    @Test fun bundleIsPresentOnlyWhenEveryCompanionIsTheExactPinnedArtifact() {
        val root = Files.createTempDirectory("model-bundle-present").toFile()
        try {
            val models = bundleDirectory(root)
            val depthBytes = ByteArray(101) { 1 }
            val embedBytes = ByteArray(57) { 2 }
            val depth = companion("depth.tflite", depthBytes)
            val embed = companion("embed.tflite", embedBytes)
            // Nothing on disk yet.
            assertFalse(companionFilePresent(models.resolve(depth.fileName), depth))
            assertFalse(companionFilePresent(models.resolve(embed.fileName), embed))
            // A partial bundle — one companion still missing — is not installed.
            models.resolve(depth.fileName).writeBytes(depthBytes)
            assertTrue(companionFilePresent(models.resolve(depth.fileName), depth))
            assertFalse(companionFilePresent(models.resolve(embed.fileName), embed))
            // A truncated companion reads as absent, not as a smaller valid file.
            models.resolve(embed.fileName).writeBytes(embedBytes.copyOf(20))
            assertFalse(companionFilePresent(models.resolve(embed.fileName), embed))
            // The complete bundle is present.
            models.resolve(embed.fileName).writeBytes(embedBytes)
            assertTrue(companionFilePresent(models.resolve(depth.fileName), depth))
            assertTrue(companionFilePresent(models.resolve(embed.fileName), embed))
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptCompanionFailsVerificationEvenAtThePinnedSize() {
        val root = Files.createTempDirectory("model-bundle-corrupt").toFile()
        try {
            val models = bundleDirectory(root)
            val bytes = ByteArray(64) { 7 }
            val companion = companion("codec.tflite", bytes)
            val file = models.resolve(companion.fileName)
            file.writeBytes(bytes)
            assertTrue(companionFileVerified(file, companion))
            // Same length, different bytes: present, but never verified.
            file.writeBytes(ByteArray(64) { 8 })
            assertTrue(companionFilePresent(file, companion))
            assertFalse(companionFileVerified(file, companion))
            // A missing file verifies as nothing.
            file.delete()
            assertFalse(companionFileVerified(file, companion))
        } finally { root.deleteRecursively() }
    }

    @Test fun deleteRemovesEveryBundleFileAndNothingElse() {
        val root = Files.createTempDirectory("model-bundle-delete").toFile()
        try {
            val models = bundleDirectory(root)
            val cache = root.resolve("cache/LFM3-Omni-2B").apply { mkdirs() }
            cache.resolve("compiled.bin").writeText("cache")
            val bundleNames = ModelCatalog.lfm3Omni2b.bundleFileNames()
            assertEquals(4, bundleNames.size)
            bundleNames.forEach { name ->
                models.resolve(name).writeText(name)
                models.resolve("$name.part").writeText("partial")
                models.resolve("$name.part.chunks").apply { mkdirs() }
                    .resolve("0.part").writeText("checkpoint")
            }
            val other = models.resolve("other.litertlm").apply { writeText("other") }
            val otherPart = models.resolve("other.litertlm.part").apply { writeText("other partial") }
            // The same per-name removal loop ModelStore.deleteModel runs.
            bundleNames.forEach { name -> removeModelFiles(models, name, cache) }
            bundleNames.forEach { name ->
                assertFalse(models.resolve(name).exists())
                assertFalse(models.resolve("$name.part").exists())
                assertFalse(models.resolve("$name.part.chunks").exists())
            }
            assertFalse(cache.exists())
            assertEquals("other", other.readText())
            assertEquals("other partial", otherPart.readText())
            // Deleting an already-deleted bundle is harmless.
            bundleNames.forEach { name -> removeModelFiles(models, name, cache) }
        } finally { root.deleteRecursively() }
    }

    @Test fun discardRemovesEveryBundlePartialsResumableStateOnly() {
        val root = Files.createTempDirectory("model-bundle-discard").toFile()
        try {
            val models = bundleDirectory(root)
            val bundleNames = ModelCatalog.lfm3Omni2b.bundleFileNames()
            bundleNames.forEach { name ->
                models.resolve(name).writeText("installed $name")
                models.resolve("$name.part").writeText("partial")
                models.resolve("$name.part.chunks").apply { mkdirs() }
                    .resolve("0.part").writeText("checkpoint")
            }
            // The same per-name discard loop ModelStore.discardPartialDownload runs.
            bundleNames.forEach { name -> removePartialDownloadFiles(models, name) }
            bundleNames.forEach { name ->
                assertEquals("installed $name", models.resolve(name).readText())
                assertFalse(models.resolve("$name.part").exists())
                assertFalse(models.resolve("$name.part.chunks").exists())
            }
        } finally { root.deleteRecursively() }
    }
}
