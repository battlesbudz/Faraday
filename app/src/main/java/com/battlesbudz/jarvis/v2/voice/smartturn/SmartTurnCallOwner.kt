package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.voice.CaptureShadowObserver
import java.io.File

/** Process-retained budget for one call worker. Closing never means that native work drained. */
internal class SmartTurnCallOwner(
    private val clock: () -> Long = System::nanoTime,
    private val createShadow: (File) -> SmartTurnShadow,
) : AutoCloseable {
    private var callId: String? = null
    private var shadow: SmartTurnShadow? = null
    private var observer: SmartTurnCaptureObserver? = null
    private var closing = false
    private var disposed = false

    @Synchronized fun beginCapture(
        expectedCallId: String, turnId: String, captureGeneration: Long,
        enabled: Boolean, model: File?, telemetry: SmartTurnTelemetry,
        ownerIsCurrent: () -> Boolean, admissionBlocker: () -> String?,
        observeCapture: Boolean = true,
        endpointEnabled: Boolean = false,
    ): CaptureShadowObserver? {
        val evidence = SafeSmartTurnTelemetry(telemetry)
        // A stale setup attempt has no authority to revoke/reap a newer call owner.
        if (!runCatching(ownerIsCurrent).getOrDefault(false)) {
            evidence.configuration("smart_turn_mode", "owner_not_current"); return null
        }
        if (disposed) { evidence.configuration("smart_turn_mode", "runtime_disposed"); return null }
        if (callId != null && (callId != expectedCallId || !enabled || model == null)) revoke()
        if (closing) {
            if (shadow?.awaitClosed(0) == false) {
                evidence.configuration("smart_turn_mode", "unavailable_previous_owner_draining")
                return null
            }
            shadow = null; callId = null; closing = false
        }
        if (!enabled || model == null || !ownerIsCurrent()) {
            evidence.configuration("smart_turn_mode", if (!enabled) "disabled" else if (model == null) "model_unavailable" else "owner_not_current")
            return null
        }
        observer?.close()
        observer = null
        // Already sealed follow-ups need no observer. Still apply disable, unavailable
        // model and changed-call revocation above, while retaining a valid call worker.
        if (!observeCapture) {
            evidence.configuration("smart_turn_mode", "ineligible_sealed_capture")
            return null
        }
        return try {
            if (shadow == null) {
                shadow = createShadow(model)
                callId = expectedCallId
            }
            SmartTurnCaptureObserver(requireNotNull(shadow), turnId, captureGeneration,
                evidence, ownerIsCurrent, admissionBlocker, clock, endpointEnabled).also { observer = it }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            revoke()
            throw cancelled
        } catch (_: Exception) {
            revoke()
            evidence.configuration("smart_turn_mode", "unavailable_optional_setup")
            null
        } catch (_: LinkageError) {
            revoke()
            evidence.configuration("smart_turn_mode", "unavailable_native_binding")
            null
        }
    }

    /** Stale previous-call finalizers cannot close a newer call. */
    @Synchronized fun closeCall(expectedCallId: String?) {
        if (expectedCallId == null || expectedCallId != callId) return
        revoke()
    }
    private fun revoke() {
        val prior = observer
        observer = null
        closing = shadow != null
        runCatching { prior?.close() }
        runCatching { shadow?.close() }
        // Retain the exact owner until awaitClosed(0) proves actual thread exit.
    }
    @Synchronized override fun close() {
        disposed = true
        revoke()
    }
}
