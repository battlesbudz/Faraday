package com.battlesbudz.jarvis.v2.ai
import org.junit.Assert.*
import org.junit.Test

class ModelCompatibilityTest {
    @Test fun removedModelsCannotBeSelectedAndSavedPreferencesRecover() {
        val removed = listOf(
            "Qwen3-8B",
            "Qwen2-1.5B-Instruct",
            "Llama-3.2-1B",
            "Llama-3.2-3B",
            "MedGemma-1.5-4B-IT",
            "MiniCPM5-1B",
            "Qwen3-0.6B-int4",
            "Qwen3-14B",
            "SmolLM2-1.7B-Instruct",
            "SmolLM2-135M-Instruct",
            "SmolLM2-360M-Instruct",
            "Ternary-Bonsai-1.7B",
            "Ternary-Bonsai-8B",
            "TinySwallow-1.5B-Instruct",
            "gemma-3-270m-it",
            "gemma-4-26B-A4B-it-litert-lm",
            "gemma-4-31B-it-litert-lm",
            "granite-4.0-350m-litert-lm"
        )
        assertEquals(72, ModelCatalog.all.size)
        removed.forEach { id ->
            assertNull(id, ModelCatalog.find(id))
            assertEquals(ModelCatalog.gemma4E2b, ModelCatalog.resolve(id))
        }
    }
    @Test fun requestedExperimentsAreKeptAndNotStartingOptions() {
        val phone = PhoneProfile("Phone", "SM-F956U", "chip", 12_000_000_000, 8_000_000_000, 50_000_000_000, true)
        listOf("codegemma-7b-it-int4-litertlm", "DeepSeek-R1-Distill-Qwen-7B", "MiniCPM-V-4").forEach {
            val spec = ModelCatalog.find(it)!!
            assertEquals(ModelEvidenceStatus.EXPERIMENTAL, ModelCompatibility.assess(spec).status)
            assertFalse(ModelGuidance.assess(spec, phone).startingOption)
        }
    }
    @Test fun rebootWarningIsScopedVisibleAndRequiresAcknowledgment() {
        val spec = ModelCatalog.find("Zamba2-2.7B-instruct")!!
        assertFalse(spec.recommendedGpu)
        val evidence = ModelCompatibility.assess(spec)
        assertEquals(ModelEvidenceStatus.ISSUE, evidence.status)
        assertTrue(evidence.confirmBeforeSelection)
        assertTrue(evidence.summary.contains("rebooted"))
        assertTrue(evidence.summary.contains("No data wipe was reported"))
        assertTrue(evidence.summary.contains("Jarvis uses CPU"))
    }
    @Test fun changedArtifactsAndBackendsNeverInheritTestedClaims() {
        val spec = ModelCatalog.gemma4E2b
        assertEquals(ModelEvidenceStatus.TESTED, ModelCompatibility.assess(spec).status)
        listOf(spec.copy(expectedSha256 = "changed"), spec.copy(recommendedGpu = false),
            spec.copy(contextTokens = 1), spec.copy(id = "unknown")).forEach {
            assertEquals(ModelEvidenceStatus.EXPERIMENTAL, ModelCompatibility.assess(it).status)
        }
        ModelCatalog.all.forEach { assertNotNull(it.id, ModelCompatibility.assess(it).source) }
    }
}
