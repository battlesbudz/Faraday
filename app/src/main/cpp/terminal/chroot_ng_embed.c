/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */
/*
 * Embedded entry for chroot-ng (native/chroot-ng), the native AArch64
 * userland engine. The standalone binary enters through its own _start
 * (src/rt/start.S), which hands cng_bootstrap the kernel-built initial stack
 * to carve argc/argv/envp/auxv out of. Linked into libterm.so there is no
 * such stack, so this shim reconstructs the same contract in the fork()ed
 * PTY child: argv comes from Java, envp is the process environ, and the
 * auxv is read back from /proc/self/auxv — the kernel's own record of it,
 * which the loader consults for AT_RANDOM and AT_UID..AT_EGID when
 * synthesizing the guest's auxv.
 *
 * Bionic is safe here: the shim runs before any guest code exists, on the
 * child's own TLS. Only past cng_main() does the no-libc rule apply.
 *
 * FARADAY ADDITIONS (security review of the mediation layer, 2026-10-30):
 * M-4 — the session child is forked from Faraday's app process, which holds
 * live MCP/download sockets and model files. cng_fds_sanitize deliberately
 * admits inherited file/socket descriptors, so a guest that guessed an
 * inherited connected socket's number would hold a network channel no
 * mediation sees. This shim therefore closes every inherited descriptor
 * except the PTY stdio set before cng_main runs.
 * M-5 — the engine sets no resource limits; the embed sets them here:
 * RLIMIT_NPROC (fork bombs), RLIMIT_FSIZE (single-file disk fill; the
 * storage accounting lives in SandboxStore), RLIMIT_CPU (backstop to the
 * run layer's wall-clock timeout), and a sane RLIMIT_NOFILE.
 */
#include <dirent.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <unistd.h>

/* chroot-ng's real main (native/chroot-ng/src/main.c). */
int cng_main(int argc, char **argv, char **envp, unsigned long *auxv);
/* Loader page granularity, normally set by cng_bootstrap from AT_PAGESZ
 * (native/chroot-ng/src/rt/rt.c). Defaults to 4096; 16 KB-page devices need
 * the real value or every mmap/mprotect rounding in the loader is wrong. */
extern unsigned long cng_page_size;

extern char **environ;

/* FARADAY M-4: close every inherited fd above stderr. stdio (0/1/2) is the
 * PTY slave by the time this runs; everything else the app process held —
 * sockets, model files, pipes — is not the guest's. Enumerating
 * /proc/self/fd keeps this exact (a blind close loop to RLIMIT_NOFILE
 * would be thousands of syscalls); the bounded blind loop is the fallback
 * if /proc is somehow unavailable. */
static void faraday_close_inherited_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (d != NULL) {
        int dir_fd = dirfd(d);
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (e->d_name[0] == '.') continue;
            long fd = strtol(e->d_name, NULL, 10);
            if (fd > STDERR_FILENO && fd != dir_fd) close((int)fd);
        }
        closedir(d);
        return;
    }
    long maxfd = sysconf(_SC_OPEN_MAX);
    if (maxfd < 0 || maxfd > 65536) maxfd = 65536;
    for (long fd = STDERR_FILENO + 1; fd < maxfd; fd++) close((int)fd);
}

/* FARADAY M-5: resource limits, set pre-entry because the engine sets
 * none. Values are embed-layer policy: generous enough that no legitimate
 * Phase 1 workload (package installs, pipelines) comes close, tight
 * enough that a runaway guest cannot take the app down with it. */
static void faraday_apply_rlimits(void) {
    struct rlimit rl;
    /* Fork bombs: the count is per real UID (the whole app), so the cap
     * sits far above the app's own thread/process baseline. */
    rl.rlim_cur = 1024;
    rl.rlim_max = 1024;
    setrlimit(RLIMIT_NPROC, &rl);
    /* Single-file size: a filled rootfs fills Faraday's own storage; the
     * aggregate is bounded by SandboxStore's accounting, this bounds any
     * one file. */
    rl.rlim_cur = 512UL * 1024 * 1024;
    rl.rlim_max = 512UL * 1024 * 1024;
    setrlimit(RLIMIT_FSIZE, &rl);
    /* CPU backstop: the run layer's wall-clock ceiling is 600 s; a guest
     * burning every core for that long stays under this, a pathological
     * spinner does not. SIGXCPU at the soft limit, SIGKILL at the hard. */
    rl.rlim_cur = 7140;
    rl.rlim_max = 7200;
    setrlimit(RLIMIT_CPU, &rl);
    /* Open files: only ever tightened, never raised. */
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur > 4096) {
        rl.rlim_cur = 4096;
        setrlimit(RLIMIT_NOFILE, &rl);
    }
}

int chroot_ng_main(int argc, char **argv) {
    faraday_close_inherited_fds();
    faraday_apply_rlimits();
    /* Static is fine: the fork()ed child is single-threaded and enters this
     * once. 64 pairs is far above any kernel's auxv (~20 pairs); a larger
     * one is silently truncated, which only costs optional entries. */
    static unsigned long auxv[130];
    ssize_t n = 0;
    int fd = open("/proc/self/auxv", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        n = read(fd, auxv, sizeof(auxv) - 2 * sizeof(auxv[0]));
        close(fd);
        if (n < 0) n = 0;
    }
    size_t words = ((size_t)n / sizeof(auxv[0])) & ~(size_t)1;
    auxv[words] = 0; /* AT_NULL terminator, also for a truncated read */
    auxv[words + 1] = 0;
    for (unsigned long *a = auxv; a[0] != 0; a += 2) {
        if (a[0] == 6 /* AT_PAGESZ */ && a[1]) cng_page_size = a[1];
    }
    return cng_main(argc, argv, environ, auxv);
}
