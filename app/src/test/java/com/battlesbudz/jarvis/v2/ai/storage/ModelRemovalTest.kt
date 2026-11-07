package com.battlesbudz.jarvis.v2.ai.storage

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ModelRemovalTest {
    @Test fun removesOnlySelectedModelAndItsPartialFilesAndCache() {
        val root = Files.createTempDirectory("model-removal").toFile()
        try {
            val models = root.resolve("models").apply { mkdirs() }
            val cache = root.resolve("cache/selected").apply { mkdirs() }
            cache.resolve("compiled.bin").writeText("cache")
            val removed = listOf("model.litertlm", "model.litertlm.part", "model.litertlm.123.part")
            val retained = listOf("other.litertlm", "model.litertlm-extra.part", "other.litertlm.part")
            (removed + retained).forEach { models.resolve(it).writeText(it) }
            val chunks = models.resolve("model.litertlm.part.chunks").apply { mkdirs() }
            chunks.resolve("0.part").writeText("download checkpoint")
            val otherChunks = models.resolve("other.litertlm.part.chunks").apply { mkdirs() }
            otherChunks.resolve("0.part").writeText("other checkpoint")
            val original = root.resolve("Downloads/model.litertlm")
            original.parentFile.mkdirs()
            original.writeText("original")
            val otherCache = root.resolve("cache/other").apply { mkdirs() }
            otherCache.resolve("compiled.bin").writeText("other")
            removeModelFiles(models, "model.litertlm", cache)
            removed.forEach { assertFalse(models.resolve(it).exists()) }
            retained.forEach { assertTrue(models.resolve(it).isFile) }
            assertFalse(cache.exists())
            assertFalse(chunks.exists())
            assertTrue(otherChunks.resolve("0.part").isFile)
            assertTrue(otherCache.resolve("compiled.bin").isFile)
            assertEquals("original", original.readText())
            // Missing files and a retry after successful deletion are harmless.
            removeModelFiles(models, "model.litertlm", cache)
        } finally { root.deleteRecursively() }
    }

    @Test fun removePartialDownloadFilesRemovesOnlyTheResumableState() {
        val root = Files.createTempDirectory("model-partial-removal").toFile()
        try {
            val models = root.resolve("models").apply { mkdirs() }
            val cache = root.resolve("cache/selected").apply { mkdirs() }
            cache.resolve("compiled.bin").writeText("cache")
            models.resolve("model.litertlm").writeText("installed")
            models.resolve("model.litertlm.part").writeText("partial")
            val chunks = models.resolve("model.litertlm.part.chunks").apply { mkdirs() }
            chunks.resolve("0.part").writeText("download checkpoint")
            chunks.resolve("source").writeText("identity")
            models.resolve("other.litertlm.part").writeText("other partial")
            val otherChunks = models.resolve("other.litertlm.part.chunks").apply { mkdirs() }
            otherChunks.resolve("0.part").writeText("other checkpoint")
            removePartialDownloadFiles(models, "model.litertlm")
            assertFalse(models.resolve("model.litertlm.part").exists())
            assertFalse(chunks.exists())
            assertEquals("installed", models.resolve("model.litertlm").readText())
            assertEquals("cache", cache.resolve("compiled.bin").readText())
            assertEquals("other partial", models.resolve("other.litertlm.part").readText())
            assertEquals("other checkpoint", otherChunks.resolve("0.part").readText())
            // A second discard with nothing left is harmless.
            removePartialDownloadFiles(models, "model.litertlm")
        } finally { root.deleteRecursively() }
    }
}
