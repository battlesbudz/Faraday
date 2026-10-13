package com.battlesbudz.jarvis.v2.voice

/** One scoped request for communication routing. The port releases only this app's requests. */
internal class DuplexRouteLease(private val port: Port, private val device: Int) : AutoCloseable {
    interface Port {
        var mode: Int
        val device: Int?
        fun selectDevice(id: Int): Boolean
        fun clearDevice()
        fun requestFocus(): Boolean
        fun abandonFocus()
    }
    private var modeRequested = false
    private var routeRequested = false
    private var focusRequested = false
    var closed = false
        private set
    val ownsCommunication: Boolean get() = !closed && modeRequested
    val valid: Boolean get() = ownsCommunication && port.mode == COMMUNICATION && port.device == device

    fun start() {
        check(!closed && !modeRequested)
        // Never take over an existing call, ringtone or another app's communication mode.
        check(port.mode == NORMAL) { "Audio is already owned by a call or another communication app." }
        try {
            modeRequested = true
            port.mode = COMMUNICATION
            // Android route/mode changes can complete asynchronously; the session awaits valid.
            routeRequested = true
            check(port.selectDevice(device)) { "Communication speaker selection was rejected." }
            focusRequested = true
            check(port.requestFocus()) { "Communication audio focus was not granted." }
        } catch (error: Throwable) {
            runCatching { close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Even if another call took priority, withdraw our requests so they cannot reappear later.
        // Clear our route instead of forcing a formerly observed device over Android's new choice.
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error } }
        if (routeRequested) release { port.clearDevice() }
        if (modeRequested) release { port.mode = NORMAL }
        if (focusRequested) release { port.abandonFocus() }
        failure?.let { throw it }
    }
    companion object { const val NORMAL = 0; const val COMMUNICATION = 3 }
}
