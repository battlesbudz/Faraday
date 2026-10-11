package com.battlesbudz.jarvis.v2.voice.whisper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class WhisperDecoderTest {
    private class Decoder(override val backend: String, val action: () -> String = { "text" }) : WhisperDecoder {
        var calls = 0; var closes = 0; var cancels = 0
        override fun decode(samples: FloatArray): String { calls++; return action() }
        override fun close() { closes++ }
        override fun cancel() { cancels++ }
    }
    @Test fun setupFailureUsesIndependentCpuAndReportsIt() {
        val cpu = Decoder("cpu"); val logs = mutableListOf<String>()
        val decoder = FallbackWhisperDecoder({ error("provider missing") }, { cpu }, logs::add)
        assertEquals("text", decoder.decode(floatArrayOf(0f))); assertEquals("cpu", decoder.backend)
        assertEquals(1, cpu.calls); assertTrue(logs.single().contains("qnn_setup_unavailable"))
        decoder.close(); decoder.close(); assertEquals(1, cpu.closes)
    }
    @Test fun linkageFailureAlsoFallsBackWithoutMasqueradingAsNpu() {
        val decoder = FallbackWhisperDecoder({ throw UnsatisfiedLinkError() }, { Decoder("cpu") }, {})
        assertEquals("cpu", decoder.backend); decoder.close()
    }
    @Test fun runtimeFailureClosesQnnBeforeCpuAndRetriesOnlyOnce() {
        val qnn = Decoder("qnn") { error("device failure") }; val cpu = Decoder("cpu")
        val decoder = FallbackWhisperDecoder({ qnn }, { assertEquals(1, qnn.closes); cpu }, {})
        repeat(2) { assertEquals("text", decoder.decode(floatArrayOf(0f))) }
        assertEquals(1, qnn.calls); assertEquals(2, cpu.calls); assertEquals("cpu", decoder.backend)
        decoder.close()
    }
    @Test fun eachDecodeLabelsOnlyItsOwnWorkAndCpuFailurePropagates() {
        var count = 0
        val qnn = Decoder("qnn") { if (count++ == 0) "first" else error("htp") }
        val decoder = FallbackWhisperDecoder({ qnn }, { Decoder("cpu") }, {})
        decoder.decode(floatArrayOf(0f)); assertEquals("qnn", decoder.lastDecodeBackend)
        decoder.decode(floatArrayOf(0f)); assertEquals("qnn_failed_attempt_then_sherpa_cpu", decoder.lastDecodeBackend)
        decoder.decode(floatArrayOf(0f)); assertEquals("cpu", decoder.lastDecodeBackend)
        assertEquals("cpu", decoder.backend); decoder.close()
        val failed = FallbackWhisperDecoder({ error("no htp") }, { Decoder("cpu") { error("cpu failed") } }, {})
        assertThrows(IllegalStateException::class.java) { failed.decode(floatArrayOf(0f)) }; failed.close()
    }
    @Test fun cancellationNeverRetriesOnCpuAndRevokesFutureDecodes() {
        val qnn = Decoder("qnn") { throw CancellationException() }; var cpu = 0
        val decoder = FallbackWhisperDecoder({ qnn }, { cpu++; Decoder("cpu") }, {})
        assertThrows(CancellationException::class.java) { decoder.decode(floatArrayOf(0f)) }
        decoder.cancel(); assertEquals(1, qnn.cancels)
        assertThrows(CancellationException::class.java) { decoder.decode(floatArrayOf(0f)) }
        assertEquals(0, cpu); decoder.close()
    }
}
