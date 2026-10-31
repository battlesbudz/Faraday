package com.battlesbudz.jarvis.v2.sandbox

import android.content.Context
import io.github.sylirre.terminal.term.SessionManager
import io.github.sylirre.terminal.term.TerminalSession
import io.github.sylirre.terminal.term.UserlandOptions
import io.github.sylirre.terminal.term.UserlandRootfs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exec-style sandbox runs (Phase 1 scope §6): one shell command per run,
 * no persistent shell, no model-facing surface (Phase 2 adds the tool).
 *
 * Each run spawns a fresh chroot-ng session for `/bin/sh -c <script>` with
 * the workspace bound at /workspace as its working directory, captures the
 * raw PTY stream (never the rendered screen — control sequences and
 * redraws would corrupt it), and enforces the decision-D4 limits:
 * 120 s default timeout under a 600 s ceiling, a 64 KiB capture ring
 * (newest bytes win), and at most 8,000 characters surfaced. A timeout
 * kills the whole session process group, not just the leader (review
 * M-5); the engine child's own RLIMIT_CPU is the backstop below that.
 *
 * Output is the PTY stream verbatim apart from CRLF→LF normalization (the
 * PTY line discipline rewrites \n to \r\n on the wire): no exit-code
 * marker lines, no prompt stripping.
 */
class SandboxRunner(
    private val context: Context,
    private val manager: SandboxManager
) {

    data class RunResult(
        /** Normalized captured output (tail; see [truncated]). */
        val output: String,
        /** Guest exit code (0–255), negative for a signal, null on timeout/start failure. */
        val exitCode: Int?,
        val timedOut: Boolean,
        /** True when output was cut, by the ring or by the surface cap. */
        val truncated: Boolean,
        val durationMs: Long,
        /** Typed start failure (not installed, spawn error); null when the run started. */
        val error: String? = null
    )

    /**
     * Runs [script] in the sandbox. Blocking for the run's duration — call
     * off the main thread. [timeoutMs] is clamped to the D4 ceiling.
     */
    fun runShellCommand(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RunResult {
        val started = System.nanoTime()
        fun elapsed(): Long = (System.nanoTime() - started) / 1_000_000

        val store = manager.store
        if (!store.isUsable()) {
            return RunResult(
                output = "", exitCode = null, timedOut = false, truncated = false,
                durationMs = elapsed(),
                error = "Sandbox is not installed. Install it from the sandbox settings first."
            )
        }
        val timeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        val ring = SandboxRingBuffer()
        val exited = CountDownLatch(1)
        // Written by the exit callback before it counts the latch down;
        // the latch gives the happens-before edge for the read below.
        var exitCode: Int? = null

        val session: TerminalSession
        try {
            val opts = UserlandOptions.defaults("/bin/sh")
                .withWorkDir("/workspace")
            // The settings-driven network gate (decision D3, review M-1)
            // is wired into these options in unit 6, together with the
            // engine-side --no-network flag it drives.
            val command = UserlandRootfs.commandExec(
                context, opts,
                listOf("/bin/sh", "-c", script),
                listOf("${store.workspaceDir.absolutePath}:/workspace"),
                "/workspace",
                "sandbox-run"
            )
            session = SessionManager.get().create(
                command, RUN_COLS, RUN_ROWS, 0, 0, RUN_SCROLLBACK,
                /* terminateProcessesOnExit = */ true,
                object : TerminalSession.Listener {
                    override fun onUpdate(session: TerminalSession) {}
                    override fun onTitleChanged(session: TerminalSession) {}
                    override fun onBell(session: TerminalSession) {}
                    override fun onExited(session: TerminalSession, code: Int) {
                        exitCode = code
                        exited.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            return RunResult(
                output = "", exitCode = null, timedOut = false, truncated = false,
                durationMs = elapsed(),
                error = "Sandbox run failed to start: ${e.message ?: "unknown error"}"
            )
        }

        manager.onSessionOpened(session)
        // The tap hands over a reused buffer; the ring copies synchronously.
        session.setRawOutputListener { buf, n -> ring.append(buf, 0, n) }
        val finished = try {
            exited.await(timeout, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val timedOut = !finished
        if (timedOut) {
            // Watchdog: close() group-kills the engine and every guest
            // thread in its process group (TerminalSession contract).
            SessionManager.get().close(session)
            // Give the exit callback a brief chance to land for bookkeeping.
            try {
                exited.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else {
            SessionManager.get().close(session)
        }
        manager.onSessionClosed(session)

        val text = ring.surfacedText()
        val truncated = ring.truncated || ring.text().length > text.length
        val result = RunResult(
            output = text,
            exitCode = if (timedOut) null else exitCode,
            timedOut = timedOut,
            truncated = truncated,
            durationMs = elapsed()
        )
        store.recordRun(result.exitCode, result.timedOut, result.durationMs)
        return result
    }

    companion object {
        /** Decision D4: default per-run timeout. */
        const val DEFAULT_TIMEOUT_MS: Long = 120_000

        /** Decision D4: hard ceiling any caller-provided timeout is clamped to. */
        const val MAX_TIMEOUT_MS: Long = 600_000

        /** Decision D4: raw capture ring size (see SandboxRingBuffer). */
        const val CAPTURE_RING_BYTES: Int = SandboxRingBuffer.CAPACITY_BYTES

        /** Decision D4: characters surfaced to the caller. */
        const val SURFACED_CHARS: Int = SandboxRingBuffer.SURFACED_CHARS

        // Headless grid: the PTY needs a size, but no view renders it; the
        // raw tap, not the emulator screen, is the capture of record.
        private const val RUN_COLS: Int = 80
        private const val RUN_ROWS: Int = 24
        private const val RUN_SCROLLBACK: Int = 200
    }
}
