/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide session list backing the tab strip.
 *
 * A singleton (not Activity state) so shells survive rotation and Activity
 * recreation. Sessions die with the process; there is deliberately no
 * foreground service.
 *
 * FARADAY ADAPTATIONS (see cpp/terminal/NOTICE.md): the VM attach path
 * (attachVm/isVmTerminalOpen, VmMachine) is not vendored, and a
 * create(SessionCommand, ...) overload serves Faraday's exec-style runs.
 */
public final class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    public static SessionManager get() {
        return INSTANCE;
    }

    private final List<TerminalSession> sessions = new ArrayList<>();

    private SessionManager() {}

    /**
     * Spawns a shell at the given grid size. Callers should pass the real
     * view size: spawning at a wrong size triggers a SIGWINCH on first
     * layout, and mksh reacts by wiping its initial prompt.
     *
     * @param userland Login shell under chroot-ng (rootfs must be
     *               installed) instead of /system/bin/sh.
     * @param userlandOptions chroot-ng inputs (login shell, identity, home,
     *                   working directory, /proc isolation, storage binding);
     *                   used only when {@code userland} is true.
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener) throws IOException {
        SessionCommand command = userland
                ? UserlandRootfs.command(context, userlandOptions)
                : SessionCommand.androidShell(
                        context.getFilesDir().getAbsolutePath(),
                        TmpDir.ensure(context));
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, terminateProcessesOnExit,
                listener);
        synchronized (this) {
            sessions.add(s);
        }
        return s;
    }

    /**
     * FARADAY ADDITION: spawns a session from an already-built command.
     * Faraday's run layer constructs its own SessionCommand (a chroot-ng
     * exec-style invocation) instead of going through
     * {@link UserlandRootfs#command}; this is {@link #create} minus the
     * command construction.
     */
    public TerminalSession create(SessionCommand command, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines,
            boolean terminateProcessesOnExit, TerminalSession.Listener listener)
            throws IOException {
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, terminateProcessesOnExit,
                listener);
        synchronized (this) {
            sessions.add(s);
        }
        return s;
    }

    public List<TerminalSession> sessions() {
        synchronized (this) {
            return new ArrayList<>(sessions);
        }
    }

    public int indexOf(TerminalSession s) {
        synchronized (this) {
            return sessions.indexOf(s);
        }
    }

    public boolean close(TerminalSession s) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(s);
        }
        if (removed) s.close();
        return removed;
    }

    /**
     * Closes every session and empties the list. For callers that need the
     * tabs gone — for example, replacing the userland rootfs.
     */
    public void closeSessions() {
        List<TerminalSession> copy;
        synchronized (this) {
            copy = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (TerminalSession s : copy) {
            s.close();
        }
    }

    /**
     * Kills every shell and empties the list. Used by the "Exit" action in
     * the foreground-service notification, which can fire while no Activity
     * is alive — so it must leave no dead sessions behind for a later
     * relaunch to re-attach to.
     */
    public void closeAll() {
        closeSessions();
    }

    public boolean isEmpty() {
        synchronized (this) {
            return sessions.isEmpty();
        }
    }
}
