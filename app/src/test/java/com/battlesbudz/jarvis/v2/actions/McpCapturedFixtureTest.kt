package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Item 8a: replay of real Streamable HTTP exchanges captured from live
 * servers (see app/src/test/resources/mcp-fixtures/README.md for
 * provenance), plus the streaming-transport proof: against a raw socket
 * serving the captured SSE bytes, the real [UrlConnectionMcpHttpClient]
 * returns as soon as the reply frame arrives instead of waiting for the
 * server to close the stream.
 */
class McpCapturedFixtureTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/mcp-fixtures/$name")) {
            "missing captured fixture $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Only the headers the client reads were kept from the capture. */
    private fun fixtureHeaders(name: String): Map<String, String> =
        fixture(name).lines().drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()

    // -- Parse-level replay ----------------------------------------------

    @Test fun everythingSseExchangesParse() {
        val negotiation = McpProtocol.parseInitializeResponse(
            200, fixture("everything-initialize.sse"), fixtureHeaders("everything-initialize.headers"))
        assertTrue(negotiation is McpProtocol.Negotiation.Ok)
        negotiation as McpProtocol.Negotiation.Ok
        assertEquals("2025-06-18", negotiation.protocolVersion)
        assertEquals("230fbf2e-9fb5-4ad0-b499-285adb6eb8cc", negotiation.sessionId)

        val listed = McpProtocol.parseToolsList(200, fixture("everything-tools-list.sse"))
        assertTrue(listed is McpProtocol.ToolsList.Ok)
        val tools = (listed as McpProtocol.ToolsList.Ok).tools
        assertEquals(16, tools.size)
        assertTrue(tools.any { it.name == "echo" })
        assertTrue(tools.any { it.name == "get-sum" })

        val called = McpProtocol.parseCallToolResponse(200, fixture("everything-tools-call-echo.sse"))
        assertTrue(called is ProviderCallResult.Success)
        assertEquals("Echo: fixture-hello", (called as ProviderCallResult.Success).data["text"])
    }

    @Test fun fastMcpJsonExchangesParse() {
        val negotiation = McpProtocol.parseInitializeResponse(
            200, fixture("fastmcp-initialize.json"), fixtureHeaders("fastmcp-initialize.headers"))
        assertTrue(negotiation is McpProtocol.Negotiation.Ok)
        negotiation as McpProtocol.Negotiation.Ok
        assertEquals("2025-06-18", negotiation.protocolVersion)
        assertEquals("8d9896619a2d48acb199899bf03cf914", negotiation.sessionId)

        val listed = McpProtocol.parseToolsList(200, fixture("fastmcp-tools-list.json"))
        assertTrue(listed is McpProtocol.ToolsList.Ok)
        assertEquals(listOf("echo", "add"),
            (listed as McpProtocol.ToolsList.Ok).tools.map { it.name })

        val called = McpProtocol.parseCallToolResponse(200, fixture("fastmcp-tools-call-echo.json"))
        assertTrue(called is ProviderCallResult.Success)
        assertEquals("Echo: fixture-hello", (called as ProviderCallResult.Success).data["text"])
    }

    @Test fun capturedBadSessionIsTheRecoveryTriggerShape() {
        // The everything server's real answer to a call carrying an
        // unknown session id: HTTP 404. The protocol layer must surface
        // it as the session-expired error the recovery keys on.
        val parsed = McpProtocol.parseCallToolResponse(
            404, fixture("everything-tools-call-bad-session.json"))
        assertTrue(parsed is ProviderCallResult.TypedError)
        val error = parsed as ProviderCallResult.TypedError
        assertEquals(ProviderErrorCode.NETWORK_ERROR, error.code)
        assertTrue(error.message.contains("session expired"))
    }

    // -- Flow-level replay -------------------------------------------------

    /** Serves the captured everything-server bytes by JSON-RPC method. */
    private inner class FixtureHttp : McpHttpClient {
        override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse =
            when {
                body.contains("\"method\":\"tools/call\"") ->
                    McpHttpResponse(200, fixture("everything-tools-call-echo.sse"), emptyMap())
                body.contains("\"method\":\"tools/list\"") ->
                    McpHttpResponse(200, fixture("everything-tools-list.sse"), emptyMap())
                body.contains("\"method\":\"notifications/initialized\"") ->
                    McpHttpResponse(202, "", emptyMap())
                body.contains("\"method\":\"initialize\"") ->
                    McpHttpResponse(200, fixture("everything-initialize.sse"),
                        fixtureHeaders("everything-initialize.headers"))
                else -> McpHttpResponse(202, "", emptyMap())
            }
    }

    @Test fun capturedEverythingSessionReplaysThroughSetupAndInvoke() {
        val http = FixtureHttp()
        val credentials = InMemoryMcpCredentialStore()
        val connected = McpSetupFlow(http, credentials)
            .run("Everything", "https://example.com/mcp", null)
        assertTrue(connected is McpSetupFlow.FlowResult.Connected)
        val status = (connected as McpSetupFlow.FlowResult.Connected).status
        assertEquals("230fbf2e-9fb5-4ad0-b499-285adb6eb8cc", status.sessionId)
        assertEquals(16, status.tools.size)
        // The captured server declares no Jarvis pricing, so nothing is
        // enabled by default; the user enables a tool explicitly.
        assertTrue(status.enabledTools.isEmpty())
        val registry = McpRegistry(http, credentials)
        val placed = registry.add(status)
        assertTrue(registry.setToolEnabled(placed.config.id, "echo", true))
        val result = McpInvoker(registry, http, credentials).invoke(
            ProviderCall(ProviderId.mcp(placed.config.id), "echo", mapOf("message" to "fixture-hello")))
        assertTrue(result is ProviderCallResult.Success)
        assertEquals("Echo: fixture-hello", (result as ProviderCallResult.Success).data["text"])
    }

    // -- Streaming transport proof (real client, raw socket) ---------------

    /** One-shot raw HTTP server: reads one request, then [respond] owns the socket stream. */
    private fun serveOnce(respond: (java.io.OutputStream) -> Unit): Int {
        val serverSocket = ServerSocket(0)
        thread(isDaemon = true, name = "mcp-fixture-server") {
            try {
                serverSocket.accept().use { socket ->
                    readRequest(socket.getInputStream())
                    respond(socket.getOutputStream())
                }
            } catch (_: Exception) { }
        }
        return serverSocket.localPort
    }

    private fun readRequest(input: java.io.InputStream) {
        val reader = input.bufferedReader(Charsets.UTF_8)
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val chars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(chars, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
    }

    private fun sseHead(): ByteArray =
        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n\r\n").toByteArray(Charsets.UTF_8)

    @Test fun streamingClientReturnsAsSoonAsTheReplyArrives() {
        val sse = fixture("everything-tools-call-echo.sse")
        val port = serveOnce { out ->
            out.write(sseHead())
            out.write(sse.toByteArray(Charsets.UTF_8))
            out.flush()
            // The stream stays open (the server still has a
            // long-running operation in flight). Reading to close would
            // stall for the whole hold; the client must return now.
            Thread.sleep(30_000)
        }
        val client = UrlConnectionMcpHttpClient()
        val started = System.nanoTime()
        val response = client.postForResponse(
            "http://127.0.0.1:$port/mcp",
            McpProtocol.callToolRequest(3, "echo", mapOf("message" to "fixture-hello")),
            emptyMap(), expectedId = 3, phase = McpPhase.DISCOVERY)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, response.status)
        assertTrue("returned in ${elapsedMs}ms, long before the held stream ends",
            elapsedMs < 10_000)
        val parsed = McpProtocol.parseCallToolResponse(response.status, response.body)
        assertTrue(parsed is ProviderCallResult.Success)
        assertEquals("Echo: fixture-hello", (parsed as ProviderCallResult.Success).data["text"])
    }

    @Test fun streamingClientSkipsMessagesThatAreNotTheReply() {
        val progressFrame = "event: message\ndata: {\"jsonrpc\":\"2.0\"," +
            "\"method\":\"notifications/progress\",\"params\":{\"progress\":1,\"total\":2}}\n\n"
        val port = serveOnce { out ->
            out.write(sseHead())
            out.write(progressFrame.toByteArray(Charsets.UTF_8))
            out.write(fixture("everything-tools-call-echo.sse").toByteArray(Charsets.UTF_8))
            out.flush()
            Thread.sleep(30_000)
        }
        val client = UrlConnectionMcpHttpClient()
        val started = System.nanoTime()
        val response = client.postForResponse(
            "http://127.0.0.1:$port/mcp",
            McpProtocol.callToolRequest(3, "echo", mapOf("message" to "fixture-hello")),
            emptyMap(), expectedId = 3, phase = McpPhase.DISCOVERY)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("returned in ${elapsedMs}ms", elapsedMs < 10_000)
        assertTrue("the matched reply is returned, not the progress frame",
            response.body.contains("\"id\":3"))
        assertFalse(response.body.contains("notifications/progress"))
    }

    @Test fun streamingClientFallsBackToTheWholeBodyWhenNothingMatches() {
        // The stream closes without ever carrying the expected id: the
        // caller gets the full body and the protocol layer's existing
        // last-message tolerance decides what it means.
        val port = serveOnce { out ->
            out.write(sseHead())
            out.write(fixture("everything-tools-call-echo.sse").toByteArray(Charsets.UTF_8))
            out.flush()
        }
        val client = UrlConnectionMcpHttpClient()
        val response = client.postForResponse(
            "http://127.0.0.1:$port/mcp",
            McpProtocol.callToolRequest(99, "echo", mapOf("message" to "fixture-hello")),
            emptyMap(), expectedId = 99, phase = McpPhase.DISCOVERY)
        assertEquals(200, response.status)
        val payload = McpProtocol.extractPayload(response.body)
        assertEquals(3, payload?.optInt("id"))
    }
}
