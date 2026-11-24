package com.battlesbudz.jarvis.v2.display

import android.content.Context
import android.view.KeyEvent
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.perception.OperatedObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.coroutineContext

fun interface SendApprovalGate {
    suspend fun awaitApproval(taskId: String, request: ActionRequest): ApprovalDecision
}

/** Owns the complete display, station and terminal-event lifetime for one operated task. */
class OperatedTaskRunner(
    private val context: Context,
    private val display: HelperOperatedDisplay,
    private val observations: OperatedObservationReader,
    private val liveNodes: (OperatedDisplayIdentity) -> List<LiveOperatedNode>,
    private val injector: OperatedInputInjector,
    private val launcher: OperatedAppLauncher,
    private val approvals: SendApprovalGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostics: (String) -> Unit = {},
    private val resolvePackage: (OperatedTaskRequest) -> String?,
    private val taskLocation: TaskLocationProvider = AccessibilityTaskLocationProvider(),
    private val screenSize: () -> Triple<Int, Int, Int>,
    private val textEntry: OperatedTextEntry = OperatedTextEntry(injector, AndroidKeyEventMapper),
    private val maxSteps: Int = 40,
    private val maxActiveMs: Long = 10 * 60_000L,
) : OperatedTaskControls.Runner {
    @Volatile private var activeTaskId: String? = null
    @Volatile private var paused = false
    @Volatile private var stopFact: String? = null
    private var steps = 0
    @Volatile private var activeStarted = 0L
    @Volatile private var pausedAt: Long? = null
    @Volatile private var pausedTotal = 0L
    private var frameFeed: OperatedFrameFeed? = null

    override fun pause(taskId: String) { if (activeTaskId == taskId) { pausedAt = clock(); paused = true } }
    override fun stop(taskId: String) { if (activeTaskId == taskId) stopFact = "Task stopped." }
    override fun handBack(taskId: String) { if (activeTaskId == taskId) stopFact = "Task handed back." }
    fun resume(taskId: String) { if (activeTaskId == taskId) {
        pausedAt?.let { pausedTotal += (clock() - it).coerceAtLeast(0) }; pausedAt = null; paused = false
    } }

    suspend fun awaitControl() {
        while (paused && stopFact == null) kotlinx.coroutines.delay(50)
        stopFact?.let { throw IllegalStateException(it) }
        check(clock() - activeStarted - pausedTotal <= maxActiveMs) { "The operated task reached its 10-active-minute limit." }
        coroutineContext.ensureActive()
    }

    suspend fun run(request: OperatedTaskRequest): OperatedTaskOutcome {
        val taskId = UUID.randomUUID().toString()
        activeTaskId = taskId
        steps = 0; activeStarted = clock(); pausedTotal = 0; pausedAt = null; stopFact = null
        var terminal: OperatedTaskOutcome? = null
        val feedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        OperatedTaskControls.runner = this
        try {
            OperatedTaskService.start(context, taskId, request.appName, "Send message")
            val outcome = execute(taskId, request, feedScope)
            terminal = outcome
            return outcome
        } catch (cancelled: CancellationException) {
            val outcome = OperatedTaskOutcome.Failed(stopFact ?: "Task stopped.")
            terminal = outcome
            throw cancelled
        } catch (failure: Exception) {
            val outcome = OperatedTaskOutcome.Failed(stopFact ?: failure.message ?: "Task stopped.")
            terminal = outcome
            return outcome
        } finally {
            frameFeed?.stop()
            frameFeed = null
            feedScope.cancel()
            runCatching { display.close() }
            activeTaskId = null
            if (OperatedTaskControls.runner === this) OperatedTaskControls.runner = null
            runCatching { OperatedTaskService.end(context, taskId) }
            diagnostics("TaskEnded task=$taskId outcome=${terminal?.javaClass?.simpleName ?: "Failed"} message=${terminal?.message ?: "Task stopped."}")
        }
    }

    private suspend fun execute(taskId: String, request: OperatedTaskRequest, feedScope: CoroutineScope): OperatedTaskOutcome {
        val recipient = request.recipient ?: return OperatedTaskOutcome.Failed("The request has no recipient.")
        val body = request.body ?: return OperatedTaskOutcome.Failed("The request has no message body.")
        checkpoint()
        val packageName = resolvePackage(request) ?: return OperatedTaskOutcome.Blocked("No Messages app package was found on this device.")
        when (taskLocation.location(packageName)) {
            TaskLocation.MAIN_DISPLAY -> return OperatedTaskOutcome.Blocked("${request.appName} is open on your main screen, so I left it alone.")
            TaskLocation.LIVE_VIRTUAL -> return OperatedTaskOutcome.Blocked("${request.appName} is already open on another virtual display, so I left it alone.")
            TaskLocation.DEAD_VIRTUAL, TaskLocation.NONE -> Unit
        }
        checkpoint()
        val (width, height, dpi) = screenSize()
        val identity = try { display.create(taskId, request.appName, width, height, dpi) }
        catch (failure: IllegalStateException) { return OperatedTaskOutcome.Failed(failure.message ?: "The app-control helper is unavailable. The task stopped.") }
        if (identity.displayId <= 0) return OperatedTaskOutcome.Failed("The helper returned an invalid operated display.")
        OperatedFrameFeed(display).also { frameFeed = it; it.start(feedScope) }
        checkpoint()
        if (!launcher.launch(packageName, identity)) return OperatedTaskOutcome.Failed("The $packageName app did not launch on the operated display.")
        var observation = observe(identity) ?: return OperatedTaskOutcome.Failed("No focused app window was observed on the operated display.")
        if (observation.foregroundPackage != packageName)
            return OperatedTaskOutcome.Failed("The operated display showed ${observation.foregroundPackage}, not $packageName.")

        var thread = false
        var searched = false
        for (attempt in 0 until 8) {
            observation = observe(identity) ?: return OperatedTaskOutcome.Failed("The operated app window could not be read.")
            val nodes = observation.nodes
            val title = nodes.firstOrNull { it.viewId.orEmpty().contains("title", true) }?.text
                ?: nodes.firstOrNull { it.className.contains("Toolbar", true) }?.text
            if (titleMatches(title, recipient)) { thread = true; break }
            val matchingRows = nodes.filter { "clickable" in it.stateFlags &&
                (it.text.equals(recipient, true) || it.text?.startsWith(recipient, true) == true) }
            val exactRow = matchingRows.firstOrNull { it.text.equals(recipient, true) }
            val row = exactRow ?: matchingRows.singleOrNull()
            if (row != null) {
                val receipt = tap(identity, observation, row)
                if (receipt.reason == "tapped") {
                    val opened = observe(identity)
                    val openedTitle = opened?.nodes?.firstOrNull { it.viewId.orEmpty().contains("title", true) }?.text
                        ?: opened?.nodes?.firstOrNull { it.className.contains("Toolbar", true) }?.text
                    if (titleMatches(openedTitle, recipient)) { thread = true; break }
                    continue
                }
                if (receipt.reason != "target_missing") return OperatedTaskOutcome.Failed("Recipient row tap failed: ${receipt.reason}.")
            } else if (title != null && !title.equals(request.appName, true)) {
                injectKey(identity, KeyEvent.KEYCODE_BACK)
            } else {
                val compose = nodes.firstOrNull { node ->
                    val value = node.text.orEmpty()
                    value.contains("Start chat", true) || value.equals("New message", true) || value.equals("Compose", true)
                }
                if (compose == null) continue
                val receipt = tap(identity, observation, compose)
                if (receipt.reason != "tapped") return OperatedTaskOutcome.Failed("Compose control tap failed: ${receipt.reason}.")
                val afterCompose = observe(identity) ?: return OperatedTaskOutcome.Failed("The contact search screen could not be read.")
                val search = afterCompose.nodes.firstOrNull { "editable" in it.stateFlags }
                    ?: return OperatedTaskOutcome.Failed("The opened compose screen had no editable search field.")
                val searchTap = tap(identity, afterCompose, search)
                if (searchTap.reason != "tapped") return OperatedTaskOutcome.Failed("Contact search field tap failed: ${searchTap.reason}.")
                if (!type(identity, recipient)) return OperatedTaskOutcome.Failed("The contact search text was not accepted by the operated display.")
                searched = true
                val resultsObs = observe(identity) ?: return OperatedTaskOutcome.Failed("Contact search results could not be read.")
                val results = resultsObs.nodes.filter { "editable" !in it.stateFlags && "clickable" in it.stateFlags }
                val exact = results.firstOrNull { it.text.equals(recipient, true) }
                val starts = results.filter { it.text?.startsWith(recipient, true) == true }
                val result = exact ?: starts.singleOrNull()
                    ?: return OperatedTaskOutcome.Blocked("I opened ${request.appName} and searched, but there is no contact or conversation named \"$recipient\".")
                val resultTap = tap(identity, resultsObs, result)
                if (resultTap.reason != "tapped") return OperatedTaskOutcome.Failed("Contact result tap failed: ${resultTap.reason}.")
                val opened = observe(identity)
                val openedTitle = opened?.nodes?.firstOrNull { it.viewId.orEmpty().contains("title", true) }?.text
                    ?: opened?.nodes?.firstOrNull { it.className.contains("Toolbar", true) }?.text
                if (!titleMatches(openedTitle, recipient))
                    return OperatedTaskOutcome.Failed("The opened conversation title did not match $recipient.")
                thread = true
                break
            }
        }
        if (!thread) return if (searched)
            OperatedTaskOutcome.Blocked("I opened ${request.appName} and searched, but there is no contact or conversation named \"$recipient\".")
        else OperatedTaskOutcome.Failed("The ${request.appName} list showed no conversation or compose control.")

        observation = observe(identity) ?: return OperatedTaskOutcome.Failed("The message thread could not be read.")
        val editor = chooseEditor(observation.nodes, true)
            ?: return OperatedTaskOutcome.Failed("No message compose field was observed in the thread.")
        if (tap(identity, observation, editor).reason != "tapped") return OperatedTaskOutcome.Failed("Message compose field tap failed.")
        if (!type(identity, body)) return OperatedTaskOutcome.Failed("The message text was not accepted by the operated display.")
        observation = observe(identity) ?: return OperatedTaskOutcome.Failed("The message compose field could not be verified.")
        val typed = chooseEditor(observation.nodes, true)?.text.orEmpty()
        if (typed.isBlank() || !typed.startsWith(body)) return OperatedTaskOutcome.Failed("The message compose field did not show the entered text.")

        val action = ActionRequest("send_message", mapOf("app" to request.appName, "destination" to recipient, "message" to body))
        when (approvals.awaitApproval(taskId, action)) {
            ApprovalDecision.DENIED -> return OperatedTaskOutcome.Declined("The message to $recipient wasn't sent — you declined the approval.")
            ApprovalDecision.APPROVED -> Unit
            else -> return OperatedTaskOutcome.Failed("The send approval did not complete.")
        }
        val beforeSend = observation
        val send = beforeSend.nodes.firstOrNull { it.text.equals("Send", true) || it.viewId.orEmpty().contains("send", true) }
            ?: return OperatedTaskOutcome.Failed("No send control was observed in the message thread.")
        if (tap(identity, beforeSend, send).reason != "tapped") return OperatedTaskOutcome.Failed("Send control tap failed.")
        val after = observe(identity) ?: return OperatedTaskOutcome.Failed("The message send result could not be observed.")
        val cleared = chooseEditor(after.nodes, true)?.text.isNullOrBlank()
        val bodyVisible = after.nodes.any { it.text?.contains(body) == true }
        if (cleared && (bodyVisible || after.screenFingerprint != beforeSend.screenFingerprint))
            return OperatedTaskOutcome.Completed("Message sent to $recipient: \"$body\"")
        return OperatedTaskOutcome.Failed("The message thread did not show a verified send result.")
    }

    private suspend fun checkpoint() {
        check(++steps <= maxSteps) { "The operated task reached its 40-step limit." }
        awaitControl()
    }
    private suspend fun observe(identity: OperatedDisplayIdentity): OperatedObservation? { checkpoint(); return observations.read(identity) }
    private suspend fun type(identity: OperatedDisplayIdentity, text: String): Boolean {
        checkpoint()
        return textEntry.type(identity.displayId, text).succeeded
    }
    private suspend fun injectKey(identity: OperatedDisplayIdentity, key: Int) {
        checkpoint(); check(identity.displayId > 0)
        if (!injector.inject(identity.displayId, android.os.Bundle().apply { putString("type", "key"); putInt("keyCode", key); putInt("metaState", 0) }))
            throw IllegalStateException("The Back key was not accepted by the operated display.")
    }
    private suspend fun tap(identity: OperatedDisplayIdentity, old: OperatedObservation, node: com.battlesbudz.jarvis.v2.perception.ObservedNode): OperatedTapReceipt {
        checkpoint()
        val key = node.viewId?.let(OperatedNodeKey::ViewId) ?: OperatedNodeKey.ClassAndText(node.className, node.text.orEmpty())
        val liveReader = OperatedLiveNodeReader { displayIdentity -> liveNodes(displayIdentity).firstOrNull { live ->
            if (node.viewId != null) live.viewId == node.viewId else live.className == node.className && live.text == node.text
        } }
        val fresh = observations.read(identity) ?: return OperatedTapReceipt(false, "target_missing")
        checkpoint()
        val receipt = OperatedTapExecutor(liveReader, injector, observations).execute(
            OperatedTapRequest(old, fresh, identity, key, paymentMarked = false, cancelled = false))
        checkpoint()
        return receipt
    }
    private fun chooseEditor(nodes: List<com.battlesbudz.jarvis.v2.perception.ObservedNode>, preferMessage: Boolean) =
        nodes.filter { "editable" in it.stateFlags }.sortedByDescending { node ->
            (if (node.viewId.orEmpty().contains("compose", true)) 4 else 0) +
                (if (preferMessage && node.text.orEmpty().contains("message", true)) 2 else 0)
        }.firstOrNull()

    private fun titleMatches(title: String?, recipient: String): Boolean {
        val value = title?.trim().orEmpty()
        return value.equals(recipient, ignoreCase = true) ||
            (value.startsWith(recipient, ignoreCase = true) && value.getOrNull(recipient.length)?.let { it.isWhitespace() || it in "·•-" } == true)
    }
}
