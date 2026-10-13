package com.battlesbudz.jarvis.v2.actions

/**
 * M3 provider dispatch (T16, T17, T08): one admission path for provider
 * functions with grant checks, strict argument conversion and typed
 * results.
 *
 * Admission order mirrors the native pipeline: exposure gate, remembered
 * source access (denial/revocation/scope — the grant can never broaden),
 * paid-service confirmation (D11 is never waived: a paid function returns
 * [ProviderCallResult.NeedsPurchaseConfirmation] instead of dispatching),
 * strict argument conversion, then the provider's invoker. A successful
 * call records the provider's first-source grant through the same journal
 * as native tools. Receipts are honest: what was dispatched, never a
 * claimed external effect that cannot be confirmed.
 *
 * Provider exposure to the model stays disabled until M7: [ProviderCallerKind.MODEL]
 * calls are rejected structurally, not by policy text.
 */
data class ProviderCall(
    val provider: ProviderId,
    val functionId: String,
    val arguments: Map<String, Any?> = emptyMap()
)

enum class ProviderErrorCode {
    INVALID_ARGUMENTS,
    NOT_FOUND,
    DENIED_PERMISSION,
    SCOPE_DENIED,
    NOT_ENABLED,
    PROVIDER_UNAVAILABLE,
    PLATFORM_ABSENT,
    VERSION_MISMATCH,
    AUTH_FAILED,
    SCHEMA_CHANGED,
    NETWORK_ERROR,
    UNKNOWN
}

sealed interface ProviderCallResult {
    data class Success(val data: Map<String, Any?>, val receipt: String) : ProviderCallResult
    data class TypedError(val code: ProviderErrorCode, val message: String) : ProviderCallResult
    data class UriResult(val uri: String, val label: String) : ProviderCallResult
    data class NeedsUserInteraction(val prompt: String, val actionLabel: String) : ProviderCallResult
    /**
     * D11: paid enablement never waives purchase confirmation. The call did
     * not dispatch; the caller must confirm explicitly before any retry.
     */
    data class NeedsPurchaseConfirmation(val summary: String, val priceNote: String) : ProviderCallResult
}

/** Executes one converted provider call. Implementations never see model prompts. */
fun interface ProviderInvoker {
    fun invoke(call: ProviderCall): ProviderCallResult
}

enum class ProviderCallerKind { INTERNAL, MODEL }

/** M7 gate: provider functions are not exposed to the model in this milestone. */
object ProviderExposure {
    const val MODEL_EXPOSURE_ENABLED = false
}

/** One step of a controlled dependent-function journey: later steps bind earlier outputs. */
data class ProviderJourneyStep(
    val id: String,
    val alias: String,
    val arguments: Map<String, Any?> = emptyMap()
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_]{1,32}"))) { "Step id must be 1-32 chars of [a-zA-Z0-9_]." }
    }
}

data class ProviderJourneyResult(
    val succeeded: Boolean,
    val stepResults: List<ProviderCallResult>,
    val receipt: String
)

class ProviderDispatcher(
    private val ledger: ToolTaskLedger,
    private val appFunctions: ProviderRegistry,
    private val mcp: McpRegistry,
    private val invokers: Map<ProviderId, ProviderInvoker> = emptyMap()
) {
    private val sourceAccess = ToolSourceAccess(ledger)

    /** Resolve a model-visible alias, then dispatch. Unknown aliases never guess. */
    fun dispatchByAlias(
        alias: String,
        arguments: Map<String, Any?> = emptyMap(),
        caller: ProviderCallerKind = ProviderCallerKind.INTERNAL
    ): ProviderCallResult {
        if (caller == ProviderCallerKind.MODEL && !ProviderExposure.MODEL_EXPOSURE_ENABLED) {
            return ProviderCallResult.TypedError(ProviderErrorCode.DENIED_PERMISSION,
                "Provider tools are not available to the model in this build.")
        }
        return when (val resolution = appFunctions.aliasRegistry.resolve(alias)) {
            is AliasResolution.Resolved -> {
                val binding = resolution.binding
                val metadata = appFunctions.metadataForWire(binding.wireName)
                    ?: return ProviderCallResult.TypedError(ProviderErrorCode.NOT_FOUND,
                        "The provider function behind '$alias' is no longer available.")
                dispatch(ProviderCall(metadata.providerId, metadata.functionId, arguments), caller)
            }
            AliasResolution.Unknown -> ProviderCallResult.TypedError(ProviderErrorCode.NOT_FOUND,
                "No provider function is bound to '$alias'.")
        }
    }

    fun dispatch(
        call: ProviderCall,
        caller: ProviderCallerKind = ProviderCallerKind.INTERNAL
    ): ProviderCallResult {
        if (caller == ProviderCallerKind.MODEL && !ProviderExposure.MODEL_EXPOSURE_ENABLED) {
            return ProviderCallResult.TypedError(ProviderErrorCode.DENIED_PERMISSION,
                "Provider tools are not available to the model in this build.")
        }
        val meta = lookupMeta(call)
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.NOT_FOUND,
                "Unknown provider function '${call.functionId}' on '${call.provider.id}'.")
        val wireName = ProviderWireNames.toolName(call.provider, call.functionId)
        // T08: remembered source access gates every adapter. A denial or
        // revocation blocks dispatch; a narrower grant blocks broader scopes.
        when (val denial = sourceAccess.denial(ActionRequest(wireName))) {
            null -> Unit
            else -> {
                val code = if (denial.message.contains("broader access")) ProviderErrorCode.SCOPE_DENIED
                    else ProviderErrorCode.DENIED_PERMISSION
                return ProviderCallResult.TypedError(code, denial.message)
            }
        }
        if (!meta.enabled) {
            return ProviderCallResult.TypedError(ProviderErrorCode.NOT_ENABLED,
                "'${meta.displayName}' is not enabled. (${ProviderWireNames.describePricing(meta.pricing)})")
        }
        // D11: a paid function never dispatches without explicit purchase
        // confirmation — paid enablement does not waive it.
        if (meta.pricing == ProviderPricing.PAID) {
            return ProviderCallResult.NeedsPurchaseConfirmation(
                summary = "Call '${meta.displayName}' on '${call.provider.id}'.",
                priceNote = "This is a paid operation. Confirm explicitly before any charge.")
        }
        val convertedArgs = when (val conversion =
            AppFunctionTypeConverter.convertArguments(meta.parameters, call.arguments)) {
            is TypeConversion.Error -> return ProviderCallResult.TypedError(
                ProviderErrorCode.INVALID_ARGUMENTS,
                "Invalid arguments at ${conversion.path}: expected ${conversion.expected}, got ${conversion.actual}. Nothing was dispatched.")
            is TypeConversion.Ok -> {
                @Suppress("UNCHECKED_CAST")
                conversion.value as Map<String, Any?>
            }
        }
        val invoker = invokers[call.provider]
            ?: return ProviderCallResult.TypedError(ProviderErrorCode.PROVIDER_UNAVAILABLE,
                "No execution path is registered for '${call.provider.id}'. Nothing was dispatched.")
        val result = try {
            invoker.invoke(call.copy(arguments = convertedArgs))
        } catch (_: Exception) {
            ProviderCallResult.TypedError(ProviderErrorCode.UNKNOWN,
                "The provider call ended before its outcome could be confirmed; it will not be repeated automatically.")
        }
        // First-source access is remembered after a successful dispatch, and
        // only within the provider's declared scopes (T08).
        if (result is ProviderCallResult.Success) {
            sourceAccess.recordProviderGrant(wireName, meta.scopes)
        }
        return result
    }

    /**
     * Run one controlled dependent-function journey: each step's argument
     * templates may reference earlier outputs as `${stepId.path}` (a whole-
     * string placeholder injects the raw value; embedded placeholders
     * interpolate textually). The first non-success stops the journey; a
     * completed step never re-runs.
     */
    fun runJourney(
        steps: List<ProviderJourneyStep>,
        caller: ProviderCallerKind = ProviderCallerKind.INTERNAL
    ): ProviderJourneyResult {
        require(steps.isNotEmpty() && steps.size <= 8) { "A journey needs 1-8 steps." }
        require(steps.map { it.id }.toSet().size == steps.size) { "Step ids must be unique." }
        val outputs = linkedMapOf<String, Map<String, Any?>>()
        val results = arrayListOf<ProviderCallResult>()
        for (step in steps) {
            val resolvedArgs = try {
                resolveTemplates(step.arguments, outputs)
            } catch (e: IllegalArgumentException) {
                val error = ProviderCallResult.TypedError(ProviderErrorCode.INVALID_ARGUMENTS,
                    "Step '${step.id}': ${e.message}")
                results += error
                return ProviderJourneyResult(false, results,
                    "The journey stopped at step '${step.id}': an output binding did not resolve. No later step ran.")
            }
            val result = dispatchByAlias(step.alias, resolvedArgs, caller)
            results += result
            if (result is ProviderCallResult.Success) {
                outputs[step.id] = result.data
            } else {
                val reason = when (result) {
                    is ProviderCallResult.TypedError -> result.message
                    is ProviderCallResult.NeedsUserInteraction -> result.prompt
                    is ProviderCallResult.NeedsPurchaseConfirmation -> result.summary
                    is ProviderCallResult.UriResult -> result.label
                    is ProviderCallResult.Success -> ""
                }
                return ProviderJourneyResult(false, results,
                    "The journey stopped at step '${step.id}': $reason No later step ran.")
            }
        }
        return ProviderJourneyResult(true, results,
            "The journey completed ${steps.size} step(s).")
    }

    private data class ProviderFunctionMeta(
        val provider: ProviderId,
        val displayName: String,
        val parameters: AppFunctionType.Obj,
        val scopes: Set<String>,
        val pricing: ProviderPricing,
        /** MCP tools need explicit per-tool enablement; discovered AppFunctions are enabled. */
        val enabled: Boolean
    )

    private fun lookupMeta(call: ProviderCall): ProviderFunctionMeta? = when (call.provider.kind) {
        ProviderKind.APP_FUNCTIONS -> {
            val metadata = appFunctions.metadataFor(call.provider, call.functionId) ?: return null
            ProviderFunctionMeta(metadata.providerId, metadata.displayName, metadata.parameters,
                metadata.scopes, metadata.pricing, enabled = true)
        }
        ProviderKind.MCP -> {
            val tool = mcp.toolsFor(call.provider.id).firstOrNull { it.name == call.functionId } ?: return null
            // MCP tools carry JSON Schema; M3 validates the call envelope and
            // per-tool enablement/scope/pricing, not the full JSON Schema.
            // Full JSON-Schema validation is future work, stated honestly here.
            ProviderFunctionMeta(call.provider, tool.description.ifBlank { tool.name },
                AppFunctionType.Obj(emptyMap(), additionalProperties = true),
                tool.scopes.map { ProviderWireNames.scopedName(call.provider, it) }.toSet(),
                tool.pricing, enabled = tool.name in mcp.enabledToolsFor(call.provider.id))
        }
    }

    private fun resolveTemplates(
        template: Map<String, Any?>,
        outputs: Map<String, Map<String, Any?>>
    ): Map<String, Any?> = template.mapValues { (_, value) -> resolveValue(value, outputs) }

    private fun resolveValue(value: Any?, outputs: Map<String, Map<String, Any?>>): Any? = when (value) {
        is String -> resolveString(value, outputs)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            (value as Map<String, Any?>).mapValues { (_, nested) -> resolveValue(nested, outputs) }
        }
        is List<*> -> value.map { resolveValue(it, outputs) }
        else -> value
    }

    private val placeholder = Regex("\\$\\{([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_.]+)\\}")

    private fun resolveString(template: String, outputs: Map<String, Map<String, Any?>>): Any? {
        val whole = placeholder.matchEntire(template.trim())
        if (whole != null) return lookupBinding(whole, outputs, template)
        return placeholder.replace(template) { match ->
            val resolved = lookupBinding(match, outputs, template)
            resolved?.toString() ?: throw IllegalArgumentException("binding '$template' did not resolve")
        }
    }

    private fun lookupBinding(
        match: MatchResult,
        outputs: Map<String, Map<String, Any?>>,
        template: String
    ): Any? {
        val stepId = match.groupValues[1]
        val path = match.groupValues[2].split(".")
        var current: Any? = outputs[stepId]
            ?: throw IllegalArgumentException("binding '$template' refers to unknown step '$stepId'")
        for (segment in path) {
            current = when (current) {
                is Map<*, *> -> current[segment]
                is List<*> -> segment.toIntOrNull()?.let { index ->
                    current.getOrNull(index)
                }
                else -> null
            } ?: throw IllegalArgumentException("binding '$template' did not resolve at '$segment'")
        }
        return current
    }
}
