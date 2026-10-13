package com.battlesbudz.jarvis.v2.actions

/**
 * M3 settings projection for ecosystem providers (D36, T16, T17): every
 * known provider is listed with an honest availability state and a
 * plain-language explanation. Unavailable providers explain why — the UI
 * never implies a provider works when it does not.
 *
 * Provider exposure to the model stays disabled until M7; that boundary is
 * stated on the rows themselves.
 */
data class ProviderSettingsRow(
    val id: String,
    val displayName: String,
    val kind: String,
    /** available, connected, unavailable, denied, disabled, not configured, blocked. */
    val state: String,
    val explanation: String
)

object ProviderSettings {
    fun rows(
        appFunctions: ProviderRegistry,
        mcp: McpRegistry,
        platform: AppFunctionPlatformStatus?
    ): List<ProviderSettingsRow> {
        val rows = arrayListOf<ProviderSettingsRow>()
        if (platform != null) {
            val accessLabel = if (platform.accessMethod == DiscoveryAccessMethod.ORDINARY_APP) "ordinary app" else "ADB"
            rows += ProviderSettingsRow(
                id = "appfunctions-platform",
                displayName = "AppFunctions",
                kind = "AppFunctions",
                state = if (platform.providersFound > 0) "available" else "unavailable",
                explanation = "${platform.note} (Discovery via $accessLabel access.) " +
                    "Provider tools stay off the model's surface until a later milestone.")
        }
        for (provider in appFunctions.providerIds()) {
            val functions = appFunctions.functionsOf(provider)
            rows += ProviderSettingsRow(
                id = "provider:${provider.id}",
                displayName = provider.id,
                kind = "AppFunctions",
                state = if (functions.isEmpty()) "unavailable" else "available",
                explanation = if (functions.isEmpty())
                    "No functions discovered for this provider."
                else
                    "${functions.size} function(s) discovered. Model access stays off until a later milestone.")
        }
        val servers = mcp.all()
        if (servers.isEmpty()) {
            rows += ProviderSettingsRow(
                id = "mcp",
                displayName = "MCP servers",
                kind = "MCP",
                state = "not configured",
                explanation = "No MCP servers connected. Add a server URL below to connect; " +
                    "only free tools turn on by default, and a price that is unknown is never called free.")
        }
        for (server in servers) {
            rows += ProviderSettingsRow(
                id = "mcp:${server.config.id}",
                displayName = server.config.name,
                kind = "MCP",
                state = server.state.name.lowercase(),
                explanation = server.explanation
            )
        }
        return rows
    }
}
