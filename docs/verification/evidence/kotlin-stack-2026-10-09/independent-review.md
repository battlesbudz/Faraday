# Independent Kotlin compiler stack repair review

Reviewed 2026-10-09. Verdict: no blocking findings for this narrowly scoped build configuration repair.

## Reviewed revision and preservation

- Commit: `f9e16c03332a6bcf2efefd7fe9e9e11392e6164b`
- Tree: `968c18fc7463fd92027105e07af90958aaae0dc7`
- Parent: `165ffbb46af313d51e3947a2e70cab3eaef3a543`
- Only `gradle.properties` differs from the parent. Production code, tests, acceptance maps, workflows and scripts are unchanged. Working tree is clean.
- `AudioTurnCapture.kt` remains Git blob `bc969cda6e22b1712e420b3fe79b97a5899c2ece`, SHA-256 `5470c5f3bb6437ba498db36f876d581fc3f911319a237f48d7fe52e7b76f5303`.

## Configuration findings

Both property values use valid space-separated JVM arguments. Adding `-Xss8m` to `kotlin.daemon.jvmargs` covers the Kotlin compiler daemon; adding it to `org.gradle.jvmargs` covers compilation inside Gradle when daemon fallback occurs. This matches [Kotlin JVM-argument configuration](https://kotlinlang.org/docs/gradle-compilation-and-caches.html#kotlin-daemon-jvmargs-property) and [in-process fallback documentation](https://kotlinlang.org/docs/compiler-execution-strategy.html#fallback-strategy).

Repository-wide inspection found no compiler execution strategy, task-specific Kotlin daemon JVM arguments, or CI command-line override defeating these settings. `scripts/ci_gradle.sh` invokes Gradle with `--no-daemon`, and CI retains all existing release tasks. Heap limits remain 2048 MiB for Gradle and 4096 MiB for Kotlin; worker limit remains 2. The config receipt reports successful Java Properties parsing and JVM acceptance with `ThreadStackSize=8192` for both values, while heap sizes match those limits. Its config hash matches the committed file. No compiler option disables the problematic analysis or changes compilation semantics.

## Evidence reviewed

Build 1245's retained Kotlin report contains the recursive coroutine monitor-depth `StackOverflowError` in the daemon at line 23782, explicit in-process fallback at lines 24812–24815, and the same failure at line 48952. The retained default-stack focused failure contains the same backend trace.

The fresh candidate compile receipt records Kotlin 2.3.21, `-Xss8m`, `-Xmx768m`, 52 actual production/test source files, exit 0, and 15.615 seconds. Every recorded source hash matches the candidate; all 52 hashes also match the retained successful source manifest. All 12 dependency hashes match current cached JARs and the retained successful receipt. The fresh log hash matches its receipt. The only fresh diagnostic is an unnecessary non-null assertion warning in unchanged `AudioTurnCapture.kt`.

## Scope and material limitations

This is persuasive evidence that more JVM thread stack avoids this particular backend overflow on unchanged source. It is not a fresh controlled default-stack versus 8 MiB experiment: the failure baseline is retained, and the earlier success manifest corroborates its source context rather than providing a standalone fail-time source manifest for that historical default-stack invocation. No additional compile was run for this review.

The fresh compile calls `K2JVMCompiler` directly with cached Android API stubs; it does not execute Gradle property propagation, launch a Kotlin daemon, exercise real fallback, load the full Android/Compose build, or validate NDK/R8, packaging, tests at runtime, APK installation, model behavior or physical audio. Exact-revision hosted release and sandbox results are still required before any APK validation claim.

The larger stack increases per-thread native stack reservation, including Gradle threads; the unchanged worker/heap bounds do not cap total thread count or measure peak RSS. Persistent local Kotlin daemons may need restarting to ensure new JVM options apply; the hosted job starts on a fresh runner. Eight MiB is a demonstrated successful value here, not a measured minimum or a proof against future source growth.
