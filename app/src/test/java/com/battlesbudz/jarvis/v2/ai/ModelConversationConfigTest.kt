package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
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
}
