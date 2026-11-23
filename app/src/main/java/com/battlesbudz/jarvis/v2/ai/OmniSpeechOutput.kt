package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.util.ArrayDeque
import java.util.SplittableRandom
import kotlin.math.exp

/** Speech graph and host-loop contracts for the dedicated Omni runtime. All graph calls are synchronous. */
internal object OmniSpeechTokens {
    const val IM_END = 124900
    const val IMAGE = 125030
    const val VIDEO = 125029
    const val AUDIO = 125031
    const val AUDIO_OUT = 125032
    const val AUDIO_START = 125012
    const val TEXT_END = 125014
    const val MIXED_START = 125015
    const val CODEBOOKS = 12
    const val CODEBOOK_SIZE = 2017
    const val AUDIO_EOS = 2016
    const val TEXT_SLOTS = 6
    const val AUDIO_SLOTS = 12
    val NEVER_SAMPLE = intArrayOf(AUDIO, AUDIO_OUT, IMAGE, VIDEO)
}

/** RNG sequence matches the official Kotlin sample. */
internal class OmniSpeechRng(seed: Long) {
    private val random = SplittableRandom(seed)
    fun nextDouble(): Double = random.nextDouble()
    fun permutation(size: Int): IntArray {
        val order = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        return order
    }
}

/** Direct reference sampler, retaining the sample's operation order for check-mode comparisons. */
internal object OmniReferenceSampler {
    fun sampleAt(logits: FloatArray, offset: Int, size: Int, temperature: Float, topK: Int,
                 penalty: Float, counts: IntArray?, suppress: IntArray?, draw: Double): Int {
        val scores = FloatArray(size) { logits[offset + it] }
        if (counts != null && penalty != 1f) for (i in 0 until size) {
            if (counts[i] > 0) scores[i] = if (scores[i] < 0f) scores[i] * penalty else scores[i] / penalty
        }
        suppress?.forEach { if (it in 0 until size) scores[it] = Float.NEGATIVE_INFINITY }
        if (temperature == 0f) return argmax(scores)
        for (i in scores.indices) scores[i] /= temperature
        if (topK in 1 until size) {
            val sorted = scores.copyOf(); sorted.sort(); val threshold = sorted[size - topK]
            for (i in scores.indices) if (scores[i] < threshold) scores[i] = Float.NEGATIVE_INFINITY
        }
        val max = scores.maxOrNull() ?: Float.NEGATIVE_INFINITY
        val weights = DoubleArray(size)
        var total = 0.0
        for (i in scores.indices) {
            weights[i] = if (scores[i] == Float.NEGATIVE_INFINITY) 0.0 else exp((scores[i] - max).toDouble())
            total += weights[i]
        }
        val target = draw * total
        var cumulative = 0.0
        var last = -1
        for (i in weights.indices) if (weights[i] > 0.0) {
            cumulative += weights[i]; last = i
            if (target < cumulative) return i
        }
        return last
    }

    fun argmax(scores: FloatArray): Int {
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
        return best
    }
}

/** Allocation-free fast sampler; check mode returns the reference choice and counts any disagreement. */
internal class OmniFastSampler(private val capacity: Int) {
    private val scores = FloatArray(capacity)
    private val order = FloatArray(capacity)
    private val weights = DoubleArray(capacity)
    var mismatches: Long = 0
        private set

    fun sample(logits: FloatArray, offset: Int, size: Int, temperature: Float, topK: Int,
               penalty: Float, counts: IntArray?, draw: Double, checkReference: Boolean): Int {
        for (i in 0 until size) scores[i] = logits[offset + i]
        if (counts != null && penalty != 1f) for (i in 0 until size) {
            if (counts[i] > 0) scores[i] = if (scores[i] < 0f) scores[i] * penalty else scores[i] / penalty
        }
        if (temperature == 0f) {
            var best = 0
            for (i in 1 until size) if (scores[i] > scores[best]) best = i
            return best
        }
        for (i in 0 until size) scores[i] /= temperature
        val cut = topK in 1 until size
        val threshold = if (cut) {
            System.arraycopy(scores, 0, order, 0, size)
            select(size, size - topK)
        } else Float.NEGATIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until size) if ((!cut || scores[i] >= threshold) && scores[i] > max) max = scores[i]
        var total = 0.0
        for (i in 0 until size) {
            val v = scores[i]
            val w = if ((cut && v < threshold) || v == Float.NEGATIVE_INFINITY) 0.0 else exp((v - max).toDouble())
            weights[i] = w; total += w
        }
        val target = draw * total
        var acc = 0.0
        var last = -1
        var fast = -1
        for (i in 0 until size) if (weights[i] > 0.0) {
            acc += weights[i]; last = i
            if (fast < 0 && target < acc) fast = i
        }
        if (fast < 0) fast = last
        if (!checkReference) return fast
        val reference = OmniReferenceSampler.sampleAt(logits, offset, size, temperature, topK, penalty, counts, null, draw)
        if (reference != fast) mismatches++
        return reference
    }

    private fun select(size: Int, index: Int): Float {
        var low = 0
        var high = size - 1
        while (high > low) {
            val mid = (low + high) ushr 1
            if (java.lang.Float.compare(order[mid], order[low]) < 0) swap(mid, low)
            if (java.lang.Float.compare(order[high], order[low]) < 0) swap(high, low)
            if (java.lang.Float.compare(order[high], order[mid]) < 0) swap(high, mid)
            val pivot = order[mid]
            var i = low
            var j = high
            while (i <= j) {
                while (java.lang.Float.compare(order[i], pivot) < 0) i++
                while (java.lang.Float.compare(order[j], pivot) > 0) j--
                if (i <= j) { swap(i, j); i++; j-- }
            }
            when {
                index <= j -> high = j
                index >= i -> low = i
                else -> return order[index]
            }
        }
        return order[index]
    }

    private fun swap(i: Int, j: Int) { val value = order[i]; order[i] = order[j]; order[j] = value }
}

internal interface OmniSpeechLm {
    fun embedToken(token: Int): FloatArray
    fun step(embedding: FloatArray, logits: Boolean): FloatArray
    fun textLogits(): FloatArray
}

internal interface OmniDepthFrame {
    fun sample(hidden: FloatArray, presence: FloatArray, reveal: Array<FloatArray>, uniforms: FloatArray): IntArray
}

internal interface OmniFrameEmbedder { fun embed(codes: IntArray): FloatArray }

/** depth_frame2: inputs and output use the official sample/Wren signature names. */
internal class CompiledDepthFrame2(file: File, options: CompiledModel.Options, env: Environment) : OmniDepthFrame, AutoCloseable {
    private val model = CompiledModel.create(file.absolutePath, options, env)
    private val signature = "depth_frame"
    private val hidden = model.createInputBuffer("hidden", signature)
    private val presence = model.createInputBuffer("presence", signature)
    private val reveals = Array(3) { model.createInputBuffer("reveal$it", signature) }
    private val uniform = model.createInputBuffer("u", signature)
    private val codes = model.createOutputBuffer("codes", signature)
    override fun sample(hidden: FloatArray, presence: FloatArray, reveal: Array<FloatArray>, uniforms: FloatArray): IntArray {
        require(reveal.size == 3 && reveal.all { it.size == OmniSpeechTokens.CODEBOOKS })
        this.hidden.writeFloat(hidden); this.presence.writeFloat(presence)
        for (i in reveals.indices) reveals[i].writeFloat(reveal[i])
        uniform.writeFloat(uniforms)
        model.run(mapOf("hidden" to this.hidden, "presence" to this.presence, "reveal0" to reveals[0],
            "reveal1" to reveals[1], "reveal2" to reveals[2], "u" to uniform), mapOf("codes" to codes), signature)
        return codes.readInt()
    }
    override fun close() = model.close()
}

internal class CompiledFrameEmbedder(file: File, options: CompiledModel.Options, env: Environment,
                                    private val signature: String = "frame_embed") : OmniFrameEmbedder, AutoCloseable {
    private val model = CompiledModel.create(file.absolutePath, options, env)
    private val codes = model.createInputBuffer("codes", signature)
    private val embeds = model.createOutputBuffer("embeds", signature)
    override fun embed(codes: IntArray): FloatArray {
        require(codes.size == OmniSpeechTokens.CODEBOOKS)
        this.codes.writeInt(codes)
        model.run(mapOf("codes" to this.codes), mapOf("embeds" to embeds), signature)
        return embeds.readFloat()
    }
    override fun close() = model.close()
}

/** Callback must enqueue quickly; playback consumes its own queue while generation continues. */
internal fun interface OmniPcmSink { fun offer(pcm24k: FloatArray): Boolean }

/** Overlap-adds codec frames and emits the finalized PCM as soon as each 12-frame graph chunk completes. */
internal class OmniCodecStream(private val decoder: OmniCodecDecoder, window: FloatArray, private val sink: OmniPcmSink) {
    private val stream = OmniOverlapAdd(window)
    private val pending = ArrayList<IntArray>(12)
    fun reset() { pending.clear(); decoder.reset(); stream.reset() }
    fun push(codes: IntArray): Boolean {
        pending += codes.copyOf()
        if (pending.size < 12) return true
        return emit(decoder.decode(pending.toList()), false).also { pending.clear() }
    }
    fun finish(): Boolean {
        var ok = true
        if (pending.isNotEmpty()) { ok = emit(decoder.decode(pending.toList()), true); pending.clear() }
        else {
            val tail = stream.finish()
            ok = tail.isEmpty() || sink.offer(tail)
        }
        reset()
        return ok
    }
    private fun emit(frames: FloatArray, final: Boolean): Boolean {
        // The codec graph returns 6 time frames of 1,280 samples per codec
        // frame (the sample's FRAME_STACK = 6), and the overlap-add consumes
        // time frames: the count is derived from the decoded output's size,
        // never from the codec-frame count.
        require(frames.size % 1280 == 0) {
            "codec decoder output is not a whole number of 1280-sample frames: ${frames.size}"
        }
        val pcm = stream.push(frames, frames.size / 1280, final)
        return pcm.isEmpty() || sink.offer(pcm)
    }
}

internal interface OmniCodecDecoder {
    /** Frames are channel-major codes, matching decode(codes)[12, frames] and returns frames[time, 1280]. */
    fun decode(frames: List<IntArray>): FloatArray
    fun reset()
}

/**
 * The codec decoder's state geometry: the element count of every state
 * tensor in the `decode` signature. Constants of the pinned codec graph,
 * read out of its flatbuffer once, offline, and carried in the verified
 * sections map (lfm3_omni_sections.json `codec_signatures`): the five
 * short-convolution states c0–c4 are [1, 6, 512] FLOAT32 (3,072 elements)
 * and the six attention caches k0–k2 / v0–v2 are [1, 2, 29, 64] FLOAT32
 * (3,712 elements).
 *
 * [CompiledCodecDecoder.reset] zeroes its state buffers from these
 * counts; the load path never queries the graph for them, for the same
 * reason the decoder and audio-encoder geometry is pinned (see
 * [OmniStatePlan]): tensor-type queries are out of the stack build.
 */
internal object OmniCodecStatePlan {
    /**
     * Validates the map's element counts against the state names read
     * from the codec graph's own signature: coverage must be exact — a
     * missing name would zero a state buffer to the wrong length, and an
     * extra name means the map and the pinned graph have drifted apart.
     * Both fail the stack build here, in managed code.
     */
    fun build(stateNames: List<String>, stateElements: Map<String, Int>): Map<String, Int> {
        require(stateNames.isNotEmpty()) { "codec decoder has no state tensors" }
        require(stateElements.keys == stateNames.toSet()) {
            "codec state element map does not cover the signature state list exactly"
        }
        require(stateElements.values.all { it > 0 }) { "codec state element counts must be positive" }
        return LinkedHashMap(stateElements)
    }
}

/** Stateful f12 codec graph driver. The state tensor names are passed from the section signature catalog. */
internal class CompiledCodecDecoder(
    file: File,
    options: CompiledModel.Options,
    env: Environment,
    private val stateNames: List<String>,
    private val stateSizes: Map<String, Int>,
) : OmniCodecDecoder, AutoCloseable {
    private val model = CompiledModel.create(file.absolutePath, options, env)
    private val signature = "decode"
    private val codes = model.createInputBuffer("codes", signature)
    private val start = model.createInputBuffer("start", signature)
    private val frames = model.createOutputBuffer("frames", signature)
    private val state = Array(2) { stateNames.associateWith { model.createInputBuffer(it, signature) } }
    private var current = 0
    private var frameOffset = 0

    override fun decode(frames: List<IntArray>): FloatArray {
        require(frames.isNotEmpty() && frames.size <= 12)
        val frameCount = 12
        val channelMajor = IntArray(OmniSpeechTokens.CODEBOOKS * frameCount)
        for (i in frames.indices) {
            require(frames[i].size == OmniSpeechTokens.CODEBOOKS)
            for (channel in 0 until OmniSpeechTokens.CODEBOOKS) channelMajor[channel * frameCount + i] = frames[i][channel]
        }
        codes.writeInt(channelMajor)
        start.writeInt(intArrayOf(frameOffset * 6))
        val inputs = HashMap<String, TensorBuffer>()
        val outputs = HashMap<String, TensorBuffer>()
        inputs["codes"] = codes; inputs["start"] = start; outputs["frames"] = this.frames
        for (name in stateNames) {
            inputs[name] = state[current].getValue(name)
            outputs["${name}_out"] = state[1 - current].getValue(name)
        }
        model.run(inputs, outputs, signature)
        current = 1 - current
        frameOffset += frames.size
        return this.frames.readFloat()
    }

    override fun reset() {
        for (buffers in state) for ((name, buffer) in buffers) buffer.writeFloat(FloatArray(stateSizes.getValue(name)))
        current = 0; frameOffset = 0
    }

    override fun close() = model.close()
}

/** Host overlap-add from the official sample's codec streaming branch (320 hop, centered trim, window power). */
internal class OmniOverlapAdd(private val window: FloatArray, private val hop: Int = 320) {
    private val trim = (window.size - hop) / 2
    private val held = 2 * trim
    private var tailAudio: FloatArray? = null
    private var tailEnv: FloatArray? = null
    private var started = false
    fun reset() { tailAudio = null; tailEnv = null; started = false }
    fun push(frames: FloatArray, count: Int, finalize: Boolean): FloatArray {
        if (count == 0) return if (finalize) finish() else FloatArray(0)
        val size = (count - 1) * hop + window.size
        val audio = FloatArray(size); val envelope = FloatArray(size)
        for (i in 0 until count) for (s in window.indices) {
            val at = i * hop + s
            // Each frame's samples live at its own window-size stride in the
            // decoder output (the sample's CodecStream reads src = i * nFft),
            // not at the hop-sized output stride.
            audio[at] += frames[i * window.size + s]
            envelope[at] += window[s] * window[s]
        }
        tailAudio?.let { for (i in it.indices) audio[i] += it[i] }
        tailEnv?.let { for (i in it.indices) envelope[i] += it[i] }
        val from = if (started) 0 else trim
        started = true
        val to = if (finalize) size - trim else size - held
        if (finalize) { tailAudio = null; tailEnv = null }
        else { tailAudio = audio.copyOfRange(size - held, size); tailEnv = envelope.copyOfRange(size - held, size) }
        return FloatArray((to - from).coerceAtLeast(0)) { audio[from + it] / envelope[from + it] }
    }
    fun finish(): FloatArray {
        val audio = tailAudio ?: return FloatArray(0)
        val envelope = tailEnv!!
        reset()
        return FloatArray(audio.size - trim) { audio[it] / envelope[it] }
    }
}

/** Speech loop port from photo_voice_chat/SpeechGenerator.kt. Initial hidden state is from the caller's final prefill. */
internal class OmniSpeechGenerator(
    private val lm: OmniSpeechLm,
    private val depth: OmniDepthFrame,
    private val frame: OmniFrameEmbedder,
    private val codec: OmniCodecStream,
) {
    enum class Mode { TEXT, AUDIO, INTERLEAVED }
    data class Result(val tokens: List<Int>, val frames: List<IntArray>, val finished: Boolean, val stopped: Boolean,
                      val samplerDraws: Long, val samplerMismatches: Long)

    fun generate(initialHidden: FloatArray, initialMode: Mode = Mode.INTERLEAVED, maxSteps: Int,
                 rng: OmniSpeechRng, onTextToken: (Int) -> Unit = {}, stop: () -> Boolean = { false }): Result {
        codec.reset()
        var hidden = initialHidden
        var mode = initialMode
        var slotsLeft = OmniSpeechTokens.TEXT_SLOTS
        var speaking = false
        var textDone = false; var interleavedAudioEnded = false
        val tokens = ArrayList<Int>(); val frames = ArrayList<IntArray>()
        val history = ArrayDeque<IntArray>()
        val counts = Array(OmniSpeechTokens.CODEBOOKS) { IntArray(OmniSpeechTokens.CODEBOOK_SIZE) }
        val presence = FloatArray(OmniSpeechTokens.CODEBOOKS * OmniSpeechTokens.CODEBOOK_SIZE)
        var draws = 0L; var finished = false; var stopped = false; var speechOpen = false
        for (step in 0 until maxSteps) {
            if (stop()) { stopped = true; break }
            val interleaved = mode == Mode.INTERLEAVED
            var audioEndedThisStep = false
            val next: FloatArray
            if (mode == Mode.AUDIO || (interleaved && speaking)) {
                val order = rng.permutation(OmniSpeechTokens.CODEBOOKS)
                val reveal = Array(3) { FloatArray(OmniSpeechTokens.CODEBOOKS) }
                var p = 0
                for ((pass, count) in intArrayOf(4, 4, 4).withIndex()) {
                    for (j in p until p + count) reveal[pass][order[j]] = 1f
                    p += count
                }
                val uniform = FloatArray(OmniSpeechTokens.CODEBOOKS)
                for (j in order.indices) uniform[order[j]] = minOf(rng.nextDouble().toFloat(), 1f - 1f / 2048)
                for (k in counts.indices) for (id in counts[k].indices) presence[k * OmniSpeechTokens.CODEBOOK_SIZE + id] = if (counts[k][id] > 0) 1f else 0f
                draws += OmniSpeechTokens.CODEBOOKS
                var codes = depth.sample(hidden, presence, reveal, uniform)
                require(codes.size == OmniSpeechTokens.CODEBOOKS)
                val eos = codes.any { it == OmniSpeechTokens.AUDIO_EOS }
                if (eos) {
                    codes = IntArray(OmniSpeechTokens.CODEBOOKS) { OmniSpeechTokens.AUDIO_EOS }
                    if (!codec.finish()) { stopped = true; break }
                    speechOpen = false
                    if (!interleaved) mode = Mode.TEXT
                    else { interleavedAudioEnded = true; speaking = false; slotsLeft = OmniSpeechTokens.TEXT_SLOTS; audioEndedThisStep = true }
                } else {
                    speechOpen = true
                    frames += codes.copyOf()
                    history.addLast(codes.copyOf())
                    for (k in codes.indices) counts[k][codes[k]]++
                    if (history.size > 64) { val old = history.removeFirst(); for (k in old.indices) counts[k][old[k]]-- }
                    if (!codec.push(codes)) { stopped = true; break }
                }
                if (!eos) tokens += OmniSpeechTokens.AUDIO_OUT
                next = frame.embed(codes)
            } else {
                val logits = lm.textLogits()
                val suppress = if (initialMode == Mode.TEXT) OmniSpeechTokens.NEVER_SAMPLE + intArrayOf(OmniSpeechTokens.MIXED_START, OmniSpeechTokens.AUDIO_START) else OmniSpeechTokens.NEVER_SAMPLE
                val token = OmniReferenceSampler.sampleAt(logits, 0, logits.size, 0f, 0, 1f, null, suppress, 0.0)
                tokens += token; onTextToken(token)
                if (token == OmniSpeechTokens.TEXT_END) {
                    if (interleaved) { textDone = true; if (!interleavedAudioEnded) speaking = true } else finished = true
                } else if (token == OmniSpeechTokens.IM_END) finished = true
                else if (!interleaved && token == OmniSpeechTokens.AUDIO_START) mode = Mode.AUDIO
                else if (!interleaved && token == OmniSpeechTokens.MIXED_START) { mode = Mode.INTERLEAVED; speaking = false; textDone = false; slotsLeft = OmniSpeechTokens.TEXT_SLOTS }
                next = lm.embedToken(token)
            }
            if (interleaved && !textDone && !audioEndedThisStep) {
                slotsLeft--
                if (slotsLeft == 0) { speaking = !speaking; slotsLeft = if (speaking) OmniSpeechTokens.AUDIO_SLOTS else OmniSpeechTokens.TEXT_SLOTS }
            }
            if (finished || step == maxSteps - 1) break
            val speechNext = mode == Mode.AUDIO || (mode == Mode.INTERLEAVED && speaking)
            hidden = lm.step(next, logits = !speechNext)
        }
        if (speechOpen && !stopped && !codec.finish()) stopped = true
        if (stopped) codec.reset()
        return Result(tokens, frames, finished, stopped, draws, 0L)
    }
}
