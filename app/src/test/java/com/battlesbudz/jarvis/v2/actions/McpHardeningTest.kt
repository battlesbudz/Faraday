package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 3 (MCP client hardening) behavior tests: tools/list pagination,
 * tool-result truncation, and expired-session recovery — exactly one
 * re-handshake and one retry, never more.
 */
class McpHardeningTest {

    /** Records every request and answers through a script. */
    private class ScriptedHttp(
        private val handler: (body: String, headers: Map<String, String>) -> McpHttpResponse
    ) : McpHttpClient {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse {
            requests += body to headers
            return handler(body, headers)
        }

        fun count(method: String): Int = requests.count { it.first.contains("\"method\":\"$method\"") }
        fun bodies(method: String): List<String> =
            requests.map { it.first }.filter { it.contains("\"method\":\"$method\"") }
    }

    private fun toolJson(name: String): String =
        """{"name":"$name","description":"d","inputSchema":{"type":"object"},"x-jarvis-pricing":"free"}"""

    private fun listPage(tools: List<String>, nextCursor: String?): String {
        val cursorJson = nextCursor?.let { ""","nextCursor":"$it"""" } ?: ""
        return """{"jsonrpc":"2.0","id":2,"result":{"tools":[${tools.joinToString(",") { toolJson(it) }}]$cursorJson}}"""
    }

    private fun initOk(sessionId: String?): McpHttpResponse {
        val headers = sessionId?.let { mapOf(McpProtocol.SESSION_HEADER to it) } ?: emptyMap()
        return McpHttpResponse(200,
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""", headers)
    }

    // -- Pagination ------------------------------------------------------

    @Test fun discoveryFollowsCursorsToTheLastPage() {
        val http = ScriptedHttp { body, _ ->
            when {
                body.contains("\"method\":\"tools/list\"") -> when {
                    body.contains("\"cursor\":\"c2\"") -> McpHttpResponse(200, listPage(listOf("beta"), "c3"), emptyMap())
                    body.contains("\"cursor\":\"c3\"") -> McpHttpResponse(200, listPage(listOf("gamma"), null), emptyMap())
                    else -> McpHttpResponse(200, listPage(listOf("alpha"), "c2"), emptyMap())
                }
                body.contains("\"method\":\"initialize\"") -> initOk("sess-1")
                else -> McpHttpResponse(202, "", emptyMap())
            }
        }
        val result = McpSetupFlow(http, InMemoryMcpCredentialStore())
            .run("Paged", "https://example.com/mcp", null)
        assertTrue(result is McpSetupFlow.FlowResult.Connected)
        val status = (result as McpSetupFlow.FlowResult.Connected).status
        assertEquals(listOf("alpha", "beta", "gamma"), status.tools.map { it.name })
        assertEquals("exactly three list pages", 3, http.count("tools/list"))
        assertTrue("the first page carries no cursor",
            !http.bodies("tools/list").first().contains("\"cursor\""))
    }

    @Test fun discoveryStopsAtTheTwentyPageCap() {
        val http = ScriptedHttp { body, _ ->
            when {
                body.contains("\"method\":\"tools/list\"") ->
                    McpHttpResponse(200, listPage(listOf("tool"), "always-more"), emptyMap())
                body.contains("\"method\":\"initialize\"") -> initOk("sess-1")
                else -> McpHttpResponse(202, "", emptyMap())
            }
        }
        val result = McpSetupFlow(http, InMemoryMcpCredentialStore())
            .run("Endless", "https://example.com/mcp", null)
        assertTrue(result is McpSetupFlow.FlowResult.Connected)
        assertEquals(MAX_TOOLS_LIST_PAGES, http.count("tools/list"))
        assertEquals(MAX_TOOLS_LIST_PAGES,
            (result as McpSetupFlow.FlowResult.Connected).status.tools.size)
    }

    @Test fun aFailedPageFailsTheWholeDiscovery() {
        val http = ScriptedHttp { body, _ ->
            when {
                body.contains("\"method\":\"tools/list\"") ->
                    if (body.contains("\"cursor\"")) McpHttpResponse(500, "", emptyMap())
                    else McpHttpResponse(200, listPage(listOf("alpha"), "c2"), emptyMap())
                body.contains("\"method\":\"initialize\"") -> initOk("sess-1")
                else -> McpHttpResponse(202, "", emptyMap())
            }
        }
        val result = McpSetupFlow(http, InMemoryMcpCredentialStore())
            .run("Paged", "https://example.com/mcp", null)
        assertTrue(result is McpSetupFlow.FlowResult.Failed)
        assertEquals(McpSetupFlow.Stage.DISCOVERY,
            (result as McpSetupFlow.FlowResult.Failed).stage)
    }

    // -- Truncation ------------------------------------------------------

    private fun callResponseWithText(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"$escaped"}]}}"""
    }

    @Test fun oversizedToolResultsAreTruncatedWithAnExplicitMarker() {
        val parsed = McpProtocol.parseCallToolResponse(200, callResponseWithText("x".repeat(10_000)))
        assertTrue(parsed is ProviderCallResult.Success)
        val text = (parsed as ProviderCallResult.Success).data["text"] as String
        assertEquals(
            McpProtocol.MAX_TOOL_RESULT_CHARS + "\n[truncated, 2000 more characters]".length,
            text.length)
        assertTrue(text.endsWith("[truncated, 2000 more characters]"))
        assertEquals("the stored receipt stays clipped as before", 512, parsed.receipt.length)
    }

    @Test fun resultsAtOrUnderTheCapPassThroughUntouched() {
        val exact = "y".repeat(McpProtocol.MAX_TOOL_RESULT_CHARS)
        val parsed = McpProtocol.parseCallToolResponse(200, callResponseWithText(exact))
        assertEquals(exact, (parsed as ProviderCallResult.Success).data["text"])
        val oneOver = McpProtocol.truncateResultText("z".repeat(McpProtocol.MAX_TOOL_RESULT_CHARS + 1))
        assertTrue(oneOver.endsWith("[truncated, 1 more characters]"))
    }

    // -- Expired-session recovery ----------------------------------------

    /**
     * A connected registry against a scripted server. [callBehavior]
     * decides each tools/call answer from the session header presented.
     */
    private inner class SessionWorld(
        val sessionOnInit: (initCount: Int) -> String?,
        val initStatus: () -> Int = { 200 },
        val callBehavior: (sessionHeader: String?) -> McpHttpResponse
    ) {
        var initCount = 0
        val http = ScriptedHttp { body, headers ->
            when {
                body.contains("\"method\":\"tools/call\"") ->
                    callBehavior(headers[McpProtocol.SESSION_HEADER])
                body.contains("\"method\":\"tools/list\"") ->
                    McpHttpResponse(200, listPage(listOf("echo"), null), emptyMap())
                body.contains("\"method\":\"notifications/initialized\"") ->
                    McpHttpResponse(202, "", emptyMap())
                body.contains("\"method\":\"initialize\"") -> {
                    initCount++
                    if (initStatus() != 200) McpHttpResponse(initStatus(), "", emptyMap())
                    else initOk(sessionOnInit(initCount))
                }
                else -> McpHttpResponse(202, "", emptyMap())
            }
        }
        val credentials = InMemoryMcpCredentialStore()
        val registry = McpRegistry(http, credentials)
        val serverId: String

        init {
            val connected = McpSetupFlow(http, credentials)
                .run("Session Server", "https://example.com/mcp", null)
                    as McpSetupFlow.FlowResult.Connected
            serverId = registry.add(connected.status).config.id
        }

        fun invoke(): ProviderCallResult =
            McpInvoker(registry, http, credentials)
                .invoke(ProviderCall(ProviderId.mcp(serverId), "echo", mapOf("message" to "hi")))
    }

    private fun sessionGone(): McpHttpResponse = McpHttpResponse(404,
        """{"jsonrpc":"2.0","error":{"code":-32001,"message":"Session not found"},"id":9}""",
        emptyMap())

    private fun callOk(text: String): McpHttpResponse = McpHttpResponse(200,
        """{"jsonrpc":"2.0","id":9,"result":{"content":[{"type":"text","text":"$text"}]}}""",
        emptyMap())

    @Test fun droppedSessionRehandshakesOnceAndRetriesOnce() {
        val world = SessionWorld(
            sessionOnInit = { count -> if (count == 1) "s1" else "s2" },
            callBehavior = { session -> if (session == "s2") callOk("recovered") else sessionGone() })
        val result = world.invoke()
        assertTrue("the retried call succeeds: $result", result is ProviderCallResult.Success)
        assertEquals("recovered", (result as ProviderCallResult.Success).data["text"])
        assertEquals("setup handshake + exactly one recovery handshake", 2, world.initCount)
        assertEquals("the original call + exactly one retry", 2, world.http.count("tools/call"))
        assertEquals("the fresh session is stored", "s2", world.registry.status(world.serverId)!!.sessionId)
    }

    @Test fun failedRehandshakeSurfacesTheOriginalSessionError() {
        var initHealthy = true
        val world = SessionWorld(
            sessionOnInit = { "s1" },
            initStatus = { if (initHealthy) 200 else 500 },
            callBehavior = { sessionGone() })
        initHealthy = false
        val result = world.invoke()
        assertTrue(result is ProviderCallResult.TypedError)
        val error = result as ProviderCallResult.TypedError
        assertEquals(ProviderErrorCode.NETWORK_ERROR, error.code)
        assertTrue(error.message.contains("session expired"))
        assertEquals("no retry is attempted after a failed re-handshake",
            1, world.http.count("tools/call"))
    }

    @Test fun aSecond404AfterRecoverySurfacesTheHonestError() {
        val world = SessionWorld(
            sessionOnInit = { count -> if (count == 1) "s1" else "s2" },
            callBehavior = { sessionGone() })
        val result = world.invoke()
        assertTrue(result is ProviderCallResult.TypedError)
        assertTrue((result as ProviderCallResult.TypedError).message.contains("session expired"))
        assertEquals("exactly one retry, never a loop", 2, world.http.count("tools/call"))
        assertEquals(2, world.initCount)
    }

    @Test fun noRecoveryIsAttemptedWhenNoSessionIsHeld() {
        val world = SessionWorld(
            sessionOnInit = { null },
            callBehavior = { sessionGone() })
        val result = world.invoke()
        assertTrue(result is ProviderCallResult.TypedError)
        assertTrue((result as ProviderCallResult.TypedError).message.contains("session expired"))
        assertEquals("a stateless 404 is a plain failure: no re-handshake",
            1, world.initCount)
        assertEquals(1, world.http.count("tools/call"))
    }
}
