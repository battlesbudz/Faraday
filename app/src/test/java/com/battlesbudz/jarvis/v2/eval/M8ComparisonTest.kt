package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M8ComparisonTest {

    // ---- ModelLatencyTracker ----

    @Test fun percentileNearestRank() {
        // 1..10: p50 -> rank ceil(0.5*10)=5 -> 5; p95 -> rank ceil(0.95*10)=10 -> 10
        val samples = (1L..10L).toList()
        assertEquals(5L, ModelLatencyTracker.percentile(samples, 50))
        assertEquals(10L, ModelLatencyTracker.percentile(samples, 95))
    }

    @Test fun percentileSingleSample() {
        assertEquals(42L, ModelLatencyTracker.percentile(listOf(42L), 50))
        assertEquals(42L, ModelLatencyTracker.percentile(listOf(42L), 95))
    }

    @Test fun percentileEmptyIsNull() {
        assertNull(ModelLatencyTracker.percentile(emptyList(), 50))
    }

    @Test fun trackerSeparatesColdAndWarm() {
        val tracker = ModelLatencyTracker()
        tracker.record(LatencySample(isCold = true, millis = 1000))
        tracker.record(LatencySample(isCold = false, millis = 100))
        tracker.record(LatencySample(isCold = false, millis = 200))
        val stats = tracker.stats()
        assertEquals(1, stats.coldCount)
        assertEquals(2, stats.warmCount)
        assertEquals(1000L, stats.coldP50Ms)
        assertEquals(150L, stats.warmP50Ms) // ceil(0.5*2)=1 -> first of [100,200]
    }

    @Test fun timingRunnerMarksFirstUtteranceCold() = runBlocking {
        val tracker = ModelLatencyTracker()
        var now = 0L
        val inner = FakeToolCallRunner(mapOf("a" to emptyList(), "b" to emptyList()))
        val timing = TimingToolCallRunner(inner, tracker, clock = { now })
        now = 100; timing.runUtterance("m", "a") // cold: 0 elapsed
        now = 150; timing.runUtterance("m", "b") // warm: 50 elapsed
        val stats = tracker.stats()
        assertEquals(1, stats.coldCount)
        assertEquals(1, stats.warmCount)
        assertEquals(0L, stats.coldP50Ms)
        assertEquals(50L, stats.warmP50Ms)
        // after markModelLoaded the next utterance is cold again
        timing.markModelLoaded()
        now = 200; timing.runUtterance("m", "a")
        assertEquals(2, tracker.stats().coldCount)
    }

    // ---- FunctionGemmaAdapter ----

    private val tools = setOf("set_volume", "read_battery")

    @Test fun parseSingleCall() {
        val out = "thinking <start_function_call>call:set_volume{level:<escape>50<escape>}<end_function_call>"
        val calls = FunctionGemmaAdapter.parseFunctionCalls(out, tools)
        assertEquals(1, calls.size)
        assertEquals("set_volume", calls[0].name)
        assertEquals("50", JSONObject(calls[0].arguments).getString("level"))
    }

    @Test fun parseMultipleCallsInOrder() {
        val out = "<start_function_call>call:read_battery{}<end_function_call> " +
            "<start_function_call>call:set_volume{level:20}<end_function_call>"
        val calls = FunctionGemmaAdapter.parseFunctionCalls(out, tools)
        assertEquals(listOf("read_battery", "set_volume"), calls.map { it.name })
        assertEquals(20, JSONObject(calls[1].arguments).getInt("level"))
    }

    @Test fun parseNoCallBlockIsEmpty() {
        val calls = FunctionGemmaAdapter.parseFunctionCalls("Just a chat reply, no tools.", tools)
        assertTrue(calls.isEmpty())
    }

    @Test fun parseUnknownToolIsSkipped() {
        val out = "<start_function_call>call:launch_missiles{target:<escape>nowhere<escape>}<end_function_call>"
        assertTrue(FunctionGemmaAdapter.parseFunctionCalls(out, tools).isEmpty())
    }

    @Test fun parseMalformedBlockIsSkipped() {
        // missing closing brace on args
        val out = "<start_function_call>call:set_volume{level:<escape>50<escape><end_function_call>"
        assertTrue(FunctionGemmaAdapter.parseFunctionCalls(out, tools).isEmpty())
    }

    @Test fun parseUnterminatedBlockIsIgnored() {
        val out = "<start_function_call>call:set_volume{level:<escape>50<escape>}"
        assertTrue(FunctionGemmaAdapter.parseFunctionCalls(out, tools).isEmpty())
    }

    @Test fun adapterImplementsRunner() = runBlocking {
        val adapter = FunctionGemmaAdapter(
            generate = { "<start_function_call>call:read_battery{}<end_function_call>" },
            knownTools = tools
        )
        val calls: List<ToolCall> = adapter.runUtterance("fg", "battery?")
        assertEquals(1, calls.size)
        assertEquals("read_battery", calls[0].name)
    }

    @Test fun promptUsesTriggerPhraseAndDeveloperRole() {
        val prompt = FunctionGemmaAdapter.buildPrompt("hi")
        assertTrue(prompt.contains(FunctionGemmaAdapter.TRIGGER_PHRASE))
        assertTrue(prompt.contains("<start_of_turn>developer"))
    }

    // ---- AdoptionDecider ----

    private fun result(
        id: String,
        correctness: Double,
        warmP95: Long?,
        fingerprint: String? = "fp-$id"
    ) = BenchmarkResult(
        modelId = id,
        correctness = correctness,
        warmP50Ms = warmP95?.let { it - 10 },
        warmP95Ms = warmP95,
        coldP50Ms = 999,
        modelFingerprint = fingerprint,
        suiteVersion = 2
    )

    @Test fun adoptOnNonRegressionAndMeaningfulImprovement() {
        val decision = AdoptionDecider.decide(
            baseline = result("base", 0.90, 1000),
            candidate = result("cand", 0.92, 800) // 20% better, no regression
        )
        assertTrue(decision is AdoptionDecision.Adopt)
    }

    @Test fun retainOnReliabilityRegression() {
        val decision = AdoptionDecider.decide(
            baseline = result("base", 0.90, 1000),
            candidate = result("cand", 0.85, 500) // much faster but worse
        )
        assertTrue(decision is AdoptionDecision.Retain)
    }

    @Test fun retainOnInsufficientImprovement() {
        val decision = AdoptionDecider.decide(
            baseline = result("base", 0.90, 1000),
            candidate = result("cand", 0.90, 900) // only 10% < 15% bar
        )
        assertTrue(decision is AdoptionDecision.Retain)
    }

    @Test fun retainOnMissingFingerprint() {
        val decision = AdoptionDecider.decide(
            baseline = result("base", 0.90, 1000, fingerprint = null),
            candidate = result("cand", 0.95, 500)
        )
        assertTrue(decision is AdoptionDecision.Retain)
    }

    @Test fun retainOnMissingLatency() {
        val decision = AdoptionDecider.decide(
            baseline = result("base", 0.90, 1000),
            candidate = result("cand", 0.95, null)
        )
        assertTrue(decision is AdoptionDecision.Retain)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectCrossVersionComparison() {
        val base = result("base", 0.90, 1000).copy(suiteVersion = 1)
        AdoptionDecider.decide(base, result("cand", 0.95, 500))
    }
}
