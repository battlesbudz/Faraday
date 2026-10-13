package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ModelGuidanceTest {
    private val gib = 1_073_741_824L
    private val phone = PhoneProfile("Samsung Fold6", "SM-F956U", "SM8650", 12*gib, 8*gib, 50*gib, true)


    @Test fun transientFreeRamStorageAndInstallationCannotChangeSuitabilityOrFamilyOrder() {
        ModelCatalog.all.forEach { spec ->
            assertEquals(spec.id, ModelGuidance.assess(spec, phone),
                ModelGuidance.assess(spec, phone.copy(availableRamBytes = 1, freeStorageBytes = 1)))
            // Installed status is accepted only by storage advice, never by suitability or sorting.
            ModelGuidance.storageNotice(spec, phone, installed = true)
            ModelGuidance.storageNotice(spec, phone, installed = false)
        }
        assertEquals(ModelGuide.families(), ModelGuide.families(ModelCatalog.all.reversed()))
    }

    @Test fun experimentalStatusCannotTurnPositiveMemoryAdviceIntoAWarning() {
        val experimental = ModelCatalog.find("Phi-4-mini-instruct")!!
        val fit = ModelGuidance.assess(experimental, phone)
        assertNotNull(fit.compatibilityNotice)
        assertEquals("Memory estimate: Likely enough room", fit.quickMemoryLabel)
        assertFalse(fit.memoryWarning)
        assertFalse(ModelGuidance.assess(experimental.copy(downloadBytes = null), phone).memoryWarning)
        assertTrue(ModelGuidance.assess(experimental, phone.copy(totalRamBytes = 4_000_000_000)).memoryWarning)
    }

    @Test fun downloadStorageRemainsSeparateFromMemory() {
        val full = phone.copy(freeStorageBytes = 1)
        assertNotNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full, installed = false))
        assertNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full, installed = true))
        assertNull(ModelGuidance.storageNotice(ModelCatalog.gemma4E2b, full.copy(freeStorageBytes = -1), false))
    }

    @Test fun memoryBoundariesAreExplicitAndUnknownInputsDoNotPretendToFit() {
        val spec = ModelCatalog.gemma4E2b
        listOf(.39 to 0, .40 to 1, .64 to 1, .65 to 2, .84 to 2, .85 to 3).forEach { (ratio, expected) ->
            assertEquals(expected, ModelGuidance.assess(spec.copy(downloadBytes = (ratio * 10000).toLong()),
                phone.copy(totalRamBytes = 10000)).memoryRisk)
        }
        assertEquals("Memory needs unknown", ModelGuidance.assess(spec.copy(downloadBytes = null), phone).label)
        assertEquals("Memory needs unknown", ModelGuidance.assess(spec, phone.copy(totalRamBytes = 0)).label)
        assertEquals("Unsupported architecture", ModelGuidance.assess(spec, phone.copy(arm64 = false)).label)
    }

    @Test fun compressionAndThinkingDoNotMasqueradeAsLowCompute() {
        val bonsai = ModelCatalog.gemma4E2b.copy(id = "Ternary-Bonsai-8B", downloadBytes = 2559504325L)
        assertEquals("Heavy workload", ModelGuidance.assess(bonsai, phone).workload)
        assertEquals("Likely memory headroom", ModelGuidance.assess(bonsai, phone).label)
        val thinking = ModelCatalog.find("LFM2.5-1.2B-Thinking")!!
        assertTrue(ModelGuidance.assess(thinking, phone).workloadExplanation.contains("long pause"))
        assertTrue(ModelGuidance.assess(ModelCatalog.find("Qwen3.5-2B")!!, phone).workloadExplanation.contains("CPU"))
    }

    @Test fun familiesCoverEveryModelOnceAndSortByArtifactSize() {
        val families = ModelGuide.families()
        assertEquals(ModelCatalog.all.size, families.values.flatten().size)
        assertEquals(ModelCatalog.all.toSet(), families.values.flatten().toSet())
        families.values.forEach { specs ->
            assertEquals(specs.map { it.downloadBytes }, specs.map { it.downloadBytes }.sortedBy { it ?: Long.MAX_VALUE })
        }
        assertEquals("Gemma", ModelGuide.family(ModelCatalog.find("codegemma-7b-it-int4-litertlm")!!))
        assertEquals("DeepSeek", ModelGuide.family(ModelCatalog.find("DeepSeek-R1-Distill-Qwen-1.5B")!!))
        assertEquals("Mistral", ModelGuide.family(ModelCatalog.find("Ministral-3-3B-Instruct-2512")!!))
        assertEquals("Phi", ModelGuide.family(ModelCatalog.find("Phi-4-mini-instruct")!!))
    }

    @Test fun everyCardHasVisiblePurposeAndSpecialistsHaveAccurateLimitations() {
        ModelCatalog.all.forEach {
            val purpose = ModelGuide.purpose(it)
            assertTrue(it.id, purpose.tags.isNotEmpty() && purpose.description.isNotBlank())
        }
        assertTrue(ModelGuide.purpose(ModelCatalog.find("Qwen2.5-Coder-1.5B-Instruct")!!).tags.contains("Coding"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("FastContext-1.0-4B-SFT")!!).caveat.contains("not connected"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("FastVLM-0.5B")!!).caveat.contains("Attach one image"))
        assertTrue(ModelGuide.purpose(ModelCatalog.find("Hy-MT2-1.8B")!!).tags.contains("Translation"))
    }

    @Test fun searchingFindsPurposesAcrossFamiliesWithoutHidingHeavyModels() {
        val coding = ModelGuide.families(query = "coding")
        assertTrue(coding.keys.containsAll(listOf("Gemma", "Qwen")))
        assertTrue(ModelGuide.families(query = "no_such_model").isEmpty())
        assertEquals(ModelCatalog.gemma4E2b, ModelGuide.startingPoint(ModelGuide.families()["Gemma"]!!, phone))
        assertEquals("Qwen3-1.7B", ModelGuide.startingPoint(ModelGuide.families()["Qwen"]!!, phone)!!.id)
        assertNull(ModelGuide.startingPoint(ModelGuide.families()["Gemma"]!!, phone.copy(arm64 = false)))
    }
}
