package com.battlesbudz.jarvis.v2.ai.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class GemmaStreamingPackagedAssetsTest {
    private fun sourceAssets(): Map<String, ByteArray> {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { File(it, "gemma_streaming/source-copy-recipe.bin").isFile }
            ?: error("Run from the app or repository directory with actual source assets")
        return listOf(GemmaStreamingArtifactStore.RECIPE_ASSET_NAME,
            GemmaStreamingArtifactStore.LITERALS_ASSET_NAME).associate { name ->
            val path = "${GemmaStreamingArtifactStore.ASSET_DIRECTORY}/$name"
            path to File(root, path).readBytes()
        }
    }

    private val literalPath = "gemma_streaming/structural-literals.bin.gzip"
    private fun read(assets: Map<String, ByteArray>, path: String): InputStream =
        ByteArrayInputStream(assets[path] ?: throw FileNotFoundException(path))

    @Test fun exactAssetsUsePreservedNameAndVerifyDecodedBytes() {
        val assets = sourceAssets()
        val opened = mutableListOf<String>()
        val result = GemmaStreamingArtifactStore.verifyPackagedAssets { path ->
            opened += path
            read(assets, path)
        }
        assertEquals(WeightlessEncoderRecipe.RECIPE_SHA256, result["gemma_streaming/source-copy-recipe.bin"])
        assertEquals(WeightlessEncoderRecipe.LITERALS_SHA256, result[literalPath])
        assertEquals(WeightlessEncoderRecipe.LITERALS_DECODED_SHA256, result["$literalPath:decoded"])
        assertEquals(listOf("gemma_streaming/source-copy-recipe.bin", literalPath, literalPath), opened)
    }

    @Test fun oldApkDecompressedAndRenamedAssetIsRejected() {
        val assets = sourceAssets().toMutableMap()
        val compressed = checkNotNull(assets.remove(literalPath))
        assets["gemma_streaming/structural-literals.bin"] =
            GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val failure = assertThrows(FileNotFoundException::class.java) {
            GemmaStreamingArtifactStore.verifyPackagedAssets { read(assets, it) }
        }
        assertEquals(literalPath, failure.message)
    }

    @Test fun changedCompressedBytesFailBeforeDecode() {
        val assets = sourceAssets().toMutableMap()
        val literal = checkNotNull(assets[literalPath]).copyOf()
        literal[literal.lastIndex] = (literal.last().toInt() xor 1).toByte()
        assets[literalPath] = literal
        val failure = assertThrows(IllegalStateException::class.java) {
            GemmaStreamingArtifactStore.verifyPackagedAssets { read(assets, it) }
        }
        assertEquals("native_audio_recipe_integrity_failed", failure.message)
    }

    @Test fun truncatedAssetIsRejected() {
        val assets = sourceAssets().toMutableMap()
        assets[literalPath] = checkNotNull(assets[literalPath]).dropLast(1).toByteArray()
        val failure = assertThrows(IllegalStateException::class.java) {
            GemmaStreamingArtifactStore.verifyPackagedAssets { read(assets, it) }
        }
        assertEquals("native_audio_recipe_truncated", failure.message)
    }

    @Test fun oversizedAssetIsRejected() {
        val assets = sourceAssets().toMutableMap()
        assets[literalPath] = checkNotNull(assets[literalPath]) + byteArrayOf(0)
        val failure = assertThrows(IllegalStateException::class.java) {
            GemmaStreamingArtifactStore.verifyPackagedAssets { read(assets, it) }
        }
        assertEquals("native_audio_recipe_oversized", failure.message)
    }

    @Test fun readerThatDoesNotAdvanceFailsAndCloses() {
        var closed = false
        val failure = assertThrows(IllegalStateException::class.java) {
            GemmaStreamingArtifactStore.verifyPackagedAssets {
                object : InputStream() {
                    override fun read(): Int = error("Bulk read expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
                    override fun close() { closed = true }
                }
            }
        }
        assertEquals("native_audio_recipe_read_stalled", failure.message)
        assertTrue(closed)
    }
}
