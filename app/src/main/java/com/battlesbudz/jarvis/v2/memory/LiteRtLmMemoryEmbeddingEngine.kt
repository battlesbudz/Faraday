package com.battlesbudz.jarvis.v2.memory

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File

/**
 * The narrow seam between [LiteRtLmMemoryEmbeddingEngine] and the native embedding
 * runtime. The runtime cannot run in JVM unit tests (it loads the LiteRT-LM native
 * library), so the binding's state machine is tested against a fake at this seam and
 * only [LiteRtEmbeddingRuntime] touches the bridge classes.
 */
internal fun interface EmbeddingRuntimeFactory {
    fun create(modelFile: File, maxInputLength: Int): EmbeddingRuntime
}

internal interface EmbeddingRuntime {
    /** Raw model-width embedding of [text]. May throw any runtime exception. */
    fun embed(text: String): FloatArray

    fun close()
}

/** Production runtime: the LiteRT-LM [EmbeddingEngine] on the CPU backend. */
internal class LiteRtEmbeddingRuntime(modelFile: File, maxInputLength: Int) : EmbeddingRuntime {
    private val engine = EmbeddingEngine(
        EmbeddingEngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            maxInputLength = maxInputLength,
        ),
    )

    init {
        engine.initialize()
    }

    override fun embed(text: String): FloatArray =
        engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions()).embedding

    override fun close() {
        runCatching { engine.close() }
    }
}

/**
 * Production [MemoryEmbeddingEngine] over the LiteRT-LM embedding runtime
 * (EmbeddingGemma 2 text tower). CPU backend only: the chip-specific NPU artifact
 * stays unpinned, per the Unit 1.5 spike.
 *
 * Honesty rules mirror the rest of the memory stack: nothing is fabricated. The
 * engine reports unavailable until the pinned model file is installed; integrity
 * (the store's SHA-256 pin check) is verified once before the native runtime is
 * created; a native failure latches the engine failed for the rest of the process
 * so diagnostics never claim a working engine that already failed. Acquisition-state
 * problems (model missing, integrity mismatch) are NOT latched — a later download
 * or re-download is picked up on the next embed attempt.
 *
 * The port contract is raw model-width vectors: [MemoryEmbeddingService] owns role
 * formatting, width validation, Matryoshka truncation and normalization, so the
 * native `outputSize` option is deliberately left unset and the stored dimension
 * keeps flowing from the stamped spec through the service.
 */
class LiteRtLmMemoryEmbeddingEngine(
    private val store: EmbeddingModelStore,
    private val spec: EmbeddingModelSpec = EmbeddingModelCatalog.default,
    private val runtimeFactory: EmbeddingRuntimeFactory =
        EmbeddingRuntimeFactory { file, maxInputLength -> LiteRtEmbeddingRuntime(file, maxInputLength) },
) : MemoryEmbeddingEngine {
    override val modelId: String = spec.id

    private val lock = Any()

    @Volatile private var runtime: EmbeddingRuntime? = null

    @Volatile private var failureReason: String? = null

    @Volatile private var integrityVerified: Boolean = false

    /** Content-free reason the engine cannot embed right now, when one is known. */
    val unavailableReason: String?
        get() = failureReason ?: if (!modelPresent()) MODEL_NOT_INSTALLED else null

    override val available: Boolean
        get() = failureReason == null && modelPresent()

    override fun embed(text: String, role: EmbeddingRole): FloatArray {
        // Role formatting already happened in MemoryEmbeddingService; the port
        // contract is the raw model-width embedding of the text exactly as given.
        synchronized(lock) {
            failureReason?.let { throw EmbeddingEngineUnavailable(it) }
            val active = runtime ?: createRuntime()
            val raw = try {
                active.embed(text)
            } catch (error: Exception) {
                throw fail("Embedding runtime failed (${error.javaClass.simpleName}).")
            }
            if (raw.size != spec.outputDimension) {
                throw fail(
                    "Embedding runtime returned ${raw.size} dimensions; expected ${spec.outputDimension}.",
                )
            }
            return raw
        }
    }

    /** Releases the native runtime. A later embed re-creates it if the model is installed. */
    fun close() {
        synchronized(lock) {
            runtime?.close()
            runtime = null
        }
    }

    private fun createRuntime(): EmbeddingRuntime {
        val file = store.fileFor(spec)
        if (!file.isFile || file.length() != spec.downloadBytes) {
            throw EmbeddingEngineUnavailable(MODEL_NOT_INSTALLED)
        }
        if (!integrityVerified) {
            if (!store.isInstalled(spec)) {
                throw EmbeddingEngineUnavailable("Embedding model failed its integrity check.")
            }
            integrityVerified = true
        }
        val created = try {
            runtimeFactory.create(file, MAX_INPUT_LENGTH)
        } catch (error: Exception) {
            throw fail("Embedding runtime failed to start (${error.javaClass.simpleName}).")
        }
        runtime = created
        return created
    }

    private fun fail(reason: String): EmbeddingEngineUnavailable {
        failureReason = reason
        runtime?.close()
        runtime = null
        return EmbeddingEngineUnavailable(reason)
    }

    private fun modelPresent(): Boolean {
        val file = store.fileFor(spec)
        return file.isFile && file.length() == spec.downloadBytes
    }

    private companion object {
        const val MODEL_NOT_INSTALLED = "Embedding model is not installed."

        /** EmbeddingGemma 2's published context window; memory records are capped far below it. */
        const val MAX_INPUT_LENGTH = 8192
    }
}
