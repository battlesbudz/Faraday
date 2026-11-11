package com.battlesbudz.jarvis.v2

import android.content.Context
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelStore

/**
 * The narrow bridge between the scheduled memory dream pass and the
 * app's one shared engine slot (single-engine redesign, 2026-11-10).
 *
 * Process-runtime access belongs to composition, so the memory layer
 * never reaches for the runtime object itself. The runtime installs
 * its [Access] here when its class loads; until then [access] is
 * null, which can only mean no runtime has been constructed in this
 * process — no engine is resident and no voice session exists.
 */
internal object EngineSlot {
    @Volatile var access: Access? = null

    interface Access {
        /** True while the shared slot holds an engine, whatever it is doing. */
        fun engineLoaded(): Boolean

        /** True while a voice session is live or wake-word listening is armed. */
        fun sessionActive(): Boolean

        /**
         * Creates the judge engine (the dream's historical configuration:
         * no tools, no vision, no audio) into the shared slot and returns
         * it. The caller must [unloadJudgeEngine] on every exit path.
         */
        suspend fun loadJudgeEngine(app: Context, spec: LocalModelSpec, store: ModelStore): LocalModelEngine

        /** Clears the shared slot, then closes the engine it held. */
        suspend fun unloadJudgeEngine()
    }
}
