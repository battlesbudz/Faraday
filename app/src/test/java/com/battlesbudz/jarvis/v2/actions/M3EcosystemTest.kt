package com.battlesbudz.jarvis.v2.actions

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * M3 ecosystem integrations (D05, D07, T16, T17, T08-for-providers).
 *
 * Covers provider identity/wire names/families, strict nested type
 * conversion, discovery diff/invalidation, collision-safe aliases,
 * task-relevant selection, the dispatcher (grants, typed results, paid
 * confirmation, model-exposure gate, dependent journeys), MCP guided setup
 * (URL policy, negotiation, auth, discovery, pricing defaults), schema
 * change/disconnect handling, credential hygiene, scope caps in the file
 * store, and the settings projection.
 */
class M3EcosystemTest {

    private val pkgA = "com.example.sample"
    private val pkgB = "com.other.sample"
    private val providerA = ProviderId.appFunctions(pkgA)

    private fun scopes(pkg: String, vararg names: String): Set<String> {
        val provider = ProviderId.appFunctions(pkg)
        return names.map { ProviderWireNames.scopedName(provider, it) }.toSet()
    }

    private fun textParam(name: String, required: Boolean = true) =
        name to AppFunctionProperty(AppFunctionType.Text, required)

    private fun sampleMetadata(
        packageName: String = pkgA,
        functionId: String = "echo",
        version: Long = 1,
        params: AppFunctionType.Obj = AppFunctionType.Obj(mapOf(textParam("text"))),
        resultType: AppFunctionType = AppFunctionType.Text,
        scopeNames: Set<String> = setOf("read"),
        pricing: ProviderPricing = ProviderPricing.FREE,
        needsUserInteraction: Boolean = false,
        description: String = "Echoes text."
    ) = AppFunctionMetadata(
        providerPackage = packageName,
        functionId = functionId,
        displayName = functionId.replaceFirstChar { it.uppercase() },
        description = description,
        versionCode = version,
        parameters = params,
        resultType = resultType,
        scopes = scopes(packageName, *scopeNames.toTypedArray()),
        pricing = pricing,
        needsUserInteraction = needsUserInteraction
    )

    private fun snapshot(vararg functions: AppFunctionMetadata, atMs: Long = 1000L) =
        DiscoverySnapshot(functions.toList(), DiscoveryAccessMethod.ORDINARY_APP, atMs)

    private class FakeInvoker(
        private val behavior: (ProviderCall) -> ProviderCallResult = {
            ProviderCallResult.Success(mapOf("text" to it.arguments["text"].toString()), "ok")
        }
    ) : ProviderInvoker {
        val calls = mutableListOf<ProviderCall>()
        override fun invoke(call: ProviderCall): ProviderCallResult {
            calls += call
            return behavior(call)
        }
    }

    private class FakeHttp(
        private val handler: (url: String, body: String, headers: Map<String, String>) -> McpHttpResponse
    ) : McpHttpClient {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse {
            requests += Triple(url, body, headers)
            return handler(url, body, headers)
        }
    }

    private fun registryWith(vararg functions: AppFunctionMetadata): ProviderRegistry {
        val registry = ProviderRegistry()
        registry.update(snapshot(*functions))
        return registry
    }

    private fun dispatcherFor(
        registry: ProviderRegistry,
        mcp: McpRegistry,
        invokers: Map<ProviderId, ProviderInvoker>,
        ledger: ToolTaskLedger = ToolTaskLedger()
    ): ProviderDispatcher {
        val reg = registry
        val mcpReg = mcp
        ToolSourcePolicy.setProviderScopeResolver { wireName ->
            ProviderWireNames.parseToolName(wireName)?.let { parsed ->
                when (parsed.provider.kind) {
                    ProviderKind.APP_FUNCTIONS -> reg.metadataFor(parsed.provider, parsed.functionId)?.scopes.orEmpty()
                    ProviderKind.MCP -> mcpReg.toolsFor(parsed.provider.id)
                        .firstOrNull { it.name == parsed.functionId }
                        ?.scopes?.map { ProviderWireNames.scopedName(parsed.provider, it) }.orEmpty().toSet()
                }
            }.orEmpty()
        }
        return ProviderDispatcher(ledger, reg, mcpReg, invokers)
    }

    @Before fun resetResolver() {
        ToolSourcePolicy.setProviderScopeResolver { emptySet() }
    }

    // -- Provider identity ---------------------------------------------------

    @Test fun wireNameRoundTrip() {
        val wire = ProviderWireNames.toolName(providerA, "echo")
        assertEquals("provider:appfunctions:com.example.sample:echo", wire)
        val parsed = ProviderWireNames.parseToolName(wire)!!
        assertEquals(providerA, parsed.provider)
        assertEquals("echo", parsed.functionId)
        assertTrue(ProviderWireNames.isProviderTool(wire))
        assertFalse(ProviderWireNames.isProviderTool("read_battery"))
        assertNull(ProviderWireNames.parseToolName("provider:bogus:x:y"))
    }

    @Test fun familyMappingAndScopeCap() {
        val family = ProviderWireNames.familyFor(providerA)
        assertEquals("provider:appfunctions:com.example.sample", family)
        assertTrue(ProviderWireNames.isProviderFamily(family))
        assertEquals(providerA, ProviderWireNames.parseFamily(family))
        assertTrue(ProviderWireNames.scopeWithinCap(family, "appfunctions:com.example.sample:read"))
        assertFalse(ProviderWireNames.scopeWithinCap(family, "appfunctions:com.other.sample:read"))
        assertFalse(ProviderWireNames.scopeWithinCap(family, "battery.read"))
        assertFalse(ProviderWireNames.scopeWithinCap("phone", "appfunctions:com.example.sample:read"))
    }

    @Test fun unknownPriceIsNeverCalledFree() {
        assertEquals("Free", ProviderWireNames.describePricing(ProviderPricing.FREE))
        assertEquals("Paid", ProviderWireNames.describePricing(ProviderPricing.PAID))
        assertEquals("Price unknown", ProviderWireNames.describePricing(ProviderPricing.UNKNOWN))
        assertNotEquals("Free", ProviderWireNames.describePricing(ProviderPricing.UNKNOWN))
    }

    // -- Strict nested type conversion ----------------------------------------

    @Test fun strictConversionAcceptsNestedShapes() {
        val schema = AppFunctionType.Obj(mapOf(
            "title" to AppFunctionProperty(AppFunctionType.Text, true),
            "count" to AppFunctionProperty(AppFunctionType.Integer, true),
            "ratio" to AppFunctionProperty(AppFunctionType.Number, false),
            "flag" to AppFunctionProperty(AppFunctionType.Bool, false),
            "mode" to AppFunctionProperty(AppFunctionType.Enum(setOf("a", "b")), false),
            "nested" to AppFunctionProperty(AppFunctionType.Obj(mapOf(
                "ids" to AppFunctionProperty(AppFunctionType.Arr(AppFunctionType.Integer, minItems = 1), true),
                "label" to AppFunctionProperty(AppFunctionType.Text, false)
            )), true)
        ))
        val args = mapOf(
            "title" to "hi",
            "count" to 3,
            "ratio" to 1.5,
            "flag" to true,
            "mode" to "a",
            "nested" to mapOf("ids" to listOf(1, 2), "label" to "x")
        )
        val result = AppFunctionTypeConverter.convertArguments(schema, args)
        assertTrue(result is TypeConversion.Ok)
        @Suppress("UNCHECKED_CAST")
        val converted = (result as TypeConversion.Ok).value as Map<String, Any?>
        assertEquals(3, converted["count"])
        assertEquals(1.5, converted["ratio"])
        @Suppress("UNCHECKED_CAST")
        val nested = converted["nested"] as Map<String, Any?>
        assertEquals(listOf(1, 2), nested["ids"])
    }

    @Test fun strictConversionRejectsWithoutCoercion() {
        val schema = AppFunctionType.Obj(mapOf("count" to AppFunctionProperty(AppFunctionType.Integer, true)))
        // Numeric string is not an integer.
        var result = AppFunctionTypeConverter.convertArguments(schema, mapOf("count" to "3"))
        assertTrue(result is TypeConversion.Error)
        assertEquals("$.count", (result as TypeConversion.Error).path)
        // A whole double is not an integer either: no coercion.
        result = AppFunctionTypeConverter.convertArguments(schema, mapOf("count" to 3.0))
        assertTrue(result is TypeConversion.Error)
        // Missing required property.
        result = AppFunctionTypeConverter.convertArguments(schema, emptyMap())
        assertTrue(result is TypeConversion.Error)
        // Unknown property rejected.
        result = AppFunctionTypeConverter.convertArguments(schema, mapOf("count" to 3, "extra" to 1))
        assertTrue(result is TypeConversion.Error)
        // Enum outside its values.
        val enumSchema = AppFunctionType.Obj(mapOf(
            "mode" to AppFunctionProperty(AppFunctionType.Enum(setOf("a", "b")), true)))
        result = AppFunctionTypeConverter.convertArguments(enumSchema, mapOf("mode" to "c"))
        assertTrue(result is TypeConversion.Error)
        // Array bounds and item types.
        val arrSchema = AppFunctionType.Obj(mapOf(
            "ids" to AppFunctionProperty(AppFunctionType.Arr(AppFunctionType.Integer, minItems = 1, maxItems = 2), true)))
        result = AppFunctionTypeConverter.convertArguments(arrSchema, mapOf("ids" to emptyList<Int>()))
        assertTrue(result is TypeConversion.Error)
        result = AppFunctionTypeConverter.convertArguments(arrSchema, mapOf("ids" to listOf(1, "x")))
        assertTrue(result is TypeConversion.Error)
        assertEquals("$.ids[1]", (result as TypeConversion.Error).path)
    }

    @Test fun canonicalJsonIsStableForSchemaHashing() {
        val first = AppFunctionTypeConverter.canonicalJson(mapOf("b" to 1, "a" to listOf(2, 1)))
        val second = AppFunctionTypeConverter.canonicalJson(mapOf("a" to listOf(2, 1), "b" to 1))
        assertEquals(first, second)
        assertEquals("{\"a\":[2,1],\"b\":1}", first)
        assertEquals(64, AppFunctionTypeConverter.sha256Hex(first).length)
    }

    // -- Discovery diff and invalidation --------------------------------------

    @Test fun discoveryDiffDetectsAddedUpdatedRemoved() {
        val before = snapshot(sampleMetadata(version = 1), sampleMetadata(functionId = "gone", version = 1))
        val after = snapshot(
            sampleMetadata(version = 2), // version bump
            sampleMetadata(functionId = "fresh", version = 1))
        val diff = AppFunctionDiscovery.diff(before, after)
        assertEquals(listOf("fresh"), diff.added.map { it.functionId })
        assertEquals(listOf("echo"), diff.updated.map { it.functionId })
        assertEquals(listOf("gone"), diff.removed.map { it.functionId })
    }

    @Test fun registryUpdateInvalidatesRemovedAliases() {
        val registry = registryWith(sampleMetadata(), sampleMetadata(functionId = "gone"))
        assertTrue(registry.aliasRegistry.resolve("gone") is AliasResolution.Resolved)
        registry.update(snapshot(sampleMetadata()))
        assertTrue(registry.aliasRegistry.resolve("gone") is AliasResolution.Unknown)
        assertTrue(registry.aliasRegistry.resolve("echo") is AliasResolution.Resolved)
    }

    // -- Collision-safe aliases ------------------------------------------------

    @Test fun collidingFunctionNamesGetUniqueAliases() {
        val registry = registryWith(
            sampleMetadata(packageName = pkgA, functionId = "echo"),
            sampleMetadata(packageName = pkgB, functionId = "echo"))
        val all = registry.aliasRegistry.all()
        assertEquals(2, all.size)
        assertEquals(setOf("echo", "echo_sample"), all.map { it.alias }.toSet())
        assertTrue(all.all { it.collided })
        val first = (registry.aliasRegistry.resolve("echo") as AliasResolution.Resolved).binding
        val second = (registry.aliasRegistry.resolve("echo_sample") as AliasResolution.Resolved).binding
        assertEquals(pkgA, first.identity.providerPackage)
        assertEquals(pkgB, second.identity.providerPackage)
        assertNotNull(first.collisionNote)
        // Resolution binds the full identity: no ambiguity, no guessing.
        assertEquals("provider:appfunctions:com.other.sample:echo", second.wireName)
    }

    @Test fun distinctFunctionsKeepShortAliases() {
        val registry = registryWith(sampleMetadata(functionId = "echo"), sampleMetadata(functionId = "shout"))
        val all = registry.aliasRegistry.all()
        assertEquals(setOf("echo", "shout"), all.map { it.alias }.toSet())
        assertTrue(all.none { it.collided })
    }

    // -- Task-relevant selection -------------------------------------------------

    @Test fun taskSelectionRanksRelevantFunctions() {
        val registry = registryWith(
            sampleMetadata(functionId = "create_event", description = "Create a calendar event with a title and time."),
            sampleMetadata(functionId = "send_message", description = "Send a chat message to a contact."),
            sampleMetadata(functionId = "echo"))
        val selected = AppFunctionTaskSelection.select(
            "create a calendar event tomorrow",
            registry.aliasRegistry.all(),
            metadataFor = { binding -> registry.metadataForWire(binding.wireName) })
        assertEquals("create_event", selected.first().alias)
        assertTrue(selected.none { it.alias == "send_message" || it.alias == "echo" })
    }

    @Test fun taskSelectionDropsIrrelevantQuery() {
        val registry = registryWith(sampleMetadata())
        val selected = AppFunctionTaskSelection.select(
            "completely unrelated zebra query",
            registry.aliasRegistry.all(),
            metadataFor = { binding -> registry.metadataForWire(binding.wireName) })
        assertTrue(selected.isEmpty())
    }

    // -- Dispatcher: success, grants, typed results -----------------------------

    private fun mcpRegistry(): McpRegistry =
        McpRegistry(FakeHttp { _, _, _ -> McpHttpResponse(500, "", emptyMap()) }, InMemoryMcpCredentialStore())

    @Test fun successfulCallRecordsProviderGrant() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val ledger = ToolTaskLedger()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker), ledger)
        val result = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertTrue(result is ProviderCallResult.Success)
        assertEquals(1, invoker.calls.size)
        // The converted arguments reached the invoker strictly typed.
        assertEquals("hi", invoker.calls.single().arguments["text"])
        val record = ledger.journal().sourceAccess.single { it.family == "provider:appfunctions:com.example.sample" }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertEquals(scopes(pkgA, "read"), record.scopes)
    }

    @Test fun invalidArgumentsDispatchNothing() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.dispatchByAlias("echo", mapOf("text" to 42))
        assertTrue(result is ProviderCallResult.TypedError)
        val error = result as ProviderCallResult.TypedError
        assertEquals(ProviderErrorCode.INVALID_ARGUMENTS, error.code)
        assertEquals(0, invoker.calls.size)
    }

    @Test fun unknownAliasNeverGuesses() {
        val registry = registryWith(sampleMetadata())
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to FakeInvoker()))
        val result = dispatcher.dispatchByAlias("nope", emptyMap())
        assertTrue(result is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.NOT_FOUND, (result as ProviderCallResult.TypedError).code)
    }

    @Test fun typedResultsPassThrough() {
        val registry = registryWith(
            sampleMetadata(functionId = "open"),
            sampleMetadata(functionId = "confirm"))
        val invoker = FakeInvoker { call ->
            when (call.functionId) {
                "open" -> ProviderCallResult.UriResult("https://example.com/x", "Open X")
                else -> ProviderCallResult.NeedsUserInteraction("Tap continue in the app.", "Continue")
            }
        }
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val uri = dispatcher.dispatchByAlias("open", mapOf("text" to "x"))
        assertTrue(uri is ProviderCallResult.UriResult)
        assertEquals("https://example.com/x", (uri as ProviderCallResult.UriResult).uri)
        val interaction = dispatcher.dispatchByAlias("confirm", mapOf("text" to "x"))
        assertTrue(interaction is ProviderCallResult.NeedsUserInteraction)
    }

    @Test fun paidFunctionNeedsPurchaseConfirmationAndNeverDispatches() {
        val registry = registryWith(sampleMetadata(pricing = ProviderPricing.PAID))
        val invoker = FakeInvoker()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertTrue(result is ProviderCallResult.NeedsPurchaseConfirmation)
        assertEquals(0, invoker.calls.size)
    }

    @Test fun modelCallerIsStructurallyRejected() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"),
            caller = ProviderCallerKind.MODEL)
        assertTrue(result is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.DENIED_PERMISSION, (result as ProviderCallResult.TypedError).code)
        assertEquals(0, invoker.calls.size)
        assertFalse(ProviderExposure.MODEL_EXPOSURE_ENABLED)
    }

    // -- Dispatcher: T08 grant discipline for providers --------------------------

    @Test fun revocationBlocksEveryAdapter() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker), ledger)
        assertTrue(dispatcher.dispatchByAlias("echo", mapOf("text" to "hi")) is ProviderCallResult.Success)
        assertTrue(access.revoke("provider:appfunctions:com.example.sample"))
        val denied = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertTrue(denied is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.DENIED_PERMISSION, (denied as ProviderCallResult.TypedError).code)
        assertEquals(1, invoker.calls.size)
    }

    @Test fun newFunctionCannotBroadenTheGrant() {
        val registry = registryWith(
            sampleMetadata(functionId = "read_fn", scopeNames = setOf("read")),
            sampleMetadata(functionId = "write_fn", scopeNames = setOf("write")))
        val invoker = FakeInvoker()
        val ledger = ToolTaskLedger()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker), ledger)
        assertTrue(dispatcher.dispatchByAlias("read_fn", mapOf("text" to "hi")) is ProviderCallResult.Success)
        // The write function needs a broader scope than the remembered grant.
        val denied = dispatcher.dispatchByAlias("write_fn", mapOf("text" to "hi"))
        assertTrue(denied is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.SCOPE_DENIED, (denied as ProviderCallResult.TypedError).code)
        assertEquals(1, invoker.calls.size)
    }

    @Test fun grantNeverExceedsItsNamespaceCap() {
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        val wire = ProviderWireNames.toolName(providerA, "echo")
        access.recordProviderGrant(wire, scopes(pkgA, "read") + setOf("battery.read", "appfunctions:com.other.sample:x"))
        val record = ledger.journal().sourceAccess.single()
        // Out-of-namespace scopes are dropped at record time.
        assertEquals(scopes(pkgA, "read"), record.scopes)
    }

    @Test fun denialNeverOverwrittenByDispatch() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        access.recordDenial("provider:appfunctions:com.example.sample")
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker), ledger)
        val denied = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertEquals(ProviderErrorCode.DENIED_PERMISSION, (denied as ProviderCallResult.TypedError).code)
        assertEquals(0, invoker.calls.size)
        assertEquals(SourceAccessState.DENIED,
            ledger.journal().sourceAccess.single().state)
    }

    @Test fun providersHaveIndependentFamilies() {
        val registry = registryWith(
            sampleMetadata(packageName = pkgA, functionId = "echo"),
            sampleMetadata(packageName = pkgB, functionId = "echo"))
        val invoker = FakeInvoker()
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(
            ProviderId.appFunctions(pkgA) to invoker, ProviderId.appFunctions(pkgB) to invoker), ledger)
        assertTrue(dispatcher.dispatchByAlias("echo", mapOf("text" to "hi")) is ProviderCallResult.Success)
        access.revoke("provider:appfunctions:com.example.sample")
        // The other provider's family is independent: still dispatchable.
        val other = dispatcher.dispatchByAlias("echo_sample", mapOf("text" to "hi"))
        assertTrue(other is ProviderCallResult.Success)
        // The revoked one stays blocked.
        val blocked = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertEquals(ProviderErrorCode.DENIED_PERMISSION, (blocked as ProviderCallResult.TypedError).code)
    }

    // -- Dependent-function journeys -----------------------------------------------

    @Test fun dependentJourneyBindsOutputs() {
        val registry = registryWith(
            sampleMetadata(functionId = "echo"),
            sampleMetadata(functionId = "shout"))
        val invoker = FakeInvoker { call ->
            val text = call.arguments["text"].toString()
            val out = if (call.functionId == "shout") text.uppercase() else text
            ProviderCallResult.Success(mapOf("text" to out), "ok")
        }
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.runJourney(listOf(
            ProviderJourneyStep("s1", "echo", mapOf("text" to "hello")),
            ProviderJourneyStep("s2", "shout", mapOf("text" to "\${s1.text}"))))
        assertTrue(result.succeeded)
        assertEquals(2, result.stepResults.size)
        assertEquals("HELLO", (result.stepResults[1] as ProviderCallResult.Success).data["text"])
    }

    @Test fun journeyStopsAtFirstFailureWithoutRerun() {
        val registry = registryWith(
            sampleMetadata(functionId = "echo"),
            sampleMetadata(functionId = "shout"))
        val invoker = FakeInvoker { call ->
            if (call.functionId == "shout") ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN, "boom")
            else ProviderCallResult.Success(mapOf("text" to "ok"), "ok")
        }
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.runJourney(listOf(
            ProviderJourneyStep("s1", "echo", mapOf("text" to "hello")),
            ProviderJourneyStep("s2", "shout", mapOf("text" to "\${s1.text}")),
            ProviderJourneyStep("s3", "echo", mapOf("text" to "never"))))
        assertFalse(result.succeeded)
        assertEquals(2, result.stepResults.size)
        assertEquals(2, invoker.calls.size)
        assertTrue(result.receipt.contains("s2"))
    }

    @Test fun journeyBindingFailureStopsBeforeDispatch() {
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val dispatcher = dispatcherFor(registry, mcpRegistry(), mapOf(providerA to invoker))
        val result = dispatcher.runJourney(listOf(
            ProviderJourneyStep("s1", "echo", mapOf("text" to "\${missing.text}"))))
        assertFalse(result.succeeded)
        assertEquals(ProviderErrorCode.INVALID_ARGUMENTS,
            (result.stepResults.single() as ProviderCallResult.TypedError).code)
        assertEquals(0, invoker.calls.size)
    }

    // -- MCP URL policy -------------------------------------------------------------

    @Test fun mcpUrlPolicy() {
        assertTrue(McpUrlPolicy.validate("https://mcp.example.com/rpc") is McpUrlPolicy.Validation.Valid)
        assertTrue(McpUrlPolicy.validate("http://127.0.0.1:9999") is McpUrlPolicy.Validation.Valid)
        assertTrue(McpUrlPolicy.validate("http://localhost:8080/x") is McpUrlPolicy.Validation.Valid)
        val plain = McpUrlPolicy.validate("http://mcp.example.com/rpc")
        assertTrue(plain is McpUrlPolicy.Validation.Invalid)
        assertTrue((plain as McpUrlPolicy.Validation.Invalid).reason.contains("https"))
        val creds = McpUrlPolicy.validate("https://user:pass@mcp.example.com/")
        assertTrue(creds is McpUrlPolicy.Validation.Invalid)
        assertTrue(McpUrlPolicy.validate("ftp://mcp.example.com/") is McpUrlPolicy.Validation.Invalid)
        assertTrue(McpUrlPolicy.validate("") is McpUrlPolicy.Validation.Invalid)
        assertTrue(McpUrlPolicy.validate("not a url") is McpUrlPolicy.Validation.Invalid)
    }

    // -- MCP protocol ------------------------------------------------------------------

    private fun stubToolsJson(vararg tools: JSONObject): String {
        val toolsArray = org.json.JSONArray()
        for (tool in tools) toolsArray.put(tool)
        return JSONObject().put("jsonrpc", "2.0").put("id", 2)
            .put("result", JSONObject().put("tools", toolsArray)).toString()
    }

    private fun stubTool(name: String, pricing: String?, scopes: List<String> = listOf("lookup")): JSONObject {
        val tool = JSONObject().put("name", name).put("description", "$name tool")
            .put("inputSchema", JSONObject().put("type", "object"))
        if (pricing != null) tool.put("x-jarvis-pricing", pricing)
        tool.put("x-jarvis-scopes", org.json.JSONArray(scopes))
        return tool
    }

    private fun initializeOk(version: String = "2025-06-18", session: String? = "sess-1"): McpHttpResponse {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("result", JSONObject().put("protocolVersion", version)
                .put("capabilities", JSONObject())
                .put("serverInfo", JSONObject().put("name", "stub").put("version", "1"))).toString()
        return McpHttpResponse(200, body,
            if (session == null) emptyMap() else mapOf("mcp-session-id" to session))
    }

    @Test fun initializeNegotiation() {
        val ok = McpProtocol.parseInitializeResponse(200, initializeOk().body, initializeOk().headers)
        assertTrue(ok is McpProtocol.Negotiation.Ok)
        assertEquals("2025-06-18", (ok as McpProtocol.Negotiation.Ok).protocolVersion)
        assertEquals("sess-1", ok.sessionId)
        val mismatch = McpProtocol.parseInitializeResponse(
            200, initializeOk(version = "1999-01-01").body, emptyMap())
        assertTrue(mismatch is McpProtocol.Negotiation.Failed)
        assertTrue((mismatch as McpProtocol.Negotiation.Failed).reason.startsWith("Version mismatch"))
        val denied = McpProtocol.parseInitializeResponse(401, "", emptyMap())
        assertTrue(denied is McpProtocol.Negotiation.Failed)
        assertTrue((denied as McpProtocol.Negotiation.Failed).reason.contains("credentials"))
    }

    @Test fun toolsListParsesPricingAndScopes() {
        val body = stubToolsJson(
            stubTool("free_lookup", "free"),
            stubTool("paid_export", "paid"),
            stubTool("mystery", null))
        val parsed = McpProtocol.parseToolsList(200, body)
        assertTrue(parsed is McpProtocol.ToolsList.Ok)
        val tools = (parsed as McpProtocol.ToolsList.Ok).tools
        assertEquals(3, tools.size)
        assertEquals(ProviderPricing.FREE, tools[0].pricing)
        assertEquals(ProviderPricing.PAID, tools[1].pricing)
        // Absent pricing is UNKNOWN, never free.
        assertEquals(ProviderPricing.UNKNOWN, tools[2].pricing)
        assertEquals(setOf("lookup"), tools[0].scopes)
    }

    @Test fun sseEnvelopeIsTolerated() {
        val inner = JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("result", JSONObject().put("protocolVersion", "2025-06-18")).toString()
        val body = "event: message\ndata: $inner\n\n"
        val parsed = McpProtocol.parseInitializeResponse(200, body, emptyMap())
        assertTrue(parsed is McpProtocol.Negotiation.Ok)
    }

    @Test fun callToolResponseMapping() {
        val textBody = JSONObject().put("jsonrpc", "2.0").put("id", 5)
            .put("result", JSONObject().put("content",
                org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "hello")))).toString()
        val success = McpProtocol.parseCallToolResponse(200, textBody)
        assertTrue(success is ProviderCallResult.Success)
        assertEquals("hello", (success as ProviderCallResult.Success).data["text"])
        val uriBody = JSONObject().put("jsonrpc", "2.0").put("id", 5)
            .put("result", JSONObject().put("content", org.json.JSONArray().put(
                JSONObject().put("type", "resource_link").put("uri", "https://example.com/r")))).toString()
        val uri = McpProtocol.parseCallToolResponse(200, uriBody)
        assertTrue(uri is ProviderCallResult.UriResult)
        val denied = McpProtocol.parseCallToolResponse(401, "")
        assertTrue(denied is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.AUTH_FAILED, (denied as ProviderCallResult.TypedError).code)
    }

    // -- MCP guided setup ------------------------------------------------------------

    private fun setupHandler(
        tools: List<JSONObject> = listOf(stubTool("free_lookup", "free"), stubTool("paid_export", "paid")),
        protocolVersion: String = "2025-06-18",
        requireToken: String? = "good-token"
    ): (String, String, Map<String, String>) -> McpHttpResponse = { _, body, headers ->
        val payload = JSONObject(body)
        if (requireToken != null && headers["Authorization"] != "Bearer $requireToken") {
            McpHttpResponse(401, "unauthorized", emptyMap())
        } else when (payload.optString("method")) {
            "initialize" -> initializeOk(protocolVersion)
            "tools/list" -> McpHttpResponse(200, stubToolsJson(*tools.toTypedArray()), emptyMap())
            else -> McpHttpResponse(200, JSONObject().put("jsonrpc", "2.0")
                .put("id", payload.optInt("id")).put("result", JSONObject()).toString(), emptyMap())
        }
    }

    @Test fun guidedSetupConnectsWithFreeOnlyDefault() {
        val credentials = InMemoryMcpCredentialStore()
        val flow = McpSetupFlow(FakeHttp(setupHandler()), credentials)
        val result = flow.run("Stub", "https://mcp.example.com/rpc", "good-token")
        assertTrue(result is McpSetupFlow.FlowResult.Connected)
        val status = (result as McpSetupFlow.FlowResult.Connected).status
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(setOf("free_lookup"), status.enabledTools)
        assertEquals(2, status.tools.size)
        // The secret is stored under an opaque reference, never in the config.
        val ref = status.config.authRef!!
        assertNotEquals("good-token", ref)
        assertEquals("good-token", credentials.getSecret(ref))
        assertFalse(status.config.redacted().contains("good-token"))
    }

    @Test fun guidedSetupRejectsBadUrlBeforeNetwork() {
        val http = FakeHttp { _, _, _ -> fail("no network on URL failure"); McpHttpResponse(500, "", emptyMap()) }
        val flow = McpSetupFlow(http, InMemoryMcpCredentialStore())
        val result = flow.run("Stub", "ftp://example.com/", null)
        assertTrue(result is McpSetupFlow.FlowResult.Failed)
        val failed = result as McpSetupFlow.FlowResult.Failed
        assertEquals(McpSetupFlow.Stage.URL, failed.stage)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun guidedSetupReportsUnreachableHost() {
        val http = FakeHttp { _, _, _ -> throw java.io.IOException("no route") }
        val flow = McpSetupFlow(http, InMemoryMcpCredentialStore())
        val result = flow.run("Stub", "https://mcp.example.com/rpc", null)
        assertTrue(result is McpSetupFlow.FlowResult.Failed)
        assertEquals(McpSetupFlow.Stage.NEGOTIATION, (result as McpSetupFlow.FlowResult.Failed).stage)
    }

    @Test fun guidedSetupReportsAuthFailureAsDenied() {
        val flow = McpSetupFlow(FakeHttp(setupHandler(requireToken = "good-token")), InMemoryMcpCredentialStore())
        val result = flow.run("Stub", "https://mcp.example.com/rpc", "wrong-token")
        assertTrue(result is McpSetupFlow.FlowResult.Failed)
        val failed = result as McpSetupFlow.FlowResult.Failed
        assertEquals(McpSetupFlow.Stage.NEGOTIATION, failed.stage)
        assertTrue(failed.reason.contains("credentials"))
    }

    @Test fun guidedSetupReportsVersionMismatch() {
        val flow = McpSetupFlow(FakeHttp(setupHandler(protocolVersion = "1999-01-01")), InMemoryMcpCredentialStore())
        val result = flow.run("Stub", "https://mcp.example.com/rpc", "good-token")
        assertTrue(result is McpSetupFlow.FlowResult.Failed)
        val failed = result as McpSetupFlow.FlowResult.Failed
        assertEquals(McpSetupFlow.Stage.NEGOTIATION, failed.stage)
        assertTrue(failed.reason.startsWith("Version mismatch"))
    }

    @Test fun realHttpNegotiationAgainstLoopbackServer() {
        // The real HttpURLConnection transport negotiates against a real
        // (local) HTTP server: this is transport evidence, not a fake.
        // A raw ServerSocket stub is used because jdk.httpserver is not on
        // the unit-test compile classpath.
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val running = AtomicBoolean(true)
        fun handle(sock: java.net.Socket) {
            sock.use { s ->
                val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                if (requestLine.startsWith("POST")) {
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(chars, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(chars, 0, read)
                val method = try { JSONObject(body).optString("method") } catch (_: Exception) { "" }
                val responseBody = when (method) {
                    "initialize" -> initializeOk().body
                    "tools/list" -> stubToolsJson(stubTool("free_lookup", "free"))
                    else -> JSONObject().put("jsonrpc", "2.0").put("id", 1)
                        .put("result", JSONObject()).toString()
                }
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nMcp-Session-Id: loopback-1\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
        val worker = thread(isDaemon = true, name = "loopback-mcp") {
            while (running.get()) {
                try {
                    handle(serverSocket.accept())
                } catch (_: Exception) {
                    if (!running.get()) return@thread
                }
            }
        }
        try {
            val credentials = InMemoryMcpCredentialStore()
            val flow = McpSetupFlow(UrlConnectionMcpHttpClient(), credentials)
            val result = flow.run("Loopback", "http://127.0.0.1:$port/", null)
            assertTrue(result is McpSetupFlow.FlowResult.Connected)
            val status = (result as McpSetupFlow.FlowResult.Connected).status
            assertEquals("loopback-1", status.sessionId)
            assertEquals(setOf("free_lookup"), status.enabledTools)
        } finally {
            running.set(false)
            try { serverSocket.close() } catch (_: Exception) { }
            worker.join(5000)
        }
    }

    // -- MCP registry: refresh, schema change, disconnect, enablement --------------------

    private fun registryWithStub(
        tools: List<JSONObject> = listOf(stubTool("free_lookup", "free"), stubTool("paid_export", "paid")),
        credentials: InMemoryMcpCredentialStore = InMemoryMcpCredentialStore()
    ): Triple<McpRegistry, McpServerStatus, FakeHttp> {
        var currentTools = tools
        val http = FakeHttp(setupHandler(currentTools))
        val flow = McpSetupFlow(http, credentials)
        val connected = flow.run("Stub", "https://mcp.example.com/rpc", "good-token")
            as McpSetupFlow.FlowResult.Connected
        val registry = McpRegistry(http, credentials)
        val placed = registry.add(connected.status)
        return Triple(registry, placed, http)
    }

    @Test fun paidToolNeedsExplicitEnablementThenPurchaseConfirmation() {
        val (registry, status, _) = registryWithStub()
        val serverId = status.config.id
        assertFalse(registry.setToolEnabled(serverId, "no_such_tool", true))
        // Paid tool is disabled by default.
        assertFalse("paid_export" in registry.enabledToolsFor(serverId))
        assertTrue(registry.setToolEnabled(serverId, "paid_export", true))
        val invoker = McpInvoker(registry, FakeHttp(setupHandler()), InMemoryMcpCredentialStore())
        val provider = ProviderId.mcp(serverId)
        val ledger = ToolTaskLedger()
        ToolSourcePolicy.setProviderScopeResolver { wireName ->
            ProviderWireNames.parseToolName(wireName)?.let { parsed ->
                registry.toolsFor(parsed.provider.id).firstOrNull { it.name == parsed.functionId }
                    ?.scopes?.map { ProviderWireNames.scopedName(parsed.provider, it) }.orEmpty().toSet()
            }.orEmpty()
        }
        val dispatcher = ProviderDispatcher(ledger, ProviderRegistry(), registry, mapOf(provider to invoker))
        // Enabled but paid: purchase confirmation, never a silent dispatch.
        val confirmation = dispatcher.dispatch(ProviderCall(provider, "paid_export", mapOf("q" to "x")))
        assertTrue(confirmation is ProviderCallResult.NeedsPurchaseConfirmation)
    }

    @Test fun unknownPriceToolStaysDisabledAndIsNeverCalledFree() {
        val (registry, status, _) = registryWithStub(tools = listOf(stubTool("mystery", null)))
        val serverId = status.config.id
        assertFalse("mystery" in registry.enabledToolsFor(serverId))
        val tool = registry.toolsFor(serverId).single()
        assertEquals(ProviderPricing.UNKNOWN, tool.pricing)
        assertEquals("Price unknown", ProviderWireNames.describePricing(tool.pricing))
    }

    @Test fun schemaChangeBlocksCallsUntilReviewed() {
        var tools: List<JSONObject> = listOf(stubTool("free_lookup", "free"))
        val credentials = InMemoryMcpCredentialStore()
        val http = FakeHttp { url, body, headers -> setupHandler(tools)(url, body, headers) }
        val flow = McpSetupFlow(http, credentials)
        val connected = flow.run("Stub", "https://mcp.example.com/rpc", "good-token")
            as McpSetupFlow.FlowResult.Connected
        val registry = McpRegistry(http, credentials)
        val placed = registry.add(connected.status)
        // The server changes its tool surface.
        tools = listOf(stubTool("free_lookup", "free"),
            stubTool("free_lookup", "free").put("description", "changed"))
        val refreshed = registry.refresh(placed.config.id)
        assertEquals(McpServerState.SCHEMA_CHANGED, refreshed.state)
        assertTrue(refreshed.explanation.contains("changed"))
        // Calls are blocked while the schema change is unreviewed.
        val invoker = McpInvoker(registry, http, credentials)
        val provider = ProviderId.mcp(placed.config.id)
        val blocked = invoker.invoke(ProviderCall(provider, "free_lookup"))
        assertTrue(blocked is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE, (blocked as ProviderCallResult.TypedError).code)
        // After review, the unchanged-enabled tool works again.
        assertTrue(registry.acknowledgeSchemaChange(placed.config.id))
        val after = registry.status(placed.config.id)!!
        assertEquals(McpServerState.CONNECTED, after.state)
    }

    @Test fun disconnectMakesCallsHonestlyUnavailable() {
        val (registry, status, http) = registryWithStub()
        val serverId = status.config.id
        assertTrue(registry.disconnect(serverId))
        assertEquals(McpServerState.DISABLED, registry.status(serverId)!!.state)
        val invoker = McpInvoker(registry, http, InMemoryMcpCredentialStore())
        val result = invoker.invoke(ProviderCall(ProviderId.mcp(serverId), "free_lookup"))
        assertTrue(result is ProviderCallResult.TypedError)
        val error = result as ProviderCallResult.TypedError
        assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE, error.code)
        assertTrue(error.message.contains("Disconnected"))
    }

    @Test fun removeDeletesTheStoredSecret() {
        val credentials = InMemoryMcpCredentialStore()
        val (registry, status, _) = registryWithStub(credentials = credentials)
        val ref = status.config.authRef!!
        assertTrue(credentials.hasSecret(ref))
        assertTrue(registry.remove(status.config.id))
        assertFalse(credentials.hasSecret(ref))
        assertNull(registry.status(status.config.id))
    }

    // -- File store: provider grants round-trip; tampered scopes refused -------------------

    /** Temp dir with a not-yet-created journal file (the store treats an existing empty file as corrupt). */
    private fun withJournalFile(test: (File) -> Unit) {
        val directory = java.nio.file.Files.createTempDirectory("m3-journal").toFile()
        try { test(File(directory, "journal.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun providerGrantRoundTripsInTheFileStore() = withJournalFile { file ->
        val store = FileToolTaskStore(file)
        val ledger = ToolTaskLedger(store)
        ToolSourcePolicy.setProviderScopeResolver { wireName ->
            if (wireName == ProviderWireNames.toolName(providerA, "echo")) scopes(pkgA, "read")
            else emptySet()
        }
        val wire = ProviderWireNames.toolName(providerA, "echo")
        // Admitting a provider call persists like any other request.
        ledger.create(ActionRequest(wire))
        ToolSourceAccess(ledger).recordProviderGrant(wire, scopes(pkgA, "read"))
        val reread = FileToolTaskStore(file).readJournal()
        val record = reread.sourceAccess.single { it.family == "provider:appfunctions:com.example.sample" }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertEquals(scopes(pkgA, "read"), record.scopes)
    }

    @Test fun tamperedCrossProviderScopeIsRefused() = withJournalFile { file ->
        file.writeText(JSONObject()
            .put("schemaVersion", 3)
            .put("attempts", org.json.JSONArray())
            .put("groups", org.json.JSONArray())
            .put("approvals", org.json.JSONArray())
            .put("grants", org.json.JSONArray())
                .put("events", org.json.JSONArray())
                .put("sourceAccess", org.json.JSONArray().put(JSONObject()
                    .put("family", "provider:appfunctions:com.a")
                    .put("scopes", org.json.JSONArray().put("appfunctions:com.b:read"))
                    .put("state", "GRANTED")
                    .put("updatedAtMs", 0)))
                .put("workflows", org.json.JSONArray())
                .put("occurrences", org.json.JSONArray())
                .put("workflowReceipts", org.json.JSONArray())
                .put("activeQuestionId", JSONObject.NULL)
                .toString())
            try {
                FileToolTaskStore(file).readJournal()
                fail("tampered cross-provider scope must be refused")
            } catch (_: ToolTaskStorageException) { }
    }

    // -- Settings projection -------------------------------------------------------------------

    @Test fun settingsRowsAreHonestAboutAvailability() {
        val appRegistry = registryWith(sampleMetadata())
        val credentials = InMemoryMcpCredentialStore()
        val mcp = McpRegistry(FakeHttp(setupHandler()), credentials)
        val platform = AppFunctionPlatformStatus(DiscoveryAccessMethod.ORDINARY_APP,
            platformServiceAvailable = false, providersFound = 0,
            note = "No provider declarations found by the package scan.")
        val rows = ProviderSettings.rows(appRegistry, mcp, platform)
        val platformRow = rows.single { it.id == "appfunctions-platform" }
        assertEquals("unavailable", platformRow.state)
        assertTrue(platformRow.explanation.contains("ordinary app"))
        val providerRow = rows.single { it.id == "provider:com.example.sample" }
        assertEquals("available", providerRow.state)
        val mcpRow = rows.single { it.id == "mcp" }
        assertEquals("not configured", mcpRow.state)
        assertTrue(mcpRow.explanation.contains("unknown"))
        // The projection carries the rows through.
        val projection = WorkflowSettingsProjection.from(ToolTaskJournal(), 0L, rows)
        assertEquals(rows, projection.providers)
    }

    @Test fun connectedMcpServerAppearsWithItsState() {
        val (mcp, status, _) = registryWithStub()
        val rows = ProviderSettings.rows(ProviderRegistry(), mcp, null)
        val row = rows.single { it.id == "mcp:${status.config.id}" }
        assertEquals("connected", row.state)
        assertTrue(row.explanation.contains("discovered"))
    }

    // -- Finding 2: same-name / normalized-name MCP servers stay independent -------

    /**
     * MCP handler that routes by URL and enforces the per-URL bearer token:
     * a request carrying the wrong server's token is rejected, so
     * per-endpoint header correctness is proven by the flow succeeding.
     */
    private fun perUrlMcpHandler(
        tools: List<JSONObject>,
        tokens: Map<String, String>,
        onCall: (url: String, body: String) -> Unit = { _, _ -> }
    ): (String, String, Map<String, String>) -> McpHttpResponse = { url, body, headers ->
        val expected = tokens[url]
        if (expected != null && headers["Authorization"] != "Bearer $expected") {
            McpHttpResponse(401, "unauthorized", emptyMap())
        } else {
            val payload = JSONObject(body)
            when (payload.optString("method")) {
                "initialize" -> initializeOk()
                "tools/list" -> McpHttpResponse(200, stubToolsJson(*tools.toTypedArray()), emptyMap())
                "tools/call" -> {
                    onCall(url, body)
                    McpHttpResponse(200, JSONObject().put("jsonrpc", "2.0")
                        .put("id", payload.optInt("id"))
                        .put("result", JSONObject().put("content", org.json.JSONArray().put(
                            JSONObject().put("type", "text").put("text", "search results"))))
                        .toString(), emptyMap())
                }
                else -> McpHttpResponse(200, JSONObject().put("jsonrpc", "2.0")
                    .put("id", payload.optInt("id")).put("result", JSONObject()).toString(), emptyMap())
            }
        }
    }

    private fun lastAuthFor(
        requests: List<Triple<String, String, Map<String, String>>>,
        url: String,
        method: String
    ): String? = requests
        .filter { it.first == url && JSONObject(it.second).optString("method") == method }
        .lastOrNull()?.third?.get("Authorization")

    @Test fun sameNameServersKeepIndependentSecrets() {
        val credentials = InMemoryMcpCredentialStore()
        val urlA = "https://a.example.com/mcp"
        val urlB = "https://b.example.com/mcp"
        val http = FakeHttp(perUrlMcpHandler(
            tools = listOf(stubTool("search", "free")),
            tokens = mapOf(urlA to "token-a", urlB to "token-b")))
        val flow = McpSetupFlow(http, credentials)
        // The same display name twice: both sanitize to "test_server", with
        // different secrets under independent opaque references.
        val statusA = (flow.run("Test Server", urlA, "token-a")
            as McpSetupFlow.FlowResult.Connected).status
        val statusB = (flow.run("Test Server", urlB, "token-b")
            as McpSetupFlow.FlowResult.Connected).status
        assertNotEquals(statusA.config.authRef, statusB.config.authRef)
        val registry = McpRegistry(http, credentials)
        val placedA = registry.add(statusA)
        val placedB = registry.add(statusB)
        // add() resolves the id collision with a "-2" suffix.
        assertEquals("test_server", placedA.config.id)
        assertEquals("test_server-2", placedB.config.id)

        // Refresh: each endpoint gets exactly its own bearer token.
        assertEquals(McpServerState.CONNECTED, registry.refresh(placedA.config.id).state)
        assertEquals(McpServerState.CONNECTED, registry.refresh(placedB.config.id).state)
        assertEquals("Bearer token-a", lastAuthFor(http.requests, urlA, "initialize"))
        assertEquals("Bearer token-b", lastAuthFor(http.requests, urlB, "initialize"))
        assertEquals("Bearer token-a", lastAuthFor(http.requests, urlA, "tools/list"))
        assertEquals("Bearer token-b", lastAuthFor(http.requests, urlB, "tools/list"))

        // Invocation: per-server Bearer headers on the tools/call posts.
        val invoker = McpInvoker(registry, http, credentials)
        val providerA = ProviderId.mcp(placedA.config.id)
        val providerB = ProviderId.mcp(placedB.config.id)
        assertTrue(invoker.invoke(ProviderCall(providerA, "search")) is ProviderCallResult.Success)
        assertTrue(invoker.invoke(ProviderCall(providerB, "search")) is ProviderCallResult.Success)
        assertEquals("Bearer token-a", lastAuthFor(http.requests, urlA, "tools/call"))
        assertEquals("Bearer token-b", lastAuthFor(http.requests, urlB, "tools/call"))

        // Removal deletes only its own secret; the survivor keeps working
        // with its own token.
        val refA = placedA.config.authRef!!
        val refB = placedB.config.authRef!!
        assertTrue(registry.remove(placedA.config.id))
        assertFalse(credentials.hasSecret(refA))
        assertTrue(credentials.hasSecret(refB))
        assertEquals(McpServerState.CONNECTED, registry.refresh(placedB.config.id).state)
        assertEquals("Bearer token-b", lastAuthFor(http.requests, urlB, "tools/list"))
        assertTrue(invoker.invoke(ProviderCall(providerB, "search")) is ProviderCallResult.Success)
        assertEquals("Bearer token-b", lastAuthFor(http.requests, urlB, "tools/call"))
    }

    @Test fun normalizedNameCollisionGetsUniqueId() {
        val credentials = InMemoryMcpCredentialStore()
        val urlA = "https://a.example.com/mcp"
        val urlB = "https://b.example.com/mcp"
        val http = FakeHttp(perUrlMcpHandler(
            tools = listOf(stubTool("search", "free")),
            tokens = mapOf(urlA to "token-a", urlB to "token-b")))
        val flow = McpSetupFlow(http, credentials)
        // ProviderId.sanitize maps both names to "test_server": the
        // underscore and the space normalize identically.
        val first = (flow.run("Test_Server", urlA, "token-a")
            as McpSetupFlow.FlowResult.Connected).status
        val second = (flow.run("Test Server", urlB, "token-b")
            as McpSetupFlow.FlowResult.Connected).status
        assertEquals("test_server", first.config.id)
        assertEquals("test_server", second.config.id)
        val registry = McpRegistry(http, credentials)
        val placedFirst = registry.add(first)
        val placedSecond = registry.add(second)
        assertEquals("test_server", placedFirst.config.id)
        assertEquals("test_server-2", placedSecond.config.id)
        assertNotEquals(placedFirst.config.id, placedSecond.config.id)
        // Secrets stay independent despite the shared base id.
        assertNotEquals(placedFirst.config.authRef, placedSecond.config.authRef)
        assertEquals("token-a", credentials.getSecret(placedFirst.config.authRef!!))
        assertEquals("token-b", credentials.getSecret(placedSecond.config.authRef!!))
    }

    // -- Finding 5: parameter-bearing calls through dispatcher -> invoker ----------

    @Test fun parameterBearingCallEmitsExactNestedJsonRpc() {
        val credentials = InMemoryMcpCredentialStore()
        val url = "https://mcp.example.com/rpc"
        var capturedCall: Pair<String, String>? = null
        val http = FakeHttp(perUrlMcpHandler(
            tools = listOf(stubTool("search", "free")),
            tokens = mapOf(url to "good-token"),
            onCall = { callUrl, body -> capturedCall = callUrl to body }))
        val flow = McpSetupFlow(http, credentials)
        val connected = (flow.run("Stub", url, "good-token")
            as McpSetupFlow.FlowResult.Connected).status
        val mcp = McpRegistry(http, credentials)
        val placed = mcp.add(connected)
        val provider = ProviderId.mcp(placed.config.id)
        val invoker = McpInvoker(mcp, http, credentials)
        val dispatcher = dispatcherFor(ProviderRegistry(), mcp, mapOf(provider to invoker))
        val args = mapOf(
            "q" to "x",
            "filter" to mapOf("a" to 1, "b" to listOf("x", "y")))
        val result = dispatcher.dispatch(ProviderCall(provider, "search", args))
        assertTrue("the parameter-bearing call must succeed: $result",
            result is ProviderCallResult.Success)
        // The real ProviderDispatcher -> McpInvoker ->
        // McpProtocol.callToolRequest path: assert the exact nested
        // arguments on the wire.
        // JUnit's fail() is Kotlin Unit, not Nothing: throw so the elvis
        // branch keeps the Pair type for destructuring.
        val (callUrl, body) = capturedCall
            ?: throw AssertionError("no tools/call post was captured")
        assertEquals(url, callUrl)
        val params = JSONObject(body).getJSONObject("params")
        assertEquals("search", params.getString("name"))
        val sent = params.getJSONObject("arguments")
        assertEquals("x", sent.getString("q"))
        val filter = sent.getJSONObject("filter")
        assertEquals(1, filter.getInt("a"))
        val b = filter.getJSONArray("b")
        assertEquals(2, b.length())
        assertEquals("x", b.getString(0))
        assertEquals("y", b.getString(1))
    }

    @Test fun strictSchemaRejectionPreserved() {
        // The MCP adapter validates the call envelope with an open schema
        // (Obj(emptyMap(), additionalProperties = true)), so no MCP argument
        // can fail conversion by design; strict rejection is therefore
        // exercised through an AppFunctions provider with a declared schema,
        // on the identical ProviderDispatcher path.
        val registry = registryWith(sampleMetadata())
        val invoker = FakeInvoker()
        val mcp = McpRegistry(
            FakeHttp { _, _, _ -> McpHttpResponse(500, "", emptyMap()) },
            InMemoryMcpCredentialStore())
        val dispatcher = dispatcherFor(registry, mcp, mapOf(providerA to invoker))
        val result = dispatcher.dispatchByAlias("echo", mapOf("text" to 42))
        assertTrue(result is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.INVALID_ARGUMENTS,
            (result as ProviderCallResult.TypedError).code)
        assertTrue("a strict-schema rejection dispatches nothing", invoker.calls.isEmpty())
    }
}
