package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context
import io.github.sylirre.terminal.term.SessionManager
import io.github.sylirre.terminal.term.TerminalSession
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the sandbox lifecycle (Phase 1 scope §7): install state, the
 * session list, and the process-death contract.
 *
 * Install state machine: NOT_INSTALLED → INSTALLING → READY ⇄ BUSY, with
 * FAILED reachable from INSTALLING and from a rootfs that turns up
 * unusable. Sessions die with the process (the vendored session layer's
 * contract); the armed-session marker in the state dir lets the next
 * process start report that sessions were lost rather than pretending
 * continuity — [sessionsLost] counts those events.
 *
 * The keepalive foreground service runs exactly while at least one
 * session is open: started on the first, stopped on the last.
 *
 * Run execution (exec-style commands, caps, watchdog) lives in
 * [SandboxRunner]; this class owns the sessions both share.
 */
class SandboxManager(private val context: Context) {

    enum class InstallState { NOT_INSTALLED, INSTALLING, READY, BUSY, FAILED }

    val store: SandboxStore = SandboxStore(context)

    @Volatile
    var installState: InstallState = when {
        store.isUsable() -> InstallState.READY
        store.isInstalled() -> InstallState.FAILED
        else -> InstallState.NOT_INSTALLED
    }
        private set

    @Volatile
    var lastInstallError: String? = null
        private set

    /** Times a fresh process found the armed marker (sessions died with the old process). */
    val sessionsLost: AtomicInteger = AtomicInteger(0)

    private val sessions = mutableListOf<TerminalSession>()
    private val markerFile: File = File(store.stateDir, "session-armed")

    init {
        store.ensureLayout()
        if (markerFile.exists()) {
            // The previous process died with sessions open; the vendored
            // contract says those sessions are gone, so say so and disarm.
            sessionsLost.incrementAndGet()
            markerFile.delete()
        }
    }

    /**
     * Installs the bundled rootfs if needed (space pre-check inside the
     * store), moving the state machine through INSTALLING to READY or
     * FAILED. Idempotent when already installed. Blocking — call off the
     * main thread.
     */
    fun ensureInstalled(onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }) {
        if (installState == InstallState.READY || installState == InstallState.BUSY) return
        installState = InstallState.INSTALLING
        val result = store.installIfNeeded(
            networkingEnabled = SandboxSettings.networkEnabled(context),
            onProgress = onProgress
        )
        when (result) {
            is SandboxStore.InstallResult.Installed,
            SandboxStore.InstallResult.AlreadyInstalled -> {
                installState = if (store.isUsable()) InstallState.READY else InstallState.FAILED
                if (installState == InstallState.FAILED) {
                    lastInstallError = "The sandbox rootfs is present but has no usable shell."
                }
            }
            is SandboxStore.InstallResult.Failed -> {
                installState = InstallState.FAILED
                lastInstallError = result.message
            }
        }
    }

    /** Registers an open session: arms the marker and the keepalive on the first. */
    fun onSessionOpened(session: TerminalSession) {
        val first: Boolean
        synchronized(sessions) {
            first = sessions.isEmpty()
            sessions.add(session)
            if (installState == InstallState.READY) installState = InstallState.BUSY
        }
        if (first) {
            store.ensureLayout()
            markerFile.writeText("armed\n")
            SandboxKeepaliveService.start(context)
        }
    }

    /** Unregisters a closed session: disarms and stops the keepalive on the last. */
    fun onSessionClosed(session: TerminalSession) {
        val last: Boolean
        synchronized(sessions) {
            sessions.remove(session)
            last = sessions.isEmpty()
            if (last && installState == InstallState.BUSY) installState = InstallState.READY
        }
        if (last) {
            markerFile.delete()
            SandboxKeepaliveService.stop(context)
        }
    }

    fun openSessionCount(): Int = synchronized(sessions) { sessions.size }

    /** Closes every sandbox session (Reset and shutdown paths). */
    fun closeAllSessions() {
        val copy = synchronized(sessions) { sessions.toList() }
        for (session in copy) {
            SessionManager.get().close(session)
        }
    }

    /**
     * Reset (decision D2): closes all sessions, then removes the rootfs
     * only — the workspace survives. The state machine returns to
     * NOT_INSTALLED; the next run installs fresh.
     */
    fun reset() {
        closeAllSessions()
        store.resetRootfs()
        installState = if (store.isInstalled()) InstallState.FAILED else InstallState.NOT_INSTALLED
        lastInstallError = null
    }

    /** Clear workspace (decision D2): empties /workspace, keeps the rootfs. */
    fun clearWorkspace() {
        store.clearWorkspace()
    }
}
