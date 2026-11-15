package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation-reset policy of the follow-up word confirmation.
 * On the persistent call conversation the confirmation must transcribe
 * without flipping the tool policy or resetting the conversation, so the
 * answer that follows keeps its delta prompt; any failure, timeout, or
 * tool-call output falls back to the reset. Off the resident conversation
 * the legacy isolate-and-reset behavior is pinned byte-for-byte.
 */
class FinalVoiceInputResolverTest {
    private class FakeEngine(
        override val hasFixedVoicePreface: Boolean = false,
        private val behaviour: suspend (call: Int) -> GenerationResult
    ) : FinalVoiceTranscriptionEngine {
        override val modelId = "fake-model"
        override val audioEnabled = true
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        val toolPolicyCalls = mutableListOf<Boolean>()
        var generateCalls = 0
        override suspend fun setToolsEnabled(enabled: Boolean) { toolPolicyCalls += enabled }
        override suspend fun generateAudio(prompt: String, audioPcm: ByteArray,
            onStreamToken: (String) -> Unit): GenerationResult {
            generateCalls++
            return behaviour(generateCalls)
        }
    }

    private fun resolver(engine: FakeEngine, resets: AtomicInteger, timeoutMs: Long = 12_000L) =
        FinalVoiceInputResolver(
            engine = engine,
            resetConversation = { resets.incrementAndGet() },
            benchmark = PipelineBenchmarkCapture("turn", "test", 0, PipelineBenchmarkProvenance("test", 1)),
            turnTrace = VoiceTurnTrace("turn"),
            comparison = null,
            recordDiagnostic = {},
            reportStatus = {},
            transcriptionTimeoutMs = timeoutMs)

    private fun confirm(resolver: FinalVoiceInputResolver, resident: Boolean) = runBlocking {
        resolver.resolve(asrTranscript = "", audioBytes = byteArrayOf(1, 2, 3),
            directAudioTurn = true, initialIssue = null, asrLabel = "test",
            requireWordConfirmation = true, residentCallContext = resident)
    }

    private fun words(text: String) =
        GenerationResult(text = text, timeToFirstTokenMs = 1, decodeTokensPerSecond = null)

    @Test fun residentConfirmationKeepsConversationWhenWordsAreConfirmed() {
        val resets = AtomicInteger()
        val engine = FakeEngine { words("what time is it") }
        val result = confirm(resolver(engine, resets), resident = true)
        assertEquals(GemmaAudioInputPolicy.REQUEST, result.text)
        assertEquals(null, result.recognitionIssue)
        assertEquals(1, engine.generateCalls)
        assertTrue(engine.toolPolicyCalls.isEmpty())
        assertEquals(0, resets.get())
    }

    @Test fun residentConfirmationWithToolCallsResetsOnceAndClarifies() {
        val resets = AtomicInteger()
        val engine = FakeEngine {
            GenerationResult(text = "", timeToFirstTokenMs = 1, decodeTokensPerSecond = null,
                toolCalls = listOf(ToolCall("noop", "{}")))
        }
        val result = confirm(resolver(engine, resets), resident = true)
        assertEquals("", result.text)
        assertEquals("audio_fallback_empty", result.recognitionIssue)
        assertEquals(2, engine.generateCalls)
        assertTrue(engine.toolPolicyCalls.isEmpty())
        // No per-attempt resets on the resident path; the single finally
        // reset discards the conversation that holds the tool exchanges.
        assertEquals(1, resets.get())
    }

    @Test fun residentConfirmationFailureResetsAndPropagates() {
        val resets = AtomicInteger()
        val engine = FakeEngine { throw IllegalStateException("native boom") }
        assertThrows(IllegalStateException::class.java) {
            confirm(resolver(engine, resets), resident = true)
        }
        assertEquals(1, resets.get())
        assertTrue(engine.toolPolicyCalls.isEmpty())
    }

    @Test fun residentConfirmationTimeoutResetsAndReportsTimeout() {
        val resets = AtomicInteger()
        val engine = FakeEngine { delay(5_000); words("too late") }
        val result = confirm(resolver(engine, resets, timeoutMs = 100), resident = true)
        assertEquals("audio_fallback_timeout", result.recognitionIssue)
        assertEquals(1, resets.get())
        assertTrue(engine.toolPolicyCalls.isEmpty())
    }

    @Test fun residentNoSpeechKeepsConversationAndClarifies() {
        val resets = AtomicInteger()
        val engine = FakeEngine { words("[NO_SPEECH]") }
        val result = confirm(resolver(engine, resets), resident = true)
        assertEquals("", result.text)
        assertEquals("audio_fallback_empty", result.recognitionIssue)
        assertEquals(1, engine.generateCalls)
        // A clean no-speech verdict leaves no partial state behind, so the
        // resident conversation survives for the next turn.
        assertEquals(0, resets.get())
    }

    @Test fun isolatedConfirmationKeepsLegacyResetBehavior() {
        val resets = AtomicInteger()
        val engine = FakeEngine { words("what time is it") }
        val result = confirm(resolver(engine, resets), resident = false)
        assertEquals(GemmaAudioInputPolicy.REQUEST, result.text)
        assertEquals(null, result.recognitionIssue)
        assertEquals(listOf(false), engine.toolPolicyCalls)
        // One reset before the attempt and one in the finally cleanup.
        assertEquals(2, resets.get())
    }

    @Test fun residentFlagIgnoredForFixedVoicePrefaceConversation() {
        val resets = AtomicInteger()
        val engine = FakeEngine(hasFixedVoicePreface = true) { words("what time is it") }
        val result = confirm(resolver(engine, resets), resident = true)
        assertEquals(GemmaAudioInputPolicy.REQUEST, result.text)
        assertEquals(listOf(false), engine.toolPolicyCalls)
        assertEquals(2, resets.get())
    }
}
