/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * PTY creation and process control for TerminalSession.
 *
 * Java owns the master fd (via ParcelFileDescriptor) for I/O; this file only
 * covers what Java can't: openpt/fork/exec, TIOCSWINSZ, waitpid, kill.
 *
 * Two spawn flavors share the PTY/fork setup: execve() for Android shells,
 * and an in-process userland engine for userland sessions — chroot_ng_main()
 * (arm64-v8a only), selected by argv[0]. The engine is linked into libterm.so
 * and entered directly in the fork()ed child: Android's W^X policy
 * (targetSdk >= 29) forbids execve() of anything under app data, and the
 * engine never execs a guest binary (guest execve is an in-process reload),
 * so there is no loader and nothing to exec (see native/chroot-ng).
 *
 * FARADAY ADAPTATION (see NOTICE.md): upstream also entered arm64chroot_main()
 * here and carried an arm64emu VM spawn path; Faraday vendors chroot-ng only,
 * so both are removed — an argv[0] other than "chroot-ng" exits 127.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

#ifdef HAVE_CHROOT_NG
/* chroot-ng's embedded entry (chroot_ng_embed.c → native/chroot-ng), the
 * native AArch64 engine. Returns the guest exit code. */
extern int chroot_ng_main(int argc, char **argv);
#endif

static int throw_errno(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof(msg), "%s: %s", what, strerror(errno));
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
    return -1;
}

/* Copies a Java String[] into a NULL-terminated char*[] for execve. */
static char **to_cstr_array(JNIEnv *env, jobjectArray arr) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc(n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

/* Frees an array built by to_cstr_array, strings included. */
static void free_cstr_array(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

/*
 * Opens a PTY and forks a child on it. If cmd is non-NULL the child
 * execve()s it; otherwise the child enters chroot_ng_main(argv) in-process.
 * Returns the master fd, or throws and returns -1.
 */
static jint spawn_on_pty(JNIEnv *env, jstring jcmd, jobjectArray jargs,
                         jobjectArray jenv, jstring jcwd, jint cols, jint rows,
                         jint cell_w, jint cell_h, jintArray jpid) {
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) return throw_errno(env, "open /dev/ptmx");

    char slave_path[64];
    if (grantpt(master) != 0 || unlockpt(master) != 0 ||
        ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        close(master);
        return throw_errno(env, "ptsname");
    }

    /* Pixel fields too: programs like Kitty's icat read them via TIOCGWINSZ
     * to size images, and otherwise give up reporting "screen sizes in
     * pixels". They must be in the initial winsize because the session spawns
     * at its final grid size and never resizes (see TerminalSession.resize). */
    struct winsize ws = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)(cols * cell_w),
                         .ws_ypixel = (unsigned short)(rows * cell_h)};
    ioctl(master, TIOCSWINSZ, &ws);

    const char *cmd = jcmd ? (*env)->GetStringUTFChars(env, jcmd, NULL) : NULL;
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char **argv = to_cstr_array(env, jargs);
    char **envp = to_cstr_array(env, jenv);

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno; /* the cleanup below may clobber it */
        close(master);
        free_cstr_array(argv);
        free_cstr_array(envp);
        if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        errno = err;
        return throw_errno(env, "fork");
    }

    if (pid == 0) {
        setsid();
        int slave = open(slave_path, O_RDWR); /* becomes controlling tty */
        if (slave < 0) _exit(127);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        /* The master belongs to the parent. O_CLOEXEC would drop it on the
         * execve path, but the in-process engines never exec: without this the
         * emulator carries a writable descriptor for its own tty for the whole
         * session, and the pair only half-closes when the parent hangs up. */
        close(master);
        if (cwd) chdir(cwd);
        /* fork() copies the calling (ART) thread's signal mask and signal
         * dispositions. execve() resets handled dispositions (though not
         * ignored ones), but the in-process engine keeps the inherited ART
         * handlers — fatal for chroot-ng, whose guests fault natively: an
         * inherited ART SIGSEGV/SIGQUIT handler would try to build a Java
         * crash report in a process that no longer runs ART. Clear both mask
         * and dispositions for every flavor (SIGKILL/SIGSTOP refusals are
         * expected and ignored). */
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        for (int s = 1; s < NSIG; s++) signal(s, SIG_DFL);
        if (cmd) {
            execve(cmd, argv, envp);
        } else {
            int argc = 0;
            while (argv[argc] != NULL) argc++;
            /* The engine gives the guest a clean environment, inheriting only
             * TERM/COLORTERM from this host environ; the rest of the guest env
             * is set explicitly via -E flags in the argv (envp is just
             * PATH=/system/bin plus TMPDIR). Java picks the engine by argv[0];
             * Faraday vendors chroot-ng only, so any other argv[0] exits 127
             * (the FARADAY ADAPTATION noted at the top of this file). */
            environ = envp;
#ifdef HAVE_CHROOT_NG
            if (argc > 0 && strcmp(argv[0], "chroot-ng") == 0)
                _exit(chroot_ng_main(argc, argv)); /* guest exit code */
#endif
            _exit(127);
        }
        _exit(127);
    }

    free_cstr_array(argv);
    free_cstr_array(envp);
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    jint pid_out = (jint)pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &pid_out);
    return master;
}

JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyCreate(
    JNIEnv *env, jclass clazz, jstring jcmd, jobjectArray jargs,
    jobjectArray jenv, jstring jcwd, jint cols, jint rows, jint cell_w,
    jint cell_h, jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, jcmd, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyCreateEmulator(
    JNIEnv *env, jclass clazz, jobjectArray jargs, jobjectArray jenv,
    jstring jcwd, jint cols, jint rows, jint cell_w, jint cell_h,
    jintArray jpid) {
    (void)clazz;
    return spawn_on_pty(env, NULL, jargs, jenv, jcwd, cols, rows, cell_w, cell_h,
                        jpid);
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptySetSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows, jint cell_w,
    jint cell_h) {
    (void)env; (void)clazz;
    struct winsize ws = {.ws_row = (unsigned short)rows,
                         .ws_col = (unsigned short)cols,
                         .ws_xpixel = (unsigned short)(cols * cell_w),
                         .ws_ypixel = (unsigned short)(rows * cell_h)};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_ptyHangupForeground(
    JNIEnv *env, jclass clazz, jint fd, jint fallback_pid) {
    (void)env; (void)clazz;

    pid_t pgrp = 0;
    if (ioctl(fd, TIOCGPGRP, &pgrp) == 0 && pgrp > 0 && pgrp != getpgrp()) {
        if (kill(-pgrp, SIGHUP) == 0) {
            kill(-pgrp, SIGCONT);
            return;
        }
    }

    if (fallback_pid > 0) {
        pgrp = getpgid((pid_t)fallback_pid);
        if (pgrp > 0 && pgrp != getpgrp() && kill(-pgrp, SIGHUP) == 0) {
            kill(-pgrp, SIGCONT);
        } else {
            kill((pid_t)fallback_pid, SIGHUP);
            kill((pid_t)fallback_pid, SIGCONT);
        }
    }
}

/* Blocks until the child exits. Returns exit code, or -signal if killed. */
JNIEXPORT jint JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_processWaitFor(
    JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_processKill(
    JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void)env; (void)clazz;
    kill((pid_t)pid, sig);
}

/* Whether this libterm.so carries the chroot-ng engine (arm64-v8a builds
 * only). Java gates the engine setting and the argv[0] choice on this, so a
 * persisted "chroot-ng" preference can never reach an x86_64 build's child. */
JNIEXPORT jboolean JNICALL
Java_io_github_sylirre_terminal_term_TerminalNative_hasChrootNg(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
#ifdef HAVE_CHROOT_NG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
