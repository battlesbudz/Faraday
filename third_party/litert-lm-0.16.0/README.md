# LiteRT-LM 0.16.0 reviewed source delta

Upstream: https://github.com/google-ai-edge/LiteRT-LM

- SDK commit: `924e79c91542761242244e4f1651851f822e4cbb`
- LiteRT dependency: `0ff28117f1cb5556d0e015bf80b773f74e2bee51`
- Source patch and every changed file SHA256: `reviewed-source.json`
- Upstream license: `LICENSE` (Apache2.0)

`PATCH.diff` contains the frozen runtime projected-audio path, native PCM owner,
normal owner JNI binding, observational timing, checked Session/Conversation lifecycle changes and their
focused source regression tests. It includes 50 source files. It does not contain
model weights, materialized Git-LFS objects, host binaries, compiled classes,
experimental quality harnesses, audio/activations or research scratch logs.

Apply it only to the exact upstream commit. The source manifest validates every
resulting changed file before compilation. Two explicit, hash-recorded build
transformations are then applied by `scripts/streaming-sdk/build_android_sdk.py`:

1. Pin android_ndk_repository to API30 rather than its default API31.
2. Select linkstatic=True only for Android in the normal native-owner JNI target.
   Keep the verified Linux topology and separate test target unchanged.

The seven Android runtime roots and all compiler/Maven downloads have independent
version/hash/size manifests under `scripts/streaming-sdk/`. All selected ELF inputs
must pass recursive dependency and unchanged ARM64/16KB checks. Compilation uses
fresh source-produced classes and native libraries together; upstream stock
classes/JNI and host test outputs are not valid substitutes.

The AAR retains the official upstream license and third-party notices. Those
notices are not an independent certification of all newly linked code; check
third-party obligations when changing these pins or publishing new distributions.
The source patch and package are a reviewed engineering candidate, not proof of
Android execution, GPU initialization, trained-model quality or physical audio.
