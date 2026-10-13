package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.TtsEngine
import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarksTest {
    private class Resources : PipelineBenchmarkResources {
        var metrics: Map<String, Double?> = mapOf("process_cpu_time_ms" to 10.0, "unavailable" to null)
        var battery: Int? = 70
        var samples = 0
        override fun provenance(models: Map<String, PipelineBenchmarkModel>, configuration: Map<String, String>) =
            PipelineBenchmarkProvenance("fixture", 1, models = models, configuration = configuration)
        override fun sample(): Map<String, Double?> { samples++; return metrics }
        override fun batteryPercent(): Int? = battery
    }
    private fun input(model: String, conversation: String, bytes: Long = 4096) = PipelineBenchmarkInput(
        LocalModelSpec(model, "$model.litertlm", recommendedGpu = true, expectedSha256 = "catalog-hash", contextTokens = 8192),
        bytes, conversation,
    )

    @Test fun attemptRetainsItsOriginalModelAndConversationWhenTheInputOwnerChanges() {
        var current = input("first-model", "first-conversation")
        var inputReads = 0
        val resources = Resources()
        val benchmarks = PipelineBenchmarks({ inputReads++; current }, resources)
        val first = benchmarks.create("first-turn", "voice", AsrEngine.WHISPER, TtsEngine.PIPER_NORTHERN)
        current = input("second-model", "second-conversation", 2048)
        resources.metrics = mapOf("process_cpu_time_ms" to 30.0)
        benchmarks.finishResources(first)
        val recorded = requireNotNull(first.finish(PipelineBenchmarkOutcome.CANCELLED, "first-call", "cancelled"))
        assertEquals(1, inputReads)
        assertEquals("first-conversation", recorded.conversationId)
        assertEquals("first-model", recorded.provenance.models.getValue("llm").id)
        assertEquals("catalog-hash", recorded.provenance.models.getValue("llm").assetSha256)
        assertEquals("4096", recorded.provenance.configuration["llm_asset_bytes"])
        assertEquals("8192", recorded.provenance.configuration["context_token_limit"])
        assertEquals(AsrEngine.WHISPER.id, recorded.provenance.models.getValue("asr").id)
        assertEquals(TtsEngine.PIPER_NORTHERN.id, recorded.provenance.models.getValue("tts").id)
        assertEquals(10.0, recorded.observedMetrics.getValue("process_cpu_time_ms_before")!!, 0.0)
        assertEquals(30.0, recorded.observedMetrics.getValue("process_cpu_time_ms_after")!!, 0.0)
        assertTrue(recorded.observedMetrics.containsKey("unavailable_before"))
        assertNull(recorded.observedMetrics["unavailable_before"])
        assertEquals(2, resources.samples)
        val next = requireNotNull(benchmarks.create("second-turn", "text").finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals("second-conversation", next.conversationId)
        assertEquals("second-model", next.provenance.models.getValue("llm").id)
        assertFalse(next.provenance.models.containsKey("asr"))
        assertFalse(next.provenance.models.containsKey("tts"))
        assertEquals("not_applicable", next.provenance.configuration["microphone_source_requested"])
        assertEquals("not_applicable", next.provenance.configuration["tts_threads"])
    }

    @Test fun missingOrInvalidDeviceBatteryNeverBecomesAnObservedPercentage() {
        val resources = Resources()
        val benchmarks = PipelineBenchmarks({ input("model", "conversation") }, resources)
        listOf(null, -1, 101).forEach { battery ->
            resources.battery = battery
            val capture = benchmarks.create("turn-$battery", "text")
            benchmarks.finishResources(capture)
            assertNull(capture.finish(PipelineBenchmarkOutcome.ERROR)?.observedMetrics?.get("battery_percent_after"))
        }
        resources.battery = 0
        val emptyBattery = benchmarks.create("empty-battery", "voice")
        benchmarks.finishResources(emptyBattery)
        assertEquals(0.0, emptyBattery.finish(PipelineBenchmarkOutcome.COMPLETE)?.observedMetrics?.get("battery_percent_after")!!, 0.0)
    }

    @Test fun unavailableBatteryServiceDoesNotLoseTheAttemptReceipt() {
        val resources = object : PipelineBenchmarkResources {
            override fun provenance(models: Map<String, PipelineBenchmarkModel>, configuration: Map<String, String>) =
                PipelineBenchmarkProvenance("fixture", 1, models = models, configuration = configuration)
            override fun sample() = emptyMap<String, Double?>()
            override fun batteryPercent(): Int? = error("battery unavailable")
        }
        val benchmarks = PipelineBenchmarks({ input("model", "conversation") }, resources)
        val capture = benchmarks.create("turn", "voice")
        benchmarks.finishResources(capture)
        val turn = requireNotNull(capture.finish(PipelineBenchmarkOutcome.CANCELLED, "call", "listener_cancelled"))
        assertNull(turn.observedMetrics["battery_percent_after"])
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, turn.outcome)
        assertEquals("call", turn.callId)
        assertEquals("listener_cancelled", turn.failureCode)
    }
}
