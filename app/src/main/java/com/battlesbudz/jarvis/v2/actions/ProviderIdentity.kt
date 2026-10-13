package com.battlesbudz.jarvis.v2.actions

/**
 * M3 ecosystem integrations (D05, D07, T16, T17): identity, wire names and
 * grant-family mapping for provider functions.
 *
 * A provider is either an Android AppFunctions provider (identified by its
 * package name) or an MCP server (identified by its configured server id).
 * Model-visible aliases are bound separately in [AppFunctionAliasRegistry];
 * on the wire and in the journal every provider function has one canonical
 * name: `provider:<kind>:<id>:<function>`.
 *
 * Grant families are per provider (`provider:<kind>:<id>`), so first-source
 * access is remembered per provider (D09, T08): a new function under an
 * already-granted provider stays inside the granted scopes (D10), a new
 * provider needs its own grant, and denial/revocation blocks every adapter.
 * Provider scopes are namespaced `<kind>:<id>:<scope>`; the journal's T08
 * invariant ("a persisted grant can never exceed its family's scope set") is
 * enforced statically by [scopeWithinCap], without needing a registry at
 * read time.
 */
enum class ProviderPricing {
    FREE, PAID,
    /** The provider did not publish a price. Never presented as free. */
    UNKNOWN
}

enum class ProviderKind(val wire: String) {
    APP_FUNCTIONS("appfunctions"),
    MCP("mcp");

    companion object {
        fun fromWire(wire: String): ProviderKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A provider's stable identity. Ids are sanitized to `[a-z0-9._-]`, 1..48 chars. */
data class ProviderId(val kind: ProviderKind, val id: String) {
    init {
        require(id.matches(ID_PATTERN)) { "Provider id must match [a-z0-9._-]{1,48}." }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9._-]{1,48}")

        fun appFunctions(packageName: String) = ProviderId(ProviderKind.APP_FUNCTIONS, sanitize(packageName))

        fun mcp(serverId: String) = ProviderId(ProviderKind.MCP, sanitize(serverId))

        fun sanitize(raw: String): String {
            val cleaned = raw.lowercase().replace(Regex("[^a-z0-9._-]"), "_").trim('_', '.', '-')
            require(cleaned.matches(ID_PATTERN)) { "Provider id '$raw' is not usable." }
            return cleaned
        }
    }
}

object ProviderWireNames {
    const val PREFIX = "provider"

    fun toolName(provider: ProviderId, functionId: String): String =
        "$PREFIX:${provider.kind.wire}:${provider.id}:${sanitizeFunctionId(functionId)}"

    fun sanitizeFunctionId(raw: String): String {
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        require(cleaned.isNotEmpty() && cleaned.length <= 64) { "Function id '$raw' is not usable." }
        return cleaned
    }

    data class ParsedTool(val provider: ProviderId, val functionId: String)

    fun parseToolName(name: String): ParsedTool? {
        if (!name.startsWith("$PREFIX:")) return null
        val parts = name.split(":")
        if (parts.size != 4) return null
        val kind = ProviderKind.fromWire(parts[1]) ?: return null
        return try {
            ParsedTool(ProviderId(kind, parts[2]), parts[3])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isProviderTool(name: String): Boolean = parseToolName(name) != null

    fun familyFor(provider: ProviderId): String = "$PREFIX:${provider.kind.wire}:${provider.id}"

    fun parseFamily(family: String): ProviderId? {
        if (!family.startsWith("$PREFIX:")) return null
        val parts = family.split(":")
        if (parts.size != 3) return null
        val kind = ProviderKind.fromWire(parts[1]) ?: return null
        return try {
            ProviderId(kind, parts[2])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isProviderFamily(family: String): Boolean = parseFamily(family) != null

    /** Every scope granted for a provider family must live under the provider's namespace. */
    fun scopeNamespace(provider: ProviderId): String = "${provider.kind.wire}:${provider.id}:"

    fun scopeWithinCap(family: String, scope: String): Boolean {
        val provider = parseFamily(family) ?: return false
        return scope.startsWith(scopeNamespace(provider)) && scope.length <= 64
    }

    fun scopedName(provider: ProviderId, scope: String): String = scopeNamespace(provider) + scope

    fun describePricing(pricing: ProviderPricing): String = when (pricing) {
        ProviderPricing.FREE -> "Free"
        ProviderPricing.PAID -> "Paid"
        // Never "Free": an unknown price must not be presented as free.
        ProviderPricing.UNKNOWN -> "Price unknown"
    }
}
