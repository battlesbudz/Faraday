# libterm.so: the vendored terminal + chroot-ng native build.
#
# FARADAY ADAPTATION of upstream's app/src/main/cpp/CMakeLists.txt (see
# NOTICE.md): the arm64chroot and arm64emu targets are not vendored, so they
# are gone; what remains is upstream's ghostty-vt import, the chroot-ng
# static library (built exactly as upstream builds it), the stb_image PNG
# decoder, and libterm.so itself. This file is added as a subdirectory from
# Faraday's root CMake entry (cpp/microwakeword/CMakeLists.txt); the native/
# tree sits beside it under cpp/native, mirroring upstream's layout.

cmake_minimum_required(VERSION 3.22.1)
project(terminal C ASM)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../native)
set(GHOSTTY_VT_DIR ${NATIVE_DIR}/ghostty-vt)

# FARADAY: upstream ships one libghostty-vt.a (14.6 MB); ours is the same
# archive split in two (identical member objects, verified byte-for-byte)
# because the single file exceeds our tooling's upload limit. See NOTICE.md.
add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/prebuilt/${ANDROID_ABI}/libghostty-vt.a)
add_library(ghostty-vt-zcu STATIC IMPORTED)
set_target_properties(ghostty-vt-zcu PROPERTIES
    IMPORTED_LOCATION ${GHOSTTY_VT_DIR}/prebuilt/${ANDROID_ABI}/libghostty-vt-zcu.a)

# --- chroot-ng: the native AArch64 userland engine (seccomp/SIGSYS path
# translation + userland ELF loader), linked into libterm.so and entered via
# chroot_ng_main() (chroot_ng_embed.c) in the fork()ed PTY child. Unlike an
# emulator it runs guest code directly on the CPU, so it exists only on
# arm64-v8a; the x86_64 build carries no trace of it (HAVE_CHROOT_NG gates
# the JNI branch). Faraday ships arm64-v8a only.
#
# Compiled exactly as its own Makefile builds it (freestanding: its SIGSYS
# handler runs with the guest's TPIDR_EL0, so it must never call Bionic)
# minus the standalone-only -static/-no-pie/linker-script — the NDK's
# default -fPIC replaces them, and ASLR already keeps libterm.so clear of
# the fixed load addresses the linker script existed to dodge. The script's
# other job, bracketing the image with __cng_image_start/__cng_image_end,
# falls to chroot_ng_image.h, force-included into every source: it maps them
# onto lld's own bounds of libterm.so. src/rt/start.S (the standalone
# _start) is excluded; the embed shim reconstructs its contract.
#
# Two NDK defaults stay on top of the Makefile's flags, both harmless:
# -funwind-tables, which -fno-asynchronous-unwind-tables does not cancel, so
# the objects carry .eh_frame (and crash reports can unwind through
# chroot-ng frames), and --gc-sections, which the NDK passes in Release
# links only.
#
# rt.c defines mem/str helpers under their libc names so compiler-emitted
# calls resolve in a -nostdlib link. Inside libterm.so those definitions
# would win intra-.so binding for ghostty-vt and the JNI code
# (byte-at-a-time loops) and be exported, so rename them via the
# preprocessor — definitions, rt.h declarations, and every explicit
# chroot-ng call rename together; implicit compiler-emitted memcpy/memset
# calls still bind to Bionic's, which is safe (they are TLS-free) and
# faster.

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CNG_DIR ${NATIVE_DIR}/chroot-ng)
    file(GLOB_RECURSE _cng_srcs CONFIGURE_DEPENDS
        ${CNG_DIR}/src/*.c ${CNG_DIR}/src/*.S)
    list(FILTER _cng_srcs EXCLUDE REGEX "src/rt/start\\.S$")
    if(NOT _cng_srcs)
        message(FATAL_ERROR "chroot-ng: no sources under ${CNG_DIR}/src")
    endif()

    add_library(chroot-ng STATIC ${_cng_srcs})
    target_include_directories(chroot-ng PRIVATE ${CNG_DIR}/include)
    target_compile_definitions(chroot-ng PRIVATE
        memset=cng_embed_memset
        memcpy=cng_embed_memcpy
        memmove=cng_embed_memmove
        memcmp=cng_embed_memcmp
        strlen=cng_embed_strlen
        strcmp=cng_embed_strcmp
        strncmp=cng_embed_strncmp
        strchr=cng_embed_strchr
        strrchr=cng_embed_strrchr)
    # -mgeneral-regs-only is load-bearing, not hygiene: on chroot-ng's
    # rewrite (-R) tier the trampoline calls straight into this C code on
    # the guest's own register file, and the kernel preserves the FP
    # registers across a syscall, so any v/d register the compiler spends
    # here is taken from the guest (measured upstream: a popcount
    # `cnt v0.8b` and an `fmov d1, x0` spill cost a guest d0/d1/d16 across
    # an openat). Its Makefile sets this deliberately unprobed for that
    # reason, so keep the two in step. The .S sources take the flag too —
    # clang, unlike gcc, turns it into a target feature for its integrated
    # assembler — which is why tramp.S brackets its one FP function
    # (cng_rwtest_fn, the self-test that must name d0/d8/FPCR) in
    # .arch_extension fp/nofp.
    target_compile_options(chroot-ng PRIVATE
        -O2 -std=gnu11 -ffreestanding -fno-builtin -fno-stack-protector
        -fno-asynchronous-unwind-tables -mno-outline-atomics
        -mgeneral-regs-only -Wall -Wextra -Wno-unused-parameter
        "SHELL:-include ${CMAKE_CURRENT_SOURCE_DIR}/chroot_ng_image.h")
    # The Makefile's one -Werror unit. unistd_check.c includes chroot-ng's
    # syscall table and <asm/unistd.h> together, so a number the two spell
    # differently is a "macro redefined" warning; -Werror makes it stop the
    # build. Never put -w back on this target: clang's -w silences even a
    # warning promoted with -Werror=, and the check passes on anything.
    set_source_files_properties(${CNG_DIR}/src/rt/unistd_check.c
        PROPERTIES COMPILE_OPTIONS -Werror)
endif()

# --- PNG decoder (vendored stb_image) for Kitty graphics ---
# Its own target so the third-party header compiles warnings-off, clear of
# libterm.so's -Wall -Werror.

add_library(pngdec STATIC png_decode.c)
target_include_directories(pngdec PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(pngdec PRIVATE -w)

# --- libterm.so (JNI) ---

add_library(term SHARED
    pty_jni.c
    terminal_jni.c
    kitty_unicode.c)

target_include_directories(term PRIVATE ${GHOSTTY_VT_DIR}/include)
target_compile_options(term PRIVATE -Wall -Werror)
target_link_libraries(term
    ghostty-vt
    ghostty-vt-zcu
    pngdec log m)

# chroot-ng needs no whole-archive treatment: it has no weak cross-module
# hooks; everything is reached transitively from cng_main().
#
# --exclude-libs makes every chroot-ng symbol hidden in libterm.so. Besides
# not exporting them, this is what lets the asm link at all: tramp.S's
# trampoline template LDRs PC-relative literals named by global labels, and
# lld refuses a PC-relative relocation against a preemptible (visible)
# symbol in a shared object. Hidden ⇒ non-preemptible ⇒ resolved at link
# time.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(term PRIVATE chroot_ng_embed.c)
    target_compile_definitions(term PRIVATE HAVE_CHROOT_NG)
    target_link_libraries(term chroot-ng)
    target_link_options(term PRIVATE -Wl,--exclude-libs,libchroot-ng.a)
endif()
