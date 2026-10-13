package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LiveTokenRateEstimator
import com.battlesbudz.jarvis.v2.diagnostics.InferenceTiming
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult

/** One invocation's text-free native timing edges and throttled estimated decode progress. */
internal class ConversationInferenceTelemetry(
    private val invocation: ConversationInvocation,
    private val callbacks: ConversationCallbacks,
    private val reply: ConversationReply,
    private val diagnostics: ConversationDiagnostics
) {
    private val liveRate = LiveTokenRateEstimator { System.nanoTime() / 1_000_000 }
    private var lastLiveRate: Double? = null

    fun attach(engine: ConversationBackend, history: List<ChatEntry>, capture: ConversationMemoryResult?) {
        engine.onBenchmarkSubmission = reply.benchmark::submission
        engine.benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        if (invocation.voiceAudio == null && !invocation.callOwned) {
            var submission = 0
            engine.onPromptSubmitted = { exact, audioBytes ->
                diagnostics.inferencePrompt("turn=${reply.id} submission=${++submission} mode=text " +
                    "recordedByBuild=${BuildConfig.VERSION_NAME} model=${engine.modelId} " +
                    "audioBytes=$audioBytes promptChars=${exact.length} historyEntries=${history.size} " +
                    "capture=${capture?.outcome ?: "unavailable"}\n${engine.inputContextDescription()}\n" +
                    "--- Exact submitted text begins ---\n$exact\n--- Exact submitted text ends ---")
            }
        }
    }

    fun begin(engine: ConversationBackend) {
        invocation.comparison?.mark("answer_submit")
        val progressSink: (com.battlesbudz.jarvis.v2.ai.InferenceProgress) -> Unit = { progress ->
            progress.submittedAtMs?.let { callbacks.onLiveInference(it, null, null, true) }
            progress.firstRawTokenAtMs?.let {
                liveRate.rawToken(it)
                invocation.comparison?.mark("answer_first_token")
                callbacks.onLiveInference(null, it, null, true)
            }
        }
        engine.onInferenceProgress = progressSink
        invocation.nativeSpeculation?.bindProgress(progressSink)
    }

    fun token(text: String) {
        liveRate.addRawChunk(text)?.let {
            lastLiveRate = it
            callbacks.onLiveInference(null, null, it, false)
        }
    }

    fun record(label: String, result: GenerationResult, promptChars: Int) {
        reply.benchmark.metric("inference_passes_completed", reply.inferencePasses.size + 1)
        invocation.comparison?.put("inference_$label", "nativeTTFTMs=${result.timeToFirstTokenMs} totalMs=${result.totalGenerationTimeMs} nativeSubmitMs=${result.nativeSubmitMs} firstCallbackMs=${result.firstCallbackMs}")
        reply.inferencePasses += InferenceTiming.from(label, result, prepared = label == "answer" && invocation.nativeSpeculation?.promoted == true)
        diagnostics.summary("Inference\n" + "stage=$label\n" + "promptChars=$promptChars\n" +
            "timeToFirstTokenMs=${result.timeToFirstTokenMs}\n" +
            "nativeSubmitMs=${result.nativeSubmitMs} firstCallbackMs=${result.firstCallbackMs}\n" +
            "totalGenerationTimeMs=${result.totalGenerationTimeMs}\n" +
            "outputTokensEstimated=${result.outputTokens ?: -1}\n" +
            "streamEvents=${result.streamEvents}\n" +
            "decodeTokensPerSecondEstimated=${result.decodeTokensPerSecond ?: -1.0}")
    }

    fun terminal(result: GenerationResult) {
        callbacks.onLiveInference(null, null, result.decodeTokensPerSecond ?: lastLiveRate, true)
    }
    fun finishProgress() { lastLiveRate?.let { callbacks.onLiveInference(null, null, it, true) } }
}
