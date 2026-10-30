# Captured MCP server fixtures (reliability epic item 8a)

Real Streamable HTTP exchanges captured from live servers on 2026-10-30,
replayed offline by `McpCapturedFixtureTest` and `McpHardeningTest`. These
are wire bytes from actual server processes, not hand-written stand-ins:
only the response headers were trimmed (to the status line, content type
and session id) when the captures were filed here.

## Servers

1. **Everything reference server** — `@modelcontextprotocol/server-everything`
   v2.1.0 (official MCP TypeScript SDK), run locally as
   `mcp-server-everything streamableHttp` on 127.0.0.1:3111. This server
   answers every request with an SSE (`text/event-stream`) body, one
   `event: message` frame per response.
2. **FastMCP** — a two-tool server (`echo`, `add`) written against the
   official MCP Python SDK (`mcp` 1.x, serverInfo version string "1.30.0"),
   run with `json_response=True` and stateful sessions on 127.0.0.1:3112,
   so its responses are the plain `application/json` shape.

## Capture method

curl against the live servers, one stateful session per server:

1. `POST initialize` (protocolVersion 2025-06-18) — response carries the
   `mcp-session-id` header recorded in the sibling `.headers` file.
2. `POST notifications/initialized` with that session id — both servers
   answered **202 Accepted** with an empty body (no fixture file; the
   status is the fixture).
3. `POST tools/list` (id 2) and `POST tools/call` for `echo` with
   `{"message":"fixture-hello"}` (id 3).

The everything `tools/list` capture holds the server's full 16-tool list.
The FastMCP list holds its 2 tools.

## The 404 capture

`everything-tools-call-bad-session.json` is the body of a real **HTTP 404**
answer from the everything server to a `tools/call` carrying an unknown
`Mcp-Session-Id` header: a JSON-RPC error object, code -32001,
"Session not found". This is the exact wire event the expired-session
recovery in `McpInvoker` exists for.
