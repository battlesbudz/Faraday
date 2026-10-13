package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M3 MCP server management (D07, T17): guided setup, custom URLs, secure
 * stored auth, session/version negotiation, discovery refresh, explicit
 * unavailable/denied states, scope limits and schema-change handling.
 *
 * Pricing policy (from the plan): free-only is the default until service
 * enablement — only FREE tools are enabled by setup; PAID and UNKNOWN stay
 * disabled until explicitly enabled. Paid enablement never waives purchase
 * confirmation ([ProviderCallResult.NeedsPurchaseConfirmation]); an unknown
 * price is never claimed to be free ([ProviderWireNames.describePricing]).
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** Opaque credential-store reference. The secret itself is never here. */
    val authRef: String?,
    val enabled: Boolean = true
) {
    init {
        require(id.matches(Regex("[a-z0-9._-]{1,48}"))) { "Server id is not usable." }
        require(name.isNotBlank() && name.length <= 64)
        require(authRef == null || authRef.matches(Regex("[a-z0-9._-]{1,64}")))
    }

    /** Redacted view for logs, receipts and settings: the secret never appears. */
    fun redacted(): String = "McpServerConfig(id=$id, name=$name, url=$url, " +
        "authRef=${if (authRef == null) "none" else "[stored]"}, enabled=$enabled)"
}

enum class McpServerState {
    NOT_CONFIGURED, CONNECTED, UNAVAILABLE, DENIED, VERSION_MISMATCH, SCHEMA_CHANGED, DISABLED
}

data class McpServerStatus(
    val config: McpServerConfig,
    val state: McpServerState,
    val explanation: String,
    val tools: List<McpTool> = emptyList(),
    /** Tools the user enabled. Setup enables only FREE tools by default. */
    val enabledTools: Set<String> = emptySet(),
    /**
     * Whole-list fingerprint at the last explicit review point (setup or
     * [McpRegistry.acknowledgeSchemaChange]). The newly discovered
     * fingerprint lives in [pendingSchemaHash] until it is explicitly
     * reviewed: a failed refresh or a disconnect must never promote it.
     */
    val schemaHash: String? = null,
    /**
     * Per-tool review fingerprints at the last explicit review point. A
     * review-relevant change must never silently re-enable a changed tool:
     * [changedToolNames] records exactly which tools changed since the user
     * last reviewed them.
     */
    val toolSchemaHashes: Map<String, String> = emptyMap(),
    /** Tool names whose review surface changed (or which are new) since the last review. */
    val changedToolNames: Set<String> = emptySet(),
    /**
     * The newly discovered whole-list fingerprint awaiting explicit review.
     * Non-null if and only if a review is pending: it is set when a refresh
     * discovers a different tool list and cleared only by explicit
     * acknowledgment (or an honest revert). Survives failed refreshes and
     * disconnects, so recovery from a failure can never grant review.
     */
    val pendingSchemaHash: String? = null,
    val protocolVersion: String? = null,
    val sessionId: String? = null
)

/**
 * Per-tool review fingerprint: covers the full review surface (input
 * schema, description, scopes and pricing), not just the schema document.
 * A scope-only change lands in [McpServerStatus.changedToolNames] and must
 * be re-enabled explicitly, exactly like a schema change.
 */
private fun toolReviewFingerprint(tool: McpTool): String = McpProtocol.schemaHash(
    listOf(
        tool.name,
        tool.description,
        McpProtocol.canonicalSchema(tool.inputSchemaJson),
        tool.scopes.sorted().joinToString(","),
        tool.pricing.toString()
    ).joinToString("\n")
)

/** Per-tool review fingerprints, for change detection finer than the whole-list hash. */
private fun toolSchemaHashes(tools: List<McpTool>): Map<String, String> =
    tools.associate { it.name to toolReviewFingerprint(it) }

/**
 * Guided setup (D07): URL entry with validation, version negotiation,
 * tool discovery, then scope/pricing review. Each stage fails with an
 * explicit reason; no stage is skipped silently.
 */
class McpSetupFlow(
    private val http: McpHttpClient,
    private val credentials: McpCredentialStore
) {
    enum class Stage { URL, NEGOTIATION, DISCOVERY, REVIEW }

    sealed interface FlowResult {
        data class Connected(val status: McpServerStatus) : FlowResult
        data class Failed(val stage: Stage, val reason: String) : FlowResult
    }

    private var requestId = 0
    private fun nextId(): Int = ++requestId

    fun run(serverName: String, rawUrl: String, authToken: String?): FlowResult {
        val name = serverName.trim().ifBlank { "MCP server" }.take(64)
        val serverId = ProviderId.sanitize(name.ifBlank { "server" })
        // Stage 1: URL.
        val url = when (val validation = McpUrlPolicy.validate(rawUrl)) {
            is McpUrlPolicy.Validation.Invalid ->
                return FlowResult.Failed(Stage.URL, validation.reason)
            is McpUrlPolicy.Validation.Valid -> validation.url
        }
        // Stage 2: version negotiation.
        val headers = mutableMapOf<String, String>()
        val token = authToken?.trim().orEmpty()
        if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
        val initResponse = try {
            http.post(url, McpProtocol.initializeRequest(nextId()), headers)
        } catch (_: Exception) {
            return FlowResult.Failed(Stage.NEGOTIATION,
                "The server could not be reached at $url. Check the URL and network.")
        }
        val negotiation = when (val result = McpProtocol.parseInitializeResponse(
            initResponse.status, initResponse.body, initResponse.headers)) {
            is McpProtocol.Negotiation.Failed ->
                return FlowResult.Failed(Stage.NEGOTIATION, result.reason)
            is McpProtocol.Negotiation.Ok -> result
        }
        // Best-effort initialized notification; a failure here is not fatal.
        try {
            val sessionHeaders = headers + sessionHeader(negotiation.sessionId)
            http.post(url, McpProtocol.initializedNotification(), sessionHeaders)
        } catch (_: Exception) { }
        // Stage 3: discovery.
        val sessionHeaders = headers + sessionHeader(negotiation.sessionId)
        val toolsResponse = try {
            http.post(url, McpProtocol.toolsListRequest(nextId()), sessionHeaders)
        } catch (_: Exception) {
            return FlowResult.Failed(Stage.DISCOVERY, "The server stopped responding during tool discovery.")
        }
        val tools = when (val parsed = McpProtocol.parseToolsList(toolsResponse.status, toolsResponse.body)) {
            is McpProtocol.ToolsList.Failed -> return FlowResult.Failed(Stage.DISCOVERY, parsed.reason)
            is McpProtocol.ToolsList.Ok -> parsed.tools
        }
        // Stage 4: review — free-only default; the caller persists the config
        // and stores the secret under its opaque reference.
        // The credential reference is unique per setup run and independent of
        // the display-name-derived server id: McpRegistry.add resolves id
        // collisions AFTER this point, so a name-based ref would overwrite
        // another server's secret and leave both configs sharing one slot.
        val authRef = if (token.isNotEmpty()) "mcp.${UUID.randomUUID()}.bearer".also {
            credentials.putSecret(it, token)
        } else null
        val config = McpServerConfig(serverId, name, url, authRef)
        return FlowResult.Connected(McpServerStatus(
            config = config,
            state = McpServerState.CONNECTED,
            explanation = buildString {
                append("Connected to $name: ${tools.size} tool(s) discovered; ")
                val free = tools.count { it.pricing == ProviderPricing.FREE }
                append("$free free tool(s) enabled by default.")
                val rest = tools.size - free
                if (rest > 0) append(" $rest tool(s) need review (paid or unknown price).")
            },
            tools = tools,
            enabledTools = tools.filter { it.pricing == ProviderPricing.FREE }.map { it.name }.toSet(),
            schemaHash = McpProtocol.toolListHash(tools),
            toolSchemaHashes = toolSchemaHashes(tools),
            protocolVersion = negotiation.protocolVersion,
            sessionId = negotiation.sessionId
        ))
    }

    private fun sessionHeader(sessionId: String?): Map<String, String> =
        if (sessionId.isNullOrBlank()) emptyMap() else mapOf(McpProtocol.SESSION_HEADER to sessionId)
}

/**
 * The connected MCP servers: refresh, enablement, disconnect and removal.
 * Schema changes invalidate cached discovery and block calls until the
 * user re-reviews; disconnect is explicit and honest.
 */
class McpRegistry(
    private val http: McpHttpClient,
    private val credentials: McpCredentialStore
) {
    private val servers = linkedMapOf<String, McpServerStatus>()
    private var requestId = 1000
    private fun nextId(): Int = ++requestId

    /** Adds a setup-flow status, assigning a unique id on collision. */
    fun add(status: McpServerStatus): McpServerStatus {
        var id = status.config.id
        var counter = 2
        while (servers.containsKey(id)) id = "${status.config.id}-$counter".also { counter++ }
        val placed = if (id == status.config.id) status
            else status.copy(config = status.config.copy(id = id))
        servers[id] = placed
        return placed
    }

    fun remove(id: String): Boolean {
        val status = servers.remove(id) ?: return false
        status.config.authRef?.let { credentials.deleteSecret(it) }
        return true
    }

    fun status(id: String): McpServerStatus? = servers[id]

    fun all(): List<McpServerStatus> = servers.values.toList()

    fun providerIdFor(serverId: String): ProviderId = ProviderId.mcp(serverId)

    fun toolsFor(serverId: String): List<McpTool> = servers[serverId]?.tools.orEmpty()

    fun enabledToolsFor(serverId: String): Set<String> = servers[serverId]?.enabledTools.orEmpty()

    /** Explicit per-tool enablement (service enablement for paid/unknown-price tools). */
    fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean): Boolean {
        val status = servers[serverId] ?: return false
        if (status.tools.none { it.name == toolName }) return false
        servers[serverId] = status.copy(
            enabledTools = if (enabled) status.enabledTools + toolName else status.enabledTools - toolName)
        return true
    }

    /** Explicit disconnect: the server stays configured but calls stop honestly. */
    fun disconnect(serverId: String): Boolean {
        val status = servers[serverId] ?: return false
        servers[serverId] = status.copy(state = McpServerState.DISABLED,
            explanation = "Disconnected. Calls to this server are unavailable until you reconnect.")
        return true
    }

    /**
     * Re-run negotiation and discovery. A changed tool list moves the
     * server to SCHEMA_CHANGED and blocks calls until re-reviewed; network
     * or auth failures move it to UNAVAILABLE/DENIED with the reason.
     *
     * A pending review is independent of connection health: failure and
     * disconnect paths keep [McpServerStatus.pendingSchemaHash],
     * [McpServerStatus.changedToolNames] and the reviewed fingerprints, so
     * recovering from a failure can never silently grant review.
     */
    fun refresh(serverId: String): McpServerStatus {
        val status = servers[serverId] ?: return McpServerStatus(
            McpServerConfig("unknown", "Unknown server", "", null, enabled = false),
            McpServerState.NOT_CONFIGURED, "No MCP server is configured with id '$serverId'.")
        val headers = mutableMapOf<String, String>()
        status.config.authRef?.let { ref ->
            credentials.getSecret(ref)?.let { secret -> headers["Authorization"] = "Bearer $secret" }
        }
        val initResponse = try {
            http.post(status.config.url, McpProtocol.initializeRequest(nextId()), headers)
        } catch (_: Exception) {
            return put(serverId, status.copy(state = McpServerState.UNAVAILABLE,
                explanation = "The server could not be reached at ${status.config.url}."))
        }
        when (val negotiation = McpProtocol.parseInitializeResponse(
            initResponse.status, initResponse.body, initResponse.headers)) {
            is McpProtocol.Negotiation.Failed -> {
                val state = when {
                    negotiation.reason.contains("credentials") -> McpServerState.DENIED
                    negotiation.reason.startsWith("Version mismatch") -> McpServerState.VERSION_MISMATCH
                    else -> McpServerState.UNAVAILABLE
                }
                return put(serverId, status.copy(state = state, explanation = negotiation.reason))
            }
            is McpProtocol.Negotiation.Ok -> {
                val sessionHeaders = headers +
                    (negotiation.sessionId?.let { mapOf(McpProtocol.SESSION_HEADER to it) }.orEmpty())
                val toolsResponse = try {
                    http.post(status.config.url, McpProtocol.toolsListRequest(nextId()), sessionHeaders)
                } catch (_: Exception) {
                    return put(serverId, status.copy(state = McpServerState.UNAVAILABLE,
                        explanation = "The server stopped responding during tool discovery."))
                }
                val tools = when (val parsed = McpProtocol.parseToolsList(toolsResponse.status, toolsResponse.body)) {
                    is McpProtocol.ToolsList.Failed -> return put(serverId,
                        status.copy(state = McpServerState.UNAVAILABLE, explanation = parsed.reason))
                    is McpProtocol.ToolsList.Ok -> parsed.tools
                }
                val newListHash = McpProtocol.toolListHash(tools)
                val newPerTool = toolSchemaHashes(tools)
                // Compare against the REVIEWED per-tool map: the pending
                // discovery must never become its own baseline.
                val changed = tools
                    .filter { toolReviewFingerprint(it) != status.toolSchemaHashes[it.name] }
                    .map { it.name }.toSet()
                val currentNames = tools.map { it.name }.toSet()
                return if (status.schemaHash != null && newListHash != status.schemaHash) {
                    // Review pending (or still pending): the reviewed
                    // baseline is untouched and the discovery is parked in
                    // pendingSchemaHash. The failure and disconnect paths
                    // use status.copy without naming these fields, so they
                    // survive them by construction.
                    put(serverId, status.copy(state = McpServerState.SCHEMA_CHANGED,
                        explanation = if (status.pendingSchemaHash != null)
                            "Still waiting for review: the server's tools changed since setup."
                        else "The server's tools changed since setup. Review them before any call runs.",
                        tools = tools, pendingSchemaHash = newListHash,
                        changedToolNames = status.changedToolNames + changed,
                        protocolVersion = negotiation.protocolVersion,
                        sessionId = negotiation.sessionId))
                } else if (status.pendingSchemaHash != null) {
                    // The discovered list matches the reviewed fingerprint
                    // again (the server reverted): clear the pending review
                    // honestly. Nothing new was granted; the reviewed
                    // baseline simply holds.
                    put(serverId, status.copy(state = McpServerState.CONNECTED,
                        explanation = "Connected: ${tools.size} tool(s) available.",
                        tools = tools,
                        enabledTools = status.enabledTools.intersect(currentNames),
                        schemaHash = newListHash, toolSchemaHashes = newPerTool,
                        changedToolNames = emptySet(), pendingSchemaHash = null,
                        protocolVersion = negotiation.protocolVersion,
                        sessionId = negotiation.sessionId))
                } else {
                    put(serverId, status.copy(state = McpServerState.CONNECTED,
                        explanation = "Connected: ${tools.size} tool(s) available.",
                        tools = tools,
                        enabledTools = status.enabledTools.intersect(currentNames),
                        schemaHash = newListHash, toolSchemaHashes = newPerTool,
                        changedToolNames = emptySet(), pendingSchemaHash = null,
                        protocolVersion = negotiation.protocolVersion,
                        sessionId = negotiation.sessionId))
                }
            }
        }
    }

    /**
     * Acknowledge a schema change after review: previously enabled tools
     * that are unchanged stay enabled; changed or new tools stay disabled
     * until explicitly enabled.
     *
     * The pending discovery parked in [McpServerStatus.pendingSchemaHash] is
     * promoted to the reviewed baseline here, explicitly, and only here: a
     * server that failed and recovered must refresh back to SCHEMA_CHANGED
     * before it can be reviewed. Recovery never grants review on its own.
     */
    fun acknowledgeSchemaChange(serverId: String): Boolean {
        val status = servers[serverId] ?: return false
        if (status.state != McpServerState.SCHEMA_CHANGED) return false
        val reviewable = status.tools
            .filter { it.pricing == ProviderPricing.FREE && it.name !in status.changedToolNames }
            .map { it.name }.toSet()
        servers[serverId] = status.copy(state = McpServerState.CONNECTED,
            explanation = "Connected: schema change reviewed; changed tools need explicit re-enablement.",
            enabledTools = status.enabledTools.intersect(reviewable),
            // The pending discovery becomes the reviewed baseline, by
            // explicit user action. It is never promoted implicitly by a refresh.
            schemaHash = status.pendingSchemaHash,
            toolSchemaHashes = toolSchemaHashes(status.tools),
            pendingSchemaHash = null,
            changedToolNames = emptySet())
        return true
    }

    private fun put(id: String, status: McpServerStatus): McpServerStatus {
        servers[id] = status
        return status
    }
}

/** Invoker that executes MCP tool calls through the registry's HTTP transport. */
class McpInvoker(
    private val registry: McpRegistry,
    private val http: McpHttpClient,
    private val credentials: McpCredentialStore
) : ProviderInvoker {
    private var requestId = 5000
    private fun nextId(): Int = ++requestId

    override fun invoke(call: ProviderCall): ProviderCallResult {
        val status = registry.status(call.provider.id)
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.NOT_FOUND,
                "No MCP server is configured with id '${call.provider.id}'.")
        if (status.state != McpServerState.CONNECTED) {
            return ProviderCallResult.TypedError(ProviderErrorCode.PROVIDER_UNAVAILABLE, status.explanation)
        }
        val tool = status.tools.firstOrNull { it.name == call.functionId }
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.NOT_FOUND,
                "The server '${status.config.name}' has no tool '${call.functionId}'.")
        if (tool.name !in status.enabledTools) {
            return ProviderCallResult.TypedError(ProviderErrorCode.NOT_ENABLED,
                "'${tool.name}' is not enabled on '${status.config.name}'. " +
                    "(${ProviderWireNames.describePricing(tool.pricing)})")
        }
        val headers = mutableMapOf<String, String>()
        status.config.authRef?.let { ref ->
            credentials.getSecret(ref)?.let { secret -> headers["Authorization"] = "Bearer $secret" }
        }
        status.sessionId?.let { headers[McpProtocol.SESSION_HEADER] = it }
        val response = try {
            http.post(status.config.url,
                McpProtocol.callToolRequest(nextId(), tool.name, call.arguments), headers)
        } catch (_: Exception) {
            return ProviderCallResult.TypedError(ProviderErrorCode.NETWORK_ERROR,
                "The server could not be reached for '${tool.name}'. Nothing was confirmed.")
        }
        return McpProtocol.parseCallToolResponse(response.status, response.body)
    }
}
