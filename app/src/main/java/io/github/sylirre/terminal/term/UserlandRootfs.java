/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Linux distribution userland: rootfs install state, the tar.xz
 * installer, and the chroot-ng command line for {@link TerminalSession}.
 *
 * FARADAY ADAPTATIONS (see cpp/terminal/NOTICE.md for the full list): the
 * rootfs asset is committed to the APK (not a build-time extra); the install
 * location is filesDir/sandbox/rootfs; the engine is chroot-ng only (native
 * AArch64 execution — Faraday ships arm64-v8a only); the backup/restore
 * surface (RootfsBackup, replaceFromTar, the strip heuristic) is not
 * vendored; guest DNS defaults are written only when networking is enabled;
 * and an exec-style {@link #commandExec} overload serves Faraday's run layer.
 *
 * The tar reader is deliberately minimal: the rootfs tarballs contain only
 * regular files, directories, symlinks and (potentially) hard links; device
 * nodes etc. are skipped because the engine passes a /dev whitelist through
 * to the host anyway.
 */
public final class UserlandRootfs {

    /** Cumulative uncompressed bytes; called from the install thread. */
    public interface ProgressListener {
        void onProgress(long bytesExtracted);
    }

    /**
     * First-install progress. {@code compressedRead}/{@code compressedTotal}
     * track the xz asset itself, so they yield a true completion fraction
     * (unlike the uncompressed byte count, whose total is unknown until the
     * end); {@code compressedTotal} is 0 when the asset size could not be
     * determined. Called from the install thread.
     */
    public interface InstallListener {
        void onProgress(long bytesExtracted, long compressedRead, long compressedTotal);
    }

    private UserlandRootfs() {}

    /**
     * FARADAY: the bundled rootfs identity, stamped into the installed tree
     * at install time (see {@link #installedVersion}). There are no rootfs
     * updates in Faraday Phase 1: a new rootfs ships only with a new app
     * build, and the stamp exists so diagnostics can name what is installed.
     */
    public static final String BUNDLED_VERSION = "alpine-3.24.3";

    /** Name of the version-stamp file at the rootfs root. */
    private static final String VERSION_FILE = "rootfs.version";

    /**
     * Rootfs root, bound as guest "/" — filesDir/sandbox/rootfs. Its
     * existence is the install marker: {@link #install} builds the tree in
     * {@link #stagingDir} and only renames it into place once complete, so
     * this directory appears atomically and never represents a half-written
     * rootfs.
     */
    public static File dir(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "sandbox"), "rootfs");
    }

    /** Where {@link #install} extracts before the atomic rename onto {@link #dir}. */
    private static File stagingDir(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "sandbox"), "rootfs.tmp");
    }

    /**
     * The version stamp written by {@link #install} (the bundled rootfs
     * identity plus the source asset name), or null when no stamp exists —
     * e.g. an install from before stamping, or a tree placed by hand.
     */
    public static String installedVersion(Context ctx) {
        File stamp = new File(dir(ctx), VERSION_FILE);
        try (InputStream in = new FileInputStream(stamp)) {
            // The stamp is a few dozen bytes; read it without newer stream
            // helpers so the vendored code keeps upstream's API floor.
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    /** The rootfs directory is present, i.e. an install completed here. */
    public static boolean isInstalled(Context ctx) {
        return dir(ctx).isDirectory();
    }

    /**
     * The rootfs is present and actually runnable: the directory exists
     * <em>and</em> the bash binary the login command execs is still there.
     *
     * The directory alone is not enough — it lives under filesDir, which a
     * session's own {@code rm -rf} (or another app) can gut from under us,
     * leaving the directory but no shell. Spawning the emulator against a
     * gutted rootfs only dies instantly, so callers gate the userland session
     * type on this instead of {@link #isInstalled}.
     */
    public static boolean isUsable(Context ctx) {
        return isInstalled(ctx) && hasShell(dir(ctx));
    }

    /** True when a login shell ({@code /bin/bash} or {@code /bin/sh}) resolves inside root. */
    private static boolean hasShell(File root) {
        // Resolved with the guest-rooted walker (not File.exists()) so both
        // Debian's relative usrmerge links (bin -> usr/bin) and Alpine's
        // absolute busybox links (/bin/sh -> /bin/busybox) count. Either bash
        // or sh is enough — command() derives/falls back to whichever exists.
        return resolveInGuest(root, "/bin/bash", 0) != null
                || resolveInGuest(root, "/usr/bin/bash", 0) != null
                || resolveInGuest(root, "/bin/sh", 0) != null
                || resolveInGuest(root, "/usr/bin/sh", 0) != null;
    }

    /**
     * Resolves a guest-absolute path against the rootfs the way the emulator
     * will: symlink targets are interpreted <em>inside the guest</em>, so an
     * absolute target (Alpine's {@code /bin/sh -> /bin/busybox}) is re-rooted
     * at the rootfs instead of the host {@code /}, where a plain
     * {@code File.exists()} would chase and miss it. Relative targets and
     * {@code ..} are normalized textually; a path that escapes the root or
     * chains too deep (loop guard) resolves to null. Returns the host file of
     * the final, non-symlink target when it exists, else null.
     */
    private static File resolveInGuest(File root, String path, int depth) {
        if (path == null || depth > 40) return null;
        String p = path.trim();
        if (p.isEmpty() || !p.startsWith("/")) return null;
        List<String> walked = new ArrayList<>();
        String[] parts = p.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (walked.isEmpty()) return null; // escapes the guest root
                walked.remove(walked.size() - 1);
                continue;
            }
            walked.add(part);
            String link = readlinkOrNull(new File(root, String.join("/", walked)));
            if (link != null) {
                StringBuilder next = new StringBuilder();
                if (link.startsWith("/")) {
                    next.append(link);
                } else {
                    walked.remove(walked.size() - 1); // relative to the link's dir
                    next.append('/').append(String.join("/", walked));
                    if (next.charAt(next.length() - 1) != '/') next.append('/');
                    next.append(link);
                }
                for (int j = i + 1; j < parts.length; j++) {
                    if (!parts[j].isEmpty()) next.append('/').append(parts[j]);
                }
                return resolveInGuest(root, next.toString(), depth + 1);
            }
        }
        File host = walked.isEmpty() ? root : new File(root, String.join("/", walked));
        try {
            Os.lstat(host.getAbsolutePath());
            return host;
        } catch (ErrnoException e) {
            return null;
        }
    }

    /**
     * True when the guest-absolute {@code path} names something that exists
     * inside the rootfs, with symlinks resolved <em>the way the emulator will
     * resolve them</em> (see {@link #resolveInGuest}). Callers that validate a
     * user-entered guest path must use this rather than
     * {@code new File(root, path).exists()}: an absolute link target — Alpine's
     * {@code /bin/sh -> /bin/busybox} — is re-rooted at the rootfs here, while
     * {@code File} chases it against the host {@code /} and misses.
     */
    public static boolean guestPathExists(File root, String path) {
        return resolveInGuest(root, path, 0) != null;
    }

    /** As {@link #guestPathExists}, but the target must also be a directory. */
    public static boolean guestDirExists(File root, String path) {
        File target = resolveInGuest(root, path, 0);
        return target != null && target.isDirectory();
    }

    /** The symlink target of {@code file}, or null when it is not a symlink. */
    private static String readlinkOrNull(File file) {
        try {
            if (!OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode)) {
                return null;
            }
            return Os.readlink(file.getAbsolutePath());
        } catch (ErrnoException e) {
            return null; // doesn't exist / can't stat: not a link we can follow
        }
    }

    /** True when this build bundles at least one userland rootfs asset. */
    public static boolean assetAvailable(Context ctx) {
        return !UserlandDistro.bundled(ctx).isEmpty();
    }

    /**
     * Extracts the bundled rootfs asset {@code assetName} (one of
     * {@link UserlandDistro#bundled}) into {@link #dir}. Idempotent: returns
     * immediately when a rootfs is already installed — whichever distribution
     * it is; there is deliberately no "switch distro" path, because the
     * installed tree holds the user's data. Extraction happens in a staging
     * directory that is renamed onto {@link #dir} only once complete, so the
     * rootfs directory — which is the install marker — appears atomically; a
     * crash mid-extract leaves only the staging dir, which the next attempt
     * discards. Deliberately does <em>not</em> rebuild an installed rootfs that
     * has become unusable (e.g. its {@code /bin/bash} was deleted) — that
     * rootfs may hold the user's data, so it is never wiped behind their back;
     * callers fall back to the Android shell instead. Blocking — call from a
     * background thread.
     */
    public static synchronized void install(Context ctx, String assetName,
            InstallListener progress) throws IOException {
        install(ctx, assetName, progress, false);
    }

    /**
     * FARADAY: as {@link #install(Context, String, InstallListener)}, with the
     * sandbox networking setting applied to the guest defaults: the default
     * {@code /etc/resolv.conf} is written only when {@code networkingEnabled}
     * (Faraday's sandbox networking defaults off — see
     * {@link #writeGuestDefaults}). The three-arg overload keeps upstream's
     * signature and installs with networking off.
     */
    public static synchronized void install(Context ctx, String assetName,
            InstallListener progress, boolean networkingEnabled) throws IOException {
        if (isInstalled(ctx)) return;

        File staging = stagingDir(ctx);
        deleteRecursively(staging); // drop any aborted previous attempt
        if (!staging.mkdirs()) throw new IOException("cannot create " + staging);

        // Total compressed size for a determinate fraction; the tarballs are
        // stored uncompressed in the APK (noCompress 'xz'), so openFd works.
        long compressedTotal;
        try (android.content.res.AssetFileDescriptor fd =
                ctx.getAssets().openFd(assetName)) {
            compressedTotal = Math.max(0, fd.getLength());
        } catch (IOException e) {
            compressedTotal = 0;
        }
        final long total = compressedTotal;

        try (InputStream raw = ctx.getAssets().open(assetName)) {
            CountingInputStream counted = new CountingInputStream(raw);
            try (XZInputStream xz = new XZInputStream(
                    new BufferedInputStream(counted, 1 << 16))) {
                // The bundled asset is trusted (we built it) and unwrapped, so
                // skip both the per-entry path-escape guard and the strip
                // heuristic that restore applies to arbitrary picked files.
                ProgressListener adapter = progress == null ? null
                        : extracted -> progress.onProgress(extracted, counted.count, total);
                extractTar(xz, staging, adapter, null, false, 0);
            }
        }
        writeGuestDefaults(staging, networkingEnabled);
        // FARADAY: version stamp (see BUNDLED_VERSION / installedVersion).
        try (OutputStream out = new FileOutputStream(new File(staging, VERSION_FILE))) {
            out.write((BUNDLED_VERSION + "\nasset=" + assetName + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        publish(ctx, staging);
    }

    /** Counts bytes consumed from the underlying stream (the xz asset). */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }

    /**
     * Atomically swaps {@code staging} onto {@link #dir} with a rename(2) within
     * filesDir. The destination is removed first because renameTo won't replace
     * a non-empty directory; for a fresh {@link #install} that delete is a
     * defensive no-op. Written for {@link #install} so
     * {@link #dir} (the install marker) only ever names a finished rootfs.
     */
    private static void publish(Context ctx, File staging) throws IOException {
        File root = dir(ctx);
        deleteRecursively(root);
        if (!staging.renameTo(root)) {
            deleteRecursively(staging);
            throw new IOException("cannot publish rootfs to " + root);
        }
    }

    /**
     * chroot-ng command for a login shell with the historical defaults
     * ({@link UserlandOptions#defaults}). Requires an installed rootfs; the
     * engine is linked into libterm.so, so there is no loader or external
     * binary to locate.
     *
     * @param shell guest-absolute path to the login shell (e.g. {@code /bin/bash})
     */
    public static SessionCommand command(Context ctx, String shell) throws IOException {
        return command(ctx, UserlandOptions.defaults(shell));
    }

    /**
     * chroot-ng command for a userland session. The raw settings in
     * {@code opts} are resolved and validated against the installed rootfs here:
     * the identity to a numeric {@code --fake-id} (see {@link UserlandIdentity}),
     * and the home / working directory to existing guest-absolute paths.
     *
     * FARADAY: chroot-ng is the only vendored engine (upstream's default,
     * arm64chroot, is not vendored), so argv[0] is always "chroot-ng" and the
     * arm64chroot-only options ({@code opts.jit}, {@code opts.jitBufferMb})
     * are ignored. Upstream's external-storage binding
     * ({@code opts.bindExternalStorage}) is likewise not vendored — Faraday
     * binds only its own workspace directory, via {@link #commandExec}.
     */
    public static SessionCommand command(Context ctx, UserlandOptions opts)
            throws IOException {
        if (!isInstalled(ctx)) throw new IOException("Userland rootfs not installed");
        File root = dir(ctx);
        // Login shell: the configured command (path + optional args) when its
        // executable exists in the rootfs, else derived from the identity's
        // passwd shell, else /bin/bash or /bin/sh. Null only when the rootfs has
        // no usable shell at all — a session there would die instantly.
        String shellCmd = resolveLoginShell(root, opts.loginShell, opts.identity);
        if (shellCmd == null) {
            throw new IOException("Userland rootfs is incomplete: no login shell "
                    + "(/bin/bash or /bin/sh) found (deleted outside the app?)");
        }
        String[] shellTokens = shellCmd.trim().split("\\s+");
        // Tab label from the login shell's basename (e.g. /bin/bash -> "bash").
        return buildCommand(ctx, root, opts, Arrays.asList(shellTokens),
                SessionCommand.labelForShell(shellTokens[0]), null);
    }

    /**
     * FARADAY ADDITION: chroot-ng command for an exec-style run — the guest
     * program tokens (e.g. {@code /bin/sh}, {@code -c}, {@code <script>}) run
     * directly instead of a login shell. Everything else (identity, env,
     * /sys bind, TMPDIR) matches {@link #command(Context, UserlandOptions)}.
     *
     * @param extraBinds additional {@code --bind} specs in the engine's
     *        {@code host:guest} form (Faraday binds its workspace directory
     *        this way), or null
     * @param workDirOverride guest-absolute working directory; validated like
     *        the options' working directory, falling back to the identity
     *        home and then "/"
     * @param label tab/run label for the session
     */
    public static SessionCommand commandExec(Context ctx, UserlandOptions opts,
            List<String> guestProgram, List<String> extraBinds,
            String workDirOverride, String label) throws IOException {
        if (!isInstalled(ctx)) throw new IOException("Userland rootfs not installed");
        if (guestProgram == null || guestProgram.isEmpty()) {
            throw new IOException("No guest program to run");
        }
        File root = dir(ctx);
        UserlandOptions effective = workDirOverride == null ? opts
                : opts.withWorkDir(workDirOverride);
        return buildCommand(ctx, root, effective, guestProgram, label,
                extraBinds);
    }

    /**
     * Shared argv/env construction for login and exec-style commands: the
     * engine flags, the guest environment via -E, the positional rootfs and
     * the guest program tokens, and the host environment for the fork()ed
     * child.
     */
    private static SessionCommand buildCommand(Context ctx, File root,
            UserlandOptions opts, List<String> guestProgram, String label,
            List<String> extraBinds) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add("chroot-ng");
        argv.add("--link2symlink");  // apps can't hard-link; dpkg needs ln to work
        // Guest identity: --fake-id takes numeric uid:gid only, so the "User
        // identity" setting is resolved against the rootfs passwd/group here.
        // An empty/invalid/unresolvable value yields 0:0 (root), matching the
        // historical default that apt/dpkg insist on.
        argv.add("--fake-id");
        argv.add(UserlandIdentity.resolveFakeId(root, opts.identity));
        // /proc is always shared across sessions of this rootfs (registry keyed
        // by rootfs), so ps/top see processes from every session.
        argv.add("--shared-proc");
        // Working directory: the configured path, else the configured user's
        // home from /etc/passwd, else "/". guestDir validates existence, so
        // --work-dir (fatal on a missing dir) is always safe to pass.
        String workDir = resolveGuestDir(root, opts.workDir, opts.identity);
        argv.add("--work-dir");
        argv.add(workDir);
        // The engine synthesizes /proc and whitelists /dev itself; /sys is
        // the one host tree it does not special-case, so bind it in like
        // PRoot did.
        if (new File("/sys").isDirectory()) {
            argv.add("--bind");
            argv.add("/sys:/sys");
        }
        if (extraBinds != null) {
            for (String bind : extraBinds) {
                argv.add("--bind");
                argv.add(bind);
            }
        }
        // Guest environment via native -E: chroot-ng starts the guest with a
        // clean env (only TERM/COLORTERM are inherited from the host, and we
        // override TERM here), so set the full env explicitly. HOME comes
        // from the "Home directory" setting; USER tracks the identity — the
        // passwd name for the resolved uid, or the uid number when unlisted.
        String home = resolveGuestDir(root, opts.home, opts.identity);
        argv.add("-E");
        argv.add("HOME=" + home);
        argv.add("-E");
        argv.add("USER=" + UserlandIdentity.userNameForIdentity(root, opts.identity));
        argv.add("-E");
        argv.add("TERM=xterm-256color");
        // Guest locale from the "Locale" setting; a blank value falls back to
        // C.UTF-8, which the minimal rootfs always provides.
        String locale = opts.locale == null ? "" : opts.locale.trim();
        argv.add("-E");
        argv.add("LANG=" + (locale.isEmpty() ? "C.UTF-8" : locale));
        // Guest PATH from the "Executable path" setting; a blank value falls
        // back to the standard search path.
        String path = opts.path == null ? "" : opts.path.trim();
        argv.add("-E");
        argv.add("PATH=" + (path.isEmpty() ? UserlandOptions.DEFAULT_PATH : path));
        // Positional rootfs, then the guest program tokens, run directly.
        argv.add(root.getAbsolutePath());
        argv.addAll(guestProgram);
        // Host env for the fork()ed child — this becomes chroot-ng's process
        // environ (what getenv sees), NOT the guest env, which rides in the -E
        // flags above. TMPDIR is where the engine keeps host-side state: the
        // --shared-proc registry, emulated tmpfs mounts and the guest's
        // /dev/shm stand-in (which needs it mode 0700 — see TmpDir).
        List<String> env = new ArrayList<>();
        env.add("PATH=/system/bin");
        env.add("TMPDIR=" + TmpDir.ensure(ctx));
        // Host cwd for the fork()ed child mirrors --work-dir inside the rootfs.
        String workRel = workDir.equals("/") ? "" : workDir.substring(1);
        String cwd = (workRel.isEmpty() ? root : new File(root, workRel))
                .getAbsolutePath();
        return new SessionCommand(null, argv.toArray(new String[0]),
                env.toArray(new String[0]), cwd, label, true);
    }

    /**
     * Resolves a Home / Working-directory setting to an existing guest-absolute
     * directory: the setting itself when valid, else the configured user's
     * {@code /etc/passwd} home, else {@code "/"} (the rootfs root, always
     * present). Guarantees a path that {@code --work-dir} and {@code HOME} can
     * safely take.
     */
    private static String resolveGuestDir(File root, String setting, String identity) {
        String dir = guestDir(root, setting);
        if (dir == null) {
            dir = guestDir(root, UserlandIdentity.homeForIdentity(root, identity));
        }
        return dir != null ? dir : "/";
    }

    /**
     * Validates a guest-absolute directory path against the installed rootfs:
     * returns it (trimmed) when it is absolute, free of {@code ".."} escapes,
     * and names an existing directory inside the rootfs; otherwise {@code null}
     * so the caller falls back to a derived default. A null/empty or relative
     * path is rejected — only absolute paths within the rootfs are allowed.
     */
    private static String guestDir(File root, String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty() || !p.startsWith("/")) return null;
        return guestDirExists(root, p) ? p : null; // null on escape or absence
    }

    /**
     * The login-shell command line to store when the identity is configured, and
     * the spawn-time fallback when the setting is empty or invalid: the
     * configured user's {@code /etc/passwd} shell with {@code -l} when it exists,
     * else {@code "/bin/bash -l"}, else {@code "/bin/sh -l"}, else {@code null}
     * when the rootfs has no usable shell.
     */
    public static String deriveLoginShell(File root, String identity) {
        String passwdShell = UserlandIdentity.shellForIdentity(root, identity);
        if (shellExists(root, passwdShell)) return passwdShell + " -l";
        if (shellExists(root, "/bin/bash")) return "/bin/bash -l";
        if (shellExists(root, "/bin/sh")) return "/bin/sh -l";
        return null;
    }

    /**
     * The login command for a session: the configured {@code setting} (path plus
     * optional whitespace-separated args) when its executable exists in the
     * rootfs, otherwise the value derived from the identity
     * ({@link #deriveLoginShell}). Null only when nothing usable is present.
     */
    private static String resolveLoginShell(File root, String setting, String identity) {
        if (setting != null) {
            String s = setting.trim();
            if (!s.isEmpty() && shellExists(root, s.split("\\s+")[0])) return s;
        }
        return deriveLoginShell(root, identity);
    }

    /** True when {@code path} is an absolute path that exists inside the rootfs. */
    private static boolean shellExists(File root, String path) {
        return guestPathExists(root, path);
    }

    // --- tar extraction ---

    private static final int BLOCK = 512;

    /**
     * Core tar reader. When {@code guard} is set, every entry is confined to
     * {@code root}: an entry whose parent (or, for a directory, itself) resolves
     * through a symlink to outside {@code root} is skipped, and any symlink
     * already present at a regular-file/hard-link target is removed before the
     * write so a planted {@code evil -> /} (followed by {@code evil/x}) can't
     * redirect onto the host. Legitimate usrmerge symlinks resolve <em>within</em>
     * {@code root} and are unaffected. {@code guard} is off only for the trusted
     * bundled-asset install, where the per-entry canonicalization is needless
     * cost.
     *
     * <p>{@code strip} drops that many leading path components from each member
     * (and from a hard link's source), so a custom archive nesting the rootfs
     * under a wrapper directory extracts at the root; a member at or above the
     * strip depth is dropped. Package-private so a test can drive the strip
     * directly.
     */
    static void extractTar(InputStream in, File root, ProgressListener progress,
            AtomicBoolean cancelled, boolean guard, int strip) throws IOException {
        final String rootCanon;
        if (guard) {
            String p;
            try {
                p = root.getCanonicalPath();
            } catch (IOException e) {
                p = root.getAbsolutePath();
            }
            rootCanon = p;
        } else {
            rootCanon = null;
        }
        byte[] header = new byte[BLOCK];
        byte[] buf = new byte[1 << 16];
        // Directory modes are applied after extraction: a read-only dir
        // applied up front would block creating its children (umask makes
        // mkdir yield 0700 during the loop, so writes always succeed).
        List<File> dirFiles = new ArrayList<>();
        List<Integer> dirModes = new ArrayList<>();
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        long extracted = 0;
        long lastReport = 0;

        while (readBlock(in, header)) {
            if (cancelled != null && cancelled.get()) {
                throw new InterruptedIOException("restore cancelled");
            }
            if (isZeroBlock(header)) break; // end-of-archive marker

            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            int mode = (int) octal(header, 100, 8);
            long size = octal(header, 124, 12);
            byte type = header[156];
            String linkName = field(header, 157, 100);

            switch (type) {
                case 'L': // GNU long name: data holds the next entry's name
                    longName = readString(in, size);
                    continue;
                case 'K': // GNU long link target
                    longLink = readString(in, size);
                    continue;
                case 'x': { // PAX per-file records
                    String[] overrides = parsePax(readEntry(in, size));
                    paxPath = overrides[0];
                    paxLink = overrides[1];
                    continue;
                }
                case 'g': // PAX global records — nothing we honor
                    skip(in, padded(size));
                    continue;
                default:
                    break;
            }

            if (longName != null) name = longName;
            if (paxPath != null) name = paxPath;
            if (longLink != null) linkName = longLink;
            if (paxLink != null) linkName = paxLink;
            longName = longLink = paxPath = paxLink = null;

            if (strip > 0) {
                name = stripComponents(name, strip);
                if (name == null) { // the wrapper dir itself or shallower
                    skip(in, padded(size));
                    continue;
                }
                if (type == '1') { // a hard link's source is a member path too
                    linkName = stripComponents(linkName, strip);
                    if (linkName == null) {
                        skip(in, padded(size));
                        continue;
                    }
                }
            }

            File target = resolve(root, name);
            if (target == null) { // unsafe path — skip entry and its data
                skip(in, padded(size));
                continue;
            }

            switch (type) {
                case '0':
                case 0:
                case '7': { // regular file
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    if (guard) deleteIfSymlink(target); // don't write through a planted link
                    try (OutputStream out = new FileOutputStream(target)) {
                        long left = size;
                        while (left > 0) {
                            if (cancelled != null && cancelled.get()) {
                                throw new InterruptedIOException("restore cancelled");
                            }
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("truncated tar entry " + name);
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, padded(size) - size);
                    chmod(target, mode);
                    break;
                }
                case '5': // directory
                    if (guard && !withinRoot(rootCanon, target)) {
                        skip(in, padded(size));
                        break;
                    }
                    target.mkdirs();
                    dirFiles.add(target);
                    dirModes.add(mode);
                    skip(in, padded(size));
                    break;
                case '2': { // symlink
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    target.delete();
                    try {
                        Os.symlink(linkName, target.getAbsolutePath());
                    } catch (ErrnoException e) {
                        throw new IOException("symlink " + name + " -> " + linkName, e);
                    }
                    skip(in, padded(size));
                    break;
                }
                case '1': { // hard link: apps can't link(); copy the target
                    File source = resolve(root, linkName);
                    if (source == null || (guard && !withinRoot(rootCanon, source))
                            || !source.isFile()) {
                        throw new IOException("hard link source missing: " + linkName);
                    }
                    File parent = target.getParentFile();
                    if (parent != null) {
                        if (guard && !withinRoot(rootCanon, parent)) {
                            skip(in, padded(size));
                            break;
                        }
                        parent.mkdirs();
                    }
                    if (guard) deleteIfSymlink(target);
                    try (InputStream src = new FileInputStream(source);
                            OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    chmod(target, mode);
                    skip(in, padded(size));
                    break;
                }
                default: // device nodes, fifos, sparse files: not needed
                    skip(in, padded(size));
                    break;
            }

            extracted += size;
            if (progress != null && extracted - lastReport >= (2 << 20)) {
                lastReport = extracted;
                progress.onProgress(extracted);
            }
        }

        for (int i = 0; i < dirFiles.size(); i++) {
            chmod(dirFiles.get(i), dirModes.get(i));
        }
    }

    /** Trims leading/trailing slashes then splits on '/'; {@code []} when empty. */
    private static String[] splitMember(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) == '/') start++;
        while (end > start && name.charAt(end - 1) == '/') end--;
        if (start >= end) return new String[0];
        return name.substring(start, end).split("/");
    }

    /**
     * Drops the first {@code strip} path components from {@code name}; returns
     * {@code null} when the name has no more than {@code strip} components (the
     * wrapper directory itself or shallower), so the caller skips it.
     */
    private static String stripComponents(String name, int strip) {
        String[] parts = splitMember(name);
        if (parts.length <= strip) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = strip; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Reads one block; false on clean EOF, throws if truncated mid-block. */
    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < BLOCK) {
            int n = in.read(block, off, BLOCK - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("truncated tar header");
            }
            off += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    /** NUL/space-padded text field. */
    private static String field(byte[] block, int off, int len) {
        int end = off;
        while (end < off + len && block[end] != 0) end++;
        return new String(block, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] block, int off, int len) throws IOException {
        if ((block[off] & 0x80) != 0) { // GNU base-256; rootfs never needs it
            throw new IOException("base-256 tar field unsupported");
        }
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            if (b < '0' || b > '7') throw new IOException("bad octal tar field");
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    /** Reads a meta entry's payload (GNU L/K name, PAX block) and its padding. */
    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size > (1 << 20)) throw new IOException("oversized tar meta entry");
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new IOException("truncated tar meta entry");
            off += n;
        }
        skip(in, padded(size) - size);
        return data;
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] data = readEntry(in, size);
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * PAX {@code "len key=value\n"} records; returns {path, linkpath} (nullable).
     * Parsed over the raw bytes, not a decoded string: {@code len} counts
     * <em>bytes</em>, so a record holding non-ASCII (exactly what a PAX header
     * is usually there for) would desynchronize a char-indexed walk and lose
     * every override after it.
     */
    private static String[] parsePax(byte[] data) {
        String path = null;
        String link = null;
        int pos = 0;
        while (pos < data.length) {
            int sp = indexOf(data, (byte) ' ', pos);
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(
                        new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length) break;
            int end = pos + len - 1;             // the record's trailing \n
            int eq = indexOf(data, (byte) '=', sp + 1);
            if (eq > sp + 1 && eq < end) {
                String key = new String(data, sp + 1, eq - sp - 1,
                        StandardCharsets.UTF_8);
                String value = new String(data, eq + 1, end - eq - 1,
                        StandardCharsets.UTF_8);
                if (key.equals("path")) path = value;
                else if (key.equals("linkpath")) link = value;
            }
            pos += len;
        }
        return new String[] {path, link};
    }

    /** First index of {@code b} at or after {@code from}, or -1. */
    private static int indexOf(byte[] data, byte b, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    private static void skip(InputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("truncated tar data");
                n = 1;
            }
            count -= n;
        }
    }

    /**
     * True when {@code path}'s real location (all symlink components resolved) is
     * {@code rootCanon} itself or lies beneath it. Used by the guarded
     * {@link #extractTar} path to reject an entry that a symlink planted by an
     * earlier member would redirect outside the staging tree, while still
     * allowing the rootfs's own usrmerge symlinks (whose parent {@code bin}
     * resolves to {@code usr/bin}, still inside {@code root}). A path that can't
     * be canonicalized is treated as unsafe.
     */
    private static boolean withinRoot(String rootCanon, File path) {
        String p;
        try {
            p = path.getCanonicalPath();
        } catch (IOException e) {
            return false;
        }
        return p.equals(rootCanon) || p.startsWith(rootCanon + File.separator);
    }

    /** Removes {@code file} only if it is itself a symlink (the link, not its target). */
    private static void deleteIfSymlink(File file) {
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode)) {
                file.delete();
            }
        } catch (ErrnoException ignored) {
            // doesn't exist (or can't stat) — nothing to remove
        }
    }

    /** Joins a tar path under root, rejecting absolute/".." escapes. */
    private static File resolve(File root, String name) {
        StringBuilder clean = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        if (clean.length() == 0) return root;
        return new File(root, clean.toString());
    }

    /** Best-effort: arm64chroot fakes root, so lost mode bits aren't fatal. */
    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 07777);
        } catch (ErrnoException ignored) {
        }
    }

    /** The default guest DNS config, written only when networking is on. */
    private static final String RESOLV_CONF = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n";

    /**
     * Bind-mount points plus network config the minimal rootfs lacks.
     *
     * FARADAY: upstream always wrote the default resolv.conf. Faraday's
     * sandbox networking defaults OFF, so the file is written only when
     * {@code networkingEnabled} — with networking off the guest has no DNS
     * configuration at all (and the engine-level gate blocks INET sockets
     * regardless; the two layers are independent).
     */
    private static void writeGuestDefaults(File root, boolean networkingEnabled)
            throws IOException {
        for (String dir : new String[] {"dev", "proc", "sys", "tmp", "root", "etc"}) {
            new File(root, dir).mkdirs();
        }
        chmod(new File(root, "tmp"), 01777);

        if (networkingEnabled) writeResolvConf(root);

        File hosts = new File(root, "etc/hosts");
        if (!hosts.exists()) {
            try (OutputStream out = new FileOutputStream(hosts)) {
                out.write("127.0.0.1 localhost\n::1 localhost\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** Writes the default resolv.conf, replacing whatever the rootfs shipped. */
    private static void writeResolvConf(File root) throws IOException {
        // Android has no /etc/resolv.conf to inherit; without one the guest
        // has no DNS. Replace whatever the rootfs shipped (often a dangling
        // systemd-resolved symlink).
        File resolv = new File(root, "etc/resolv.conf");
        resolv.delete();
        try (OutputStream out = new FileOutputStream(resolv)) {
            out.write(RESOLV_CONF.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * FARADAY ADDITION: applies the sandbox networking setting to an already
     * installed rootfs — writing the default resolv.conf when enabled,
     * removing it when disabled — so flipping the settings toggle takes
     * effect without a reinstall. No-op when no rootfs is installed (the
     * setting is applied at install time instead). Guest edits to
     * resolv.conf made while networking was on are replaced, matching the
     * install-time behavior.
     */
    public static void applyNetworkingDefaults(Context ctx, boolean networkingEnabled)
            throws IOException {
        if (!isInstalled(ctx)) return;
        File root = dir(ctx);
        if (networkingEnabled) {
            writeResolvConf(root);
        } else {
            new File(root, "etc/resolv.conf").delete();
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                // listFiles doesn't follow symlinks for listing, but delete
                // the link itself, never its target's contents.
                if (child.isDirectory() && !isSymlink(child)) {
                    deleteRecursively(child);
                } else {
                    child.delete();
                }
            }
        }
        file.delete();
    }

    private static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return true; // can't tell — don't recurse into it
        }
    }
}
