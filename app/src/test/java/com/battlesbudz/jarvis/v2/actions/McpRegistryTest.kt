package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Finding 6 (schema-review bypass) regression tests: a server's tool-schema
 * change must never silently re-enable a changed tool, and a refresh must
 * never silently clear a pending review.
 */
class McpRegistryTest {

    private class FakeMcpHttp(var toolsJson: String) : McpHttpClient {
        /** When non-null, every initialize post throws (network failure). */
        var failInitialize: Exception? = null
        /** When non-null, every tools/list post throws (discovery failure). */
        var failToolsList: Exception? = null
        var initializeStatus: Int = 200
        var initializeVersion: String = "2025-06-18"
        var toolsStatus: Int = 200

        override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse {
            return when {
                body.contains("tools/list") -> {
                    failToolsList?.let { throw it }
                    McpHttpResponse(toolsStatus, toolsJson, emptyMap())
                }
                body.contains("initialize") -> {
                    failInitialize?.let { throw it }
                    if (initializeStatus != 200) return McpHttpResponse(initializeStatus, "", emptyMap())
                    McpHttpResponse(200,
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"$initializeVersion"}}""",
                        emptyMap())
                }
                else -> McpHttpResponse(200,
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                    emptyMap())
            }
        }
    }

    private fun toolsPayload(
        vararg tools: Triple<String, String, String>,
        scopes: List<String> = emptyList()
    ): String {
        // (name, inputSchemaJson, pricing); scopes apply to every tool in the payload.
        // The scopes field name is "x-jarvis-scopes" (see McpProtocol.parseToolsList).
        val arr = tools.joinToString(",") { (name, schema, pricing) ->
            val scopesJson = if (scopes.isEmpty()) "" else
                ""","x-jarvis-scopes":[${scopes.joinToString(",") { "\"$it\"" }}]"""
            """{"name":"$name","description":"d","inputSchema":$schema,"x-jarvis-pricing":"$pricing"$scopesJson}"""
        }
        return """{"jsonrpc":"2.0","id":2,"result":{"tools":[$arr]}}"""
    }

    private val searchV1 = """{"type":"object","properties":{"q":{"type":"string"}}}"""
    private val searchV2 =
        """{"type":"object","properties":{"q":{"type":"string"},"limit":{"type":"integer"}}}"""

    private fun setup(
        vararg tools: Triple<String, String, String>,
        scopes: List<String> = emptyList()
    ): Pair<McpRegistry, FakeMcpHttp> {
        val http = FakeMcpHttp(toolsPayload(*tools, scopes = scopes))
        val flow = McpSetupFlow(http, InMemoryMcpCredentialStore())
        val result = flow.run("Test Server", "https://example.com/mcp", null)
        assertTrue("setup must connect: $result", result is McpSetupFlow.FlowResult.Connected)
        val registry = McpRegistry(http, InMemoryMcpCredentialStore())
        registry.add((result as McpSetupFlow.FlowResult.Connected).status)
        return registry to http
    }

    private fun serverId(registry: McpRegistry) = registry.all().single().config.id

    @Test fun acknowledgeReenablesOnlyUnchangedFreeTools() {
        val (registry, http) = setup(
            Triple("search", searchV1, "free"),
            Triple("summarize", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val id = serverId(registry)
        assertEquals("free tools enabled by default; paid stays disabled",
            setOf("search", "summarize"), registry.status(id)!!.enabledTools)
        // The server changes search's schema and adds a new free tool.
        http.toolsJson = toolsPayload(
            Triple("search", searchV2, "free"),
            Triple("summarize", searchV1, "free"),
            Triple("archive", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, refreshed.state)
        assertTrue(registry.acknowledgeSchemaChange(id))
        val after = registry.status(id)!!
        assertEquals(McpServerState.CONNECTED, after.state)
        assertEquals("only the unchanged free tool stays enabled; the changed " +
            "and the new tool stay disabled until explicitly enabled",
            setOf("summarize"), after.enabledTools)
        // After review the user can explicitly re-enable a changed tool.
        assertTrue(registry.setToolEnabled(id, "search", true))
        assertTrue(registry.status(id)!!.enabledTools.contains("search"))
    }

    @Test fun refreshNeverSilentlyClearsPendingReview() {
        val (registry, http) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        http.toolsJson = toolsPayload(Triple("search", searchV2, "free"))
        assertEquals(McpServerState.SCHEMA_CHANGED, registry.refresh(id).state)
        // A second refresh with the same changed tools must NOT flip back to
        // CONNECTED on its own — the review is still pending.
        val again = registry.refresh(id)
        assertEquals("a refresh must never auto-acknowledge a pending review",
            McpServerState.SCHEMA_CHANGED, again.state)
    }

    @Test fun unchangedRefreshKeepsEnabledTools() {
        val (registry, http) = setup(
            Triple("search", searchV1, "free"),
            Triple("render", searchV1, "paid"))
        val id = serverId(registry)
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.CONNECTED, refreshed.state)
        assertEquals(setOf("search"), refreshed.enabledTools)
    }

    @Test fun acknowledgeRequiresPendingReview() {
        val (registry, _) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        assertFalse(registry.acknowledgeSchemaChange(id))
        assertFalse(registry.acknowledgeSchemaChange("no-such-server"))
    }

    // -- Finding 6 (round 3): a pending review survives failures and disconnects ---

    /**
     * One free tool, then a refresh with its changed schema: the
     * review-pending baseline every failure/disconnect test starts from.
     */
    private fun pendingReview(): Triple<McpRegistry, FakeMcpHttp, String> {
        val (registry, http) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        http.toolsJson = toolsPayload(Triple("search", searchV2, "free"))
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, refreshed.state)
        assertTrue("search" in refreshed.changedToolNames)
        return Triple(registry, http, id)
    }

    @Test fun pendingReviewSurvivesNetworkFailure() {
        val (registry, http, id) = pendingReview()
        // A failed refresh must preserve the pending review, not clear or grant it.
        http.failInitialize = IOException("no route")
        val failed = registry.refresh(id)
        assertEquals(McpServerState.UNAVAILABLE, failed.state)
        assertNotNull("the pending discovery survives the failure",
            registry.status(id)!!.pendingSchemaHash)
        assertTrue("search" in registry.status(id)!!.changedToolNames)
        // Recovery sees the same changed tools: still SCHEMA_CHANGED, never CONNECTED.
        http.failInitialize = null
        val recovered = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, recovered.state)
        assertTrue("search" in recovered.changedToolNames)
        // The tool was retained as enabled-but-blocked, not granted: calls stay blocked.
        assertTrue("search" in recovered.enabledTools)
        val invoker = McpInvoker(registry, http, InMemoryMcpCredentialStore())
        val blocked = invoker.invoke(ProviderCall(ProviderId.mcp(id), "search"))
        assertTrue(blocked is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE,
            (blocked as ProviderCallResult.TypedError).code)
    }

    @Test fun pendingReviewSurvivesAuthFailure() {
        val (registry, http, id) = pendingReview()
        http.initializeStatus = 401
        val denied = registry.refresh(id)
        assertEquals(McpServerState.DENIED, denied.state)
        assertTrue(denied.explanation.contains("credentials"))
        assertNotNull("the pending discovery survives the auth failure",
            registry.status(id)!!.pendingSchemaHash)
        assertFalse("a denied server cannot be reviewed without a fresh refresh",
            registry.acknowledgeSchemaChange(id))
        http.initializeStatus = 200
        val recovered = registry.refresh(id)
        assertEquals("a failed-then-recovered server must refresh back to " +
            "SCHEMA_CHANGED before review", McpServerState.SCHEMA_CHANGED, recovered.state)
        assertTrue("search" in recovered.changedToolNames)
    }

    @Test fun pendingReviewSurvivesVersionMismatch() {
        val (registry, http, id) = pendingReview()
        http.initializeVersion = "1999-01-01"
        val mismatch = registry.refresh(id)
        assertEquals(McpServerState.VERSION_MISMATCH, mismatch.state)
        assertTrue(mismatch.explanation.startsWith("Version mismatch"))
        assertNotNull("the pending discovery survives the version mismatch",
            registry.status(id)!!.pendingSchemaHash)
        assertFalse(registry.acknowledgeSchemaChange(id))
        http.initializeVersion = "2025-06-18"
        val recovered = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, recovered.state)
        assertTrue("search" in recovered.changedToolNames)
    }

    @Test fun pendingReviewSurvivesDiscoveryFailure() {
        val (registry, http, id) = pendingReview()
        http.failToolsList = IOException("dropped")
        val failed = registry.refresh(id)
        assertEquals(McpServerState.UNAVAILABLE, failed.state)
        assertNotNull("the pending discovery survives the discovery failure",
            registry.status(id)!!.pendingSchemaHash)
        assertTrue("search" in registry.status(id)!!.changedToolNames)
        http.failToolsList = null
        val recovered = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, recovered.state)
        assertTrue("search" in recovered.changedToolNames)
    }

    @Test fun pendingReviewSurvivesDisconnectReconnect() {
        val (registry, http, id) = pendingReview()
        assertTrue(registry.disconnect(id))
        assertEquals(McpServerState.DISABLED, registry.status(id)!!.state)
        assertNotNull("disconnect keeps the pending discovery",
            registry.status(id)!!.pendingSchemaHash)
        // Reconnecting (a refresh) with the same changed tools returns to
        // SCHEMA_CHANGED, never straight to CONNECTED.
        val reconnected = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, reconnected.state)
        assertTrue("search" in reconnected.changedToolNames)
        assertTrue(reconnected.explanation.startsWith("Still waiting for review"))
    }

    @Test fun scopeOnlyChangeRequiresReEnablement() {
        // A scope-only change used to slip through: the per-tool fingerprint
        // covered only inputSchemaJson while the whole-list hash covered
        // scopes, so the tool never landed in changedToolNames and
        // acknowledgment kept it enabled.
        val (registry, http) = setup(Triple("search", searchV1, "free"), scopes = listOf("read"))
        val id = serverId(registry)
        assertEquals(setOf("search"), registry.status(id)!!.enabledTools)
        // Identical schema and description; only the scopes widen.
        http.toolsJson = toolsPayload(Triple("search", searchV1, "free"), scopes = listOf("read", "write"))
        val refreshed = registry.refresh(id)
        assertEquals(McpServerState.SCHEMA_CHANGED, refreshed.state)
        assertTrue("a scope-only change must require re-enablement",
            "search" in refreshed.changedToolNames)
        assertTrue(registry.acknowledgeSchemaChange(id))
        val after = registry.status(id)!!
        assertEquals(McpServerState.CONNECTED, after.state)
        assertFalse("the scope-changed tool stays disabled until explicitly re-enabled",
            "search" in after.enabledTools)
        assertTrue(registry.setToolEnabled(id, "search", true))
        assertTrue("search" in registry.status(id)!!.enabledTools)
    }

    @Test fun removedAndReaddedIdenticalToolStaysSafe() {
        val (registry, http) = setup(Triple("search", searchV1, "free"))
        val id = serverId(registry)
        // The server drops the tool: review pending.
        http.toolsJson = toolsPayload()
        assertEquals(McpServerState.SCHEMA_CHANGED, registry.refresh(id).state)
        // The server re-adds the identical tool: the discovered list matches
        // the reviewed fingerprint again, so the pending review clears
        // honestly and the tool keeps its reviewed enablement. Contract: a
        // revert is not a grant. The fingerprints must match the reviewed
        // baseline exactly for this path.
        http.toolsJson = toolsPayload(Triple("search", searchV1, "free"))
        val reverted = registry.refresh(id)
        assertEquals(McpServerState.CONNECTED, reverted.state)
        assertNull(reverted.pendingSchemaHash)
        assertTrue("the reverted tool keeps its reviewed enablement",
            "search" in reverted.enabledTools)
    }
}
