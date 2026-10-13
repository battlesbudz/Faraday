# Android sampler dependency derivation

The exact LiteRT-LM 0.16 Android OpenCL and WebGPU TopK sampler prebuilts import
`kLiteRtRuntimeBuiltin` without declaring the library that provides it. The
reviewed source-built `liblitertlm_jni.so` exports this table. Build 1135's actual
native-load gate failed on all five Android profiles, including API 30 and the
16 KB profile. This is also described in the upstream report
<https://github.com/google-ai-edge/LiteRT-LM/issues/3000>.

The package recipe verifies the original upstream LFS hashes, then derives fresh
copies with an explicit `DT_NEEDED` entry for `liblitertlm_jni.so`. The original
files remain untouched. Both derived outputs have fixed SHA-256 pins. No sampler
instructions, constant data, symbol ABI or relocation contents change. This
explicit plugin-to-JNI dependency is a narrow packaging compatibility measure;
it does not introduce a process-global runtime or change the SDK/model version.

PatchELF 0.18.0 is obtained from its official pinned NixOS release, with archive
and executable hashes in `patchelf-tool.json`. It is a GPL-3.0-or-later host build
tool, not an APK/AAR payload. Its license is
<https://github.com/NixOS/patchelf/blob/0.18.0/COPYING>. The generated ELF does not
contain PatchELF code. Existing SDK/prebuilt licenses and notices are retained.

The rewrite moves string/dynamic tables into one new segment. The recipe makes
that metadata-only segment and `PT_DYNAMIC` read-only, preserving every original
LOAD/RELRO range. It verifies the full old string-table prefix, dynamic pointers,
symbols, runtime sections, normal and Android-packed relocation bytes/targets,
16 KB page separation and absence of executable writable pages/text relocations.
Any unexpected input, tool, layout or output fails the build. No test bypass or
load-order workaround is used. The existing test intentionally encounters the
plugins before the JNI library, exercising the declared dependency on Android.

The APS2 decoder uses the format described by AOSP's
`android-11.0.0_r1/linker/linker_reloc_iterators.h`. AOSP's matching `linker.cpp`
supports read-only dynamic tables and only writes `DT_DEBUG` when writable;
these derived libraries have no `DT_DEBUG`. Source references:

- <https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/linker/linker_reloc_iterators.h>
- <https://android.googlesource.com/platform/bionic/+/android-11.0.0_r1/linker/linker.cpp>

AOSP reference notice:

Copyright (C) 2015 The Android Open Source Project. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
