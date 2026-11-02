package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.InferenceProgress
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import com.battlesbudz.jarvis.v2.voice.PrimedAudioInput
import com.battlesbudz.jarvis.v2.voice.VoicePrefillSession
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual input router and immutable SDK value; synthetic rows/fake backend, no inference. */
class ConversationSealedAudioInputTest {
    private open class Backend : ConversationBackend {
        override val modelId = "fake-backend"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        val modes = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val raw = mutableListOf<ByteArray>()
        val sealed = mutableListOf<Content.SealedAudioEmbeddings>()
        override fun inputContextDescription() = "fake-backend"
        override val toolsEnabled = false
        override suspend fun setToolsEnabled(enabled: Boolean) = false
        protected fun result(mode: String, prompt: String, emit: (String) -> Unit): GenerationResult {
            modes += mode
            prompts += prompt
            emit("reply")
            return GenerationResult("reply", 0, null)
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit) = result("text", prompt, onToken)
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult {
            raw += imageBytes
            return result("image", prompt, onToken)
        }
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult {
            raw += audioBytes
            return result("raw-audio", prompt, onToken)
        }
        override suspend fun generateSealedAudio(prompt: String, audio: Content.SealedAudioEmbeddings,
                                                 onToken: (String) -> Unit): GenerationResult {
            sealed += audio
            return result("sealed-audio", prompt, onToken)
        }
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult =
            error("Input selection must not dispatch tools")
    }

    private fun sealed(rows: FloatArray = FloatArray(1536) { it / 1536f }) =
        Content.SealedAudioEmbeddings.fromSealedAudio(rows, 160, 1536,
            "synthetic-routing-test", "d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b")

    private fun metadata(content: Content.SealedAudioEmbeddings) = listOf(
        content.pcmSampleCount, content.audioTokenCount, content.embeddingWidth,
        content.sealToken, content.producerSha256)

    private class PrimedFakeSession(private val answer: String = "primed answer") : VoicePrefillSession {
        val appends = mutableListOf<String>()
        var audioTokens = 0
        var closed = false
        override fun append(text: String) { appends += text }
        override fun appendImage(imageBytes: ByteArray) { error("unused") }
        override fun appendAudio(rows: FloatArray, tokenCount: Int, embeddingWidth: Int) { audioTokens = tokenCount }
        override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
            onToken(answer)
            return GenerationResult(answer, 3, null)
        }
        override fun close() { closed = true }
    }

    @Test fun primedInputAnswersOnTheSessionAndSkipsTheBackend() = runBlocking {
        val backend = Backend()
        val session = PrimedFakeSession()
        val owner = PrimedAudioInput(this, "audio prompt", { session })
        owner.prime()
        val input = ConversationInput(ByteArray(4), true, null, null, null, sealed(), primedAudio = owner)
        val result = input.generateFallback(backend, "audio prompt", {}, {})
        assertEquals("primed answer", result.text)
        assertTrue(backend.modes.isEmpty())
        assertTrue(input.primedUsed)
        assertEquals("used", input.routeBPrimingOutcome)
        assertFalse(input.nativeConversationContainsTurn)
        assertEquals(1, session.audioTokens)
        assertTrue(session.closed)
    }

    @Test fun primedInputFallsBackToSealedBackendWhenThePromptDrifted() = runBlocking {
        val backend = Backend()
        val session = PrimedFakeSession()
        val owner = PrimedAudioInput(this, "audio prompt", { session })
        owner.prime()
        val input = ConversationInput(ByteArray(4), true, null, null, null, sealed(), primedAudio = owner)
        val result = input.generateFallback(backend, "a different final prompt", {}, {})
        assertEquals("ok", result.text)
        assertEquals(listOf("sealed"), backend.modes)
        assertFalse(input.primedUsed)
        assertEquals("fallback:prompt_prefix_changed", input.routeBPrimingOutcome)
        assertTrue(input.nativeConversationContainsTurn)
    }

    @Test fun retryNeverUsesThePrimedSession() = runBlocking {
        val backend = Backend()
        val session = PrimedFakeSession()
        val owner = PrimedAudioInput(this, "audio prompt", { session })
        owner.prime()
        val input = ConversationInput(ByteArray(4), true, null, null, null, sealed(), primedAudio = owner)
        input.generateFallback(backend, "audio prompt", {}, {})
        val retried = input.retry(backend, "audio prompt") {}
        assertEquals("ok", retried.text)
        // Only the retry reached the backend; the first pass was the primed session's.
        assertEquals(listOf("sealed"), backend.modes)
    }

    @Test fun primedInputWithoutSealedAudioIsRejected() {
        val owner = PrimedAudioInput(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            "prompt", { PrimedFakeSession() })
        try {
            ConversationInput(ByteArray(4), true, null, null, null, null, primedAudio = owner)
            fail("primed audio without sealed content must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun directAudioAndRetryUseTheSameImmutableSealedInputWithoutRawFallback() = runBlocking<Unit> {
        val rows = FloatArray(1536) { it / 1536f }
        val content = sealed(rows)
        // Wire-byte ownership is tested inside the packaged SDK's test module.
        // App routing uses the same public API available to production callers.
        val before = metadata(content)
        val retained = byteArrayOf(1, 0, 2, 0)
        val backend = Backend()
        val input = ConversationInput(retained, true, null, null, null, content)
        rows.fill(Float.NaN)
        retained.fill(0)
        val tokens = mutableListOf<String>()

        input.generate(backend, "first prompt", tokens::add, onIncrementalFallback = { fail("sealed input has no incremental fallback") })
        input.retry(backend, "retry prompt", tokens::add)

        assertEquals(listOf("sealed-audio", "sealed-audio"), backend.modes)
        assertEquals(listOf("first prompt", "retry prompt"), backend.prompts)
        assertEquals(listOf("reply", "reply"), tokens)
        assertTrue(backend.raw.isEmpty())
        assertSame(content, backend.sealed[0])
        assertSame(content, backend.sealed[1])
        assertEquals(before, metadata(content))
        assertTrue(input.nativeConversationContainsTurn)
        assertFalse(input.incrementalFallbackUsed)
    }

    @Test fun sealedInputRejectsNonDirectAndMissingCompleteRawCapture() {
        val content = sealed()
        assertThrows(IllegalArgumentException::class.java) {
            ConversationInput(byteArrayOf(1, 0), false, null, null, null, content)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationInput(null, true, null, null, null, content)
        }
    }

    @Test fun sealedInputCannotAlsoOwnIncrementalTextPrefill() = runBlocking<Unit> {
        val incremental = IncrementalVoiceInput(this, "prefix", { error("must not allocate text session") })
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ConversationInput(byteArrayOf(1, 0), true, incremental, null, null, sealed())
            }
        } finally { incremental.close() }
    }

    @Test fun directRawAudioForComparisonOrRecordedCorrectionPreservesOriginalBytesOnRetry() = runBlocking<Unit> {
        val retained = byteArrayOf(1, 0, 2, 0)
        val backend = Backend()
        val input = ConversationInput(retained, true, null, null, null)

        input.generate(backend, "raw prompt", {}, onIncrementalFallback = { fail("raw direct input cannot fall back to text") })
        input.retry(backend, "raw retry", {})

        assertEquals(listOf("raw-audio", "raw-audio"), backend.modes)
        assertSame(retained, backend.raw[0])
        assertSame(retained, backend.raw[1])
        assertTrue(backend.sealed.isEmpty())
    }

    @Test fun retainedVoicePcmCannotOverrideAsrTextMode() = runBlocking<Unit> {
        val backend = Backend()
        val input = ConversationInput(byteArrayOf(1, 0), false, null, null, null)
        input.generate(backend, "recognized words", {}, onIncrementalFallback = { fail("no incremental prefill exists") })
        input.retry(backend, "recognized words again", {})
        assertEquals(listOf("text", "text"), backend.modes)
        assertTrue(backend.raw.isEmpty())
        assertTrue(backend.sealed.isEmpty())
    }

    @Test fun attachedAudioRemainsRawAcrossInitialGenerationAndRetry() = runBlocking<Unit> {
        val audio = byteArrayOf(1, 2, 3)
        val backend = Backend()
        val input = ConversationInput(null, false, null, null, audio)
        input.generate(backend, "attachment", {}, onIncrementalFallback = { fail("no incremental prefill exists") })
        input.retry(backend, "attachment retry", {})
        assertEquals(listOf("raw-audio", "raw-audio"), backend.modes)
        assertSame(audio, backend.raw[0])
        assertSame(audio, backend.raw[1])
        assertTrue(backend.sealed.isEmpty())
    }

    @Test fun unsupportedSealedBackendFailsClosedWithoutReplayingRawAudio() = runBlocking<Unit> {
        val backend = object : Backend() {
            override suspend fun generateSealedAudio(prompt: String, audio: Content.SealedAudioEmbeddings,
                                                     onToken: (String) -> Unit): GenerationResult =
                error("injected sealed backend rejection")
        }
        val input = ConversationInput(byteArrayOf(1, 0), true, null, null, null, sealed())
        val first = runCatching { input.generate(backend, "first", {}, onIncrementalFallback = { fail("no incremental fallback") }) }
        val retry = runCatching { input.retry(backend, "retry", {}) }
        assertEquals("injected sealed backend rejection", first.exceptionOrNull()?.message)
        assertEquals("injected sealed backend rejection", retry.exceptionOrNull()?.message)
        assertTrue(backend.modes.isEmpty())
        assertTrue(backend.raw.isEmpty())
        assertFalse(input.incrementalFallbackUsed)
    }
}
