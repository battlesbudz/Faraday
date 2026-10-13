# Build 1245 Kotlin compiler stack repair

[Build 1245](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37982483092)
failed release compilation at source `43cdf8ccd5a9090119e94c27c8de6af5c61f7c1f`.
Kotlin 2.3.21 overflowed its thread stack while transforming the frame collector
in `AudioTurnCapture.start`, in recursive coroutine monitor-depth analysis.
Both the compiler daemon and the subsequent in-process fallback failed.
Release JVM tests, APK/R8 verification and Android journeys were not reached.

`gradle.properties` now requests `-Xss8m` for both the Kotlin daemon and the
Gradle JVM that hosts its existing fallback. Heap limits remain 4 GiB and 2 GiB
respectively; the two-worker limit remains unchanged. This changes build-tool
thread stack capacity, not the Android application's runtime stack or voice code.

The cached compiler identity matches the CI artifact. A fresh 52-file compile
of the unchanged capture source/test set passes with the 8 MiB stack. The
retained default-stack failure has the same backend traceback and matching
source hashes from its adjacent successful receipt; it lacks its own fail-time
manifest, so a fresh single-variable A/B comparison is not claimed. The exact
CI source and failure remain separately bound to Build 1245.

Java Properties parsing and actual JVM launches with each configured argument
string confirm 8192 KiB stacks and unchanged heaps. These checks do not launch
Gradle or prove its daemon option propagation. The next exact-head workflow must
verify full release compilation, all JVM tests, native and R8 checks, signed APKs
and every Android profile. No test assertion, gate, numerical reference, model
pin or timeout is weakened.

Retained evidence: [failure provenance](evidence/kotlin-stack-2026-10-09/failure-provenance.json),
[fresh compile](evidence/kotlin-stack-2026-10-09/fresh-compile-receipt.json),
[JVM settings](evidence/kotlin-stack-2026-10-09/config-check-receipt.json),
[repair receipt](evidence/kotlin-stack-2026-10-09/repair-receipt.json) and
[independent review](evidence/kotlin-stack-2026-10-09/independent-review.md).
