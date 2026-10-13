# Benchmark picker recreation boundary (9 October 2026)

Build 1248, source `13a62a8374610fefda9b93c3213c559a9f97bef7`, exposed an
unacknowledged Compose replacement in the controlled benchmark export journey.
The [failed run](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38000787349)
completed 81 of 82 main journeys successfully on both affected profiles:

- API 36 actual 16 KB: the immediate old-destination check passed, but the second
  check at `ReleaseJourneyTest.kt:3215`, after a fresh save, read the old report.
- API 36 compact Fold: the first check at line 3207 read an empty destination.

The digest-verified archives, raw instrumentation/report hashes and exact failure
locations are retained in [failure evidence](evidence/benchmark-export-recreation-2026-10-09/failure-evidence.json).
API 30, API 35 compact and API 36 normal passed this run; those passes do not
establish a synchronization boundary or validate the repaired source.

## Cause and correction

The fixture calls `setContent`, waits for accessibility idleness and finds the
same screen resource ID that the previous composition already exposed. Pinned
Activity 1.10.0 reuses that ComposeView. Its `setContent` updates mutable content;
`createComposition` does nothing when a composition already exists. Returning
from that call therefore does not establish that replacement has committed.

Pinned Compose 1.7.6 already cancels `rememberCoroutineScope` on disposal and
unregisters each activity-result launcher. The registry removes its callback;
a late raw result stays pending under the abandoned unique key. New launchers
use a fresh namespace. The fixture was allowed to dispatch the old result while
that old owner was still alive, admitting its own frozen payload to IO. A
synchronous save already started can finish after scope cancellation. This
explains both a sentinel observed before truncation and an empty file observed
between truncation and writing. The original device traces do not record commit
and IO ordering; the host reproduction establishes these permitted schedules,
rather than claiming a uniquely observed device schedule.

The fixture now acknowledges its exact render generation in `SideEffect` and
waits for that generation using the existing five-second observation bound.
Compose dispatches forgotten/remembered observers before side effects, so this
receipt follows old scope/launcher disposal and new launcher registration.
The held old result is still actually dispatched after that boundary. Both
unchanged stale-file assertions and successful fresh export remain required.
All 57 assertion lines, ten existing wait-call lines, named scenarios, thresholds
and suite/profile deadlines remain intact. No delay or retry replaces an assertion.

This introduces one real R8 configuration change: preserve the exact
`EffectsKt.SideEffect(Function0, Composer, int)` method used by the independent
instrumentation DEX. Build 1248 removes that method's unused third argument.
Existing `Composer` keeps cover the two additional compiler-generated calls;
AtomicInteger methods are platform Java APIs. The
[independent ABI review](evidence/benchmark-export-recreation-2026-10-09/independent-review.json)
audits every added direct method reference against the actual APK/test DEX and
mapping. No broad Compose keep or production Kotlin change is introduced.

## Local acceptance evidence

The [offline host harness](evidence/benchmark-export-recreation-2026-10-09/harness/run_host.py)
compiles the exact production benchmark UI/export sources and exact changed
test45 method against cached, digest-verified Android 14, Compose 1.7.6,
Activity 1.10.0 and UiAutomator 2.3.0 dependencies. It then runs six controlled
lifecycle cases with the actual composition runtime, actual launcher registration
and raw registry result dispatch:

1. Withhold the replacement frame, admit the old callback and pause before stream
   truncation: the first sentinel check passes, then the old report arrives after
   a fresh save, reproducing the 16 KB failure state.
2. Admit the callback before commit, truncate and pause before writing: the first
   sentinel check sees empty bytes, reproducing the Fold failure state.
3. Commit replacement before delivery, then hold a fresh same-scope picker while
   delivering the old result. Across eight recreations the old URI is unopened,
   fresh busy state/payload remain intact and fresh saved bytes match that payload.
4. Dispose after callback admission but before its queued coroutine starts: no IO.
5. Dispose after the save coroutine queues its IO handoff but before provider
   entry: no IO. Two explicitly occupied IO workers establish that boundary.
6. Cancel a picker, admit another save, update the report within the same owner,
   and verify that the saved bytes still equal the original frozen report.

The existing `BenchmarkExportStateTest` and `PipelineBenchmarkTextExportTest`
suites pass all 11 cases. `python3 scripts/dev.py check` passes architecture
checks, 109 helper tests and 82 verification-helper tests. Hash-bound receipts
and logs are retained beside the harness.

Run with JDK 21 and the recorded dependency cache; the harness downloads nothing:

```sh
python3 docs/verification/evidence/benchmark-export-recreation-2026-10-09/harness/run_host.py \
  --repo . --deps /path/to/dependency-cache --out /path/to/new-evidence-directory
```

Host limitations are explicit: ContentResolver is a stream-opening adapter;
Android tracing and SDK system properties are small host shims. A manual
coroutine dispatcher/frame clock and bounded latches control scheduling. The
compile fixture uses signature-only existing navigation/activity helpers,
generated BuildConfig values and verbatim extracted conversation DTOs. These
checks do not execute Android provider IPC, a real picker, accessibility, R8,
whole Gradle builds or an emulator. The runtime cases replace a keyed screen
owner; they do not execute Activity recreation or process death. They do not promise rollback of synchronous
IO admitted before an owner was disposed.

## Required release gate

The exact integrated revision requires fresh signed/minified APKs, all release
JVM/native checks and the full five-profile Android matrix, including complete
test45, upgrade, lifecycle, layout and consolidated receipt phases. The narrow
keep must be checked in the newly built release DEX. Local evidence does not
verify the revised APK. Third-party picker/receiver behavior, real models,
acoustics and physical-device performance remain outside these checks.
