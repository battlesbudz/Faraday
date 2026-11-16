package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/** Host-owned decoder buffers and token embedder. LiteRT calls are deliberately serialized by the owning engine. */
internal class OmniLmRunner(
    decoderFile: File,
    embedderFile: File,
    options: CompiledModel.Options,
    environment: Environment,
    knownPrefill: IntArray,
    knownState: List<String>,
) : AutoCloseable {
    private val decoder = CompiledModel.create(decoderFile.absolutePath, options, environment)
    private val embedder = CompiledModel.create(embedderFile.absolutePath, options, environment)
    val prefillLengths = knownPrefill.sortedArrayDescending()
    private val stateNames = knownState
    private val stateSizes = stateNames.associateWith { elements(decoder.getInputTensorType(it, "decode")) }
    val hiddenSize = dims(decoder.getInputTensorType("embeddings", "decode")).last()
    val cacheLength = dims(decoder.getInputTensorType("mask", "decode")).last()
    private val sets: Array<Map<String, TensorBuffer>> = arrayOf(stateNames.associateWith { decoder.createOutputBuffer(it, "decode") })
    private val zeros = HashMap<Int, FloatArray>()
    private val decEmb = decoder.createInputBuffer("embeddings", "decode")
    private val decPos = decoder.createInputBuffer("input_pos", "decode")
    private val decMask = decoder.createInputBuffer("mask", "decode")
    private val decHidden = decoder.createOutputBuffer("hidden", "decode")
    private val decLogits = decoder.createOutputBuffer("logits", "decode")
    private val decMaskRow = FloatArray(cacheLength) { MASK_FILL }
    private val decodeMaps: Pair<Map<String, TensorBuffer>, Map<String, TensorBuffer>> = run {
        val inputs = HashMap<String, TensorBuffer>(stateNames.size + 3)
        inputs["embeddings"] = decEmb; inputs["input_pos"] = decPos; inputs["mask"] = decMask
        inputs.putAll(sets[0])
        val outputs = HashMap<String, TensorBuffer>(stateNames.size + 2)
        outputs["hidden"] = decHidden; outputs["logits"] = decLogits; outputs.putAll(sets[0])
        inputs to outputs
    }
    private class Prefill(val emb: TensorBuffer, val pos: TensorBuffer, val mask: TensorBuffer, val hidden: TensorBuffer, val maskData: FloatArray)
    private val prefills = HashMap<Int, Prefill>()
    private val embIn = embedder.createInputBuffer("token_ids", "embedder")
    private val embOut = embedder.createOutputBuffer("embeddings", "embedder")
    var pos: Int = 0
        private set
    private var logitsValid = false

    init {
        require(prefillLengths.isNotEmpty() && prefillLengths.last() == 1) { "invalid decoder prefill signatures" }
        require(stateNames.isNotEmpty()) { "decoder has no KV/conv state" }
        reset()
    }

    fun reset() {
        pos = 0
        logitsValid = false
        decMaskRow.fill(MASK_FILL)
        for (set in sets) for ((name, buffer) in set) {
            val n = stateSizes.getValue(name)
            buffer.writeFloat(zeros.getOrPut(n) { FloatArray(n) })
        }
    }

    fun embedToken(id: Int): FloatArray {
        embIn.writeInt(intArrayOf(id))
        embedder.run(mapOf("token_ids" to embIn), mapOf("embeddings" to embOut), "embedder")
        return embOut.readFloat()
    }

    fun prefill(rows: List<FloatArray>): FloatArray {
        reset()
        return checkNotNull(extend(rows, decodeLast = true))
    }

    fun extend(rows: List<FloatArray>, decodeLast: Boolean): FloatArray? {
        require(rows.isNotEmpty()) { "no rows to prefill" }
        check(pos + rows.size <= cacheLength) { "context full: ${pos + rows.size} > $cacheLength" }
        val prefix = if (decodeLast) rows.size - 1 else rows.size
        var from = 0
        for (size in prefillPlan(prefix, prefillLengths)) {
            prefillChunk(rows, from, size)
            from += size
        }
        return if (decodeLast) step(rows.last()) else null
    }

    private fun prefillChunk(rows: List<FloatArray>, from: Int, size: Int) {
        val p = prefills.getOrPut(size) {
            val sig = "prefill_$size"
            Prefill(decoder.createInputBuffer("embeddings", sig), decoder.createInputBuffer("input_pos", sig),
                decoder.createInputBuffer("mask", sig), decoder.createOutputBuffer("hidden", sig), FloatArray(size * cacheLength))
        }
        val input = FloatArray(size * hiddenSize)
        for (i in 0 until size) {
            require(rows[from + i].size == hiddenSize) { "embedding size ${rows[from + i].size} != $hiddenSize" }
            System.arraycopy(rows[from + i], 0, input, i * hiddenSize, hiddenSize)
        }
        p.emb.writeFloat(input)
        p.pos.writeInt(IntArray(size) { pos + it })
        p.maskData.fill(MASK_FILL)
        for (i in 0 until size) java.util.Arrays.fill(p.maskData, i * cacheLength, i * cacheLength + pos + i + 1, 0f)
        p.mask.writeFloat(p.maskData)
        val inputs = HashMap<String, TensorBuffer>(stateNames.size + 3)
        inputs["embeddings"] = p.emb; inputs["input_pos"] = p.pos; inputs["mask"] = p.mask; inputs.putAll(sets[0])
        val outputs = HashMap<String, TensorBuffer>(stateNames.size + 1)
        outputs["hidden"] = p.hidden; outputs.putAll(sets[0])
        decoder.run(inputs, outputs, "prefill_$size")
        pos += size
    }

    fun step(embedding: FloatArray): FloatArray {
        check(pos < cacheLength) { "context full ($cacheLength)" }
        require(embedding.size == hiddenSize) { "embedding size ${embedding.size} != $hiddenSize" }
        decEmb.writeFloat(embedding)
        decPos.writeInt(intArrayOf(pos))
        decMaskRow[pos] = 0f
        decMask.writeFloat(decMaskRow)
        decoder.run(decodeMaps.first, decodeMaps.second, "decode")
        pos++
        logitsValid = true
        return decHidden.readFloat()
    }

    fun textLogits(): FloatArray {
        check(logitsValid) { "text logits are not available" }
        return decLogits.readFloat()
    }

    override fun close() {
        try { decoder.close() } finally { embedder.close() }
    }

    companion object {
        const val MASK_FILL = -0.7f * Float.MAX_VALUE
        const val IM_END = 124900
        const val TEXT_END = 125014
        val TEXT_SUPPRESS = intArrayOf(125031, 125032, 125030, 125029, 125015, 125012)
        internal fun prefillPlan(count: Int, signatures: IntArray): List<Int> {
            require(count >= 0)
            val sizes = signatures.sortedArrayDescending()
            val plan = ArrayList<Int>()
            var remaining = count
            while (remaining > 0) {
                val size = sizes.firstOrNull { it <= remaining }
                    ?: error("no exact-cover prefill signature for $remaining rows")
                plan += size
                remaining -= size
            }
            return plan
        }
        private fun dims(t: com.google.ai.edge.litert.TensorType): List<Int> = t.layout?.dimensions ?: error("tensor without layout")
        private fun elements(t: com.google.ai.edge.litert.TensorType): Int = dims(t).fold(1) { a, b -> a * b }
    }
}
