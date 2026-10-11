# Audio candidate build repair — 11 October 2026

## Failure classification: test harness compilation

[Build 1271](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38113626059),
source `da588404fc80435d5cd73cec332c173d4fb12226`, passed reviewed ARM64 SDK,
recorded speech/native contracts and production Kotlin/native compilation.
Its [release-build job](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38113626059/job/114399237160)
failed `compileReleaseUnitTestKotlin`: the new `EarlyGemmaInputModeTest` directly
referenced `sun.misc.Unsafe`, absent from the Android compile bootclasspath.
The standalone JDK host compiler exposed that class and therefore missed this
Android compilation boundary. No APK or sandbox pass is claimed for this run.

## Narrow correction

Only the host-JVM test fixture resolves its existing constructor-bypass allocator
through `Class.forName` and Java reflection. Runtime allocation and the injected
checked fake native lifecycle are unchanged. All four real session-owner/history
tests and their assertions remain; no production behavior, dependency, SDK, native
ABI, acceptance threshold or workflow is changed. The allocator is never compiled
into the application or an Android instrumentation test.

The focused actual-source/reviewed-SDK suite passes 133 JVM tests after correction.
Independent review separately passed all four mode tests and all 133 tests under
a restricted public-Java-core compile classpath that reproduces the original
missing-internal-symbol error. This is an Android-symbol-visibility proxy, not an
Android SDK build. Restricted compile and review evidence are retained below. These are
not substitutes for the rerun of complete Android release compilation, all JVM
tests, package/native gates and sandbox at the repaired revision. Failed logs
remain associated with their original source/run identity.

This is repair attempt 1 for the combined audio candidate. The measurement-only
Build 1272 contains the same original fixture and is tracked independently; its
native quality work is not cancelled or represented as release acceptance.

See [evidence](evidence/audio-build-repair-2026-10-11/).
