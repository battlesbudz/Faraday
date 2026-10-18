package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.Content

/**
 * Effect-free adapter for a reviewed, immutable capture proposal. This is not an
 * admission route: the caller must hold the ordinary voice model lease, finish
 * and checked-close its encoder, and exclude every other native borrower.
 *
 * Only NativeVoiceSpeculation may admit a capture-owned frozen boundary. The
 * original complete recording remains available for cancellation/mismatch fallback.
 */
internal class NativeSpeculativeResponseBackend(private val engine: LiteRtLmEngine,
    private val progress: (InferenceProgress) -> Unit = {}) {
    data class Input(val exactPrompt: String, val audio: Content.SealedAudioEmbeddings)

    suspend fun generate(input: Input, onToken: (String) -> Unit): GenerationResult {
        check(!engine.isNativeQuarantined && engine.nativeResourcesSafeToRelease)
        engine.resetForVoiceSpeculation()
        engine.setToolsEnabled(false)
        val previousProgress = engine.onInferenceProgress
        engine.onInferenceProgress = progress
        val previousPurpose = engine.benchmarkPurpose
        engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.DRAFT
        return try {
            // Uses the existing beginTurn/finishTurn/awaitIdle path, not an SDK
            // side door or second Conversation. No action executor is reachable.
            engine.generateSealedAudio(input.exactPrompt, input.audio, onToken, maxPendingCallbacks = 64)
        } finally {
            if (engine.onInferenceProgress === progress) engine.onInferenceProgress = previousProgress
            if (engine.benchmarkPurpose == com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.DRAFT)
                engine.benchmarkPurpose = previousPurpose
        }
    }

    suspend fun rollback() {
        // Called only after generate has joined its native terminal/callbacks.
        // A checked-drain failure is quarantined by the ordinary engine lifecycle.
        check(!engine.isNativeQuarantined && engine.nativeResourcesSafeToRelease)
        engine.resetConversation()
    }

    fun nativeSafeToRelease(): Boolean = !engine.isNativeQuarantined && engine.nativeResourcesSafeToRelease
}
