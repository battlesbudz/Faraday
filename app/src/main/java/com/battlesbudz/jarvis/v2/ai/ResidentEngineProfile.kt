package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.google.ai.edge.litertlm.OpenApiTool

/**
 * The one configuration the app's single resident engine is built with,
 * whichever surface creates it first (single-engine redesign, 2026-11-10).
 *
 * Engine capabilities are frozen at construction, so before this profile
 * existed each surface built the shared slot's engine in its own image —
 * chat without audio, code mode without audio, calls with audio — and the
 * next surface destroyed and rebuilt the engine whenever the resident
 * configuration did not match its own needs. The resident engine is now
 * always built in the voice configuration: the audio encoder is wired in
 * whenever the selected model supports audio, and the phone tools are
 * attached whenever the model supports them, so an engine created by any
 * surface serves every surface and is never rebuilt over capabilities.
 *
 * Vision is the one deliberate exception: an image turn may ask for a
 * vision-built engine ([visionRequired]), because the vision subgraph is
 * only ever needed by turns that carry an image. A non-image turn never
 * rebuilds an engine over vision either — a vision-built resident simply
 * serves it — so vision can add a capability to the resident engine but
 * can never take one away.
 */
internal data class ResidentEngineProfile(
    val tools: List<OpenApiTool>,
    val visionEnabled: Boolean,
    val audioEnabled: Boolean,
) {
    companion object {
        /** The resident profile for [spec]; [visionRequired] only for image-carrying turns. */
        fun forModel(spec: LocalModelSpec, visionRequired: Boolean = false): ResidentEngineProfile =
            ResidentEngineProfile(
                tools = if (spec.supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                visionEnabled = visionRequired && spec.supportsVision,
                audioEnabled = spec.supportsAudio,
            )
    }
}

/**
 * Creates the resident engine for [spec] under the canonical
 * [ResidentEngineProfile]. Every resident-engine creation site (chat,
 * code mode, voice-call preparation) goes through here so the profile
 * cannot drift apart again.
 */
internal suspend fun createResidentEngine(
    spec: LocalModelSpec,
    modelPath: String,
    cacheDir: String,
    visionRequired: Boolean = false,
): LiteRtLmEngine {
    val profile = ResidentEngineProfile.forModel(spec, visionRequired)
    return LiteRtLmEngineFactory.createInitialized(
        modelId = spec.id,
        modelPath = modelPath,
        cacheDir = cacheDir,
        useGpu = spec.recommendedGpu,
        tools = profile.tools,
        visionEnabled = profile.visionEnabled,
        audioEnabled = profile.audioEnabled,
    )
}
