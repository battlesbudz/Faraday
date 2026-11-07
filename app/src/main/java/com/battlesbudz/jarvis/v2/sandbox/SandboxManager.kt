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
    private val sessionsLostFile: File = File(store.stateDir, "sessions-lost.txt")
    private val probeFile: File = File(store.stateDir, "probe.txt")

    init {
        store.ensureLayout()
        // The persisted total survives process death, so diagnostics can
        // report losses from before this process started too.
        sessionsLost.set(try {
            sessionsLostFile.readText().trim().toInt()
        } catch (e: Exception) {
            0
        })
        if (markerFile.exists()) {
            // The previous process died with sessions open; the vendored
            // contract says those sessions are gone, so say so and disarm.
            val total = sessionsLost.incrementAndGet()
            try {
                sessionsLostFile.writeText(total.toString())
            } catch (e: Exception) {
                // Bookkeeping only.
            }
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
        if (installState == InstallState.READY) refreshProbe()
    }

    /**
     * Runs the engine's capability probe once per install and caches the
     * output for diagnostics (review M-6: the probe is the ground truth
     * for what the mediation layer can do on this device). Best-effort —
     * a probe failure never fails an install. Blocking; called on the
     * install path, off the main thread.
     */
    fun refreshProbe() {
        if (probeFile.exists()) return
        val output = StringBuilder()
        val exited = java.util.concurrent.CountDownLatch(1)
        val session = try {
            SessionManager.get().create(
                io.github.sylirre.terminal.term.UserlandRootfs.probeCommand(context),
                80, 24, 0, 0, 200,
                /* terminateProcessesOnExit = */ true,
                object : TerminalSession.Listener {
                    override fun onUpdate(session: TerminalSession) {}
                    override fun onTitleChanged(session: TerminalSession) {}
                    override fun onBell(session: TerminalSession) {}
                    override fun onExited(session: TerminalSession, code: Int) {
                        exited.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            return
        }
        onSessionOpened(session)
        session.setRawOutputListener { buf, n ->
            synchronized(output) {
                output.append(String(buf, 0, n, Charsets.UTF_8))
            }
        }
        try {
            exited.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        SessionManager.get().close(session)
        onSessionClosed(session)
        try {
            store.ensureLayout()
            probeFile.writeText(output.toString().replace("\r\n", "\n").trim() + "\n")
        } catch (e: Exception) {
            // Diagnostics garnish only.
        }
    }

    /** The cached engine probe output, or null when never captured. */
    fun probeOutput(): String? = try {
        probeFile.readText().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
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
        // The probe described the old tree; the next install re-captures it.
        probeFile.delete()
        installState = if (store.isInstalled()) InstallState.FAILED else InstallState.NOT_INSTALLED
        lastInstallError = null
    }

    /** Clear workspace (decision D2): empties /workspace, keeps the rootfs. */
    fun clearWorkspace() {
        store.clearWorkspace()
    }
}
