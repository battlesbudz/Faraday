# Vendored terminal + chroot-ng: attribution, scope, and adaptations

This directory (and `app/src/main/cpp/native/`, `app/src/main/java/io/github/
sylirre/terminal/`) vendors code from **ghostty-android-terminal** by Sylirre:

- Upstream: https://github.com/sylirre/ghostty-android-terminal
- Pinned upstream commit: `b75be1c28d606c2206616aacf1489e063f4bd751`
- Provenance: the same tree ships in the fork
  https://github.com/battlesbudz/ghostty-android-terminal (see its SPIKE.md),
  which is how this code was evaluated on-device before vendoring.

## Licenses

- The vendored application/native code is **Apache-2.0**, Copyright 2026
  Sylirre. The full license text is at `LICENSE` in this directory;
  upstream's own NOTICE is reproduced in the adaptation list below where it
  names components. Vendored files keep upstream's package name
  (`io.github.sylirre.terminal`) and layout deliberately, so upstream diffs
  stay reviewable.
- **chroot-ng** (in `../native/chroot-ng/`) carries its own `LICENSE` and
  `NOTICE` files in-tree, preserved verbatim.
- **libghostty-vt** (Ghostty's VT engine; headers in
  `../native/ghostty-vt/include/`, prebuilt arm64-v8a archive in
  `../native/ghostty-vt/prebuilt/`) is **MIT**, Copyright (c) 2024 Mitchell
  Hashimoto, Ghostty contributors; license text at
  `../native/ghostty-vt/LICENSE-MIT.txt`. Pinned Ghostty commit:
  `5659cef41f4f2f7a478d0800a11836fa17e64d66`. Portions of `kitty_unicode.{c,h}`
  are derived from Ghostty.
- **stb_image** (`stb_image.h`) is MIT OR Public Domain (Unlicense),
  Copyright (c) 2017 Sean Barrett; its full license text is at the end of
  that file.
- **Unicode Character Database**: `case_fold.h` holds case-folding data
  generated from the Unicode Character Database (Unicode 15.0.0),
  Copyright (c) 1991-present Unicode, Inc., under the Unicode License
  (permissive).
- **XZ for Java** (`org.tukaani:xz:1.10`, a Gradle dependency, not vendored
  source) is public domain (0BSD), by Lasse Collin and contributors, based
  on Igor Pavlov's LZMA SDK.

## What was vendored

- `app/src/main/java/io/github/sylirre/terminal/term/`: OscSideScanner,
  ScreenSnapshot, SessionCommand, SessionManager, TerminalEmulator,
  TerminalNative, TerminalSession, TmpDir, UserlandDistro, UserlandIdentity,
  UserlandOptions, UserlandRootfs.
- `app/src/main/cpp/terminal/`: pty_jni.c, terminal_jni.c, kitty_unicode.c/h,
  png_decode.c/h, stb_image.h, case_fold.h, chroot_ng_embed.c,
  chroot_ng_image.h, and an adapted CMakeLists.txt.
- `app/src/main/cpp/native/chroot-ng/`: the complete upstream chroot-ng
  tree, byte-identical except `src/run.c` (Faraday patch, adaptation 4
  below).
- `app/src/main/cpp/native/ghostty-vt/`: headers and the arm64-v8a prebuilt
  archive only. The archive is vendored split in two
  (`libghostty-vt.a` + `libghostty-vt-zcu.a`): upstream's single 14.6 MB
  file exceeds our tooling's per-file upload limit. The split is a pure
  re-packaging — all 11 member objects are byte-identical to upstream's
  archive (sha256-verified member by member at vendoring time); only the
  archive containers and member name strings differ.
- `app/src/main/assets/alpine_3.24.3_aarch64_rootfs.tar.xz`: the Alpine
  3.24.3 aarch64 rootfs asset (produced upstream from the official Alpine
  minirootfs; sha256-verified at spike time).

## What was NOT vendored (excluded upstream surface)

- The arm64chroot emulator engine and the arm64emu full-system emulator:
  no native trees, no CMake targets, no JNI entry points. Faraday's engine
  is chroot-ng only.
- The interactive terminal UI (activities, views, settings screens), the
  SessionService foreground service, RootfsBackup (backup/restore),
  StorageBindings (external-storage binds), VmImages/VmMachine/VmOptions.

## Adaptations vs upstream (complete list)

Native:

1. `pty_jni.c` — removed the `arm64chroot_main()`/`arm64emu_main()` externs
   and the whole `vm_start()` spawn path (plus its JNI wrapper): an argv[0]
   other than `"chroot-ng"` now exits 127 in the forked child. Header and
   inline comments updated to match.
2. `CMakeLists.txt` (this directory) — adapted from upstream's app
   CMakeLists: NATIVE_DIR repointed to `../native`; the arm64chroot and
   arm64emu targets dropped; ghostty-vt, chroot-ng (built exactly as
   upstream builds it, including the embed symbol renames,
   `-mgeneral-regs-only`, and the `-Werror` unistd_check unit), pngdec, and
   libterm.so retained.
3. `chroot_ng_embed.c` — Faraday additions at the embed seam, from the
   2026-10-30 security review of the mediation layer: M-4, close every
   inherited file descriptor above stderr before `cng_main` (Faraday's app
   process holds live sockets/files that upstream's standalone app never
   had, and the engine deliberately admits inherited descriptors); M-5,
   set the resource limits the engine does not (RLIMIT_NPROC,
   RLIMIT_FSIZE, RLIMIT_CPU, a tightened RLIMIT_NOFILE).
4. `../native/chroot-ng/src/run.c` — Faraday patch (security review M-3):
   `cng_run` initializes the process registry eagerly (keyed as procfs.c
   keys it) and refuses to run when the backing is CNG_PROCREG_B_NONE,
   where upstream would degrade to host /proc passthrough with the kill
   family unmediated.

Java:

5. `SessionManager.java` — removed `attachVm`/`isVmTerminalOpen` and the
   `VmMachine.stopIfRunning()` call in `closeAll`; added a
   `create(SessionCommand, ...)` overload for Faraday's exec-style runs.
6. `TerminalSession.java` — removed the VmMachine constructor, the
   vm/vmTerminal fields, `isVm()`/`vmTerminal()`, and the VM branches in
   readLoop/resize/close (the close() group-kill comment now states the
   chroot-ng containment fact: guests are threads inside the engine
   process). Added an optional `RawOutputListener` tap invoked on the
   reader thread with each raw PTY chunk before terminal emulation —
   Faraday's run layer captures byte-exact output through it.
7. `TerminalNative.java` — removed the `vmStart` declaration.
8. `TmpDir.java` — the TMPDIR moved from `files/tmp` to
   `files/sandbox/tmp` so all sandbox state lives under one tree for
   storage accounting; the mode-0700 logic is unchanged.
9. `UserlandRootfs.java` —
   - Install layout: `dir()` is `files/sandbox/rootfs`, staging is
     `files/sandbox/rootfs.tmp` (upstream: `files/userland[.tmp]`).
   - Removed `replaceFromTar`, `probeStripCount`/`detectStripCount`, and
     the guarded `extractTar` wrapper (they existed only for the excluded
     RootfsBackup feature). The core tar reader is byte-identical.
   - `command()` builds for chroot-ng only: argv[0] is always
     `"chroot-ng"`, the arm64chroot-only `--jit`/`A64_JIT_MB` handling is
     gone, and the `StorageBindings` bind line is removed (the
     bindExternalStorage option is inert).
   - Added `commandExec(...)`: an exec-style overload that runs given
     guest program tokens instead of a login shell, with extra `--bind`
     specs and a working-directory override; argv/env construction is
     shared with `command()` in `buildCommand(...)`.
   - Session argv hardening (Faraday security review M-3): the host
     `/sys` bind upstream added in `buildCommand(...)` is removed — no
     host tree beyond the scoped binds a caller passes is ever bound —
     and `--no-ptrace` is always passed, since Faraday sessions have no
     guest-debugger need.
   - Guest defaults: the default `/etc/resolv.conf` is written only when
     networking is enabled (Faraday's sandbox networking defaults off);
     `install(...)` gained a networking-aware overload and
     `applyNetworkingDefaults(...)` applies the toggle to an installed
     tree. Install also stamps `rootfs.version` (see `BUNDLED_VERSION` /
     `installedVersion(...)`); there are no rootfs updates in Faraday
     Phase 1.
10. `UserlandOptions.java` — added `withWorkDir(...)`; engine-field
   comments annotated (chroot-ng is the only vendored engine).
11. Comment-only engine-name updates in SessionCommand, UserlandDistro,
   UserlandIdentity, and UserlandOptions where the text described runtime
   behavior.

Faraday's own sandbox code lives in
`app/src/main/java/com/battlesbudz/jarvis/v2/sandbox/` and is not part of
this vendored tree.
