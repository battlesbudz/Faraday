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

## Repair attempt 2: semantic fake-clock boundaries

[Build 1273](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38116461319),
source `c82b26e34032ec3ad9d3baa467e85f2d45377fa8`, passed Android test compilation
and ran 2,240 release JVM tests. Exactly one failed: the existing native timing
worker fixture expected checked-close entry at 110, but observed 150. The raw
[JUnit XML](evidence/audio-build-repair-2026-10-11/clock-fixture/build-1273-native-timing.xml)
is retained from the SHA-256-verified same-run unit artifact.

The fixture advanced its fake clock every time diagnostics read it. New append
start/end and seal start/end observations introduced four extra reads. The narrow
test-only correction advances time at fake seal return and successful checked-close
return instead; observing time is now side-effect-free. Exact 110/120 timestamps,
producer identity 99, metrics validity, cancellation suppression and mismatched
receipt rejection remain unchanged. No production code or acceptance threshold
changes in this repair.

Independent reproduction against the actual reviewed SDK failed only that case
in the old 144-test suite, then passed all 144 after correction. This extends the
earlier 133-test selection with all three native timing worker tests, five retained
encoder lifecycle tests and three queue tests. The nearby 38-test worker/timing
selection also passed ten consecutive runs. A restricted public-core API compile
and host execution passed 144 tests; it remains a symbol-visibility proxy, not
an Android SDK/device pass. Evidence and immutable-source/hash identity are in
[the clock repair receipt](evidence/audio-build-repair-2026-10-11/clock-fixture/repair-receipt.json).

Builds 1271 and 1272 ultimately passed their independent full-E2B native quality
gates but failed the original test-compilation fixture, so neither produced an
accepted APK. Build 1273's native quality work remains independently tracked.
The final repaired revision still requires the complete hosted release gate,
including all Android profiles; host checks do not qualify a release candidate.
