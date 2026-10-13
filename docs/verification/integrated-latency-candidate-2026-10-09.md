# Integrated latency candidate — 9 October 2026

This candidate combines the reviewed opt-in Smart Turn observer, the benchmark
OPENABLE document repair, and the repaired capture-first voice handoff. Its
production and test source was checked together at local commit
`68a96943f2ab78bf5494c54812e70707c1aea727`, tree
`c36fcfcb4a9002c121bb51a1dd84ca33af9f19b9`. Subsequent changes in this admission
step are verification documentation only. Full signed Android/native release
gates are still required; this is not a measured subsecond claim.

## Resulting behavior

After successful answer playback, the existing recorder can retain the next
input while the old optional caption and native-owner cleanup finish. Raw
microphone readiness and readiness for another Gemma inference are separate
events. A bounded six-second handoff retains exact bytes and source identity;
overflow or uncertain coverage is an explicit rejection. No second recognizer
or Gemma owner is introduced. Native drain/reset barriers remain prerequisites
for new native work.

Raw-risk sequence/coverage fences prevent an old caption or farewell from
overtaking newer speech. Complete native audio can revoke old caption authority
without an ASR transcript. Slow checkpoint/memory persistence runs outside the
ingress fence. The existing echo verdict can reject a finalized candidate before
capture stops, preserving the same recorder and reset/continue path. The
opportunistic farewell check reads an already-final Whisper snapshot exactly
once; it does not refresh, await, decode, or acquire a recognizer lease.

Smart Turn remains explicitly opt-in and has no endpoint authority. Its pinned
model is downloaded only through setup; repository assets contain no weights.
Priority revocation disables observations for the rest of that capture,
including rejected-candidate reset/continue. The capture-first follow-up path
currently supplies no shadow observer, so this revision does **not** provide
Smart Turn observations for every rearmed follow-up. That coverage limitation
must be considered during phone evaluation.

Benchmark TXT, JSON and CSV document creation explicitly requests OPENABLE
destinations while retaining the AndroidX MIME, title, cancellation and result
semantics. The existing test45 assertion, scenario identities and deadlines are
preserved. Copy-part2 retains the previously reviewed descendant selector.

## Review and combined checks

- Smart Turn source/support review: `0ec040030e52101add4e046b566f241eee23d02d`.
- Benchmark contract review: `b26b9802df0d10dc32a9a1005180579276799c1b`.
- Rearm repair review: `69734a4ecbc8fbbfe5ef8880b906fdcc45cb132d`.
- Only the two rearm changes were imported; its partial Smart Turn compile
  foundation was not imported. All 31 changed rearm app files match the reviewed
  repair exactly. The sole manual conflict was in the feature map, where both
  feature sections were retained.
- Exact combined source passes 109 focused JVM tests, 45 actual recorder/owner
  JVM tests and the 143-source Android API boundary compilation. The boundary
  uses documented native/model fixtures and is not a complete Android build.
- Exact combined source passes 37 Smart Turn JVM tests and five actual capture
  shadow-hook tests. No additional dynamic test of both callbacks together is
  claimed: current production follow-up capture supplies no shadow observer.
- Architecture and helper checks pass 109 Python tests plus 82 verification
  tests. The frozen frontend cases retain their independent expectations and
  unchanged tolerance. All 16 gzip fixture files were also regenerated exactly
  from deterministic/public PCM and signal processing, without a model.
- The benchmark repair separately passed 78 assertions using real Android 14
  Intent APIs and AndroidX 1.10.0, a category-removal negative control, and export
  UI/test45 API compilation. Its tested source is unchanged in this integration.

Combined receipts are in [evidence/integrated-latency-2026-10-09](evidence/integrated-latency-2026-10-09/).
Historical pre-integration receipts remain separately labeled. Four original
JUnit logs retain their trailing blank line so their recorded hashes stay valid.

## Release limits

Build1244 at `f783849de9cb6ebb2932eba52b7ebdc596aa02fa` passed 2,007 JVM tests,
signed assembly and the native 16 KB audit. All five Android profiles ran all 81
main journeys; their sole failure was the now-repaired OPENABLE assertion.
All five signed build1190 upgrades preserved data. Fold main runtime was
757.447 seconds within the unchanged 900-second budget; later phases were not
reached after the main-suite failure. The added Smart Turn journey makes the
next candidate require 82 main journeys; its device cost remains unmeasured.

Build1244 also failed the unchanged strict encoder reference: output `33bf34b9…`
on an AMD EPYC7763 host versus required `e51d19c6…`. This candidate changes no
encoder arithmetic, native oracle, or quality acceptance. The cross-host
numerical difference remains unresolved. No encrypted diagnostic workflow or
key material is included here.

The exact final revision must pass Android/NDK compilation, R8/linkage, all five
runtime profiles including actual 16 KB and Fold, signed data-preserving upgrade,
strict native quality, and receipt-bound release publication. Phone speech-end
to answer-playback timing, cutoff quality, and Smart Turn resource contention
remain separate validation needs. Browser admission remains closed.
