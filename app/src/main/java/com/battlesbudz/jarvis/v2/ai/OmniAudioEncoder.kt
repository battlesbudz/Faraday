package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * Drives the `audio/encode` graph in the Omni bundle. Keep this on OmniRuntime's
 * serial worker: CompiledModel instances and their reusable buffers are not
 * thread-safe. Input is normalized PCM16k mono represented as float samples.
 *
 * The graph's tensor geometry comes from [OmniAudioPlan] (verified sections
 * map), never from CompiledModel tensor-type queries — the load-path rule
 * the decoder init established (see [OmniStatePlan]). The buffer creations
 * below are the init's first native calls after the model create.
 */
internal class OmniAudioEncoder(
    sectionFile: File,
    environment: Environment,
    options: CompiledModel.Options,
    plan: OmniAudioPlan,
) : AutoCloseable {
    private val model = CompiledModel.create(
        sectionFile.absolutePath,
        options,
        environment,
    )
    private val signature = "encode"
    private val audioInput = model.createInputBuffer("audio", signature)
    private val featuresOutput = model.createOutputBuffer("features", signature)
    private val maskOutput = model.createOutputBuffer("mask", signature)
    private val audioWindowSamples = plan.windowSamples
    private val hiddenSize = plan.hiddenSize

    init {
        require(audioWindowSamples > 0) { "Omni audio encoder has an empty audio input" }
        require(hiddenSize > 0) { "Omni audio encoder has an empty feature dimension" }
    }

    /** Encodes a complete turn in ≤30-second graph windows and returns only mask-valid rows. */
    fun encode(pcm16kMono: FloatArray): List<FloatArray> {
        require(pcm16kMono.size <= MAX_INPUT_SAMPLES) {
            "Omni audio input exceeds the 30-second turn limit"
        }
        require(pcm16kMono.all { it.isFinite() }) { "Omni audio input contains non-finite samples" }
        if (pcm16kMono.isEmpty()) return emptyList()

        val rows = ArrayList<FloatArray>()
        OmniAudioWindowing.forEach(pcm16kMono, audioWindowSamples) { window, _ ->
            audioInput.writeFloat(window)
            model.run(
                mapOf("audio" to audioInput),
                mapOf("features" to featuresOutput, "mask" to maskOutput),
                signature,
            )

            rows += selectValidOmniAudioRows(featuresOutput.readFloat(), maskOutput.readBoolean(), hiddenSize)
        }
        return rows
    }

    override fun close() {
        audioInput.close()
        featuresOutput.close()
        maskOutput.close()
        model.close()
    }

    internal companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_INPUT_MS = 30_000
        const val MAX_INPUT_SAMPLES = SAMPLE_RATE_HZ * MAX_INPUT_MS / 1_000

        /** Converts the canonical little-endian mono PCM16 used by AudioInput to normalized float samples. */
        fun pcm16ToFloat(pcm16: ByteArray): FloatArray {
            require(pcm16.size % 2 == 0) { "PCM16 input ends with a partial sample" }
            return FloatArray(pcm16.size / 2) { index ->
                val low = pcm16[index * 2].toInt() and 0xff
                val high = pcm16[index * 2 + 1].toInt()
                ((high shl 8) or low).toShort().toInt() / 32768f
            }
        }
    }
}

/**
 * The audio encoder's tensor geometry: the `encode` signature's fixed input
 * window and its feature width. Constants of the pinned bundle's audio
 * graph, read out of its flatbuffer once, offline, and carried in the
 * verified sections map (lfm3_omni_sections.json `audio_signatures` —
 * bound to the bundle's SHA-256, which [OmniRuntime] checks before
 * anything in the map is used): input `audio` is [1, 3000, 160] FLOAT32
 * (480,000 elements — one 30-second window of 16 kHz samples, framed),
 * output `features` is [1, 375, 2048] FLOAT32 (hidden size 2048).
 *
 * The encoder's init deliberately never queries the graph for them: the
 * decoder init's tensor-type queries were the prime suspect in the
 * 1944–1965 native deaths, and build 1966 removed every one of them from
 * the load path (see [OmniStatePlan]). The encoder was the next stage the
 * call reaches, so its two queries go the same way.
 */
internal class OmniAudioPlan private constructor(
    /** Element count of one full `encode` input window. */
    val windowSamples: Int,
    /** Last dimension of the `features` output: one encoded row's width. */
    val hiddenSize: Int,
) {
    companion object {
        fun build(windowSamples: Int, hiddenSize: Int): OmniAudioPlan {
            require(windowSamples > 0) { "audio window must be positive" }
            require(hiddenSize > 0) { "audio hidden size must be positive" }
            return OmniAudioPlan(windowSamples, hiddenSize)
        }
    }
}

internal fun selectValidOmniAudioRows(features: FloatArray, mask: BooleanArray, hiddenSize: Int): List<FloatArray> {
    require(hiddenSize > 0 && features.size % hiddenSize == 0) {
        "Omni audio feature output is not a whole number of rows"
    }
    require(mask.size == features.size / hiddenSize) {
        "Omni audio feature and mask row counts differ"
    }
    // The sample takes a prefix: rows up to the last mask-valid row
    // ("LiteRT-LM's rule: up to the last 1"). Filtering instead would
    // silently drop interior rows and compress the audio timeline.
    val lastValid = mask.indexOfLast { it }
    if (lastValid < 0) return emptyList()
    return (0..lastValid).map { row ->
        val start = row * hiddenSize
        features.copyOfRange(start, start + hiddenSize)
    }
}

/** Fixed graph input windows with zero padding on the final partial window. */
internal object OmniAudioWindowing {
    fun forEach(input: FloatArray, windowSamples: Int, consume: (FloatArray, Int) -> Unit) {
        require(windowSamples > 0) { "Omni audio graph has an empty input window" }
        var from = 0
        while (from < input.size) {
            val validSamples = minOf(windowSamples, input.size - from)
            val window = FloatArray(windowSamples)
            input.copyInto(window, 0, from, from + validSamples)
            consume(window, validSamples)
            from += validSamples
        }
    }
}

