package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import com.battlesbudz.jarvis.v2.EngineSlot
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngineFactory
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore

/**
 * The dream pass's view of the app's one shared engine slot
 * (single-engine redesign, 2026-11-10; dream rule in Battles's words the
 * same day).
 *
 * The pass no longer builds a private engine when the app is alive. It
 * borrows the shared slot under a strict rule:
 *
 * - No engine resident: the judge model is loaded into the slot for the
 *   pass and unloaded again afterwards, leaving nothing behind.
 * - An engine resident, or a voice session live or wake-armed: the pass
 *   does not touch the slot at all. A resident engine and its loaded
 *   model are left exactly as they are — never swapped, never shut
 *   down for the dream — and the pass defers to a later night.
 *
 * Runtime access itself belongs to composition: this class talks to
 * the slot only through the narrow [EngineSlot] bridge the runtime
 * installs. When no bridge is installed, no runtime has been
 * constructed in this process (a cold WorkManager start): nothing is
 * resident and nothing can be disturbed, so the pass falls back to a
 * temporary engine for that one pass — the pre-redesign shape — and
 * closes it on the way out.
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

/** The production slot: the shared engine, reached through [EngineSlot]. */
internal class RuntimeDreamEngineSlot(private val app: Context) : DreamEngineSlot {
    /** Cold-process fallback only; see the class doc. */
    private var temporaryEngine: LocalModelEngine? = null

    override fun engineLoaded(): Boolean =
        EngineSlot.access?.engineLoaded() ?: false

    override fun sessionActive(): Boolean =
        EngineSlot.access?.sessionActive() ?: false

    override suspend fun loadJudgeEngine(spec: LocalModelSpec, store: ModelStore): LocalModelEngine {
        EngineSlot.access?.let { return it.loadJudgeEngine(app, spec, store) }
        val engine = LiteRtLmEngineFactory.createInitialized(
            modelId = spec.id,
            modelPath = store.fileFor(spec).path,
            cacheDir = store.engineCacheRoot().path,
            useGpu = spec.recommendedGpu,
            tools = emptyList(),
            visionEnabled = false,
            audioEnabled = false,
        )
        temporaryEngine = engine
        return engine
    }

    override suspend fun unloadJudgeEngine() {
        // The fallback engine is this pass's own, whichever path load
        // took by the time unload runs; close it directly.
        val temporary = temporaryEngine
        if (temporary != null) {
            temporaryEngine = null
            runCatching { temporary.close() }
            return
        }
        EngineSlot.access?.unloadJudgeEngine()
    }
}
