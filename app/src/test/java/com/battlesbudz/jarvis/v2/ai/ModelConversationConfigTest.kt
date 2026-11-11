package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.code.CodeSampling
import org.junit.Assert.*
import org.junit.Test

class ModelConversationConfigTest {
    @Test fun fixedPrefillContainsOnlyPolicyAndNoInitialHistoryOrAutomaticTools() {
        for (spec in listOf(ModelCatalog.gemma4E2b, ModelCatalog.gemma4E4b)) {
            val config = modelConversationConfig(spec, emptyList(), false, fixedVoicePreface = true)
            assertNotNull(config.systemInstruction); assertTrue(config.prefillPrefaceOnInit)
            assertTrue(config.initialMessages.isEmpty()); assertTrue(config.tools.isEmpty())
            assertFalse(config.automaticToolCalling)
            val ordinary = modelConversationConfig(spec, emptyList(), false)
            assertNull(ordinary.systemInstruction); assertFalse(ordinary.prefillPrefaceOnInit)
        }
        assertTrue(runCatching {
            modelConversationConfig(ModelCatalog.gemma4E2b, emptyList(), true, fixedVoicePreface = true)
        }.isFailure)
    }
    @Test fun communityBundlesReceiveToolsWithoutGemmaTemplatesOrAutomaticExecution() {
        val tools = MobileActionToolDefinitions.all()
        listOf("Qwen3-1.7B", "Phi-4-mini-instruct", "Jan-nano", "Gemma-4-E2B-it").forEach { id ->
            val spec = requireNotNull(ModelCatalog.find(id))
            val active = modelConversationConfig(spec, tools, true)
            assertEquals(id, tools.size, active.tools.size)
            assertFalse(active.automaticToolCalling)
            val ordinaryChat = modelConversationConfig(spec, tools, false)
            assertTrue(ordinaryChat.tools.isEmpty())
            assertFalse(ordinaryChat.automaticToolCalling)
        }
    }
    @Test fun codeConfigDisablesToolsAndCarriesTheCodeInstructionAndSampler() {
        // The bridge check behind this test (code-and-export scope
        // section 4.2): ConversationConfig exposes a per-conversation
        // samplerConfig, so code mode samples at CodeSampling's values
        // instead of the bridge default.
        for (spec in listOf(ModelCatalog.gemma4E2b, ModelCatalog.gemma4E4b)) {
            val config = codeConversationConfig(spec)
            assertTrue(config.tools.isEmpty())
            assertFalse(config.automaticToolCalling)
            assertNotNull(config.systemInstruction)
            val sampler = requireNotNull(config.samplerConfig)
            assertEquals(CodeSampling.TOP_K, sampler.topK)
            assertEquals(CodeSampling.TOP_P, sampler.topP, 0.0)
            assertEquals(CodeSampling.TEMPERATURE, sampler.temperature, 0.0)
            assertEquals(CodeSampling.SEED, sampler.seed)
        }
        val community = codeConversationConfig(requireNotNull(ModelCatalog.find("Qwen3-1.7B")))
        assertTrue(community.tools.isEmpty())
        assertFalse(community.automaticToolCalling)
        assertNotNull(community.systemInstruction)
        assertNotNull(community.samplerConfig)
        assertEquals(512, community.maxOutputToken)
    }
}
