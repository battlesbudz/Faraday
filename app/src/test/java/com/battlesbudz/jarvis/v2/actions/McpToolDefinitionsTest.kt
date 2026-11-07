package com.battlesbudz.jarvis.v2.actions

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Item 2 (reliability epic): the MCP declaration builder's parity contract —
 * one declaration per enabled tool on a connected server, the declaration
 * named `<server>__<tool>`, and the tool's raw input schema carried verbatim
 * as the parameters object. The list is not wired into the live conversation
 * declarations while provider exposure is disabled (see McpToolDefinitions).
 */
class McpToolDefinitionsTest {

    private val schema = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""

    private fun tool(
        name: String,
        description: String = "Description of $name",
        schema: String = this.schema
    ) = McpTool(name, description, schema, emptySet(), ProviderPricing.FREE)

    private fun status(
        id: String,
        serverName: String = id,
        state: McpServerState = McpServerState.CONNECTED,
        serverEnabled: Boolean = true,
        tools: List<McpTool> = emptyList(),
        enabledTools: Set<String> = emptySet()
    ) = McpServerStatus(
        config = McpServerConfig(id, serverName, "https://example.com/mcp", null, serverEnabled),
        state = state,
        explanation = "test",
        tools = tools,
        enabledTools = enabledTools
    )

    private fun descriptionOf(declaration: McpToolDefinitions.Declaration): JSONObject =
        JSONObject(declaration.openApiTool.getToolDescriptionJsonString())

    @Test fun oneDeclarationPerEnabledToolWithCleanNameAndRawSchema() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read"), tool("write")),
            enabledTools = setOf("read", "write")))
        val declarations = McpToolDefinitions.declarations(statuses)
        assertEquals(2, declarations.size)
        assertEquals(listOf("files__read", "files__write"), declarations.map { it.name })
        val read = descriptionOf(declarations[0])
        assertEquals("files__read", read.getString("name"))
        assertEquals("Description of read", read.getString("description"))
        // The raw schema is the parameters object, verbatim.
        assertTrue(declarations[0].openApiTool.getToolDescriptionJsonString().contains(schema))
        val parameters = read.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals("string",
            parameters.getJSONObject("properties").getJSONObject("q").getString("type"))
        assertEquals("files", declarations[0].serverId)
        assertEquals("read", declarations[0].toolName)
    }

    @Test fun disabledAndUnlistedToolsAreAbsent() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read"), tool("write"), tool("delete")),
            enabledTools = setOf("read", "ghost")))
        val declarations = McpToolDefinitions.declarations(statuses)
        assertEquals(listOf("files__read"), declarations.map { it.name })
    }

    @Test fun onlyConnectedEnabledServersContribute() {
        val one = listOf(tool("read"))
        val on = setOf("read")
        assertEquals(1, McpToolDefinitions.declarations(listOf(
            status("a", tools = one, enabledTools = on))).size)
        for (state in listOf(McpServerState.UNAVAILABLE, McpServerState.SCHEMA_CHANGED,
            McpServerState.DISABLED, McpServerState.NOT_CONFIGURED)) {
            assertTrue("state $state must contribute nothing",
                McpToolDefinitions.declarations(listOf(
                    status("a", state = state, tools = one, enabledTools = on))).isEmpty())
        }
        assertTrue(McpToolDefinitions.declarations(listOf(
            status("a", serverEnabled = false, tools = one, enabledTools = on))).isEmpty())
        assertTrue(McpToolDefinitions.declarations(emptyList()).isEmpty())
    }

    @Test fun namesAreCleanedToLegalCharacters() {
        val statuses = listOf(status("my-server",
            tools = listOf(tool("Search.Web Now")),
            enabledTools = setOf("Search.Web Now")))
        assertEquals("my_server__Search_Web_Now",
            McpToolDefinitions.declarations(statuses).single().name)
    }

    @Test fun namesAreCappedAtSixtyFourCharacters() {
        val serverId = "s".repeat(48)
        val statuses = listOf(status(serverId,
            tools = listOf(tool("t".repeat(64))),
            enabledTools = setOf("t".repeat(64))))
        val name = McpToolDefinitions.declarations(statuses).single().name
        assertEquals(McpToolDefinitions.MAX_NAME_LENGTH, name.length)
        assertTrue(name.startsWith(serverId + "__"))
    }

    @Test fun cleaningCollisionsTakeDeterministicSuffixes() {
        val statuses = listOf(status("srv",
            tools = listOf(tool("a.b"), tool("a_b")),
            enabledTools = setOf("a.b", "a_b")))
        val declarations = McpToolDefinitions.declarations(statuses)
        assertEquals(listOf("srv__a_b", "srv__a_b_2"), declarations.map { it.name })
        assertEquals(listOf("a.b", "a_b"), declarations.map { it.toolName })
    }

    @Test fun resolveInvertsTheNamespacing() {
        val statuses = listOf(status("srv",
            tools = listOf(tool("a.b"), tool("a_b")),
            enabledTools = setOf("a.b", "a_b")))
        val second = McpToolDefinitions.resolve("srv__a_b_2", statuses)
        assertNotNull(second)
        assertEquals("srv", second!!.serverId)
        assertEquals("a_b", second.toolName)
        assertEquals("a.b", McpToolDefinitions.resolve("srv__a_b", statuses)?.toolName)
        assertNull(McpToolDefinitions.resolve("srv__missing", statuses))
        assertNull(McpToolDefinitions.resolve("read_battery", statuses))
    }

    @Test fun blankToolDescriptionFallsBackToServerAndToolNames() {
        val statuses = listOf(status("files", serverName = "File Server",
            tools = listOf(tool("read", description = "")),
            enabledTools = setOf("read")))
        val description = descriptionOf(
            McpToolDefinitions.declarations(statuses).single()).getString("description")
        assertTrue(description.contains("read"))
        assertTrue(description.contains("File Server"))
    }

    @Test fun malformedSchemaDegradesToEmptyParameters() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read", schema = "not json")),
            enabledTools = setOf("read")))
        val parameters = descriptionOf(
            McpToolDefinitions.declarations(statuses).single()).getJSONObject("parameters")
        assertEquals(0, parameters.length())
    }

    @Test fun executeIsDisabledExactlyLikeBuiltInDeclarations() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read")), enabledTools = setOf("read")))
        val mcpExecute = McpToolDefinitions.declarations(statuses).single()
            .openApiTool.execute("{}")
        val builtInExecute = MobileActionToolDefinitions.all().first().execute("{}")
        assertEquals(builtInExecute, mcpExecute)
        assertTrue(mcpExecute.contains("disabled"))
    }

    @Test fun generatedNamesStayOutOfTheBuiltInNamespace() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read"), tool("read_battery")),
            enabledTools = setOf("read", "read_battery")))
        for (declaration in McpToolDefinitions.declarations(statuses)) {
            assertTrue(declaration.name.contains("__"))
            assertNull("generated name must not resolve as a built-in",
                MobileToolCatalog.find(declaration.name))
        }
    }

    @Test fun registrySnapshotOverloadMatchesStatusesOverload() {
        val statuses = listOf(status("files",
            tools = listOf(tool("read")), enabledTools = setOf("read")))
        val registry = McpRegistry(
            McpHttpClient { _, _, _ -> McpHttpResponse(200, "{}", emptyMap()) },
            InMemoryMcpCredentialStore())
        registry.add(statuses.single())
        assertEquals(
            McpToolDefinitions.all(statuses).map { it.getToolDescriptionJsonString() },
            McpToolDefinitions.all(registry).map { it.getToolDescriptionJsonString() })
    }
}
