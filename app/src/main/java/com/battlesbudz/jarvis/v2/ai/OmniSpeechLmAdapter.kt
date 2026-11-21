package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * Seam 3 (U2 x U4 integration): adapts U2's [OmniLmRunner] to U4's [OmniSpeechLm]
 * so [OmniSpeechGenerator] can run its interleaved text/speech loop on the
 * dedicated Omni runtime.
 *
 * Mapping:
 * - [OmniSpeechLm.embedToken] -> [OmniLmRunner.embedToken] (same contract).
 * - [OmniSpeechLm.step] -> [OmniLmRunner.step]: the runner's `decode`
 *   signature always produces both hidden state and text logits, so the
 *   `logits` flag is advisory only — it records whether the generator will
 *   read [OmniSpeechLm.textLogits] before the next step, but the runner
 *   cannot run a logits-free step and [OmniLmRunner.textLogits] is valid
 *   after any step (and after the caller's final prefill, which is what
 *   supplies the generator's initial hidden state).
 * - [OmniSpeechLm.textLogits] -> [OmniLmRunner.textLogits].
 *
 * All calls must run on the single dedicated Omni worker thread, like every
 * other [OmniLmRunner] call; this adapter adds no threading of its own.
 * Options/environment come from the caller, normally
 * [OmniRuntime.cpuOptions] / [OmniRuntime.createEnvironment].
 */
internal class OmniSpeechLmAdapter(private val runner: OmniLmRunner) : OmniSpeechLm {
    override fun embedToken(token: Int): FloatArray = runner.embedToken(token)
    override fun step(embedding: FloatArray, logits: Boolean): FloatArray = runner.step(embedding)
    override fun textLogits(): FloatArray = runner.textLogits()
}

/** Owns the U4 speech graphs behind one [OmniSpeechGenerator]. Close with the call. */
internal class OmniSpeechStack(
    val generator: OmniSpeechGenerator,
    private val depth: CompiledDepthFrame2,
    private val frame: CompiledFrameEmbedder,
    private val codec: CompiledCodecDecoder,
) : AutoCloseable {
    override fun close() {
        try {
            depth.close()
        } finally {
            try {
                frame.close()
            } finally {
                codec.close()
            }
        }
    }
}

/**
 * Builds the U4 speech stack (depth-frame sampler, frame embedder, stateful
 * codec decoder, overlap-add stream, generator) on top of an [OmniLmRunner].
 *
 * The codec graph's exact state input names ([codecStateNames]) must come
 * from the loaded codec graph's `decode` signature at runtime — no unit
 * supplies a default list and none is invented here. The caller (the U5
 * call owner, which loads the codec graph file) reads them from the graph
 * signature and passes them in; [CompiledCodecDecoder] binds each state's
 * `${name}_out` output the same way. Depth runs on [depthOptions] (GPU fp16
 * on Qualcomm per DESIGN section 2); frame embedding and the codec decoder
 * run on CPU per the same section, each with its own [OmniRuntime.cpuOptions]
 * (a per-graph XNNPACK weight-cache file — the options are not shareable
 * across graphs).
 */
internal object OmniSpeechOutputFactory {
    fun createStack(
        runner: OmniLmRunner,
        depthFile: File,
        frameFile: File,
        codecFile: File,
        depthOptions: CompiledModel.Options,
        frameOptions: CompiledModel.Options,
        codecOptions: CompiledModel.Options,
        environment: Environment,
        codecStateNames: List<String>,
        codecWindow: FloatArray,
        sink: OmniPcmSink,
        frameEmbedSignature: String = "frame_embed",
        /** Per-graph create breadcrumbs (the call owner wires these to diagnostics; no-op by default). */
        onStage: (String) -> Unit = {},
    ): OmniSpeechStack {
        require(codecStateNames.isNotEmpty()) {
            "codec decode state names must come from the loaded graph signature"
        }
        // One breadcrumb on each side of every native create: the depth
        // create runs on the GPU delegate (the least-proven call in the
        // stack) and the frame/codec creates write their XNNPACK weight
        // caches on first run — a silent death in any of them must name
        // the graph, not just the stage.
        onStage("speechStage=depthCreateStarted")
        val depth = CompiledDepthFrame2(depthFile, depthOptions, environment)
        onStage("speechStage=depthCreated")
        onStage("speechStage=frameCreateStarted")
        val frame = CompiledFrameEmbedder(frameFile, frameOptions, environment, frameEmbedSignature)
        onStage("speechStage=frameCreated")
        onStage("speechStage=codecCreateStarted")
        val codec = CompiledCodecDecoder(codecFile, codecOptions, environment, codecStateNames)
        onStage("speechStage=codecCreated")
        val stream = OmniCodecStream(codec, codecWindow, sink)
        return OmniSpeechStack(OmniSpeechGenerator(OmniSpeechLmAdapter(runner), depth, frame, stream), depth, frame, codec)
    }
}
