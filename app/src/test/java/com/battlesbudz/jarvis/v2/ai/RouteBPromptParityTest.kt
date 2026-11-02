package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Route B parity gate, pinned against the real prompt machinery: for
 * each decomposition the Conversation route can produce for a native-audio
 * turn, build the shape from the actual prompt builder / preface stripper /
 * tool catalog and compare it with the only shape a raw Session can carry.
 * Only the tools-disabled, preface-free decomposition is Session-equal —
 * which is why RouteBPrimingPolicy excludes the other two, and why the
 * priming machinery is dormant on the current catalog.
 */
class RouteBPromptParityTest {
    private val history = listOf(
        ChatEntry("You", "Earlier question"),
        ChatEntry("Jarvis", "Earlier answer")
    )
    private val audioRows = 188

    /** The exact prompt a production direct-audio turn submits on the sealed route. */
    private fun productionPrompt(): String =
        ConversationPromptBuilder(ShortTermConversationContext()).buildGemmaPrompt(
            GemmaAudioInputPolicy.REQUEST, null, history, seedContext = true, voice = true
        )

    @Test fun toolsDecompositionCannotMatchASession() {
        val text = productionPrompt()
        // Production direct-audio generation: no system instruction, the
        // full prompt in the user slot — plus the tool declarations the
        // Conversation config renders. A Session cannot receive those.
        val conversation = RouteBConversationShape(
            systemInstruction = null,
            userText = text,
            toolNames = MobileActionToolDefinitions.all().map { it.getToolDescriptionJsonString() },
            audioTokenRows = audioRows
        )
        assertTrue(conversation.toolNames.isNotEmpty())
        assertFalse(routeBPromptParity(conversation, RouteBSessionShape(text, audioRows)))
    }

    @Test fun fixedPrefaceDecompositionCannotMatchASession() {
        val text = productionPrompt()
        // The early-preparation / speculative-draft decomposition: the
        // voice instructions move into the native system slot and the user
        // slot carries the stripped remainder. A Session has no system slot.
        val stripped = GemmaVoicePreface.userText(text)
        assertNotNull(stripped)
        val conversation = RouteBConversationShape(
            systemInstruction = GemmaVoicePreface.instructions,
            userText = stripped!!,
            toolNames = emptyList(),
            audioTokenRows = audioRows
        )
        assertFalse(routeBPromptParity(conversation, RouteBSessionShape(text, audioRows)))
    }

    @Test fun toolsDisabledPrefaceFreeDecompositionMatchesASession() {
        val text = productionPrompt()
        // The decomposition the shipped incremental-text path already runs
        // under: one prompt string, no system slot, no declarations.
        val conversation = RouteBConversationShape(
            systemInstruction = null,
            userText = text,
            toolNames = emptyList(),
            audioTokenRows = audioRows
        )
        assertTrue(routeBPromptParity(conversation, RouteBSessionShape(text, audioRows)))
        // The audio payload is part of the shape: a different row count,
        // or any text drift, breaks parity.
        assertFalse(routeBPromptParity(conversation, RouteBSessionShape(text, audioRows + 1)))
        assertFalse(routeBPromptParity(conversation, RouteBSessionShape("$text ", audioRows)))
    }

    @Test fun catalogAudioModelsAreAllToolCapable() {
        // The fact that keeps the gate closed today: if a future catalog
        // adds a tools-disabled audio model, this test fails first and the
        // priming policy gets revisited deliberately, not by accident.
        val audioModels = ModelCatalog.all.filter { it.supportsAudio }
        assertTrue(audioModels.isNotEmpty())
        assertTrue(audioModels.all { it.supportsTools && it.incrementalGemmaInput })
        assertEquals(audioModels.size, audioModels.count { it.supportsTools })
    }
}
