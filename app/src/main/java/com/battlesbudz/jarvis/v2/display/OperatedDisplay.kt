package com.battlesbudz.jarvis.v2.display

import com.battlesbudz.jarvis.v2.privilege.IPrivilegeHelper
import com.battlesbudz.jarvis.v2.privilege.PrivilegeHandoffState
import java.util.concurrent.atomic.AtomicBoolean

/** Public-to-v2 display contract consumed by operated-task and watch surfaces. */
data class OperatedDisplayIdentity(val displayId: Int, val ownerTaskId: String, val appName: String) {
    init { require(displayId >= 0 && ownerTaskId.isNotBlank() && appName.isNotBlank()) }
}

data class OperatedDisplayEvent(val identity: OperatedDisplayIdentity, val kind: Kind, val atMs: Long) {
    enum class Kind { CREATED, FOCUS_GAINED, FOCUS_LOST, RELEASED, ORPHANED }
}

fun interface DisplayEventListener { fun onEvent(event: OperatedDisplayEvent) }

interface OperatedDisplayHelper {
    fun create(name: String, width: Int, height: Int, densityDpi: Int): Int
    fun release(displayId: Int)
    fun moveTask(taskId: Int, displayId: Int): Boolean
}

/** Owns only a helper-created display. It intentionally has no main-display fallback. */
class HelperOperatedDisplay(
    private val helperProvider: () -> OperatedDisplayHelper? = ::connectedHelper,
    private val listener: DisplayEventListener = DisplayEventListener {},
) : AutoCloseable {
    @Volatile private var identity: OperatedDisplayIdentity? = null
    private val closed = AtomicBoolean(false)

    @Synchronized fun create(taskId: String, appName: String, width: Int, height: Int, densityDpi: Int): OperatedDisplayIdentity {
        check(identity == null && !closed.get()) { "An operated display is already active or this owner is closed." }
        require(taskId.isNotBlank() && appName.isNotBlank())
        val helper = helperProvider() ?: throw IllegalStateException("The app-control helper is unavailable. The task stopped.")
        val id = helper.create("Faraday-$taskId", width, height, densityDpi)
        check(id >= 0) { "The helper could not create the operated display." }
        return OperatedDisplayIdentity(id, taskId, appName).also {
            identity = it
            listener.onEvent(OperatedDisplayEvent(it, OperatedDisplayEvent.Kind.CREATED, System.currentTimeMillis()))
        }
    }

    fun current(): OperatedDisplayIdentity? = identity

    /** Move is attempted only onto the owned display; failure stops rather than targeting display 0. */
    fun moveTask(taskId: Int): Boolean {
        val active = identity ?: return false
        return helperProvider()?.moveTask(taskId, active.displayId) == true
    }

    fun focusChanged(focused: Boolean) {
        val active = identity ?: return
        listener.onEvent(OperatedDisplayEvent(active,
            if (focused) OperatedDisplayEvent.Kind.FOCUS_GAINED else OperatedDisplayEvent.Kind.FOCUS_LOST,
            System.currentTimeMillis()))
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = identity ?: return
        identity = null
        val helper = helperProvider()
        if (helper == null) {
            listener.onEvent(OperatedDisplayEvent(active, OperatedDisplayEvent.Kind.ORPHANED, System.currentTimeMillis()))
            return
        }
        helper.release(active.displayId)
        listener.onEvent(OperatedDisplayEvent(active, OperatedDisplayEvent.Kind.RELEASED, System.currentTimeMillis()))
    }

    companion object {
        private fun connectedHelper(): OperatedDisplayHelper? {
            val binder = PrivilegeHandoffState.helper.get()?.takeIf { it.isBinderAlive } ?: return null
            val remote = IPrivilegeHelper.Stub.asInterface(binder)
            return object : OperatedDisplayHelper {
                override fun create(name: String, width: Int, height: Int, densityDpi: Int) = remote.createVirtualDisplay(name, width, height, densityDpi)
                override fun release(displayId: Int) = remote.releaseVirtualDisplay(displayId)
                override fun moveTask(taskId: Int, displayId: Int) = remote.moveTaskToDisplay(taskId, displayId)
            }
        }
    }
}

/** Single-slot frame mailbox: at most 10 fps, newest frame wins, no queued backlog. */
class DisplayFrameMailbox(private val clockMs: () -> Long = System::currentTimeMillis) {
    data class Frame(val displayId: Int, val width: Int, val height: Int, val rgba: ByteArray, val capturedAtMs: Long)
    private var lastDeliveredAt = Long.MIN_VALUE
    private var latest: Frame? = null

    @Synchronized fun offer(frame: Frame): Boolean {
        require(frame.displayId >= 0 && frame.width > 0 && frame.height > 0)
        if (frame.width.toLong() * frame.height * 4L != frame.rgba.size.toLong()) return false
        val now = clockMs()
        if (lastDeliveredAt != Long.MIN_VALUE && now - lastDeliveredAt < FRAME_INTERVAL_MS) return false
        lastDeliveredAt = now
        latest = frame.thumbnail(MAX_THUMBNAIL_EDGE)
        return true
    }

    @Synchronized fun takeLatest(): Frame? = latest.also { latest = null }

    private fun Frame.thumbnail(maxEdge: Int): Frame {
        val edge = maxOf(width, height)
        if (edge <= maxEdge) return copy(rgba = rgba.copyOf())
        val scale = maxEdge.toDouble() / edge
        val outW = maxOf(1, (width * scale).toInt())
        val outH = maxOf(1, (height * scale).toInt())
        val out = ByteArray(outW * outH * 4)
        for (y in 0 until outH) for (x in 0 until outW) {
            val sx = minOf(width - 1, (x / scale).toInt())
            val sy = minOf(height - 1, (y / scale).toInt())
            System.arraycopy(rgba, (sy * width + sx) * 4, out, (y * outW + x) * 4, 4)
        }
        return Frame(displayId, outW, outH, out, capturedAtMs)
    }

    companion object { const val MAX_FPS = 10; const val FRAME_INTERVAL_MS = 100L; const val MAX_THUMBNAIL_EDGE = 360 }
}

/** Explicit invariant recorded alongside every device proof. */
data class MainDisplayUntouchedAssertion(val operatedDisplayId: Int, val mainDisplayUsedAsWorkSurface: Boolean) {
    fun requireSatisfied() { check(!mainDisplayUsedAsWorkSurface) { "Operated task used the main display." } }
}
