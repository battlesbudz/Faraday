package com.battlesbudz.jarvis.v2.ai

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject

/** U2's text-only driver for the LFM3 bundle. All CompiledModel calls run on its dedicated worker. */
internal object OmniRuntime {
    private const val MAP_ASSET = "lfm3_omni_sections.json"
    private const val BUNDLE_SHA = "959617973a7ddcb3d887fa0d9891d79556a266490d5c432b02a18ffcac3124ed"

    /**
     * Bounds the native runner creation the caller's thread waits on. The
     * voice-call owner runs this inside its stack mutex: an unbounded wait
     * there would trap the mutex and every later shutdown (stop/closeRuntime
     * serialize behind it) behind one wedged native create, forever. A
     * timeout fails the open — the caller surfaces it and can retry — while
     * the abandoned worker is shut down by the catch below.
     */
    private const val CREATE_TIMEOUT_MS = 120_000L

    fun cpuOptions(): CompiledModel.Options = CompiledModel.Options(Accelerator.CPU)
    fun createEnvironment(): Environment = Environment.create()

    /** Opens one already extracted section. Kept as a small API for section-specific units. */
    fun openSection(section: File, environment: Environment): CompiledModel =
        CompiledModel.create(section.absolutePath, cpuOptions(), environment)

    /** Extracts another pinned bundle section (U3 uses this for the audio encoder) under the same SHA gates. */
    fun extractSection(context: Context, bundle: File, sectionName: String): File {
        val map = JSONObject(context.assets.open(MAP_ASSET).bufferedReader().use { it.readText() })
        require(bundle.isFile && bundle.length() == map.getLong("bundle_bytes")) { "LFM3 bundle size mismatch" }
        require(map.getString("bundle_sha256") == BUNDLE_SHA) { "Unexpected LFM3 bundle map revision" }
        val root = File(context.filesDir, "lfm3_sections/${BUNDLE_SHA.take(16)}").apply { check(isDirectory || mkdirs()) }
        val fingerprint = "${bundle.length()}:${bundle.lastModified()}:$BUNDLE_SHA"
        val bundleMarker = File(root, "bundle.verified")
        if (!bundleMarker.isFile || bundleMarker.readText() != fingerprint) {
            require(sha256(bundle) == BUNDLE_SHA) { "LFM3 bundle SHA-256 mismatch" }
            bundleMarker.writeText(fingerprint)
        }
        require(sectionName in setOf("decoder", "embedder", "audio")) { "Unsupported LFM3 section $sectionName" }
        val section = map.getJSONObject("sections").getJSONObject(sectionName)
        val size = section.getLong("end") - section.getLong("begin")
        val expected = section.getString("sha256")
        val target = File(root, "$sectionName.tflite")
        val marker = File(root, "$sectionName.verified")
        if (!target.isFile || target.length() != size || !marker.isFile || marker.readText() != expected) {
            target.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(bundle, "r").use { input ->
                input.seek(section.getLong("begin"))
                target.outputStream().buffered().use { output ->
                    var remaining = size
                    val buffer = ByteArray(1024 * 1024)
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(n > 0) { "Truncated LFM3 $sectionName section" }
                        digest.update(buffer, 0, n); output.write(buffer, 0, n); remaining -= n
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expected) { "$sectionName section SHA-256 mismatch: $actual" }
            marker.writeText(expected)
        }
        return target
    }

    /** Verifies the pinned bundle, extracts decoder/embedder and inflates its tokenizer exactly once per file revision. */
    fun open(context: Context, bundle: File, cacheRoot: File = File(context.filesDir, "lfm3_sections")): OmniEngine {
        val components = openBundleComponents(context, bundle, cacheRoot)
        return OmniTextEngine(components.tokenizer, components.runner, components.worker)
    }

    /**
     * The same opened bundle for the voice-call driver (U5 seam 1): the
     * runner and tokenizer the call engine needs, the environment the audio
     * encoder and speech graphs share, and the one dedicated worker every
     * LiteRT call runs on. The caller owns the returned components — see
     * [OmniBundleComponents.close]. [open] keeps its own ownership by
     * wrapping them in [OmniTextEngine] instead, and must never also close
     * them through here.
     */
    internal fun openCallComponents(
        context: Context,
        bundle: File,
        cacheRoot: File = File(context.filesDir, "lfm3_sections"),
    ): OmniBundleComponents = openBundleComponents(context, bundle, cacheRoot)

    private fun openBundleComponents(
        context: Context,
        bundle: File,
        cacheRoot: File,
    ): OmniBundleComponents {
        val map = JSONObject(context.assets.open(MAP_ASSET).bufferedReader().use { it.readText() })
        require(bundle.isFile && bundle.length() == map.getLong("bundle_bytes")) { "LFM3 bundle size mismatch" }
        require(map.getString("bundle_sha256") == BUNDLE_SHA) { "Unexpected LFM3 bundle map revision" }
        val root = File(cacheRoot, BUNDLE_SHA.take(16)).apply { check(isDirectory || mkdirs()) }
        val bundleMarker = File(root, "bundle.verified")
        val bundleFingerprint = "${bundle.length()}:${bundle.lastModified()}:$BUNDLE_SHA"
        if (!bundleMarker.isFile || bundleMarker.readText() != bundleFingerprint) {
            require(sha256(bundle) == BUNDLE_SHA) { "LFM3 bundle SHA-256 mismatch" }
            bundleMarker.writeText(bundleFingerprint)
        }
        val sections = map.getJSONObject("sections")
        fun extract(name: String): File {
            val spec = sections.getJSONObject(name)
            val target = File(root, "$name.tflite")
            val marker = File(root, "$name.verified")
            val expected = spec.getString("sha256")
            if (!target.isFile || target.length() != spec.getLong("end") - spec.getLong("begin") ||
                !marker.isFile || marker.readText() != expected) {
                target.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                RandomAccessFile(bundle, "r").use { input ->
                    input.seek(spec.getLong("begin"))
                    target.outputStream().buffered().use { output ->
                        var remaining = spec.getLong("end") - spec.getLong("begin")
                        val buffer = ByteArray(1024 * 1024)
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(n > 0) { "Truncated LFM3 $name section" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "$name section SHA-256 mismatch: $actual" }
                marker.writeText(expected)
            }
            return target
        }

        val decoder = extract("decoder")
        val embedder = extract("embedder")
        val tokSpec = sections.getJSONObject("tokenizer")
        val tokenizerBytes = RandomAccessFile(bundle, "r").use { input ->
            input.seek(tokSpec.getLong("begin"))
            ByteArray((tokSpec.getLong("end") - tokSpec.getLong("begin")).toInt()).also { input.readFully(it) }
        }
        val tokenizerSha = MessageDigest.getInstance("SHA-256").digest(tokenizerBytes)
            .joinToString("") { "%02x".format(it) }
        check(tokenizerSha == tokSpec.getString("sha256")) { "tokenizer section SHA-256 mismatch" }
        val tokenizer = InflaterInputStream(tokenizerBytes.inputStream(8, tokenizerBytes.size - 8), Inflater())
            .bufferedReader(Charsets.UTF_8).use(OmniTokenizer::load)
        val signatures = map.getJSONObject("decoder_signatures")
        val knownPrefill = signatures.getJSONArray("prefill").let { a -> IntArray(a.length()) { a.getInt(it) } }
        val knownState = signatures.getJSONArray("state").let { a -> List(a.length()) { a.getString(it) } }
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Jarvis-Omni").apply { isDaemon = true } }
        return try {
            worker.submit<OmniBundleComponents> {
                val env = createEnvironment()
                val runner = OmniLmRunner(decoder, embedder, cpuOptions(), env, knownPrefill, knownState)
                OmniBundleComponents(tokenizer, runner, env, worker)
            }.get(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            worker.shutdownNow()
            throw failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The opened bundle pieces shared by the text engine and the voice-call driver. Single ownership: whoever receives these components closes them exactly once — the text path through [OmniTextEngine], the call path through [close]. */
internal class OmniBundleComponents(
    val tokenizer: OmniTokenizer,
    val runner: OmniLmRunner,
    val environment: Environment,
    internal val worker: ExecutorService,
) {
    internal val dispatcher: CoroutineDispatcher get() = worker.asCoroutineDispatcher()

    /** Closes the runner on its worker, then shuts the worker down. Call only off the Omni worker. */
    internal fun close() {
        try {
            // Bounded for the same reason as the create wait: the call owner
            // closes the stack under its mutex, so a wedged native close
            // must not trap shutdown behind it.
            runCatching { worker.submit(Runnable { runner.close() }).get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } finally {
            worker.shutdown()
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 30_000L
    }
}

/** Stable seam consumed by chat and later audio units. A call continues the current conversation; reset starts clean. */
internal interface OmniEngine : AutoCloseable {
    fun resetConversation()
    fun generateText(prompt: String, maxNewTokens: Int = 256, stop: () -> Boolean = { false }): String
}

/** Test fake: keeps production routing tests independent of the multi-gigabyte model bundle. */
internal class FakeOmniEngine(private val reply: (String) -> String) : OmniEngine {
    var resets: Int = 0
        private set
    override fun resetConversation() { resets++ }
    override fun generateText(prompt: String, maxNewTokens: Int, stop: () -> Boolean): String = reply(prompt)
    override fun close() = Unit
}

private class OmniTextEngine(
    private val tokenizer: OmniTokenizer,
    private val lm: OmniLmRunner,
    private val worker: ExecutorService,
) : OmniEngine {
    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread().name == "Jarvis-Omni") return block()
        val future: Future<T> = worker.submit<T> { block() }
        return future.get()
    }

    override fun resetConversation() { onWorker { lm.reset() } }

    override fun generateText(prompt: String, maxNewTokens: Int, stop: () -> Boolean): String = onWorker {
        require(maxNewTokens > 0)
        val ids = tokenizer.encode(OmniChatPrompt.userTurn(prompt, startsConversation = lm.pos == 0))
        val embedded = ids.map(lm::embedToken)
        if (lm.pos == 0) lm.prefill(embedded) else lm.extend(embedded, decodeLast = true)
        val generated = ArrayList<Int>()
        for (step in 0 until maxNewTokens) {
            if (stop()) break
            val logits = lm.textLogits()
            val token = greedy(logits, OmniLmRunner.TEXT_SUPPRESS)
            if (token == OmniLmRunner.IM_END || token == OmniLmRunner.TEXT_END) break
            generated += token
            lm.step(lm.embedToken(token))
        }
        tokenizer.decode(generated.toIntArray())
    }

    private fun greedy(logits: FloatArray, suppressed: IntArray): Int {
        var best = -1
        var value = Float.NEGATIVE_INFINITY
        for (id in logits.indices) {
            if (id in suppressed) continue
            if (logits[id] > value) { value = logits[id]; best = id }
        }
        check(best >= 0) { "No text token available" }
        return best
    }

    override fun close() {
        try { onWorker { lm.close() } }
        finally { worker.shutdown() }
    }
}

/** Typed text prompt template follows LFM3's im_start/im_end message framing. */
internal object OmniChatPrompt {
    const val SYSTEM = "You are Jarvis, a helpful assistant. Respond with text."
    fun userTurn(userText: String, startsConversation: Boolean): String = if (startsConversation) {
        "<|startoftext|><|im_start|>system\n$SYSTEM<|im_end|>\n" +
            "<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n<think></think>"
    } else {
        "<|im_end|>\n<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n<think></think>"
    }
}
