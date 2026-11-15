package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * LFM3-Omni U0 coexistence spike (DESIGN.md sections 1, 7-U0, 8.1): the
 * smallest compile-level touchpoint proving Maven LiteRT 2.3.0's
 * [CompiledModel] API links and packages beside the reviewed LiteRT-LM
 * 0.18.1 streaming bridge AAR. Speech generation cannot go through
 * LiteRT-LM (its Conversation API feeds token ids, never embeddings), so
 * the Omni path drives the .litertlm bundle's sections directly through
 * this API on a dedicated runtime (DESIGN.md section 2).
 *
 * Fenced: no production callers, and nothing here runs in U0. The
 * proguard keep on this object exists only so the release build retains
 * the linkage this spike proves. U2 replaces this stub with the real
 * OmniRuntime (section extraction, LmRunner, host-owned state).
 */
internal object OmniRuntime {

    /** CPU options for one bundle section; the Omni LM runs on CPU (DESIGN.md section 2). */
    fun cpuOptions(): CompiledModel.Options = CompiledModel.Options(Accelerator.CPU)

    /** The LiteRT environment the Omni graphs run in. Spike-only; never called in U0. */
    fun createEnvironment(): Environment = Environment.create()

    /**
     * Opens one extracted bundle section (decoder, embedder, audio) through
     * the CompiledModel API, the call shape the photo_voice_chat sample's
     * LmRunner uses. Spike-only; never called in U0.
     */
    fun openSection(section: File, environment: Environment): CompiledModel =
        CompiledModel.create(section.absolutePath, cpuOptions(), environment)
}
