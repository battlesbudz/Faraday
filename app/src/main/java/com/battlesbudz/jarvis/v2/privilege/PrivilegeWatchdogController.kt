package com.battlesbudz.jarvis.v2.privilege

/**
 * Owns the app-facing privilege projection and recovery choice. The caller supplies a
 * helper heartbeat and pairing validity; neither can be inferred from a saved status string.
 */
internal class PrivilegeWatchdogController(
    private val helperResponsive: () -> Boolean,
    private val pairingValid: () -> Boolean,
    private val recoveryConsentGranted: () -> Boolean,
    private val requestConsentedRecovery: () -> Boolean,
) {
    data class Status(val present: Boolean, val reason: String? = null) {
        fun report(): String = if (present) "present" else "blocked: ${reason ?: "helper unavailable"}"
    }

    @Volatile private var current = Status(false, "helper session has not been established")

    fun status(): Status = current

    /** One live heartbeat cycle. Recovery is attempted only through the consented port. */
    @Synchronized
    fun refresh(): Status {
        val paired = runCatching(pairingValid).getOrDefault(false)
        val responsive = runCatching(helperResponsive).getOrDefault(false)
        val consented = runCatching(recoveryConsentGranted).getOrDefault(false)
        return when (HelperRecoveryPolicy.decide(responsive, paired, consented)) {
            RecoveryDecision.KEEP_HELPER -> Status(present = true)
            RecoveryDecision.REQUEST_CONSENTED_PAIRING -> {
                val recovered = runCatching(requestConsentedRecovery).getOrDefault(false)
                if (recovered && runCatching(helperResponsive).getOrDefault(false)) Status(present = true)
                else Status(false, "helper is unavailable; consented wireless-debugging recovery did not establish a session")
            }
            RecoveryDecision.BLOCK_PRIVILEGED_OPERATION -> Status(
                false,
                if (!paired) "wireless-debugging pairing is absent or revoked"
                else "helper is unavailable; wireless-debugging recovery requires user consent"
            )
        }.also { current = it }
    }

    /** Called only after the user has explicitly accepted a recovery prompt. */
    @Synchronized
    fun recoverAfterConsent(): Status {
        val paired = runCatching(pairingValid).getOrDefault(false)
        if (!paired) return Status(false, "wireless-debugging pairing is absent or revoked")
            .also { current = it }
        val recovered = runCatching(requestConsentedRecovery).getOrDefault(false)
        val status = if (recovered && runCatching(helperResponsive).getOrDefault(false)) Status(true)
        else Status(false, "consented wireless-debugging recovery did not establish a helper session")
        current = status
        return status
    }
}
