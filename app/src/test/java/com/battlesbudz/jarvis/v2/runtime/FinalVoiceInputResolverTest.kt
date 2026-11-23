package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import com.battlesbudz.jarvis.v2.voice.VoiceCallStore
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceTurnCoordinator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalVoiceInputResolverTest {
    private class FakeEngine(private val result: String) : FinalVoiceTranscriptionEngine {
        override val modelId = "fake-model"
        override val audioEnabled = true
        override var benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER
        val toolPolicyCalls = mutableListOf<Boolean>()
        var generateCalls = 0
        override suspend fun setToolsEnabled(enabled: Boolean) { toolPolicyCalls += enabled }
        override suspend fun generateAudio(prompt: String, audioPcm: ByteArray,
            onStreamToken: (String) -> Unit): GenerationResult {
            generateCalls++
            return GenerationResult(result, 1, null)
        }
    }

    private fun resolve(asr: String, gemma: String, direct: Boolean = true, confirm: Boolean = false) = runBlocking {
        val engine = FakeEngine(gemma)
        val resets = AtomicInteger()
        val resolver = FinalVoiceInputResolver(engine,
            resetConversation = { resets.incrementAndGet() },
            benchmark = PipelineBenchmarkCapture("turn", "test", 0, PipelineBenchmarkProvenance("test", 1)),
            turnTrace = VoiceTurnTrace("turn"), comparison = null,
            recordDiagnostic = {}, reportStatus = {})
        Triple(resolver.resolve(asr, byteArrayOf(1, 2, 3), direct, null, "test", confirm), engine, resets)
    }

    @Test fun bothModesPreferRecognizerTextOverGemmaCaption() {
        for (direct in listOf(false, true)) {
            val (result, engine) = resolve("recognized words", "different Gemma caption", direct).let { it.first to it.second }
            assertEquals("recognized words", result.text)
            assertEquals(0, engine.toolPolicyCalls.size)
            assertEquals(0, engine.generateCalls)
        }
    }

    @Test fun bothModesUseGemmaTranscriptionOnlyWhenRecognizerIsBlank() {
        for (direct in listOf(false, true)) {
            val (result, engine) = resolve("", "Gemma transcription", direct).let { it.first to it.second }
            assertEquals("Gemma transcription", result.text)
            assertEquals(1, engine.generateCalls)
        }
    }

    @Test fun bothModesRecordRecognizerTextAndUseGemmaOnlyAsFallback() = runBlocking {
        for ((asr, gemma, expected) in listOf(
            Triple("recognizer text", "Gemma caption", "recognizer text"),
            Triple("", "Gemma transcription", "Gemma transcription"))) {
            for (direct in listOf(false, true)) {
                val resolved = resolve(asr, gemma, direct).first.text
                assertEquals(expected, resolved)
                val calls = mutableListOf<VoiceCallRecord>()
                val session = VoiceSessionController(object : VoiceCallStore {
                    override fun list() = calls.toList()
                    override fun save(call: VoiceCallRecord) { calls.removeAll { it.id == call.id }; calls += call }
                    override fun delete(callId: String) { calls.removeAll { it.id == callId } }
                })
                session.beginCall()
                VoiceTurnCoordinator(session).processTurn(resolved) { emit -> emit("answer"); "answer" }
                assertEquals(expected, session.currentTranscript().first().text)
            }
        }
    }

    @Test fun confirmedAudioFollowupReturnsHeardText() {
        val (result, engine) = resolve("", "confirmed spoken words", confirm = true).let { it.first to it.second }
        assertEquals("confirmed spoken words", result.text)
        assertEquals(listOf(false), engine.toolPolicyCalls)
    }
}
