package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.MobileToolCatalog
import com.battlesbudz.jarvis.v2.actions.NativeActionDecoder
import com.battlesbudz.jarvis.v2.ai.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReliabilitySuiteTest {

    /** Builds the canonical ToolCall a well-behaved model emits for a fixture. */
    private fun canonicalCall(fixture: ReliabilityFixture): ToolCall {
        val tool = MobileToolCatalog.find(fixture.expectedTool)
            ?: error("unknown tool ${fixture.expectedTool}")
        val json = JSONObject()
        tool.parameters.forEach { parameter ->
            val value = fixture.expectedArguments[parameter.name]
                ?: error("fixture ${fixture.id} missing ${parameter.name}")
            when (parameter.type) {
                MobileToolCatalog.ParameterType.STRING -> json.put(parameter.name, value)
                MobileToolCatalog.ParameterType.INTEGER -> json.put(parameter.name, value.toInt())
            }
        }
        return ToolCall(fixture.expectedTool, json.toString())
    }

    private fun perfectRunner(): FakeToolCallRunner {
        val fixtures = ToolReliabilityFixtures.all()
        return FakeToolCallRunner(fixtures.associate { it.utterance to listOf(canonicalCall(it)) })
    }

    @Test fun everyCatalogToolHasFixtures() {
        val fixtures = ToolReliabilityFixtures.all()
        // MobileToolCatalog.all() withholds browse tools while BrowserRuntimeGate
        // is unwired (M4 hardening); fixtures may cover gated tools too.
        val catalogNames = MobileToolCatalog.all().map { it.name }.toSet()
        val fixtureTools = fixtures.map { it.expectedTool }.toSet()
        assertTrue(
            "missing fixtures for: ${catalogNames - fixtureTools}",
            fixtureTools.containsAll(catalogNames)
        )
        fixtureTools.forEach { name ->
            assertNotNull("$name is not a catalog tool", MobileToolCatalog.find(name))
        }
        catalogNames.forEach { name ->
            assertTrue("$name has fixtures", fixtures.count { it.expectedTool == name } >= 2)
        }
        assertEquals(fixtures.size, fixtures.map { it.id }.toSet().size)
    }

    @Test fun fixtureExpectedArgsSurviveStrictDecode() {
        ToolReliabilityFixtures.all().forEach { fixture ->
            val decoded = NativeActionDecoder.decodeStrict(canonicalCall(fixture))
            assertEquals(
                "fixture ${fixture.id}",
                ActionRequest(fixture.expectedTool, fixture.expectedArguments),
                decoded
            )
        }
    }

    @Test fun perfectRunScores100Percent() = runBlocking {
        val report = ToolReliabilityScorer.scoreModel(
            "TESTMODEL", ToolReliabilityFixtures.all(), perfectRunner()
        )
        assertEquals(100.0, report.percent, 0.001)
        assertTrue(report.caseResults.all { it.passed })
        assertTrue(report.failedCases.isEmpty())
    }

    @Test fun wrongToolNameFailsTheCase() = runBlocking {
        val fixture = ToolReliabilityFixtures.all().first { it.id == "set_volume_1" }
        val runner = FakeToolCallRunner(
            mapOf(fixture.utterance to listOf(ToolCall("media_control", "{\"action\":\"pause\"}")))
        )
        val result = ToolReliabilityScorer.scoreCase(fixture, runner.runUtterance("M", fixture.utterance))
        assertFalse(result.correctTool)
        assertFalse(result.argumentsValid)
        assertTrue(result.noExtraCalls)
        assertFalse(result.passed)
    }

    @Test fun outOfRangeArgumentFailsTheCase() = runBlocking {
        val fixture = ToolReliabilityFixtures.all().first { it.id == "set_volume_1" }
        val runner = FakeToolCallRunner(
            mapOf(fixture.utterance to listOf(ToolCall("set_volume", "{\"level\":999}")))
        )
        val result = ToolReliabilityScorer.scoreCase(fixture, runner.runUtterance("M", fixture.utterance))
        assertTrue(result.correctTool)
        assertFalse(result.argumentsValid)
        assertTrue(result.noExtraCalls)
        assertFalse(result.passed)
    }

    @Test fun extraCallFailsTheCase() = runBlocking {
        val fixture = ToolReliabilityFixtures.all().first { it.id == "read_battery_1" }
        val call = canonicalCall(fixture)
        val runner = FakeToolCallRunner(mapOf(fixture.utterance to listOf(call, call)))
        val result = ToolReliabilityScorer.scoreCase(fixture, runner.runUtterance("M", fixture.utterance))
        assertTrue(result.correctTool)
        assertFalse(result.noExtraCalls)
        assertFalse(result.passed)
    }

    @Test fun silenceFailsTheCase() {
        val fixture = ToolReliabilityFixtures.all().first { it.id == "read_battery_1" }
        val result = ToolReliabilityScorer.scoreCase(fixture, emptyList())
        assertFalse(result.correctTool)
        assertFalse(result.argumentsValid)
        assertFalse(result.noExtraCalls)
        assertFalse(result.passed)
    }

    @Test fun summaryRendersPercentagesAndFailures() = runBlocking {
        val fixtures = ToolReliabilityFixtures.all().filter { it.expectedTool == "read_battery" }
        val good = fixtures[0]
        val bad = fixtures[1]
        val runner = FakeToolCallRunner(
            mapOf(
                good.utterance to listOf(canonicalCall(good)),
                bad.utterance to listOf(ToolCall("open_app", "{\"app\":\"YouTube\"}"))
            )
        )
        val summary = ToolReliabilityScorer.scoreModel("TESTMODEL", fixtures, runner).renderSummary()
        assertTrue(summary.contains("TESTMODEL: 50% (1/2)"))
        assertTrue(summary.contains("read_battery: 50% (1/2)"))
        assertTrue(summary.contains("FAIL ${bad.id}"))
    }

    @Test fun inMemoryStoreRoundTripsReport() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        assertNull(store.load("M"))
        val report = ToolReliabilityScorer.scoreModel(
            "M", ToolReliabilityFixtures.all(), perfectRunner()
        )
        store.save(report, ranAtMs = 12345L)
        val stored = store.load("M")!!
        assertEquals("M", stored.modelId)
        assertEquals(100, stored.percent)
        assertEquals(report.passed, stored.passed)
        assertEquals(report.total, stored.total)
        assertEquals(12345L, stored.ranAtMs)
        assertTrue(stored.summary.contains("M: 100%"))
        store.clear("M")
        assertNull(store.load("M"))
    }
}
