package com.battlesbudz.jarvis.v2.actions

import org.json.JSONArray
import org.json.JSONObject

/**
 * M3 MCP protocol surface (T17): JSON-RPC message builders and parsers for
 * the Streamable-HTTP-style handshake — `initialize` with protocol-version
 * negotiation, `tools/list` discovery and `tools/call` invocation.
 *
 * Secrets never appear here: authentication travels only in HTTP headers,
 * supplied by the transport layer from the credential store.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val scopes: Set<String>,
    val pricing: ProviderPricing
)

object McpProtocol {
    const val JSONRPC = "2.0"

    /** Newest first; the server picks the newest version it also supports. */
    val CLIENT_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26")

    const val SESSION_HEADER = "mcp-session-id"

    fun initializeRequest(requestId: Int, clientName: String = "Jarvis"): String =
        JSONObject()
            .put("jsonrpc", JSONRPC)
            .put("id", requestId)
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", CLIENT_PROTOCOL_VERSIONS.first())
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject()
                    .put("name", clientName)
                    .put("version", "0.1.0")))
            .toString()

    sealed interface Negotiation {
        data class Ok(val protocolVersion: String, val sessionId: String?) : Negotiation
        data class Failed(val reason: String) : Negotiation
    }

    /**
     * Parse an initialize response. A protocol version outside
     * [CLIENT_PROTOCOL_VERSIONS] is a hard version mismatch — never silently
     * accepted.
     */
    fun parseInitializeResponse(
        httpStatus: Int,
        body: String,
        responseHeaders: Map<String, String>
    ): Negotiation {
        if (httpStatus == 401 || httpStatus == 403) {
            return Negotiation.Failed("The server rejected the credentials (HTTP $httpStatus).")
        }
        if (httpStatus !in 200..299) {
            return Negotiation.Failed("The server answered HTTP $httpStatus during version negotiation.")
        }
        val payload = extractPayload(body) ?: return Negotiation.Failed("The server's reply was not JSON-RPC.")
        if (payload.has("error")) {
            val error = payload.optJSONObject("error")
            return Negotiation.Failed("The server refused initialization: " +
                "${error?.optString("message", "unknown error")}.")
        }
        val result = payload.optJSONObject("result")
            ?: return Negotiation.Failed("The server's initialize reply had no result.")
        val version = result.optString("protocolVersion", "")
        if (version !in CLIENT_PROTOCOL_VERSIONS) {
            return Negotiation.Failed(
                "Version mismatch: the server speaks protocol '$version', " +
                    "this client supports ${CLIENT_PROTOCOL_VERSIONS.joinToString(", ")}.")
        }
        val sessionId = responseHeaders.entries
            .firstOrNull { it.key.equals(SESSION_HEADER, ignoreCase = true) }?.value
        return Negotiation.Ok(version, sessionId)
    }

    fun initializedNotification(): String = JSONObject()
        .put("jsonrpc", JSONRPC)
        .put("method", "notifications/initialized")
        .toString()

    fun toolsListRequest(requestId: Int): String = JSONObject()
        .put("jsonrpc", JSONRPC)
        .put("id", requestId)
        .put("method", "tools/list")
        .put("params", JSONObject())
        .toString()

    sealed interface ToolsList {
        data class Ok(val tools: List<McpTool>) : ToolsList
        data class Failed(val reason: String) : ToolsList
    }

    fun parseToolsList(httpStatus: Int, body: String): ToolsList {
        if (httpStatus !in 200..299) {
            return ToolsList.Failed("The server answered HTTP $httpStatus to tools/list.")
        }
        val payload = extractPayload(body) ?: return ToolsList.Failed("The server's tools reply was not JSON-RPC.")
        if (payload.has("error")) {
            return ToolsList.Failed("The server refused tools/list: " +
                "${payload.optJSONObject("error")?.optString("message", "unknown error")}.")
        }
        val result = payload.optJSONObject("result")
            ?: return ToolsList.Failed("The server's tools reply had no result.")
        val tools = result.optJSONArray("tools") ?: JSONArray()
        val parsed = arrayListOf<McpTool>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optString("name", "").trim()
            if (name.isEmpty() || name.length > 64) continue
            val scopes = tool.optJSONArray("x-jarvis-scopes")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it, null) }
                    .filter { it.isNotBlank() && it.length <= 64 }.toSet()
            } ?: setOf(name)
            val pricing = when (tool.optString("x-jarvis-pricing", "").lowercase()) {
                "free" -> ProviderPricing.FREE
                "paid" -> ProviderPricing.PAID
                // Absent or unrecognized: UNKNOWN. Never presented as free.
                else -> ProviderPricing.UNKNOWN
            }
            parsed += McpTool(
                name = name,
                description = tool.optString("description", ""),
                inputSchemaJson = tool.optJSONObject("inputSchema")?.toString() ?: "{}",
                scopes = scopes,
                pricing = pricing
            )
        }
        return ToolsList.Ok(parsed)
    }

    fun callToolRequest(requestId: Int, name: String, arguments: Map<String, Any?>): String =
        JSONObject()
            .put("jsonrpc", JSONRPC)
            .put("id", requestId)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", name)
                .put("arguments", toJsonValue(arguments) as JSONObject))
            .toString()

    fun parseCallToolResponse(httpStatus: Int, body: String): ProviderCallResult {
        if (httpStatus == 401 || httpStatus == 403) {
            return ProviderCallResult.TypedError(ProviderErrorCode.AUTH_FAILED,
                "The server rejected the credentials (HTTP $httpStatus).")
        }
        if (httpStatus == 404) {
            return ProviderCallResult.TypedError(ProviderErrorCode.NETWORK_ERROR,
                "The server session expired (HTTP 404). Reconnect before retrying.")
        }
        if (httpStatus !in 200..299) {
            return ProviderCallResult.TypedError(ProviderErrorCode.NETWORK_ERROR,
                "The server answered HTTP $httpStatus to tools/call.")
        }
        val payload = extractPayload(body)
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN,
                "The server's reply was not JSON-RPC.")
        if (payload.has("error")) {
            return ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN,
                "The tool call failed: ${payload.optJSONObject("error")?.optString("message", "unknown error")}.")
        }
        val result = payload.optJSONObject("result")
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN,
                "The tool call reply had no result.")
        if (result.optBoolean("isError", false)) {
            val text = contentText(result)
            return ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN,
                "The tool reported an error: ${text.ifBlank { "no details" }}.")
        }
        val uri = result.optJSONArray("content")?.let { content ->
            (0 until content.length()).asSequence()
                .mapNotNull { content.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "resource_link" }
                ?.optString("uri")
        }
        if (!uri.isNullOrBlank()) {
            return ProviderCallResult.UriResult(uri, result.optString("uriLabel", uri))
        }
        val text = contentText(result)
        return ProviderCallResult.Success(
            data = mapOf("text" to text),
            receipt = text.take(512).ifBlank { "The tool ran and returned no text." })
    }

    private fun contentText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        return (0 until content.length()).asSequence()
            .mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .map { it.optString("text", "") }
            .joinToString("\n").trim()
    }

    /**
     * Extract the JSON-RPC payload, tolerating a Streamable-HTTP
     * `text/event-stream` envelope by taking the last `data:` line.
     */
    fun extractPayload(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) return try { JSONObject(trimmed) } catch (_: Exception) { null }
        val dataLines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()
        val last = dataLines.lastOrNull() ?: return null
        return try { JSONObject(last) } catch (_: Exception) { null }
    }

    /** Canonical form of a tool-schema document, for change detection. */
    fun canonicalSchema(schemaJson: String): String {
        val parsed = try { JSONObject(schemaJson) } catch (_: Exception) { return schemaJson }
        return AppFunctionTypeConverter.canonicalJson(AppFunctionTypeConverter.fromJson(parsed))
    }

    fun schemaHash(canonical: String): String = AppFunctionTypeConverter.sha256Hex(canonical)

    /** Canonical schema hash over a server's whole tool list (order-independent). */
    fun toolListHash(tools: List<McpTool>): String {
        val canonical = tools.sortedBy { it.name }.joinToString("\n") { tool ->
            "${tool.name}\n${tool.description}\n${canonicalSchema(tool.inputSchemaJson)}\n" +
                "${tool.scopes.sorted().joinToString(",")}\n${tool.pricing}"
        }
        return schemaHash(canonical)
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean -> value
        is Int, is Long, is Double, is Float -> value
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = value as Map<String, Any?>
            JSONObject().apply { for ((k, v) in map) put(k, toJsonValue(v)) }
        }
        is List<*> -> JSONArray().apply { for (item in value) put(toJsonValue(item)) }
        else -> value.toString()
    }
}
