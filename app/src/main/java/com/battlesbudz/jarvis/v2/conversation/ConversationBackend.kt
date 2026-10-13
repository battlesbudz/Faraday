package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission

/** Native inference boundary used by answer/recovery stages, with the same synchronous ownership. */
internal interface ConversationBackend : LocalModelEngine {
    var onPromptSubmitted: (String, Int) -> Unit
    var onInferenceProgress: (InferenceProgress) -> Unit
    var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit
    var benchmarkPurpose: PipelineBenchmarkPurpose
    fun inputContextDescription(): String
    suspend fun setToolsEnabled(enabled: Boolean): Boolean
    suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult
    suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult
    suspend fun generateSealedAudio(prompt: String, audio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings,
                                    onToken: (String) -> Unit): GenerationResult = error("Sealed native audio is unsupported by this backend")
    suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult
}

/** Wraps the resident engine; creating this adapter never creates/replaces another native model. */
internal class LiteRtConversationBackend(private val engine: LiteRtLmEngine) : ConversationBackend {
    override val modelId get() = engine.modelId
    override var onPromptSubmitted: (String, Int) -> Unit
        get() = engine.onPromptSubmitted
        set(value) { engine.onPromptSubmitted = value }
    override var onInferenceProgress: (InferenceProgress) -> Unit
        get() = engine.onInferenceProgress
        set(value) { engine.onInferenceProgress = value }
    override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit
        get() = engine.onBenchmarkSubmission
        set(value) { engine.onBenchmarkSubmission = value }
    override var benchmarkPurpose: PipelineBenchmarkPurpose
        get() = engine.benchmarkPurpose
        set(value) { engine.benchmarkPurpose = value }
    override fun inputContextDescription() = engine.inputContextDescription()
    override suspend fun setToolsEnabled(enabled: Boolean) = engine.setToolsEnabled(enabled)
    override suspend fun generate(prompt: String, onToken: (String) -> Unit) = engine.generate(prompt, onToken)
    override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit) = engine.generate(prompt, imageBytes, onToken)
    override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit) = engine.generateAudio(prompt, audioBytes, onToken)
    override suspend fun generateSealedAudio(prompt: String, audio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings,
                                            onToken: (String) -> Unit) = engine.generateSealedAudio(prompt, audio, onToken)
    override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit) = engine.sendToolResults(results, onToken)
}
