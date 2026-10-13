# Repository maintainability refactor acceptance

Original behavior baseline: `audio-pr2` commit
`923a0e6bc2aab3f93371ed5c691afc3244e6bf17` (published Build 885).
`ed30650` is the first decomposition checkpoint, not architectural completion or
proof that a later candidate passed verification. The final candidate must meet
all structural, handoff and exact-revision gates below.

## Completion criteria

| ID | Required result | Evidence |
| --- | --- | --- |
| A1 | `JarvisRuntime` is a composition/lifecycle facade. Voice and conversation stages have named owners rather than an integrated workflow hidden in the facade. | Source review of facade, call preparation/capture/reply/accepted-input stages and conversation coordinator |
| A2 | Stage collaborators receive typed inputs/results and narrow operation ports. Domain/phase files do not reach arbitrary process fields through `JarvisRuntime` extensions or a renamed all-capability runtime wrapper. | Dependency/import/call-site audit plus `scripts/check_architecture.py`; compatibility entry adapters may only delegate to the appropriate owner |
| A3 | Admission, model/microphone leases, call identity, dispatchers, cancellation/join ordering and memory publication fences have one explicit owner each. | Owner table and existing plus focused lifecycle/failure/cancellation regressions |
| A4 | Every repository subsystem is audited for responsibility, dependencies, duplication, navigation and verification. Retained large components have a concrete cohesion/resource reason, named seams and tests. | [Whole-repository audit](../architecture/repository-audit.md) with justified exceptions; file size alone is not acceptance |
| A5 | Current entry points, package/stage owners, dependency rules, persistence/ABI contracts, common change paths and tests are mapped accurately. | [Architecture](../architecture/README.md), [owner map](../app-modularization.md), [change guide](../architecture/change-guide.md), link/source review |
| A6 | A new maintainer can restore the checkout/toolchain, understand native builds, run applicable checks, install an APK and hand off exact evidence. | README → CONTRIBUTING → architecture/setup/scripts navigation; fresh-checkout helper checks and reviewed build/install instructions |
| A7 | Observable behavior and compatibility contracts remain intact; decomposition does not waive or weaken acceptance. | Behavioral matrix below, unchanged named journey assertions/selectors and native/release ABI checks |
| A8 | The final combined revision passes every required existing release gate and produces the numbered installable APK release. | Exact branch-head/merge identity, full JVM/native/helper results, both signed variants, every API 30/API 35 journey, same-run receipt and publication |

Completion requires all eight criteria. An extraction milestone, a focused JVM
pass, fewer lines, or a passing previous build is insufficient. Single-module
packaging remains the decision in [ADR 001](../architecture/adr-001-package-boundaries.md);
new build modules are not a completion requirement.

## Preserved behavioral contracts

| Contract | Verification |
| --- | --- |
| Model setup/import/download retains selection and operation ownership | Existing model JVM tests; journeys 01–03, 10–12, 32–33, 90 |
| Chat and floating voice overlay retain controls, transcript and navigation | Existing journeys 25–31, 46–47 |
| Corrected/erased/expired memory cannot leak through an in-flight reply | Existing memory delivery/receipt/invalidation JVM tests; journeys 24–26, 34–39 |
| Accepted actions remain journaled, ordered, receipt-based and cancellation-aware | Existing action queue/lease/ledger JVM tests; journeys 14–23, 40–44 |
| Speech preserves PCM, bounded queue, drain, interruption and borrower ordering | Existing voice JVM tests, focused component tests, native packaging/callback checks |
| Invalid requests and failed storage stop effects before unauthorized dispatch | Existing rejection/failure JVM tests; journeys 05–06, 08, 15–17, 35, 38, 40–43 |
| Benchmark/status definitions and export linkage remain stable | Existing benchmark JVM tests; journeys 45–46 |
| Helper checks work from a fresh checkout without another session's files | Architecture guard, root/nested Python suites, portable artifact replay, developer-command failure cases |

Preference keys, storage schemas/serialized identities, model filenames and
catalog identities, Android component names, native callback signatures, release
UI selectors and every named journey remain compatibility constraints. No new
model backend, voice tuning or persistence migration is implied by structural work.

## Evidence and status

| Layer | Status required for final handoff |
| --- | --- |
| Architecture | A1–A4 source review complete; justified exceptions named |
| Maintainer handoff | A5–A6 links, source owners, setup/check/install path reviewed |
| Behavioral verification | A7 existing and focused regressions pass without relaxed assertions |
| Candidate release | A8 exact-revision full pipeline and publication pass |

The final architecture/source map, whole-repository audit and newcomer links are
reviewed in the [resolved boundary audit](../architecture/repository-audit.md).
This closes structural findings; it does not supply compiled regression or release
evidence. Until the final candidate's complete regressions and exact-revision
receipt/publication pass, the work remains a refactor candidate. Record the tested source commit and branch head,
run/release links, APK digests, results and outstanding gaps here or in the linked
feature acceptance map. Retain failure evidence; a later pass must not overwrite
or relabel an earlier failure.

The release gate is the existing `Android APK` workflow: release JVM/native/helper
checks, signed normal and compact APKs, all named journeys from
`scripts/verification/scenarios.json` on API 30/API 35, consolidated same-run
receipt, and publication. The receipt distinguishes the tested PR merge commit
from the candidate branch head. Review the final combined tree before pushing.

## Build 891: first final candidate failed compilation

[Build 891](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37084878313)
tested PR head `03a6147494a3685a5e309ae34de5d8e7e1c493c8` as merge commit
`bb77a727d36b613120a567e072815dc9bc43356d`. Its retained receipt reports
`passed=false`; this run is failure evidence, not a verified APK candidate.

Native keyword and Python checks passed. `compileReleaseKotlin` failed at
`JarvisRuntime.kt:601`: recursive type inference followed the lazy `voiceTurns`
initializer through its `::runVoiceTurn` restart callback, with an unresolved
`start` diagnostic at the entry method. Release JVM checks, signed APK assembly
and Android journeys did not complete, and publication remained blocked.

The composition repair explicitly types the lazy owner as `VoiceTurnRunner` and
the entry method's return as `Unit`, breaking inference recursion while preserving
the restart callback and stage behavior. This source repair requires a new exact
combined-revision full gate; the passing earlier helper/native checks cannot
stand in for its JVM, signed normal/compact, Android and publication evidence.

Retained evidence: [failed receipt artifact 11260606908](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37084878313/artifacts/11260606908)
and [build-performance artifact 11259818814](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37084878313/artifacts/11259818814).
The receipt contains no completed JVM XML or Android results because compilation
blocked their production. Preserve this failure when recording the repaired run.

## Builds 893–894: coroutine exception-identity assertion

[Build 893](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085585128)
tested PR head `60d8d6d48e28c5a131b8c1acd290ccea99b27e07` as merge commit
`a4a6077581bab3e3a650e69fa224aed081209808`. Compilation reached the release JVM
suite: 998 tests ran, 997 passed and one failed. The new
`AcceptedReportPlaybackTest.releaseFailurePropagatesAfterJoiningButCannotSkipDetachOrRepeatCleanup`
failed at line 102's `assertSame(failure, caught)` despite the expected
`IllegalStateException` type/message. Retained [JVM XML artifact 11260279745](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085585128/artifacts/11260279745)
records the failure. The [failed receipt artifact 11260845221](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085585128/artifacts/11260845221)
retains the blocked gate; signed normal/compact assembly, emulator verification
and publication did not complete.

[Build 894](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085688952)
reproduced the same 998/997/one-failure result at PR head
`def48bcdf0c4d5cc94971bf6d4a620c1ba3179d9`, tested merge
`5249bcecca851490c2e263f62941195a25d7837e`. Its
[failed receipt 11260895316](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085688952/artifacts/11260895316)
confirms `passed=false`; retained [JVM XML 11260990144](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085688952/artifacts/11260990144)
and [build-performance artifact 11260354733](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37085688952/artifacts/11260354733)
preserve its failure. Neither run is a verified APK.

The test-only repair landed concurrently in
`e977b5726e6400f8e39fbc6cd73128257435fe63` and was retained through a fast-forward.
It catches `IllegalStateException`, checks the matching message, and requires the
exact original exception anywhere in the cause chain with
`generateSequence(caught as Throwable?) { it.cause }.any { it === failure }`.
This accommodates coroutine stack-trace recovery's copied exception while retaining
original-cause identity. The pinned [kotlinx-coroutines 1.9.0 implementation](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/jvm/src/internal/StackTraceRecovery.kt)
uses `tryCopyException(cause)` and unwraps same-type causes. Every writer-join,
release, detach, cleanup-order and idempotence assertion remains. Production code,
debug flags, all other tests, named scenarios and gate requirements are unchanged.

A fresh exact combined-revision full gate must validate this assertion repair and
complete all JVM/native/helper, signed normal/compact, Android, same-run receipt
and publication requirements. Preserve both failed runs as evidence.

## Physical-device acceptance

Real model inference, actual large model transfers, microphone/speaker/Bluetooth
behavior and Fold 6 performance are separate device coverage. Their absence must
be disclosed, but it does not by itself prevent completion of the architectural
refactor after A1–A8 pass. Automated structural completion does not establish better
speech accuracy, lower latency or integrated product acceptance on the phone.
