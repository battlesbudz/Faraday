package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.CheckedConversationLifecycle
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real model-session/engine transition without loading JNI or model weights. */
class EarlyGemmaInputModeTest {
    private class Fixture(fixed: Boolean = true) {
        val events = mutableListOf<String>()
        val owner = CheckedConversationLifecycle(createConversation = { Any() },
            cancelConversation = {}, awaitIdle = { events += "drain" },
            closeConversation = { events += "close" }, closeEngine = {}, checkWorkerThread = {})
        // Constructor bypass is narrowly limited to replacing the native lifecycle with its checked fake.
        val engine = run {
            // This host-JVM fixture is compiled against Android boot classes, which intentionally
            // do not expose sun.misc types. Resolve the JDK allocator only at JVM test runtime.
            val allocator = Class.forName("sun.misc.Unsafe")
            val field = allocator.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            (allocator.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, LiteRtLmEngine::class.java) as LiteRtLmEngine).also {
                fun put(name: String, value: Any) = LiteRtLmEngine::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(it, value)
                put("lifecycle", owner)
                put("fixedVoicePreface", fixed)
                put("modelSpec", ModelCatalog.gemma4E2b)
                put("modelId", ModelCatalog.gemma4E2b.id)
                put("audioEnabled", true)
            }
        }
        val state = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = this@Fixture.engine
            override var hasContext = true
            override var characters = 1000
        }
        val shortTerm = ShortTermConversationContext()
        val session = ConversationModelSession(state, { ModelCatalog.gemma4E2b }, { true }, {}, { true },
            { "unused" }, "unused", shortTerm, {})
        val reply = ConversationReply("mode", PipelineBenchmarkCapture("mode", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), false, MemoryDeliveryFence(), { it() }, {}, {}, {}, {}, { _, _, _ -> })
        init {
            owner.initialize {}
            val turn = owner.beginTurn()
            owner.finishTurn(turn, { true })
            events.clear()
            session.beforeNativeMutation = { events += "fence" }
        }
    }

    @Test fun typedFollowupAfterDirectAudioReseedsHistoryBeforePromptAssembly() = runBlocking {
        val f = Fixture()
        val history = listOf(ChatEntry("You", "Tell me a story."), ChatEntry("Jarvis", "The dragon was named Amber."))
        f.session.prepare(imageAttached = false, audioAttached = false, voiceAttached = false, f.reply)
        assertEquals(listOf("fence", "drain", "close"), f.events)
        assertFalse(f.state.hasContext)
        assertEquals(0, f.state.characters)
        val prompt = ConversationPromptBuilder(f.shortTerm).buildGemmaPrompt("Continue the story.", null,
            history, seedContext = !f.state.hasContext)
        assertTrue(prompt.contains("The dragon was named Amber."))
        assertFalse(f.engine.prepareOrdinaryConversation())
        assertEquals(1, f.events.count { it == "close" })
    }

    @Test fun unchangedDirectVoiceRetainsPreparationAndDoesNotDrain() = runBlocking {
        val f = Fixture()
        f.session.prepare(imageAttached = false, audioAttached = false, voiceAttached = true, f.reply)
        assertTrue(f.state.hasContext)
        assertEquals(1000, f.state.characters)
        assertTrue(f.events.isEmpty())
    }

    @Test fun ordinaryTextHistoryDoesNotResetWhenNoFixedPrefaceExists() = runBlocking {
        val f = Fixture(fixed = false)
        f.session.prepare(imageAttached = false, audioAttached = false, voiceAttached = false, f.reply)
        assertTrue(f.state.hasContext)
        assertEquals(1000, f.state.characters)
        assertTrue(f.events.isEmpty())
    }

    @Test fun failedMutationFenceKeepsNativeHistoryAndAccountingOwned() = runBlocking {
        val f = Fixture()
        f.session.beforeNativeMutation = { error("native_speculation_not_drained") }
        val failure = runCatching {
            f.session.prepare(imageAttached = false, audioAttached = false, voiceAttached = false, f.reply)
        }.exceptionOrNull()
        assertEquals("native_speculation_not_drained", failure?.message)
        assertTrue(f.state.hasContext)
        assertEquals(1000, f.state.characters)
        assertTrue(f.events.isEmpty())
        assertTrue(f.engine.nativeResourcesSafeToRelease)
    }
}
