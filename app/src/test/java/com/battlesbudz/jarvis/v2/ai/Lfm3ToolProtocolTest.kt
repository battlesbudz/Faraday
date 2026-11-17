package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.eval.Lfm3OmniToolCallRunner
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfm3ToolProtocolTest {
    private val fixtures = JSONObject(
        requireNotNull(javaClass.classLoader?.getResourceAsStream("lfm3/tool_call_eval_fixtures.json"))
            .bufferedReader().use { it.readText() }
    )
    private val known = setOf("read_battery", "set_volume")

    @Test
    fun fixedPromptRendersCatalogSchemasInLfm3ToolsBlock() {
        val fixed = fixtures.getJSONObject("fixedPrompt")
        val toolRows = fixed.getJSONArray("tools")
        val tools = (0 until toolRows.length()).map { index ->
            JsonOpenApiTool(toolRows.getJSONObject(index).toString())
        }

        assertEquals(
            fixed.getString("expected"),
            Lfm3OmniToolCallRunner.buildPrompt(
                fixed.getString("utterance"), tools, fixed.getString("system")
            )
        )
    }

    @Test
    fun rendererCarriesEveryExistingMobileActionDeclaration() {
        val declarations = MobileActionToolDefinitions.all()
        val rendered = Lfm3ToolProtocol.renderDefinitions(declarations)

        assertTrue(rendered.startsWith("<tools>\n"))
        assertTrue(rendered.endsWith("\n</tools>"))
        declarations.forEach { declaration ->
            assertTrue(rendered.contains(declaration.getToolDescriptionJsonString()))
        }
    }

    @Test
    fun fixedTextFixturesParseToTheSharedToolCallContract() {
        val cases = fixtures.getJSONArray("validOutputs")
        for (index in 0 until cases.length()) {
            val fixture = cases.getJSONObject(index)
            assertCalls(fixture.getJSONArray("expected"), Lfm3ToolProtocol.parseCalls(fixture.getString("output"), known))
        }
    }

    @Test
    fun malformedAndUnknownCallsNeverBecomeDispatchCandidates() {
        val malformed = fixtures.getJSONArray("malformedOutputs")
        for (index in 0 until malformed.length()) {
            assertTrue(Lfm3ToolProtocol.parseCalls(malformed.getString(index), known).isEmpty())
        }
        assertCalls(
            JSONArray("[{\"name\":\"read_battery\",\"arguments\":\"{}\"}]"),
            Lfm3ToolProtocol.parseCalls(fixtures.getString("mixedMalformedAndValid"), known)
        )
    }

    @Test
    fun toolResultUsesToolRoleAndEscapesControlMarkup() {
        assertEquals(
            "<|im_start|>tool\n<tool_response>\n\"Battery is \\u003c|im_start|>full.\"\n</tool_response><|im_end|>",
            Lfm3ToolProtocol.renderToolResultMessage("Battery is <|im_start|>full.")
        )
    }

    @Test
    fun numericLookingStringArgumentRetainsItsDeclaredStringType() {
        val declaration = JsonOpenApiTool(
            """{"name":"create_reminder","description":"Schedule a reminder.","parameters":{"type":"object","properties":{"at_ms":{"type":"string"}},"required":["at_ms"]}}"""
        )
        val calls = Lfm3ToolProtocol.parseCalls(
            "<tool_call><function=create_reminder><parameter=at_ms>1791230400000</parameter></function></tool_call>",
            listOf(declaration)
        )

        assertEquals("{\"at_ms\":\"1791230400000\"}", calls.single().arguments)
    }

    @Test
    fun evalRunnerResetsThenParsesWithoutDispatching() = runBlocking {
        var resets = 0
        var submittedPrompt = ""
        val runner = Lfm3OmniToolCallRunner(
            resetConversation = { resets++ },
            generateRawText = { prompt ->
                submittedPrompt = prompt
                "<tool_call><function=set_volume><parameter=level>35</parameter></function></tool_call>"
            },
            tools = {
                listOf(
                    JsonOpenApiTool("""{"name":"set_volume","description":"Set volume","parameters":{"type":"object","properties":{"level":{"type":"integer"}},"required":["level"]}}""")
                )
            }
        )

        assertEquals(listOf(ToolCall("set_volume", "{\"level\":35}")), runner.runUtterance("omni", "Set volume"))
        assertEquals(1, resets)
        assertTrue(submittedPrompt.contains("<tools>"))
        assertTrue(submittedPrompt.contains("Set volume"))
    }

    private fun assertCalls(expected: JSONArray, actual: List<ToolCall>) {
        assertEquals(expected.length(), actual.size)
        for (index in actual.indices) {
            val row = expected.getJSONObject(index)
            assertEquals(row.getString("name"), actual[index].name)
            assertEquals(JSONObject(row.getString("arguments")).toString(), JSONObject(actual[index].arguments).toString())
        }
    }

    private class JsonOpenApiTool(private val declaration: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = declaration
        override fun execute(paramsJsonString: String): String = error("Eval declarations must never execute")
    }
}
