package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun importsRecognizePublisherNamesWithoutLosingInstalledIdentity() {
        val deepSeek = ModelCatalog.find("DeepSeek-R1-Distill-Qwen-1.5B")!!
        assertEquals(setOf("DeepSeek-R1-Distill-Qwen-1.5B.litertlm",
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm"), deepSeek.importFileNames())
        assertEquals(setOf("gemma-4-E2B-it.litertlm"), ModelCatalog.gemma4E2b.importFileNames())
        val generic = ModelCatalog.qwen.first { it.id == "Qwen3-4B-Thinking-2507" }
        assertTrue(generic.importFileNames().contains("model.litertlm"))
        assertEquals("Qwen3-4B-Thinking-2507.litertlm", generic.fileName)
    }
    @Test fun existingInstallationsKeepE2b() {
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve(null))
        assertEquals("gemma-4-E2B-it.litertlm", ModelCatalog.resolve(null).fileName)
    }

    @Test fun savedE4bSelectionIsRestored() {
        assertEquals(ModelCatalog.gemma4E4b, ModelCatalog.resolve("Gemma-4-E4B-it"))
        assertNotEquals(ModelCatalog.gemma4E2b.fileName, ModelCatalog.gemma4E4b.fileName)
    }

    @Test fun removedPreferenceFallsBackButUnknownWorkRequestIsRejected() {
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve("removed-model"))
        assertNull(ModelCatalog.find("removed-model"))
    }

    @Test fun downloadsHaveSeparateFilesAndPinnedChecksums() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
        ModelCatalog.all.forEach { spec ->
            assertTrue(spec.expectedSha256!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(spec.downloadUrl!!.startsWith("https://huggingface.co/"))
            assertFalse(spec.downloadUrl.contains("/resolve/main/"))
            assertTrue(spec.downloadUrl.substringBefore('?').endsWith(".litertlm"))
        }
    }

    @Test fun removedAbliteratedSelectionsFallBackToStockE2b() {
        // Battles removed both DuoNeural abliterated entries on 2026-11-01 (the
        // bundles lack the hardware audio encoder). They must not be findable,
        // listable or downloadable, and a persisted selection naming either one
        // must resolve to the stock E2B default — never a crash or no model.
        assertNull(ModelCatalog.find("Gemma-4-E2B-Abliterated"))
        assertNull(ModelCatalog.find("Gemma-4-E4B-Abliterated"))
        assertTrue(ModelCatalog.all.none { it.id.contains("Abliterated") })
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve("Gemma-4-E2B-Abliterated"))
        assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve("Gemma-4-E4B-Abliterated"))
    }

    @Test fun lfm3OmniIsAMultiFileBundleWithEveryFilePinned() {
        val omni = ModelCatalog.lfm3Omni2b
        assertEquals(omni, ModelCatalog.find("LFM3-Omni-2B"))
        assertEquals("LFM3-Omni-2B_int8.litertlm", omni.fileName)
        assertEquals("Liquid AI", omni.provider)
        assertTrue(omni.supportsAudio)
        assertFalse(omni.supportsVision)
        // Tools join the Omni path in their own unit; the catalog must not
        // offer them before the runtime can serve them.
        assertFalse(omni.supportsTools)
        assertFalse(omni.incrementalGemmaInput)
        assertEquals(4096, omni.contextTokens)
        assertEquals(2_519_286_908L, omni.derivedBytes)
        // downloadBytes counts the whole bundle: the primary file plus the
        // three speech companions, each pinned at the same HF revision.
        assertEquals(4_012_449_712L, omni.downloadBytes)
        assertEquals(3_226_750_192L, omni.primaryDownloadBytes()!!)
        assertEquals(3, omni.companionFiles.size)
        assertEquals(omni.downloadBytes!!,
            omni.primaryDownloadBytes()!! + omni.companionFiles.sumOf { it.downloadBytes })
        assertEquals(listOf("LFM3-Omni-2B_int8.litertlm",
            "LFM3-Omni-2B_depth_frame2_f32.tflite",
            "LFM3-Omni-2B_frame_embed_f32.tflite",
            "LFM3-Omni-2B_codec_decoder_f12_f32.tflite"), omni.bundleFileNames())
        omni.companionFiles.forEach { companion ->
            assertTrue(companion.expectedSha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(companion.downloadUrl.startsWith("https://huggingface.co/"))
            assertFalse(companion.downloadUrl.contains("/resolve/main/"))
            assertTrue(companion.downloadBytes > 0L)
        }
        // Every other catalog model remains a single-file download.
        ModelCatalog.all.filter { it.id != omni.id }.forEach {
            assertTrue(it.id, it.companionFiles.isEmpty())
            assertEquals(it.downloadBytes, it.primaryDownloadBytes())
        }
    }

    @Test fun qwenSelectionsDoNotEnableGemmaOnlyPaths() {
        assertEquals(13, ModelCatalog.qwen.size)
        ModelCatalog.qwen.forEach { spec ->
            assertEquals(spec, ModelCatalog.resolve(spec.id))
            assertFalse(spec.supportsAudio)
            assertTrue(spec.supportsTools)
            assertFalse(spec.incrementalGemmaInput)
            assertTrue(spec.downloadBytes!! > 0)
            assertTrue(spec.contextTokens!! in 2048..4096)
        }
        assertTrue(ModelCatalog.gemma4E2b.incrementalGemmaInput)
        assertTrue(ModelCatalog.gemma4E4b.supportsAudio)
        assertEquals(listOf("Qwen2-VL-2B"), ModelCatalog.qwen.filter { it.supportsVision }.map { it.id })
        assertFalse(ModelCatalog.find("Qwen3.5-4B")!!.recommendedGpu)
        assertNotNull(ModelCatalog.find("Qwen2.5-Coder-1.5B-Instruct"))
        assertNotNull(ModelCatalog.find("Qwen2.5-Coder-3B-Instruct"))
    }
}
