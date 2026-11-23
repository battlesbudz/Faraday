package com.battlesbudz.jarvis.v2.actions

import android.os.Bundle
import com.battlesbudz.jarvis.v2.display.OperatedDisplayIdentity
import com.battlesbudz.jarvis.v2.perception.OperatedObservation
import com.battlesbudz.jarvis.v2.perception.isObservationStale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounds are captured alongside the A3 node facts by the live-tree provider. */
data class OperatedNodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) }
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

data class LiveOperatedNode(
    val className: String,
    val viewId: String?,
    val text: String?,
    val stateFlags: Set<String>,
    val bounds: OperatedNodeBounds,
)

sealed interface OperatedNodeKey {
    data class ViewId(val value: String) : OperatedNodeKey
    data class ClassAndText(val className: String, val text: String) : OperatedNodeKey
}

data class OperatedTapRequest(
    val observation: OperatedObservation,
    val liveObservation: OperatedObservation,
    val liveDisplay: OperatedDisplayIdentity,
    val target: OperatedNodeKey,
    val paymentMarked: Boolean,
    val cancelled: Boolean,
)

fun interface OperatedLiveNodeReader { fun read(identity: OperatedDisplayIdentity): LiveOperatedNode? }
fun interface OperatedInputInjector { fun inject(displayId: Int, event: Bundle): Boolean }
fun interface OperatedObservationReader { fun read(identity: OperatedDisplayIdentity): OperatedObservation? }

data class OperatedTapReceipt(
    val succeeded: Boolean,
    val reason: String,
    val screenChanged: Boolean? = null,
    val diagnosticBounds: OperatedNodeBounds? = null,
)

/** The envelope and A3 facts cross A7 unchanged; A4 performs no classification. */
data class OperatedActionAdmission(val request: ActionRequest, val observation: OperatedObservation) {
    val facts: List<com.battlesbudz.jarvis.v2.perception.ObservedNode> get() = observation.nodes
}

fun interface OperatedActionPolicyGate {
    fun admit(request: ActionRequest, facts: List<com.battlesbudz.jarvis.v2.perception.ObservedNode>): ActionControlPolicy.Disposition
}

fun admitOperatedAction(admission: OperatedActionAdmission, gate: OperatedActionPolicyGate) =
    gate.admit(admission.request, admission.facts)

/** Phase 1 tap path: resolve live identity, then inject only through A1 helper. */
class OperatedTapExecutor(
    private val liveNodes: OperatedLiveNodeReader,
    private val injector: OperatedInputInjector,
    private val observations: OperatedObservationReader,
) {
    suspend fun executeCancellable(request: OperatedTapRequest): OperatedTapReceipt {
        try {
            currentCoroutineContext().ensureActive()
            return execute(request)
        } catch (_: CancellationException) {
            return OperatedTapReceipt(false, "cancelled")
        }
    }

    fun execute(request: OperatedTapRequest): OperatedTapReceipt {
        if (request.cancelled || Thread.currentThread().isInterrupted)
            return OperatedTapReceipt(false, "cancelled")
        if (request.paymentMarked) return OperatedTapReceipt(false, "payment_marker_refused")
        if (request.observation.displayIdentity.displayId != request.liveDisplay.displayId ||
            request.liveObservation.displayIdentity.displayId != request.liveDisplay.displayId)
            return OperatedTapReceipt(false, "display_mismatch")
        if (isObservationStale(request.observation, request.liveObservation))
            return OperatedTapReceipt(false, "stale_observation")
        val node = liveNodes.read(request.liveDisplay) ?: return OperatedTapReceipt(false, "target_missing")
        if ("password" in node.stateFlags) return OperatedTapReceipt(false, "password_target_refused")
        val identityMatches = when (val key = request.target) {
            is OperatedNodeKey.ViewId -> !key.value.isBlank() && node.viewId == key.value
            is OperatedNodeKey.ClassAndText -> node.className == key.className && node.text == key.text
        }
        if (!identityMatches) return OperatedTapReceipt(false, "target_missing")
        if (request.cancelled || Thread.currentThread().isInterrupted)
            return OperatedTapReceipt(false, "cancelled", diagnosticBounds = node.bounds)
        val accepted = try {
            injector.inject(request.liveDisplay.displayId, Bundle().apply {
                putString("type", "tap")
                putFloat("x", node.bounds.centerX.toFloat())
                putFloat("y", node.bounds.centerY.toFloat())
            })
        } catch (_: Exception) { false }
        if (!accepted) return OperatedTapReceipt(false, "helper_injection_failed", diagnosticBounds = node.bounds)
        val after = observations.read(request.liveDisplay)
            ?: return OperatedTapReceipt(true, "tapped_readback_unavailable", null, node.bounds)
        return OperatedTapReceipt(true, "tapped", isObservationStale(request.liveObservation, after), node.bounds)
    }
}

/** Provider is deliberately injected: this snapshot has no live package-task display query. */
fun interface TaskLocationProvider { fun location(packageName: String): TaskLocation }
enum class TaskLocation { NONE, LIVE_VIRTUAL, DEAD_VIRTUAL, MAIN_DISPLAY }
enum class LaunchDecision { PROCEED, BLOCKED_LIVE_TASK, END_DEAD_TASK_THEN_LAUNCH, BLOCKED_APP_OPEN_ON_MAIN_SCREEN }

fun launchDecision(location: TaskLocation): LaunchDecision = when (location) {
    TaskLocation.NONE -> LaunchDecision.PROCEED
    TaskLocation.LIVE_VIRTUAL -> LaunchDecision.BLOCKED_LIVE_TASK
    TaskLocation.DEAD_VIRTUAL -> LaunchDecision.END_DEAD_TASK_THEN_LAUNCH
    TaskLocation.MAIN_DISPLAY -> LaunchDecision.BLOCKED_APP_OPEN_ON_MAIN_SCREEN
}

fun decideLaunch(packageName: String, locations: TaskLocationProvider): LaunchDecision =
    launchDecision(locations.location(packageName))

/** Narrow adapter to the already chartered A1 helper operation. */
class HelperOperatedInputInjector(private val helper: () -> com.battlesbudz.jarvis.v2.privilege.IPrivilegeHelper?) : OperatedInputInjector {
    override fun inject(displayId: Int, event: Bundle): Boolean = helper()?.injectInput(displayId, event) == true
}
