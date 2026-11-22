package com.battlesbudz.jarvis.v2.ai

/**
 * The LFM3 decoder's state geometry: the element count of every KV/conv
 * state tensor, the hidden size, and the cache length. These are constants
 * of the pinned bundle's decoder graph, read out of its flatbuffer once,
 * offline, and carried in the verified sections map
 * (lfm3_omni_sections.json — bound to the bundle's SHA-256, which
 * [OmniRuntime] checks before anything in the map is used).
 *
 * The load path deliberately never queries the graph for them. Builds
 * 1944–1965 died natively, with no managed failure on record, in the one
 * stretch of [OmniLmRunner]'s init that issued 37
 * CompiledModel.getInputTensorType calls (35 state tensors, embeddings,
 * mask) between the embedder create and the first state buffer — the only
 * native calls in that window, and the prime suspect for the death. The
 * plan replaces all of them: geometry comes from verified metadata, and
 * the init tail's first native calls are now the buffer creations.
 *
 * Every state tensor in the pinned graph is FLOAT32, which is what the
 * runner's float zeroing and [totalStateBytes] assume.
 */
internal class OmniStatePlan private constructor(
    /** Element count of each decoder state tensor, keyed by tensor name. */
    val stateElements: Map<String, Int>,
    val hiddenSize: Int,
    val cacheLength: Int,
) {
    /** Total state footprint in bytes (FLOAT32 elements). */
    val totalStateBytes: Long = stateElements.values.sumOf { it.toLong() } * FLOAT32_BYTES

    companion object {
        private const val FLOAT32_BYTES = 4L

        /**
         * Builds a plan from the sections map's raw values. The element
         * map must cover [stateNames] exactly: a missing name would leave
         * a state buffer zeroed to the wrong length, and an extra name
         * means the map and the pinned graph have drifted apart — both
         * fail the load here, in managed code, instead of on device.
         */
        fun build(
            stateNames: List<String>,
            stateElements: Map<String, Int>,
            hiddenSize: Int,
            cacheLength: Int,
        ): OmniStatePlan {
            require(stateNames.isNotEmpty()) { "decoder has no KV/conv state" }
            require(stateElements.keys == stateNames.toSet()) {
                "state element map does not cover the decoder state list exactly"
            }
            require(stateElements.values.all { it > 0 }) { "state element counts must be positive" }
            require(hiddenSize > 0) { "hidden size must be positive" }
            require(cacheLength > 0) { "cache length must be positive" }
            return OmniStatePlan(LinkedHashMap(stateElements), hiddenSize, cacheLength)
        }
    }
}
