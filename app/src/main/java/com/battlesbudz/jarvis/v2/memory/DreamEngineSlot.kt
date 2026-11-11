package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngineFactory
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore

/**
 * The dream pass's view of the app's one shared engine slot
 * (single-engine redesign, 2026-11-10; dream rule in Battles's words the
 * same day).
 *
 * The pass no longer builds a private engine. It borrows the shared
 * slot under a strict rule:
 *
 * - No engine resident: the judge model is loaded into the slot for the
 *   pass and unloaded again afterwards, leaving nothing behind.
 * - An engine resident, or a voice session live or wake-armed: the pass
 *   does not touch the slot at all. A resident engine and its loaded
 *   model are left exactly as they are — never swapped, never shut
 *   down for the dream — and the pass defers to a later night.
 */
internal interface DreamEngineSlot {
    /** True while the shared slot holds an engine, whatever it is doing. */
    fun engineLoaded(): Boolean

    /** True while a voice session is live or wake-word listening is armed. */
    fun sessionActive(): Boolean

    /**
     * Creates the judge engine (the dream's historical configuration: no
     * tools, no vision, no audio) into the shared slot and returns it.
     * The caller must [unloadJudgeEngine] on every exit path.
     */
    suspend fun loadJudgeEngine(spec: LocalModelSpec, store: ModelStore): LocalModelEngine

    /** Clears the shared slot, then closes the engine it held. */
    suspend fun unloadJudgeEngine()
}

/** The production slot: [JarvisRuntime.conversationEngine] and its accounting. */
internal class RuntimeDreamEngineSlot(private val app: Context) : DreamEngineSlot {
    override fun engineLoaded(): Boolean =
        JarvisRuntime.peekInstance()?.conversationEngine != null

    override fun sessionActive(): Boolean {
        val runtime = JarvisRuntime.peekInstance() ?: return false
        return runtime.voiceSessionArmed || runtime.voiceSessionController.currentCallId() != null
    }

    override suspend fun loadJudgeEngine(spec: LocalModelSpec, store: ModelStore): LocalModelEngine {
        // Building the runtime is acceptable only on this path: the pass
        // is actually running, and the engine it loads has to live in the
        // runtime's slot. The read-only checks above never construct it.
        val runtime = JarvisRuntime.get(app)
        check(runtime.conversationEngine == null) { "dream_engine_slot_occupied" }
        val engine = LiteRtLmEngineFactory.createInitialized(
            modelId = spec.id,
            modelPath = store.fileFor(spec).absolutePath,
            cacheDir = store.engineCacheRoot().absolutePath,
            useGpu = spec.recommendedGpu,
            tools = emptyList(),
            visionEnabled = false,
            audioEnabled = false,
        )
        runtime.conversationEngine = engine
        runtime.nativeConversationHasContext = false
        runtime.conversationCharacters = 0
        return engine
    }

    override suspend fun unloadJudgeEngine() {
        val runtime = JarvisRuntime.peekInstance() ?: return
        val engine = runtime.conversationEngine ?: return
        // Clear the slot before closing, so nothing can pick up an
        // engine that is already on its way down.
        runtime.conversationEngine = null
        runtime.nativeConversationHasContext = false
        runtime.conversationCharacters = 0
        runCatching { engine.close() }
    }
}
