package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

/** Pins the catalog descriptor: an accidental edit to the URL, hash or size must fail loudly here. */
class EmbeddingModelCatalogTest {
    @Test fun embeddingGemma2DescriptorIsPinnedToTheVerifiedArtifact() {
        val spec = EmbeddingModelCatalog.embeddingGemma2Text
        assertEquals("embeddinggemma-2-text-270m", spec.id)
        assertEquals("embeddinggemma-2-text-270m.litertlm", spec.fileName)
        assertEquals("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb", spec.expectedSha256)
        assertEquals(164_626_432L, spec.downloadBytes)
        assertEquals(768, spec.outputDimension)
        assertEquals(256, spec.storedDimension)
        assertTrue(spec.downloadUrl.startsWith("https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/"))
        assertTrue(spec.downloadUrl.endsWith("/embeddinggemma-2-text-270m.litertlm?download=true"))
    }

    @Test fun catalogLookupAndDefaultsAreConsistent() {
        assertSame(EmbeddingModelCatalog.embeddingGemma2Text, EmbeddingModelCatalog.default)
        assertSame(EmbeddingModelCatalog.embeddingGemma2Text, EmbeddingModelCatalog.find("embeddinggemma-2-text-270m"))
        assertNull(EmbeddingModelCatalog.find("no-such-model"))
        assertTrue(EmbeddingModelCatalog.default.storedDimension < EmbeddingModelCatalog.default.outputDimension)
    }
}
