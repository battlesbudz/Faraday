package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.NativeSpeculativeResponseBackend
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serial native encoder→Conversation handoff; no concurrent model owner or cache rewind. */
internal class NativeSpeculativeAudioDriver(
    private val engine: LiteRtLmEngine,
    private val capture: GemmaStreamingAudioCapture,
) : NativeSpeculationDriver {
    private val encoderDrained = AtomicBoolean(false)
    private val completed = AtomicReference<GemmaStreamingAudioCapture.CompletedCapture?>(null)
    private var backend: NativeSpeculativeResponseBackend? = null

    override suspend fun generate(proposal: NativePauseProposal, exactPrompt: String,
                                  onToken: (String) -> Unit, onProgress: (InferenceProgress) -> Unit): GenerationResult {
        val encoded = capture.sealFrozenCandidate(proposal)
        completed.set(encoded)
        // seal returns only after the exact encoder has checked-closed. The
        // initialized Gemma engine is the same one held by this voice turn.
        val next = NativeSpeculativeResponseBackend(engine, onProgress)
        backend = next
        return next.generate(NativeSpeculativeResponseBackend.Input(exactPrompt, encoded.content), onToken)
    }

    override suspend fun rollback() {
        // A cancelled Kotlin seal may leave synchronous JNI on the independent
        // encoder worker. Its checked drain precedes ALL subsequent model work.
        encoderDrained.set(capture.drainFrozenWorker())
        check(encoderDrained.get()) { "Speculative encoder did not drain" }
        (backend ?: NativeSpeculativeResponseBackend(engine)).rollback()
    }
    override fun requestCancel() = capture.requestCancel()
    override fun safeToRelease() = encoderDrained.get() && !engine.isNativeQuarantined && engine.nativeResourcesSafeToRelease
    override fun timing() = completed.get()?.timing
}
