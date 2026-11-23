package com.battlesbudz.jarvis.v2.privilege

/** Local pairing/lifecycle policy. A connected state is only reached after the transport confirms TLS. */
internal class PairingStateMachine {
    enum class State { IDLE, DISCOVERING, PAIRING, CONNECTED, BLOCKED, RECOVERY }
    data class Snapshot(val state: State, val reason: String? = null)
    private var current = Snapshot(State.IDLE)
    fun snapshot() = current
    fun move(next: State, reason: String? = null): Snapshot {
        val allowed = when (current.state) {
            State.IDLE -> setOf(State.DISCOVERING, State.BLOCKED)
            State.DISCOVERING -> setOf(State.PAIRING, State.BLOCKED, State.IDLE)
            State.PAIRING -> setOf(State.CONNECTED, State.BLOCKED, State.IDLE)
            State.CONNECTED -> setOf(State.BLOCKED, State.RECOVERY, State.IDLE)
            State.BLOCKED -> setOf(State.DISCOVERING, State.RECOVERY, State.IDLE)
            State.RECOVERY -> setOf(State.DISCOVERING, State.PAIRING, State.BLOCKED, State.IDLE)
        }
        require(next in allowed) { "Illegal privilege transition ${current.state} -> $next" }
        require((next == State.BLOCKED) == !reason.isNullOrBlank()) {
            "Blocked state requires a precise reason; other states cannot carry one."
        }
        return Snapshot(next, reason).also { current = it }
    }
}

/** Values read from the phone's own wireless-debugging configuration; not proof of pairing. */
internal data class AdbEndpoint(val host: String, val port: Int)
internal fun adbEndpoint(host: String?, port: Int?): AdbEndpoint? =
    if (host.isNullOrBlank() || port == null || port !in 1..65535) null else AdbEndpoint(host, port)

internal enum class RecoveryDecision { KEEP_HELPER, REQUEST_CONSENTED_PAIRING, BLOCK_PRIVILEGED_OPERATION }
internal object HelperRecoveryPolicy {
    fun decide(helperResponsive: Boolean, pairingValid: Boolean, consented: Boolean): RecoveryDecision = when {
        helperResponsive && pairingValid -> RecoveryDecision.KEEP_HELPER
        !pairingValid -> RecoveryDecision.BLOCK_PRIVILEGED_OPERATION
        consented -> RecoveryDecision.REQUEST_CONSENTED_PAIRING
        else -> RecoveryDecision.BLOCK_PRIVILEGED_OPERATION
    }
}

/** On-device wireless-debugging pairing port. Implementations must establish the ADB TLS session. */
internal interface WirelessDebuggingPairingTransport {
    fun pair(endpoint: AdbEndpoint, pairingCode: CharArray): String
}

internal class PairingCoordinator(
    private val states: PairingStateMachine,
    private val discover: () -> AdbEndpoint?,
    private val transport: WirelessDebuggingPairingTransport?
) {
    constructor(states: PairingStateMachine, discover: () -> AdbEndpoint?) :
        this(states, discover, AndroidAdbPairingTransport())

    fun pair(code: CharArray): String {
        return try {
            states.move(PairingStateMachine.State.DISCOVERING)
            val endpoint = discover() ?: return blocked("Wireless debugging endpoint is unavailable.")
            states.move(PairingStateMachine.State.PAIRING)
            val activeTransport = transport ?: return blocked("Secure on-device ADB pairing transport is unavailable.")
            activeTransport.pair(endpoint, code).also {
                PairingIdentity.storePeerInfo(it)
                states.move(PairingStateMachine.State.CONNECTED)
            }
        } catch (failure: PairingTransportException) {
            blocked(failure.blockedReason)
        } catch (failure: Exception) {
            blocked("Wireless-debugging pairing failed: ${failure.message ?: "secure session was not established"}.")
        } finally { code.fill('\u0000') }
    }
    private fun blocked(reason: String): String {
        states.move(PairingStateMachine.State.BLOCKED, reason)
        return reason
    }
}

internal interface PrivilegeHelperBackend {
    fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int): Int
    fun releaseVirtualDisplay(displayId: Int)
    fun enableAccessibilityService(component: String): Boolean
    fun grantPermission(permissionName: String): Boolean
    fun state(): String
    fun injectInput(displayId: Int, event: android.os.Bundle): Boolean = false
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = false
    fun getClipboardText(): String? = null
    fun setClipboardText(text: String): Boolean = false
}

/** Enforces the charter surface even if a future backend grows additional methods. */
internal class PrivilegeHelperContract(private val backend: PrivilegeHelperBackend) {
    fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int): Int {
        require(name.isNotBlank() && width > 0 && height > 0 && densityDpi > 0)
        return backend.createVirtualDisplay(name, width, height, densityDpi)
    }
    fun releaseVirtualDisplay(displayId: Int) { require(displayId >= 0); backend.releaseVirtualDisplay(displayId) }
    fun enableAccessibilityService(component: String): Boolean {
        require(component == "com.battlesbudz.jarvis.v2/.actions.ScreenControlService")
        return backend.enableAccessibilityService(component)
    }
    fun grantPermission(permissionName: String): Boolean {
        require(permissionName in ALLOWED_PERMISSION_NAMES) { "Permission is outside the A7 allowlist." }
        return backend.grantPermission(permissionName)
    }
    fun getPrivilegeState(): String = backend.state()
    fun injectInput(displayId: Int, event: android.os.Bundle): Boolean {
        require(displayId >= 0)
        return backend.injectInput(displayId, event)
    }
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        require(taskId >= 0 && displayId >= 0)
        return backend.moveTaskToDisplay(taskId, displayId)
    }
    fun getClipboardText(): String? = backend.getClipboardText()
    fun setClipboardText(text: String): Boolean = backend.setClipboardText(text)
    companion object { val ALLOWED_PERMISSION_NAMES = setOf("display", "accessibility") }
}

internal class PrivilegedOperationGate(private val helperAvailable: () -> Boolean) {
    fun <T> run(operation: () -> T): Result<T> = if (helperAvailable()) runCatching(operation)
    else Result.failure(IllegalStateException("The app-control helper is unavailable. The task stopped."))
}
