package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.pm.PackageManager

/**
 * M3 AppFunctions platform probe (T16): honest, labeled discovery of what
 * the device actually offers, using ordinary app access only.
 *
 * What it checks:
 * - whether the `android.app.appfunctions.AppFunctionManager` framework
 *   class exists on this device (platform service presence);
 * - a heuristic PackageManager scan for installed services whose intent
 *   actions mention app functions (ordinary app access — no special
 *   permission; labeled as such).
 *
 * What it does not claim: broad consumer AppFunctions access is a
 * platform-gated capability (see the decision record's unresolved
 * engineering choices). An empty result is reported as "none found by this
 * probe", never as proof that no provider exists, and it never blocks the
 * validated MCP adapters.
 */
data class AppFunctionPlatformStatus(
    val accessMethod: DiscoveryAccessMethod,
    val platformServiceAvailable: Boolean,
    val providersFound: Int,
    val note: String
)

class AppFunctionPlatformProbe(private val context: Context) {

    fun probe(): AppFunctionPlatformStatus {
        val platformServiceAvailable = try {
            Class.forName("android.app.appfunctions.AppFunctionManager")
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
        val providersFound = try {
            countDeclaredProviders()
        } catch (_: Exception) {
            -1
        }
        val note = when {
            providersFound < 0 -> "The package scan failed; provider state is unknown. " +
                "AppFunctions discovery stays unavailable until the scan succeeds."
            providersFound == 0 && !platformServiceAvailable ->
                "No AppFunctions framework service on this device and no provider " +
                    "declarations found by the package scan. AppFunctions tools are " +
                    "unavailable; this does not block the other providers."
            providersFound == 0 ->
                "No provider declarations found by the package scan. Broad consumer " +
                    "AppFunctions access may be platform-gated; absence here does not " +
                    "prove no provider exists."
            else -> "$providersFound provider declaration(s) matched the heuristic " +
                "package scan. Consumer call eligibility is still platform-gated."
        }
        return AppFunctionPlatformStatus(
            accessMethod = DiscoveryAccessMethod.ORDINARY_APP,
            platformServiceAvailable = platformServiceAvailable,
            providersFound = maxOf(providersFound, 0),
            note = note
        )
    }

    /**
     * Heuristic scan: installed packages exposing a service whose intent
     * action mentions app functions. Labeled as heuristic in the note —
     * declaration styles vary by platform release.
     */
    private fun countDeclaredProviders(): Int {
        val pm = context.packageManager
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_SERVICES)
        } catch (_: Exception) {
            return -1
        }
        var count = 0
        for (pkg in packages) {
            val services = pkg.services ?: continue
            val matched = services.any { service ->
                service.metaData?.keySet()?.any { key ->
                    key.contains("appfunction", ignoreCase = true)
                } == true
            }
            if (matched) count++
        }
        return count
    }
}
