package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ai.*
import com.battlesbudz.jarvis.v2.diagnostics.*
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual final input router with a fake single-owner native driver, never model weights. */
class ConversationSpeculativeInputTest {
    private class Backend : ConversationBackend {
        override val modelId = "fake"
        override var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
        override var onInferenceProgress: (InferenceProgress) -> Unit = {}
        override var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        var raw: ByteArray? = null
        var calls = 0
        override fun inputContextDescription() = "fake"
        override suspend fun setToolsEnabled(enabled: Boolean) = false
        override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult = error("must keep audio")
        override suspend fun generate(prompt: String, imageBytes: ByteArray, onToken: (String) -> Unit): GenerationResult = error("must keep audio")
        override suspend fun generateAudio(prompt: String, audioBytes: ByteArray, onToken: (String) -> Unit): GenerationResult {
            calls++; raw = audioBytes; onToken("Fallback."); return GenerationResult("Fallback.", 100, null)
        }
        override suspend fun sendToolResults(results: List<Pair<ToolCall, String>>, onToken: (String) -> Unit): GenerationResult = error("no effects")
    }
    @Test fun promotionStreamsOnceAndReportsNativeHistoryEmptyAfterCheckedRollback() = runBlocking<Unit> {
        for (matchingPrompt in listOf(false, true)) {
            val pcm = byteArrayOf(1, 0, 2, 0)
            val full = pcm + byteArrayOf(0, 0)
            val wav = WavEncoder.pcm16Mono(full, 16000)
            var resets = 0
            val driver = object : NativeSpeculationDriver {
                override suspend fun generate(proposal: NativePauseProposal, exactPrompt: String,
                    onToken: (String) -> Unit, onProgress: (InferenceProgress) -> Unit): GenerationResult {
                    onToken("Prepared."); return GenerationResult("Prepared.", 20, null)
                }
                override suspend fun rollback() { resets++ }
                override fun requestCancel() {}
                override fun safeToRelease() = resets == 1
                override fun timing(): NativeAudioCaptureTiming? = null
            }
            val candidate = NativeVoiceSpeculation(this, "call", "turn", 1, driver,
                NativeVoicePromptPreview("prompt") { true }, { true }, { true })
            val proposal = NativePauseProposal("turn", 1, 1, 2, 300, pcm)
            assertTrue(candidate.onProposal(proposal))
            assertTrue(candidate.confirmCapture(NativePauseCertificate(proposal, full.size / 2, pcmHash(full),
                1, 3, 650), wav))
            val backend = Backend()
            val input = ConversationInput(wav, true, null, null, null, nativeSpeculation = candidate)
            val text = StringBuilder()
            val result = input.generate(backend, if (matchingPrompt) "prompt" else "corrected prompt", text::append) { fail("no text retry") }
            assertEquals(1, resets)
            if (matchingPrompt) {
                assertEquals("Prepared.", result.text); assertEquals("Prepared.", text.toString())
                assertEquals(0, backend.calls); assertFalse(input.nativeConversationContainsTurn)
                input.retry(backend, "reference retry", {})
                assertSame(wav, backend.raw); assertTrue(input.nativeConversationContainsTurn)
            } else {
                assertEquals("Fallback.", result.text); assertEquals(1, backend.calls)
                assertSame(wav, backend.raw); assertArrayEquals(full, backend.raw!!.copyOfRange(44, wav.size))
                assertTrue(input.nativeConversationContainsTurn)
            }
            assertTrue(candidate.closeAndDrain())
        }
    }
}
