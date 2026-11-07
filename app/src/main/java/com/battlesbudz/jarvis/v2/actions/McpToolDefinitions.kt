package com.battlesbudz.jarvis.v2.actions

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONObject

/**
 * Item 2 (reliability epic): the MCP half of the model's tool surface.
 *
 * Built-in tools are declared from [MobileToolCatalog] through
 * [MobileActionToolDefinitions]. This builder produces the matching
 * declarations for MCP tools: one [OpenApiTool] per ENABLED tool on a
 * CONNECTED server, carrying the tool's real description and its raw
 * `inputSchemaJson` as the parameters object.
 *
 * Declarations share one flat namespace, unlike the provider layer, which
 * identifies a tool by server + name. Each declaration is therefore named
 * `<server>__<tool>`, with both parts cleaned to legal function-name
 * characters and the whole name capped at [MAX_NAME_LENGTH]. The mapping
 * is reversible through [resolve], which regenerates the same names from
 * the same statuses instead of trying to split a cleaned name apart.
 *
 * This list is deliberately NOT merged into the live conversation
 * declarations yet. Provider exposure to the model is structurally
 * disabled ([ProviderExposure.MODEL_EXPOSURE_ENABLED] is false), no
 * conversation dispatch route from a model-emitted name to
 * [ProviderDispatcher] exists, and this codebase never advertises tools
 * the runtime cannot execute (see [MobileToolCatalog.BrowserRuntimeGate]).
 * The milestone that opens provider exposure wires these declarations in
 * together with that dispatch route; until then this builder is the
 * parity contract, pinned by McpToolDefinitionsTest.
 */
object McpToolDefinitions {
    /** Declaration-name ceiling, matching the protocol's 64-char tool-name cap. */
    const val MAX_NAME_LENGTH = 64

    /** One MCP tool prepared for declaration, with its way back to (server, tool). */
    data class Declaration(
        val serverId: String,
        val toolName: String,
        val name: String,
        val openApiTool: OpenApiTool
    )

    /** Declarations for every enabled MCP tool, in registry order. */
    fun declarations(statuses: List<McpServerStatus>): List<Declaration> {
        val used = mutableSetOf<String>()
        val built = arrayListOf<Declaration>()
        for (status in statuses) {
            if (status.state != McpServerState.CONNECTED || !status.config.enabled) continue
            for (tool in status.tools) {
                if (tool.name !in status.enabledTools) continue
                val name = uniqueName(status.config.id, tool.name, used)
                built += Declaration(
                    serverId = status.config.id,
                    toolName = tool.name,
                    name = name,
                    openApiTool = DeclaredMcpTool(descriptionJson(name, status.config.name, tool))
                )
            }
        }
        return built
    }

    /** Just the declarations, in the shape the conversation config consumes. */
    fun all(statuses: List<McpServerStatus>): List<OpenApiTool> =
        declarations(statuses).map { it.openApiTool }

    /** Just the declarations for a live registry snapshot. */
    fun all(registry: McpRegistry): List<OpenApiTool> = all(registry.all())

    /** Inverse of the namespacing: which (server, tool) a declaration name came from. */
    fun resolve(name: String, statuses: List<McpServerStatus>): Declaration? =
        declarations(statuses).firstOrNull { it.name == name }

    /**
     * `<server>__<tool>` cleaned and capped, made unique against [used]:
     * a cleaned collision takes a deterministic `_2`, `_3`, ... suffix in
     * discovery order, re-truncated to the cap. The `__` joiner also keeps
     * every generated name outside [MobileToolCatalog]'s namespace, whose
     * entries never contain a double underscore.
     */
    private fun uniqueName(serverId: String, toolName: String, used: MutableSet<String>): String {
        val base = baseName(serverId, toolName)
        if (used.add(base)) return base
        var counter = 2
        while (true) {
            val suffix = "_$counter"
            val candidate = base.take(MAX_NAME_LENGTH - suffix.length) + suffix
            if (used.add(candidate)) return candidate
            counter++
        }
    }

    private fun baseName(serverId: String, toolName: String): String {
        val server = clean(serverId)
        val tool = clean(toolName).ifBlank { "tool" }
        val joined = server + "__" + tool
        if (joined.length <= MAX_NAME_LENGTH) return joined
        // Server ids are capped at 48 chars, so the tool part always keeps
        // room; the degenerate branch only guards a future id-cap change.
        val toolRoom = MAX_NAME_LENGTH - server.length - 2
        return if (toolRoom >= 1) server + "__" + tool.take(toolRoom)
        else joined.take(MAX_NAME_LENGTH)
    }

    /** Function names keep letters, digits and underscores; everything else folds to `_`. */
    private fun clean(part: String): String =
        part.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    /** The declaration document, in the same shape as [MobileToolCatalog.Tool.schemaJson]. */
    private fun descriptionJson(name: String, serverName: String, tool: McpTool): String {
        val description = tool.description.ifBlank {
            "Call the '${tool.name}' tool on the '$serverName' MCP server."
        }
        return buildString {
            append("{\"name\":").append(JSONObject.quote(name))
            append(",\"description\":").append(JSONObject.quote(description))
            append(",\"parameters\":").append(parametersJson(tool.inputSchemaJson))
            append('}')
        }
    }

    /**
     * The tool's raw schema, verbatim — it already is the parameters
     * object. A schema that does not parse as a JSON object (impossible
     * from [McpProtocol.parseToolsList], which builds it from a parsed
     * object) degrades to an empty object rather than corrupting the
     * whole declaration list.
     */
    private fun parametersJson(inputSchemaJson: String): String =
        if (runCatching { JSONObject(inputSchemaJson) }.isSuccess) inputSchemaJson else "{}"

    private class DeclaredMcpTool(private val descriptionJson: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson

        /** Automatic LiteRT tool execution is disabled; validated runtime dispatch owns side effects. */
        override fun execute(paramsJsonString: String): String =
            "{\"error\":\"Native tool execution is disabled; Jarvis must validate and dispatch this request.\"}"
    }
}
