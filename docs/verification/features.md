# Feature and acceptance map

## Faraday main integration and rename repairs — 12 October 2026

[Integration evidence](faraday-integration-2026-10-12.md) retains Build 1295's
repository-rename failure: Sherpa's cached CMake build referenced the old
Jarvis-OS-V2 checkout. A repository-scoped native cache key prevents restoring
that non-relocatable build into Faraday. Application/native source, dependencies,
assertions and release gates are unchanged. The regression fails against the
old workflow. The same run's diagnostic admission also rejected Faraday because
two exact-name guards still pinned Jarvis-OS-V2. Their expected namespace and
synthetic fixtures are updated without loosening identity, cleanup, resource or
quality checks; negative-repository regressions and failure evidence are retained.
Exact-revision full release verification remains required.

## Discoverable timing detail and bounded benchmark navigation — 12 October 2026

[Timing presentation and API 30 repair](benchmark-timing-presentation-2026-10-12.md)
groups the 31 newly added preparation/Smart Turn measurements behind a labelled
expand/collapse control while retaining all measured and unavailable fields in
exports. Required test45 exercises repeated visibility changes, immutable
retained/exported data and the original scoring/review/export flows. Its API 30
NOISY lookup uses existing held discovery with unchanged bounds and tap checks;
build 1292 failure evidence is retained. Build 1293 passed all four phone/16KB
profiles; its Fold collapse check exposed a cached childless accessibility node.
The second bounded instrumentation repair requires fresh collapsed-label and
details-absence observations within the original five seconds, with no extra
action or removed assertion. Both failed artifacts are retained; full exact-revision
Android CI is required.

## Measured audio preparation overlap — 12 October 2026

[Capture and speech-span overlap evidence](audio-overlap-and-incremental-prefill-2026-10-12.md)
adds same-clock preparation/capture/speech intervals, actual Smart Turn backend
call edges and separate endpoint-decision/final-recognition stages. Missing or
invalid boundaries remain unavailable; internal pauses stay explicitly inside
the detected-speech span. Source review distinguishes current incremental audio
encoding from a future incremental language-model-prefill path and defines the
native ordering/cache parity gate before that experiment. No physical overlap,
phone speedup or new LLM audio-streaming implementation is claimed.

## Smart Turn on Whisper text capture — 12 October 2026

[Whisper Smart Turn endpointing](smart-turn-whisper-2026-10-12.md) enables the
existing retained-PCM worker on the ordinary CPU/QNN Whisper text path while
requiring authoritative final recognition. Whisper ASR segmentation no longer
disables a fresh PCM verdict; committed words, overlap uncertainty/recovery,
continued speech, playback guards, stale/deadline fallback and finalization
drain remain protected. Gemma-direct keeps its existing eligibility and caption
behavior; this change does not resolve its pre-existing caption/control coupling.
Recovery repeats hardware-drain checks so a new suffix cannot be sealed out while
the full-recording retry is running. The combined source-bound host suite passed
218 JVM checks and both Python helper suites
(124 and 101 tests). Full exact-revision release/Android gates and physical
model/audio/latency acceptance remain required.

## Gemma processing measurements — 11 October 2026

[Clocked Gemma processing diagnostics](gemma-processing-measurements-2026-10-11.md)
add send/callback/consumer/drain boundaries, guard-to-Piper/PCM/playback spans and
final-receipt encoder interval bounds. Existing early fixed-preface and retained
encoder API metrics remain separate. SDK's misleading first-recorded-turn TTFT
proxy is explicitly relabelled; native count/rate-derived executor-prefill and
whole-decode phases require successful checked idle. Cancelled/ambiguous/missing
observations stay unavailable. Focused JVM/API coverage does not qualify physical
latency, real-model execution or the integrated release candidate.

## Combined audio candidate — 11 October 2026

[Audio build fixture repairs](audio-build-repair-2026-10-11.md) retain the
Android compile failure and reflection-only correction, then Build 1273's
read-count-coupled fake-clock failure and semantic-boundary correction. Exact
assertions remain. Build 1276 passed all 2,240 JVM tests, then exposed Android's
automatic `.gz` asset inflation; opaque `.gz.bin` names fix the production
packaging contract without weakening integrity checks. Full release verification
must rerun at the repaired revision.

Optional [Snapdragon Whisper QNN](whisper-qnn-2026-10-11.md) with verified assets,
strict provider diagnostics and CPU fallback replaces the retired Moonshine option.
[first meaningful Piper clause](first-piper-clause-2026-10-11.md) delivery retains
later passage batching, while [early Gemma preparation](early-gemma-session-2026-10-11.md)
uses a real fixed system preface and checked lifecycle barrier. Host tests are
source-level evidence; Android release and Fold6 acceleration/latency qualification
remain separate gates.

## Same-host native encoder acceptance proposal — October 10, 2026

This local-only harness proposal uses the versioned
`same_host_original_static_stateful_observable_equivalence_v1` contract. The
unchanged pinned original static encoder and adapter execute independently from
the reconstructed stateful encoder. It requires bitwise equality of all 77 by
1536 valid post-adapter rows, exact masks/counts/EOA, all 131 output schema/value
checks and the supported invariants on all 98 state outputs. The historical
`e51d19c6…` digest is retained as a fingerprint diagnostic, with no whitelist or
tolerance. Newly computed cache rows have no fresh static reference, so the
contract expressly does not establish all-layer cache equality or cross-CPU
numerical identity.

Normal execution now binds live prerequisite witnesses and immutable files to
both independent full-E2B lanes. Request identities, positive counts, lifecycle
cleanup and exact embedding taps fail before another lane starts; full response,
count and public documentation phrase comparison remain mandatory. Release
binding requires the new contract and detailed oracle/input/lane evidence.
Historical failed or diagnostic receipts remain failed and cannot be promoted.
See [the native contract](../../scripts/streaming-quality/README.md) for precise
scope, invariants and unchanged resource/export limits.

Only synthetic Python fixtures and source inspection qualify this proposal.
Nonzero heterogeneous tensors exercise final-row and chunk-seam single-ULP
errors, reordered/dropped/duplicated rows, state/mask/count/EOA errors,
post-pin substitution, cleanup failures and downstream receipt rejection.
A finite final appended-cache mutation is an explicit passing counterexample to
any broader state-reference claim. No model, native rebuild, capsule, hosted
rerun, Android execution or new release acceptance is claimed by these tests.

## Smart Turn native endpoint control — 10 October 2026

The installed pinned model is enabled by default for eligible native audio. Its
COMPLETE verdict can close a current pause; CONTINUE suppresses early close until
speech resumes, a fresh bounded reassessment says COMPLETE, or at least 3500 ms
of sampled/classified quiet reaches fallback at an exactly covered boundary
(3500–4200 ms across existing read phases). Missing/loading/busy/
expired work adds no mandatory wait to ordinary endpointing. An explicit new
preference preserves a disable choice without inheriting the old shadow flag.
At this checkpoint ASR-text mode remained excluded. The 12 October extension
above enables Whisper while retaining playback/quiet and native-caption guards.

`SmartTurnAudioEndpointTest` exercises the actual collector, bounded worker and
PCM owner with synthetic acoustic/model outputs: 100 ms and irregular reads,
65/120/220/expired inference scheduling, concurrent ready/PCM ordering, full
retention, stale call/revision, continued/quiet speech, eight-pause budget,
caption skipping versus mandatory text finalization, and cleanup. Existing
Smart Turn, native-pause, capture-first and echo/final-verdict suites remain.
Journey81 checks default-on/disable/recreation separately from its companion
native probe with pinned temporary model input. Its [composition-safe settings
action](smart-turn-android-validation-2026-10-10.md#settings-composition-boundary-build-1264)
requires the exact label under the enabled tagged parent and re-resolves that
selector for one tap; source-extracted host/API checks are not Android evidence. No weights enter source, APK
or output artifacts. All five release profiles retain their existing deadlines.

[Endpoint policy, scheduling traces and coverage limits](smart-turn-endpoint-2026-10-10.md)
distinguish source/host tests from pending release JNI and actual phone evidence.
The [9 October shadow contract](smart-turn-recovery-2026-10-09.md) is historical;
the same retained worker and old-reply cleanup gate now support endpoint control.


## Capture-first rearm candidate — 9 October 2026

Successful playback transfers a byte-bounded retained recorder before optional
caption/native cleanup; exact input tickets protect the caption backstop and an
already-final Whisper snapshot can opportunistically recognize farewell without
new ASR work. The first candidate failed independent raw-onset, ASR-disabled-tail
and slow-storage race review. [The separate repair and callback ownership contract](capture-first-rearm-repair-2026-10-09.md)
record the fixes and source-bound tests/API compilation. Independent re-review
cleared the repair; [combined source checks](integrated-latency-candidate-2026-10-09.md)
pass. Full signed Android/native release gates and phone latency validation remain.
[Original candidate evidence](capture-first-rearm-2026-10-09.md) remains historical.

## Idle native-caption finalization — 9 October 2026

Eligible clean ordinary Gemma audio capture skips a new endpoint-final Whisper
decode only when the existing worker is already idle. Busy, short, quiet,
playback-tail/unknown-timing, segmented and text-ASR cases retain final recognition.
No endpoint threshold, native pin/oracle, recognizer count, authoritative barge
verification or resource-return barrier changes. This is a partial optimization;
it does not remove cold preparation, busy decoding or cleanup waits.

[Eligibility, timing, ownership and verification](idle-native-caption-finalization-2026-10-09.md)
describes deterministic worker/capture/PCM and preserved-regression coverage.
Exact-revision hosted release verification and actual device/model latency remain
pending; host policy tests do not verify an APK or acoustic quality.

## Audio/Muse integration candidate — 9 October 2026

Latest terminal Build 1244 passed signed assembly and the native 16 KB audit,
but failed the strict encoder reference and the benchmark document OPENABLE
contract. The latter is repaired in the current candidate; exact-head release
verification remains required. The earlier CameraX 1.3.4 alignment failure and
its consistent 1.4.0 repair remain documented as historical evidence.
[Failure and artifact preflight](audio-muse-integration-2026-10-09.md#build-1233-camerax-native-compatibility-repair)
retains the source/run, hashes, compatibility checks and verification limits.

The integration combines verified audio source `a86c3cc439b550c8bbb52bf223d81458ae6066b4`
(Build 1190) with verified Muse source `e413a89f9249e47484db62d18e4db8d11f190d54`
(Build 1229). Those independent results do not verify this combined revision.
The current named contract requires **82 main journeys**: tests 01–81 and 90.
Audio's Wisp journey remains test49; the camera-farewell journey is test80 so
both have unique execution identities. The latter retains the notification
grant and cleanup assertions without revoking its own live process permission.

Required preserved behavior includes checked native turn/drain/quarantine and
confirmed-history ownership; atomic conversation/model-test admission; durable
startup and workflow occurrence barriers; Wisp journal observation; private
credential/task/export handling; call-scoped camera cleanup; and the closed
browser runtime gate. Model details combine observed speed with model-file- and
suite-bound tool fixture agreement, retaining both sets of caveats and expanded
evidence. The full existing audio native pins, strict numerical oracle,
five-profile matrix, actual 16 KB/Fold checks, receipt binding and signed
previous-APK upgrade without clearing user data remain authoritative.

[Integration acceptance and verification status](audio-muse-integration-2026-10-09.md)
records the reconciliations and remaining gates. No combined APK is verified
until the exact candidate finishes the complete hosted release workflow.


## Fixed Memory navigation correction — 9 October 2026

Release tests 24/27 now observe only the source-proven fixed Memory controls
without body swipes. Their full 300 ms stability check, safe/enabled fresh-node
checks and one 15-second preparation/admission deadline remain; post-diagnostic
node/window/geometry revalidation precedes one physical tap, with no action
retry. Search retains text assignment/conditional keyboard dismissal, and test27's
final header-Back preparation remains observation-only before system Back.
All content scrolling, generic helpers, existing assertions/evidence and release
budgets are unchanged. [Scope, proof and limits](fixed-memory-navigation-2026-10-09.md)
records 61 exact-helper controlled-boundary checks and real-API focused
compilation; neither verifies an APK or promises the full Fold budget repair.
The exact-revision hosted Android gate remains pending.

## Video notification queue serialization — 9 October 2026

Build 1240, source `18ef4d118c2e5ce44a795871a2bb430d3c7c4a29`, again failed
unchanged test80 on the API 36 normal phone: the stopped service's idle
notification remained. Pinned AOSP reveals a source-supported direct-notify race:
foreground flag admission can precede system stop while NMS enqueue follows its
cancellation, and app cancel cannot remove that foreground-flagged record. The
exact historical callback/interleaving is not established by the retained logs.

Live refresh now uses the existing service token's three-argument foreground
route with its exact accepted startup type. A specialUse fallback cannot imply
camera eligibility after a later grant. Closed/generation/call-owner guards and
explicit final cancellation remain; update rejection revokes first, then queues
idempotent video-only cleanup outside the publication lock. App-owned terminal
stops close before stopping; platform teardown exceptions cannot escape to kill
audio. Replacement registry removal is also owner-checked.

[Detailed source evidence and local checks](video-notification-serialization-2026-10-09.md)
cover 116 focused host tests, the asynchronous AMS/NMS two-queue negative control,
256 deterministic queue schedules, six mutation controls, accepted types,
rejection and unresolved-cleanup ownership, shipping-route binding and limited
Android API-signature/bytecode validation. Test80 and all release gates remain
unchanged. Exact-head Android release verification is still required.

## Initial video notification shutdown repair — 9 October 2026

The following records the earlier repair; the queue-serialization update above
extends it after the build 1240 recurrence.


[Build 1236](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37887424893),
source `a797ec1499b307cdeb9a53d490031a770df9ae0f`, failed release test80 on the
actual API 36 16 KB profile: the service was gone but its idle notification
remained. All 81 main tests ran; the signed 1190→1236 upgrade retained data.
The captured logs show foreground stop, a later posted-notification event and
an ID 482 enqueue-record warning; they do not identify the exact historical
callback ordering or establish a cause in the answer-only speech change.

`VideoNotificationLifecycle` now serializes notification posts with terminal
shutdown. It revokes publication before camera cleanup, then removes foreground
state and explicitly cancels the exact ID, even if cleanup or foreground removal
throws. Late detach/farewell callbacks cannot republish. Replacement-service
identity and Android main-thread lifecycle ordering protect a newer notification.
Capture generation, failed-detach ownership and the active audio engine are
unchanged. This addresses the platform interval in which foreground state is
already removed before app `onDestroy`; a racing ordinary notification would
otherwise survive a later foreground-only removal (see Android 16
[`ActiveServices`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActiveServices.java),
`bringDownServiceLocked` and `setServiceForegroundInnerLocked`).

Local regression evidence:
- Eight tests run the exact Android-free production notification owner, covering
  retained ordinary notifications, in-flight posts, synchronous/asynchronous
  cleanup callbacks, shared-ID replacement and cleanup/removal failures.
- Negative controls fail when explicit cancellation is omitted (7 failures),
  closure moves after camera cleanup (2), or post/revocation serialization is
  removed (1). These are controlled mutations, not claimed historical reruns.
- The focused camera/controller/admission/farewell/frame suite passes 102 tests,
  including a real-controller failed-detach regression that retains
  `CLEANUP_PENDING` and its call ID, blocks notification reposting and permits
  the existing cleanup retry. The host-only CameraX extraction excludes Android
  constructors, YUV conversion and platform binding; it is not Android compilation.
- Architecture plus 102 general Python and 82 verification-helper tests pass.
  Release test80, its no-resurrection assertion and its existing timing/permission
  setup are unchanged. Exact-head release compilation and the full Android
  matrix remain required; no repaired APK is verified by these local results.

## Answer-only spoken turns — 9 October 2026

Normal turns and accepted-action follow-up speech no longer request, load,
synthesize or play canned waiting acknowledgements. The output API has no cue
opt-in. Recognition, reference lookup and answer generation no longer invoke
spoken-wait callbacks. The removed sentence-gap cue branch never received a
sentence-end marker from the existing whole-passage producer; removing it does
not change active sentence buffering. Nonverbal wake/ready tones are unchanged.

The normal output retains its eight-passage PCM queue and follow-up reports retain
two. Piper text boundaries, model `silenceScale=1`, native-speed playback,
startup-headroom policy, complete PCM including natural pauses, actual-playback
captions/echo references, delivery ledger, cancellation and native/drain ordering
remain unchanged. Ordinary answer text is not filtered for phrases formerly used
as canned cues. Legacy cue helpers/cache files are dormant; no model, preference
or stored user data is deleted.

Acceptance and local evidence:
- `scripts/test_voice_answer_only.py`: six source-contract checks prevent live
  normal/follow-up/runtime/conversation dependencies on the retired cue paths,
  preserve queue capacities and synthesis/pause/lifecycle wiring, and exercise
  reintroduction failures. The original `e91369a` source fails the cue contract.
  These are structural regressions, not a Kotlin compiler or acoustic test.
- `SynthesizedSpeechPcmTest`: model PCM with a 278 ms interior sentence pause and
  leading/trailing silence retains every sample at 16, 22.05 and 24 kHz.
  `PiperTextStreamTest` preserves actual answer words and produces none while
  waiting without text. Existing queue, startup, clock, drain, captions and
  delivery-ledger checks remain required. The focused real-source JVM run passes
  53 tests using Kotlin 2.3.21/coroutines 1.9.0, without Android/native stubs.
- `python3 scripts/dev.py check` passes the architecture guard, 102 helper tests
  and 82 verification-harness tests, including the new source contracts.

Full release compilation/JVM/native checks, signed APKs and the exact-revision
five-profile Android gate remain pending. Physical/model playback and measured
latency improvement require a new real-call comparison; the previously observed
acknowledgement wait is not a promised fixed saving on every turn. No APK is
verified by these focused checks. See [answer-only voice acceptance](voice-audio-and-metrics.md#answer-only-speech).


## Android model-free timing handshake proposal — 7 October 2026

The existing five-profile layout `test04_nativeLibrariesLoadAtExpectedPageSize`
adds a dedicated non-Looper worker that calls the shipping public
`NativeAudioOwner.createOnWorker` with a test-only lease. The reviewed SDK runs
three timing-v1 JNI handshakes and validates their schema before the first lease
check. That check throws a unique sentinel; success requires that exact instance,
exactly one lease check, zero immutable-path reads, no returned native owner and
a terminated worker. Missing JNI symbols, rejected schemas, another exception,
unexpected artifact access and timeout/interruption fail the existing test.
Its library/page-size and real installed-APK asset checks remain required.

The worker has a five-second join and at most one one-second interrupt/join
cleanup. A still-live worker fails; Java cannot safely terminate a wedged JNI
call, so process teardown remains the outer instrumentation/controller boundary.
The existing native JSON gains a bounded fixed-field `timing_preflight` object,
and its overall pass predicate also requires the new probe. Existing broad SDK
release keeps cover the public ABI; no production source, SDK API, new layout
scenario or fake JNI is introduced.

This isolated proposal has not run on Android and does not qualify Build1174.
A narrow helper-only Kotlin check passes against the actual Build1174 AAR
classes and Android 15 framework headers; the complete Compose test-file compilation,
minified release/test APK build and exact next-candidate five-profile execution
remain required. Even an Android pass proves only handshake/schema compatibility:
it does not prove usable clock calibration, timing snapshots, native owner
lifecycle, model inference, physical audio or device performance. The explicit
`UNVERIFIED` clock contract and receipt preserve those limits.

## Failed-reference decoder diagnostic proposal — 7 October 2026

The hosted candidate explicitly selects the otherwise default-off
`run_quality.py --diagnostic-on-known-reference-mismatch` option. It may enter the existing bounded Conversation pair only after the exact known
historical encoder reference failure and freshly verified same-run row/EOA
equality. Pure Python tests cover a successful diagnostic preserving the strict
failed receipt/error/exit, rejection of copied/edited/stale prerequisites, other
numerical/identity/cleanup/resource failures, all emitted-output validity and
supported state transitions, bounded public export, and release binding refusal.
These are host harness logic tests, with injected process/model boundaries.

Strict success behavior, reference hashes, native source, resource profiles and
release acceptance are unchanged. The workflow opt-in adds diagnostic evidence
while preserving the failed quality job and blocked release. All 98 state files
can be validated for schema/finiteness/history/retention; newly computed cache
rows lack a fresh observed-static `raw_00` through `raw_11` oracle. Structural
validity does not prove cache equivalence. No native/model execution, hosted
decoder pass, phone-quality claim, APK verification or Build1174 change follows
from this proposal. Exact-candidate execution remains a separate review step.

## Hosted full-E2B context and resource control — 7 October 2026

The selected CPU prefill128 graph has a fixed local-attention update width639,
which cannot fit the old context512. Context640 is the smallest128-aligned
control and adds1.125MiB to one int8 KV-input bank. Sparse pinned graph/source
inspection and the retained context640 projected-only run establish that narrow
cause; they do not prove long-context or Android/GPU behavior.

The earlier local projected control consumed all77 reference rows exactly and
completed the read-only transcription in19.764s, peaking near3.37GiB RSS and
4.70GiB address space. Its matched raw control failed the3.5GiB RSS watchdog.
The original4GiB mapping failure and both later resource-failure receipts remain
retained. A matched4GiB-RSS local pair is prepared but has not run because its
6GiB admission threshold was not met. No passing pair or hosted result is claimed.

The hosted control gives only the hash-bound full-E2B Conversation probe a
fixed6GiB address-space/4GiB aggregate-tree RSS/6GiB effective host+cgroup
admission profile. Both fresh lanes use context640 and identical limits, keeping
one CPU,240s wall/239s-soft240s-hard CPU,128MiB file cap,1GiB live reserve,
max64 output and no persistent model cache. Existing prerequisite, compiler,
diagnostic and workflow limits remain unchanged. PID-identity/subreaper cleanup
covers escaped descendants; persistent uncertainty blocks all later model work.
Model output is kept separate from compiler diagnostics.

Acceptance remains fail closed: pinned encoder reference hash, original/streamed
and EOA byte gates, exact raw/projected identity/response/count checks, the public
semantic reference and checked drain/delete are unchanged. Tiny synthetic-process
checks cover resource refusal, whole-tree RSS, escaped children, cancellation,
CPU/file limits, cleanup exceptions and receipt failures. They are not a native
probe rebuild, model inference, Android validation or verified release. The exact
hosted candidate and full release gates remain pending exact-candidate execution.


## Encoder diagnostic capture evidence — 7 October 2026

Build 1158 completed the host probe compiler and repeated the same historical
projected-tensor hash mismatch. Its optional runtime capsule collector failed
after about four seconds with positive process cleanup, but the private child
log was outside the evidence allowlist. The available receipt does not establish
whether its tests, preflight or metadata collection failed. The strict encoder
and full-model quality acceptance predicates remain unchanged.

The diagnostic guard now writes a bounded public status before preflight and
records fixed phase/error codes, reviewed source module/line, exact producer
identity, child exit, resource outcome and checked cleanup. It never exports raw
exception text, arguments, paths or private logs. The explicit evidence exporter
retains that strict 8 KiB schema; final release binding requires the same quality
producer and complete verified cleanup. Capture itself remains optional, and
uncertain process ownership still blocks model admission through the existing
hash-bound cleanup latch.

A separate source defect was reproduced locally: the old normalizer rejected
valid in-root parent segments in GCC and library headers. The repair chooses the
original trusted namespace before canonical resolution and preserves confinement
to the named repository/system root. Named Bazel virtual includes may resolve
only into that repository's source or owned generated-output subtree. Sixteen
regression tests cover valid forms and private/cross-repository escapes.
Replaying the retained 3,934-header graph accepts 3,805 approved-root sources and
continues rejecting 129 unapproved local compiler-root headers. This proves the
source repair, not the cause of the lost hosted failure or successful capsule
collection. Exact hosted capture and numerical diagnosis remain pending.

## Preserve repaired sampler bytes through APK packaging — 7 October 2026

Build 1158 passed the SDK producer, its native audit and all 1,471 JVM tests, then
produced both signed APKs. The independent final-package audit rejected both
samplers: the added read-only metadata LOAD segment's file offset changed while
its virtual address stayed fixed, breaking the required 16 KB congruence. The
APK samplers also lost debug/symbol sections and no longer matched the reviewed
SDK output hashes. These APKs are not qualified for native phone testing.

The app now preserves debug symbols for exactly the two repaired sampler names.
AGP's documented `keepDebugSymbols` copy path avoids the second strip operation;
all other native libraries retain their existing packaging policy. This retains
additional upstream symbol bytes and may increase APK size. A bounded final ZIP
check now requires both APK sampler payloads and modification notices to match
the exact producer and reviewed output pins before artifact upload. The final
release receipt repeats that check independently of the earlier package result.

Sixty-nine SDK helper tests pass. Both actual signed Build1158 APKs are rejected
by the new check, while stored and compressed ZIP fixtures containing the exact
reviewed sampler bytes pass. These fixtures are not signed app builds or Android
execution. The new candidate still requires full APK assembly, unchanged 16 KB
and device loading gates, numerical/model quality and final release binding.



## Native audio timing and ASR prefix reuse candidate — 7 October 2026

The isolated next candidate adds `First audio → speech` to completed native-audio
reply metrics. Its start is actual validated native PCM admission, not microphone
capture, enqueue or append return. Its end is the first nonsilent answer playback
head event, excluding filler. The span includes remaining capture and does not
backdate retained pre-roll. TTF-SW remains the last detected speech-to-playback
observation. Nullable JSON/CSV fields carry clock interval bounds, native step/row
counts and the retained pre-roll count; they contain no PCM, text or raw clocks.

A final snapshot proves exact work before endpoint only when its first/last step
bounds establish that count; otherwise it reports a lower bound or unavailable.
Only the final candidate after successful seal and checked close may publish it.
Clock mapping is qualified by the reviewed AOSP API30–36 implementation-family
assumption and bounded runtime calibration, not installed/OEM binary attestation.
Artifact preparation has separate start/finish stage offsets so cold encoder
reconstruction is visible before first native admission.

The trained C++ timing harness completed six encoder steps before its paced
fixture endpoint, then sealed seven steps and 77 rows bitwise equal to the pinned
reference. That is host encoder evidence, not Android JNI, microphone, LLM/Piper
or phone latency evidence. After rebasing onto the Build1172 asset/startup repair,
a fresh separate-module check compiles 16 production SDK Kotlin inputs, 93
selected app/test Kotlin sources and the real Java recipe reader. All 161 focused
JVM tests pass, including six asset-reader and five terminal voice UI regressions;
the fresh SDK satisfies the exact 104-class allowlist. The 86 SDK/package helper
checks and architecture guard also pass. The earlier real normal JNI timing
harness passes 42 checks, but is not a fresh Android or rebased-candidate native
build. Five changed runtime-stage files remain outside the focused compilation
closure. Full runtime-stage compilation, APK assembly, Android loading and
device/model gates remain pending.

The reviewed ASR prefix fix shares the exact compact/full instruction prefix and
appends final-request quoted memory/continuity/capture context afterward. Twenty-two
focused JVM tests cover valid reuse, genuine mismatch rebuild, cancellation and
success-only prefill completion observations. Persisted prefill completion fields
use signed offsets from the observed input seal request; they are explicitly
distinct from speech end and accepted endpoint, and absolute clocks stay private. Neither those tests nor native
timing establishes which voice path is faster. The ordinary paired replay seam
and its private-state lifecycle remain outside this candidate pending their
complete runtime compilation and release ABI checks.


## Streaming sampler dependency checkpoint — 7 October 2026

Build 1135 produced signed normal and compact APKs and passed all 1,471 release
JVM tests. All five Android profiles passed their 76 main journeys and the
preserved-data upgrade from Build 1106. Each profile failed native library
admission because the shipped TopK sampler could not resolve
`kLiteRtRuntimeBuiltin`. No verified release was approved.

The next candidate adds a pinned, metadata-only dependency derivation for both
samplers, retaining their original code/data/relocations and RELRO while making
new dynamic metadata read-only. Producer checks and consumer provenance require
the exact tool/input/output hashes. Twenty-four actual-binary derivation/mutation
checks and the unchanged 16 KB ELF audit pass locally; exact-candidate Android
loading, model inference and full release gates remain pending.

The hosted encoder oracle still reports same-run static/stateful projected-row
and EOA bitwise equality, but its historical full-tensor hash differs. The
numerical acceptance check remains unchanged. A separate, bounded weight-free
runtime diagnostic artifact is added to establish the actual compiled runtime,
source/tool flags and public license obligations; failure to capture it never
substitutes for quality evidence. It contains no model weights, audio, activation
values or private user data. Full native Conversation quality and Fold6 latency
remain unverified.


## Packaged SDK test boundary and oracle diagnostics — October 7, 2026

Build 1133 (head `bc7a6201`, tested merge `19b5e6b2`) passed the reviewed
ARM64 SDK producer and production app Kotlin compilation, but release unit-test
compilation rejected two calls to the SDK-internal `Content.toJson` method.
No JVM release tests or APK upload completed. The app routing test now checks the
public sealed value's identity and metadata. The full copied-byte/serialization
assertions run in a separate SDK-owned friend test module against the actual
production `classes.jar`, before AAR packaging. Its classes stay outside the
explicit production allowlist, and its bounded receipt binds the source and jar.
The exact Build 1133 jar passed 106 focused app tests with no SDK friend access,
and 13 packaged SDK serialization/immutability checks. Whole hosted release
verification is still required for the repaired revision.

Build 1132's host probe compiler completed in 2444.904 seconds. Frontend PCM/Mel,
stateful counts/masks, original-versus-streamed post-adapter byte equality, and
learned end-of-audio equality passed. The later comparison to the earlier local
complete projected-row SHA failed. Neither full Conversation lane ran. Local
one-thread and two-thread encoder replays both reproduce the earlier hash, so
thread count does not explain the hosted difference. Its cause and magnitude
remain unresolved. The quality harness now preserves booleans and actual/expected
output hashes before raising that same strict failure, plus bounded runner CPU
metadata. It uploads no rows, weights, audio or activations and changes no numeric
threshold, expected hash, process cap, or required release gate.

## Streaming Gradle configuration repair — October 7, 2026

[Build 1132](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37572005554)
passed the SDK producer, recorded speech and upgrade-baseline jobs. Its APK job
then failed while compiling the Gradle Kotlin DSL, before app compilation or APK
output: appending a string list with `+=` to the heterogeneous validator argument
list was resolved as an invalid reassignment. The repair uses explicit `addAll`,
preserving the same three workflow/provenance arguments. A standalone check of
the verbatim mutation lines reproduced the original compile failure and compiled
and executed the corrected form; this does not replace the hosted Gradle build.
Full-E2B quality was continuing independently when APK configuration failed.

## Parallel streaming build candidate — October 7, 2026

[Build 1127](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37560739072)
tested head `8ee1d21bfe4852c1c8b57acc86714b2404c0a637`, merge
`1d840ad575fe420e1dc60852e3c6ca41238daa61`. Its ARM64 SDK, dependency closure
and 16 KB audit passed. Linux probe compilation timed out at 2,700 seconds after
6,416 of 6,477 actions, with continued progress, sampled peak tree RSS of
1,904,570,368 bytes and verified cleanup with zero surviving processes. Full-model
inference, signed APKs and device gates did not run. Separately, the unchanged
recorded-speech job failed to resolve pinned NumPy before any speech inference;
that dependency failure remains unresolved, not a speech-quality result.

The scoped repair admits four compiler CPUs on the existing runner with measured
headroom and a 60-minute compiler deadline. The 6 GiB RSS, 2 GiB reserve and all
model subprocess limits remain unchanged. The successful Android SDK producer
now uploads its authenticated source receipt and AAR before independent quality
and signed APK jobs run in parallel. Their 45/115-minute producer/quality budgets
retain the combined 160-minute ceiling. A fresh pinned SDK checkout must reproduce
the producer's reviewed source and overlays; a local proof matched all 1,206
source hashes against the retained producer snapshot.

Early APK artifacts explicitly describe their upload-time unverified status.
Final receipts and every release publisher still require successful full-E2B
quality and all existing gates, with independently checked SDK/quality attempts,
artifact IDs, source-receipt hashes and both APKs' embedded provenance. This
candidate changes build orchestration only; ASR repairs and new timing metrics
remain separate. No Android/model success is inferred from helper tests.

## Hosted streaming compiler checkpoint — October 6, 2026

[Build 1117](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37538648794)
tested head `139b640fac74c9ec7c5735b3ae24eb33aa62acfa`, PR merge
`e038cb829a930d270ac501b44245c4bd3c32498a`. The reviewed ARM64 SDK compiled
and packaged successfully: all nine SDK libraries passed the 16 KB ELF audit,
the normal owner JNI exposed the six required methods, and its recursive
dependency closure had no unresolved non-system libraries. The resulting AAR's
recorded SHA-256 is `0eb94821a6a666252531b74ceb53ef7c1ff0fee4f4e845c595f1484134e8bba6`.

The later Linux quality-probe build exhausted its 2,700-second compiler budget
with one compiler job. This was not a model-quality result: full-E2B execution,
signed APK assembly and all Android device/release gates were skipped. The
retained timeout receipt and source snapshot prove the timeout; the original
exporter omitted compiler output, so they do not prove whether it was still
making progress. The scoped retry retains the same compiler/model time limits,
admits two compiler jobs only after observed CPU/RAM checks, and retains bounded
compiler-only diagnostics with checked process cleanup. It is a candidate
repair until the exact revised hosted build passes.

## Native incremental E2B audio candidate — October 6, 2026

Implementation candidate, not yet an Android-verified feature. The ordinary
direct-audio path for the exact pinned E2B bundle reconstructs a stateful encoder
locally from 220,198 bytes of audited structural assets. No trained model weights
are added to APK assets. Imports with a different verified fingerprint fail closed.
The accepted pre-roll and retained PCM go to a bounded dedicated encoder worker;
capture join, full PCM count/hash, generation identity and checked native close
precede sealed Conversation input. Captions and provisional audio remain unable
to authorize actions; existing manual tool and complete-plan admission are intact.

Host evidence includes actual pinned native encoder byte parity, 54 injected JNI
lifecycle checks and 14 actual JVM/native speech checks. Focused capture/worker
tests cover exact PCM, candidate resets, capacity failure, cancellation and retained
ownership. These do not prove Android library loading or language-model quality.
The checked Conversation/legacy Session ownership changes must additionally pass
their exact-source tests and the complete release matrix.

The focused host JVM source set passes 106 tests, including the capture/worker
and checked-engine regressions plus twelve new lifecycle/input checks.
`RetainedPcmEncoderLifecycleTest` latches factory, seal and close operations:
cancellation cannot publish completed content, discarded candidates receive a
fresh owner only after checked close, and a failed discarded-owner close remains
quarantined. `ConversationSealedAudioInputTest` exercises the actual immutable
SDK content and Conversation input router: initial/retry requests retain the
same sealed value, incompatible text/non-direct inputs fail, unsupported sealed
submission never falls back to raw audio, and existing raw audio/ASR/attachment
routes remain intact. These tests inject fake native encoders/backends and
synthetic embedding rows; they do not run native inference or an Android runtime.
The acquire-to-`withContext` cancellation window, ordinary exact-E2B eligibility
gate and finalizer-to-model-lease quarantine were source-reviewed, not executed
as complete preparation/finalizer integration tests. Those gaps remain open.

Acceptance gates still open: production ARM64 AAR build/dependency closure and
16 KB audit; signed normal/compact release builds; all five required emulator
profiles; actual Gemma static-versus-streaming transcription/comprehension/tool
quality; native input/output consumption; physical Fold6 latency, memory, thermal,
echo and capture-while-Piper-playback behavior. The first path excludes explicit
static comparisons and prerecorded correction buffers. It does not overlap LLM
prefill with capture or claim a jointly full-duplex language model.

## Muse tools integration checkpoint — October 6, 2026

The integration of Muse `9f965bad` retains all audio-pr2 verification boundaries:
Android 11/API 30 minimum, the five required profiles including actual API 36
16 KB, pinned Pixel Fold provisioning, and every upgrade, process-loss,
permission, platform, layout, native and receipt phase. The active controller
and receipt remain `android.py` and `receipt.py`; older parallel `_full` copies
and the retired API 29 software-emulator path are not reinstated.

The main contract now contains 76 unique journeys: `test01`–`test75`, plus
`test90`. Wisp retains `test49`; Muse tools occupy `test50`–`test75`. Launch
journeys retain the real foreground-visibility assertions and distinguish a
submitted/unconfirmed request from a verified opening. Screen fixtures include
the current content fingerprint so stale-window/content checks remain active.
All existing deadlines are unchanged. The independently shrunk instrumentation
boundary preserves both Wisp and Muse tool classes and top-level function facades.

SDK-free checks validate the helper contracts and source/contract correspondence.
This merged candidate still requires exact-revision release JVM tests, signed
normal/compact builds, and the complete Android/release receipt gate. Controlled
screen bridges and provider fixtures do not establish real accessibility-service,
third-party AppFunctions/MCP, model-selected tools or physical-device coverage.
The notification journey checks posting; its conditional DND setup does not
establish that DND was active, and it does not measure acoustic silence.

## Approved Wisp expressions and independent speaking face — October 9, 2026

The approved mouth is 2× the original width, with a 1.65× speaking opening and
1.5× resting-smile curve height, matching the selected design preview. The cyan
silhouette, eye size, anchored header, call-only viewport growth and 110 ms
playback-envelope smoothing are retained. This is a presentation change, not a
Piper timing, pause, synthesis, native inference or camera change.

`WispPresenter.audioActivity` projects the real call/audio owner separately from
`WispPresentation`'s task body. A running tool, approval or unknown-outcome card
cannot suppress an actual speaking mouth. Reference cards remain visible while
speech/listening overlaps them. Explicit interruption/end rejects a stale
speaking phase; a real listening/restarted-speech phase restores its own envelope.
Changing audio owners discards the previous interpolation rather than leaking
playback amplitude into microphone ripples. Zero/invalid amplitude never creates
syllables; silence uses the resting smile. Reduced motion disables mouth/body
animation and restores static poses without affecting actual playback.

The decorative set maps only existing observations:

- Listening leans toward the user with actual microphone ripples.
- A brief nod acknowledges a newly observed admitted conversation operation.
  It is not a success checkmark. Initial/historical observation, recomposition,
  foreign conversations, a returning reference lease and stale operation IDs
  cannot replay it.
- Thinking retains a slight tilt with softly gathering internal light.
- Actual reference-read observations show layered cards and a scanning lens.
  They do not claim a search has run. A RUNNING `screen_observe` displays a page;
  there is no separate document-read producer, so document reading is not claimed.
- RUNNING `screen_tap`, `screen_scroll` and `set_volume` have distinct target,
  scrolling and slider illustrations. Other tools retain a neutral activity tile;
  none gets a fictitious volume adjustment inferred from its public label.
- Queued/ready/resource-waiting/paused tasks show a patient clock/float with their
  existing authored status, never a countdown, percentage or invented ETA.
- Approval retains amber/open-palm decoration. Only the existing explicit task
  controls can grant approval; the character has no click or action authority.
- Fresh verified success gets one small bounce; failed/unknown outcomes retain
  the puzzled/coral pose and existing task controls. No retry is dispatched.
- Interruption closes the mouth immediately and reflects listening only when the
  actual call owner rearms. Idle keeps gentle blink/float with no status text.

Status privacy filtering, lock/background hiding, conversation scoping, lifecycle
suspension and system animation-scale handling remain in their existing owners.
`WispMotion` holds pure geometry/one-shot policy; `WispReceptionTracker` tracks only
transient admission identity. No storage format or action authority changes.

Coverage added: `WispPresentationTest` exercises independent speaking/tool/error/
approval ownership, exact request-kind props, waiting and stale audio precedence.
`WispMotionTest` covers the selected dimensions, reduced-motion policy, bounded
one-shots, silence/invalid levels, receipt nod fencing and conversation switches.
Release test49 retains all previous assertions and adds actual screen-read/tap/
scroll/waiting captures and repeated speech-stop while the tool remains RUNNING.
It enables animator scale only for its fixture, captures a zero-scale transition,
and restores the original setting in `finally`; no suite timeout or sleeps change.
No new test-Dex app APIs are referenced, so the existing narrow WispPresence keep
rule continues to own minified linkage. The 81 named scenario contract is unchanged.

Exact revised signed/R8/Android gate and screenshots remain required. Any local
Compose/Skia drawing-kernel renders are source-render evidence, not Android
screenshots or physical-device/Piper synchronization validation. Real acoustics,
TalkBack, OEM timing and device performance remain explicit device-signoff gaps.

### Build 1238: in-flight animation-scale transition gradient crash

Build 1238, source `4525bb4498b500cefc1a68fc41966c86be1d48b9`, passed
all 1,877 release JVM tests and signed/minified assembly, but API 30 and API 36
actual-16-KB test49 crashed after the first 48 journeys. The retained reduced-motion
capture precedes scale restoration; the native `LinearGradient.nativeCreate`
exception follows 31–34 ms afterward. Exact R8 mapping resolves the failing
`drawPath` to Wisp's time-dependent ribbon gradient. API 36 normal completed the
81 journeys on that same source; this does not invalidate the transition race.
Run: https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37897391259.

The duration-scale local was delegated Compose state, read again inside an
in-flight `withFrameNanos` callback. The settings observer can publish zero before
recomposition cancels the old effect: elapsed / zero becomes infinity, then the
clock modulo becomes NaN. Reduced motion hides the clock with time zero; restoring
motion exposes its poisoned value as a non-finite shader endpoint. A real Compose
state/native-Skia reproducer independently reproduces this sequence against the
pre-fix drawing kernel. Desktop finite-input renders and API-only compilation did
not cover this runtime transition and are not sufficient Android verification.

The repair snapshots a plain finite scale per effect, uses bounded finite frame
increments/clock recovery, and normalizes time/audio at the drawing boundary.
JVM regressions include the previous arithmetic failure, invalid/subnormal scale
and clock inputs, and repeated 1→0→1 transitions. Test49 retains the failing
transition and every existing assertion; a fresh, bounded after-draw observer
waits for each requested motion state before capture, rather than accepting a
shell setting readback as proof that Compose processed it. The observer is nullable
and unused by production callers, has no UI semantics or action authority, and
uses the existing preserved WispPresence test-DEX entry point. Setting restoration
and original-error preservation remain unchanged. No scenario, acceptance gate,
suite limit or deliberate delay is removed or relaxed.

The complete revised signed/R8/Android gate is required again. A repaired native
Skia stress run is supplemental finite-input evidence, not an Android pass.

### Build 1239: committed-window Wisp capture boundary

Build 1239, source `fb4499cddaaf6b6172b0d232c8c144cbbc3c3fde`, passed the
four phone-profile main suites, including the repaired scale transition. Actual
API 36 16-KB evidence nevertheless exposed stale auxiliary PNGs: the
SpeakingWhileScrolling XML already said “Scrolling the screen,” while its PNG
still showed the preceding “Speaking” pose without a card. The reduced-motion
PNG still showed an open mouth after the false after-draw acknowledgement.
Thus a passing semantics predicate and a completed Compose draw are not proof
that the system screenshot has reached the matching submitted buffer.

Only test49's auxiliary Wisp pose captures now use a hardware frame-commit callback
followed by native `PixelCopy` of the fixture app window. A fresh invalidation
also produces a committed frame when reduced motion has stopped the clock. One
5-second absolute deadline covers UI scheduling, commit and copy. Unsupported
rendering, changed/detached targets, changed geometry or copy errors fail explicitly;
there is no screenshot retry, sleep, old-image fallback or acceptance relaxation.
The destination uses the decor's full logical dimensions and a matching source
rectangle; Android's Window overload accounts for surface padding. These PNGs
are app-window pixels, not a claim to capture system bars or other windows.

The helper is in the release instrumentation source set, not production. Timeout
cancels delivery, never an in-flight bitmap write; the late PixelCopy callback
owns recycling if delivery was cancelled. A completed-but-unclaimed timeout race
also releases its bitmap. Pending commit callbacks are removed on the UI thread.
Successful PNG encoding retains caller-owned cleanup. The separate task-warning
dialog keeps its original whole-display capture, as does the generic per-test
capture. Existing PNG/XML names, exports, assertions, scale transitions, cleanup,
81-scenario contract and 900-second suite limit remain intact.

Android documents this boundary explicitly:
[frame commit callback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))
and [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy).
API compilation and ownership-model tests do not establish a device pass. The
next exact-head gate must inspect the actual PNGs and measure capture/total suite
time, especially on the Fold profile; no timing improvement is assumed locally.

## Truthful Wisp activity text — October 6, 2026

The permanent character now has a bounded, two-line text box only while there is
actual work or a real waiting state. Idle removes the text node entirely, including
its accessibility semantics. Persistent journal storage warnings remain in the
existing task panel/banner; Wisp's idle warning pose does not echo them as activity.
Fresh completion/error receipts retain their existing bounded display lifetime.

Activity labels are free-form public sentences, independent of the finite pose
categories. The admitted conversation owns a progress lease; actual reference
reads temporarily supersede it and return to the latest admitted stage when done.
Lease identity plus monotonic event sequences reject stale, duplicate, reordered,
cancelled and old-turn callbacks. Reads inherit the accepted conversation identity,
so navigating to another chat cannot relabel old work as belonging to the new chat.
The current stages are emitted at real coordinator boundaries, with no new model
invocations. Answer tokens, private reasoning and diagnostic prompt text are never
used as activity. `ConversationActivity` permits future orchestrators to submit
explicitly public task-specific blurbs through this same lifecycle.

Tool summaries use per-tool safe argument rules: e.g. “Opening YouTube”, “Adjusting
media volume to 25%”, “Opening Wi-Fi settings” and “Scrolling the screen down”.
Screen text, URLs/query strings, destinations, reminder bodies, notification bodies,
executor receipts and arbitrary provider payloads are not copied. Reference reads
identify only authored operation metadata and bounded source/result counts.
Public blurbs are capped at 160 characters and reject control/bidi characters and
obvious credential/contact/URL patterns. This defensive filter is not a general PII
classifier; producers remain responsible for supplying deliberately public text.
Locked/background UI removes the activity node. Foreground-resume and screen-lock
broadcasts recheck the real Android keyguard state.

Approval/input and unknown outcomes retain priority over ordinary work. Concurrent
active journal attempts are counted without inventing progress percentages or ETAs.
Speech/listening that overlaps conversation progress retains the actual audio owner
and identifies both observations. There is no typewriter or timer-driven narrative;
one polite live region replaces the sentence at event boundaries. Text wraps to two
lines with normal font scaling. Existing system reduced-motion handling is retained.

Acceptance additions: `AgentActivityTest` covers open-ended progress, sequence and
lease fencing, nested read cleanup, privacy and actual reference labels;
`WispPresentationTest` covers safe argument summaries, idle/locked suppression,
concurrent task priority/counts and overlapping actual audio. Release test49 checks
idle node removal, arbitrary public progress, unsafe metadata fallback and the
updated volume label while retaining all prior call/draft/journal assertions.
Exact merged release/R8/emulator, lockscreen, TalkBack, fold and 200% font validation
remain part of the hosted gate; local policy tests alone are not device verification.

## Persistent Wisp character — October 5, 2026

Wisp is the single, always-present top-center character in the app shell, including
idle chat, model setup, saved calls and Memory navigation. There is no visual-mode
selector. The prior in-call waveform is replaced; the existing call-control pill,
shared transcript, draft, stop-reply, microphone pause and explicit end behavior
remain. The character reserves a small header area instead of painting over text.
Idle Wisp uses a 168×104 dp drawing viewport. An armed, active call smoothly
expands it to 204×128 dp over 320 ms, giving the character and tool card more
room. Short/landscape windows use 116×66 dp idle and 138×80 dp in-call. Paused
microphones, interrupted replies, working and approval poses retain the active
call workspace. Explicit end or passive wake listening returns to idle size.
Android reduced-motion settings snap directly to the requested size, including
when animation scale changes during a tween. Standard modal dialogs
still appear above the app chrome; the full-screen development-metrics dialog
covers Wisp until dismissed. This is in-app UI, not an Android system overlay.

`WispPresence` observes existing process owners and has no operation authority.
`WispPresenter` deterministically maps call phases, microphone/playback envelopes,
chat activity and durable phone-task state to a pose. `WispCharacter` draws the
curled cyan silhouette, translucent filaments, face and purposeful props using
native Compose Canvas. Its frame loop stops below STARTED, respects Android's
animator duration scale (including zero/reduced motion), and uses no bitmap blur
or offscreen WebView. Audio updates are collected below the app/transcript owner.

Current honest action coverage:

- `JournaledActionPipeline` publishes its already-durable RUNNING boundary before
  Android execution and terminal state afterward. Battery checking uses a scan
  card, volume changes use sliders, and app opening uses an application tile.
  Fast operations may be conflated by StateFlow; they are never slowed just to
  display an animation. An observer failure cannot block or retry an action.
- Actual `ReferenceGroundingClient` byte reads emit a token-scoped
  CHECKING_REFERENCES observation. Query-routing heuristics do not emit fake
  search activity. Snapshots contain authored operation metadata and bounded counts, not URLs,
  prompts or source contents. Stale cleanup cannot clear a newer operation.
- A generic persistent task-store error remains visible in the phone-task panel
  and becomes Wisp's idle attention fallback. It does not mask actual voice,
  text or reference activity. This presentation choice neither clears the error
  nor resumes phone actions.
- Approval/input requirements show a waiting pose. Failure and unknown outcomes
  show attention, not success. A brief success pose requires a newly observed
  durable SUCCEEDED receipt with `ExecutionResult.Outcome.SUCCEEDED`; history
  loading, recomposition and conversation changes do not replay celebrations.
- Setup download/import/test flags, active/wake listening, thinking, speaking,
  microphone pause and interruption retain their real owners. Actual model failures
  and exhausted/terminal voice failures emit a bounded error lease; a new admitted
  turn clears it and stale expiry cannot dismiss newer work. Stop, pause and
  recoverable microphone handoffs are not errors. The drawing does not invent
  document editing, send messages, complete tasks or grant approval.

| Layer | Acceptance and failure cases | Coverage |
| --- | --- | --- |
| JVM presentation | Real audio owner, finite/clamped amplitude, idle after end, conversation isolation, approval priority, unknown versus success, fresh receipts only | `WispPresentationTest` |
| JVM operation observation | Actual read boundaries, failure/finally cleanup, stale and cancelled leases, observer failure isolation, no prose/heuristic events | `AgentActivityTest` |
| JVM phone dispatch | Durable RUNNING before executor, terminal state after result/cancellation, observer exceptions do not block effects, invalid claims do not animate | `JournaledActionPipelineTest` |
| Android UI | Character above transcript and centered while idle; real state DTO projection; unknown outcome never celebrates; repeated call/start/back/pause/speak/stop/end preserves draft and character, enlarges the active call workspace and shrinks after end | Release `test49_wispStaysPresentAndReflectsOnlyObservedWork` |
| Android navigation | Shipping parent retains Wisp before calls and during Memory; former waveform removed from the call controls; actual Wisp frame participates in the existing 320 dp/200% font and rotation/fold continuity cases without obscuring transcript or call controls | Release `test28`, `test30`, existing layout/accessibility matrix |
| Visual/performance | Actual shared DrawScope code rendered at 168×104 and compact 116×66, all 11 poses; Android screenshots and physical frame/thermal measurements remain separately required | Shared Skia render is geometry evidence, not an Android screenshot or APK verification |

Local focused verification passed with Kotlin 2.3.21 and coroutines 1.9.0:
90 JVM regressions across 12 classes, plus the architecture guard and 232 Python
helper tests. The Android character/header compile check used actual API 35,
Compose 1.7.6 and lifecycle 2.8.7 classes with no DTO stubs. All 11 shared-drawing
poses rendered at three initial sizes; 132 maximum-audio edge checks found no
clipping. The call-growth addition also passed 88 maximum-audio frames at its two
expanded viewport sizes without clipping.
These are targeted checks, not the full Gradle/R8/native/Android gate. The
zero-scale bypass and mid-transition snap are source-reviewed; first-frame
reduced-motion timing has not been measured on an Android device.

The Android release journey and exact-head release matrix must pass before an APK
is called verified. General model accuracy, acoustic reaction, Bluetooth, physical
microphone/speaker behavior and device performance remain physical-device signoff.

### Wisp Build 1025 release-test ABI correction

[Build 1025](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37376965136)
tests head `d3c6e041843591e867b8d09e4a68f5c07da40556`, merge
`40043be0c68b5498c6b5f5d329f29154c7f73e0f`. Both signed APK variants, all
1,029 release JVM tests (zero failures/errors/skips), 232 helper checks,
recorded speech and native audits pass. This is not a passing Android gate.

The API 35 compact profile preserves all first 48 main methods and actual Wisp
screenshots for idle, reference reading, three tool props, approval, confirmed
success and unknown outcome. The call portion of test49 then crashes on
`NoSuchMethodError: VoicePhase.getLabel()` at `ReleaseJourneyTest.kt:3223`.
R8 inlined/removed the getter in the app while the separately compiled test DEX
still calls its public ABI. API 35 artifact `11373212383`, ZIP SHA-256
`73256b818c7c8b40dc9733c0ced33cc0c222f997cc01e9c9d09156bf0448b03f`, retains
instrumentation, logcat, screenshots and XML. Test49 and test90 did not pass;
subsequent lifecycle/layout phases were not reached on this profile.

An independent test-DEX/R8 audit confirms that the test calls
`VoicePhase.getLabel():String`, while the release mapping/usage removes or
reshapes that getter. Its enum fields are consistently remapped. The remaining
call-flow references (`VoiceSessionUi`, `VoiceSessionState`, `VoicePlaybackFrame`
and `VoiceCallOverlay`) have retained shared ABI entries.

API 35 with 16 KB pages also has only that test49 failure (artifact `11372603079`,
ZIP SHA-256 `49d545be7e6feaa64236fecb0819326205bdd0229a0cd02da61006ac4a72c71c`).
API 36 phone additionally fails unchanged test45 while locating the restored
benchmark reference control (artifact `11373770626`, ZIP SHA-256
`d369dbee372f8ed623119692ff887eaa25a3a99c0279fcf13e13a6329df3cccf`). That fixture
mounts `PipelineBenchmarkScreen` directly, without Wisp chrome; its screenshot
confirms the standalone dashboard. Its earlier scoring/review steps passed,
but the restored-store visibility assertion did not. Preserve that failure and
rerun the same required navigation assertion rather than assuming a pass.

The API 36 Pixel Fold emulator also passes the first 48 main methods before the
same test49 getter failure. Artifact `11373681757`, ZIP SHA-256
`b97596109b1e1de973301a11202e677d1206424e60cba2209248ac33e4d96686`, retains eight
Wisp state captures. Visual inspection of the 2208×1840 unfolded layout shows
centered, unclipped Wisp/approval content above a separate transcript and composer.
This snapshot is not completion of the later fold/unfold lifecycle/layout phase.

The narrow correction preserves the `VoicePhase` enum ABI beside existing
`VoiceSessionUi`/`VoiceSessionState` boundaries. It changes no animation,
assertion, scenario, timeout or acceptance gate. The same call-flow regression
and complete exact-revision matrix must rerun successfully. API 30 separately
failed to download a valid Emulator archive before startup; that infrastructure
failure ran no app tests and cannot be treated as Wisp evidence.

### Build 1032 call-growth coverage and API 30 accessibility observation

[Build 1032](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37388436317)
tests head `cae607b0027ed02f9eb48561e888abdc7ff6b558`, merge
`fea42d4a975d6ca33eddd912171206b5ae768b26`. Both signed APKs, all 1,032 JVM
tests (zero failures/errors/skips), 232 helpers, recorded speech and native
checks pass. API 35 compact, API 36 phone and the actual Pixel Fold profile
pass their complete gates. The old `VoicePhase.getLabel()` failure is resolved.

The fold profile passes all 50 main methods, real fold/unfold continuity,
large-font/small-screen controls, upgrade and other required phases. Artifact
`11380409400`, ZIP SHA-256
`e020c1ee0ee95239916975f378a5f4b9f063b6bb1c1532dc9563aafb3a939912`, retains
native screenshots/XML. Wisp's drawing viewport grows from 441×273 to 536×336
pixels during both controlled calls and returns to idle afterward. Unfolded and
folded captures retain the draft, transcript and call actions. These fixtures
verify UI/state integration, not physical microphone/speaker behavior.

API 30 passes all 50 main tests and the large-font, rotation and native-load
layout cases, but layout test01's second microphone toggle fails its exact
five-second `Pause microphone` description wait. The PNG shows the resumed
pause-bar icon and Wisp Listening, while the later XML retains both the old
`Resume microphone` description and play glyph under the same action. The
unchanged retry repeats that mismatch. First artifact `11381475636`, ZIP SHA-256
`21ae83853a6312b928844f93d1f04f7ebd7ab1578f000d215473a6afee5472c2`; retry artifact
`11381288607`, ZIP SHA-256
`82bec248054ea18ed024c0d56ec864281bd86163ba2c327254e16156b56d8838`.

Exact-source inspection shows that Compose 1.7.6 exports a merging button's
spoken description through a synthetic child. UiAutomator 2.3.0 selector/XML
traversal can reuse cached child nodes, whereas its property getters refresh
nodes. This supports a stale-observation diagnosis; the failed captures alone
do not prove the accessibility provider's event-delivery behavior.

The next correction is confined to the layout test's two label observations.
It reuses the existing version-scoped public cache refresh, preserving the old
API's service configuration unchanged. It records bounded cached-before and
fresh-after evidence, re-finds the exact package-owned microphone action, and
requires its exact description plus the existing actionable/name/48 dp/on-screen
checks. Each wait still starts one 5,000 ms deadline and rejects late matches.
There is no extra tap, activity restart, timeout extension or production change;
fresh wrong state still fails. Actual API 35/UiAutomator 2.3.0 helper compilation
and 20 deterministic exact-source contract cases pass locally, including wrong
labels/owners, text-only substitutes, invalid action/bounds, failed refresh,
stale-node reacquisition, bounded diagnostic failures and late-observation rejection.
These are helper checks, not a fresh Android pass. The complete exact-revision
gate remains required.

### Build 1038 fresh accessibility observations and retained runtime failures

[Build 1038](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37395119386)
tests head `a81ece1f70b4318e13c8f0a9c01847b45254531e`, merge
`640f5f75a308de89c39d5fddb9cf2ef1f61e8ecf`. Both signed APK variants, 1,032
release JVM tests (zero failures/errors/skips), 243 helpers, recorded speech and
native checks pass. API 30,
API 35 compact, API 36 phone and API 36 Pixel Fold pass their complete profiles.
The overall run and its unchanged failed-profile retry remain failed.

API 30 artifact `11383590310`, ZIP SHA-256
`0306f07e1cb5145f015c710922941442571f393017bd6ccec76120b0b2d4e768`, directly
confirms the observation correction. After Resume, the cached subtree retains
`Resume microphone` and the play glyph; the fresh same package-owned
`voice_call_pause` button exposes exact `Pause microphone` description and
spoken name, with 4,930 ms remaining in the unchanged 5,000 ms budget. The
preceding pause check leaves 4,493 ms. Exactly one tap occurs per toggle, and
all existing action, 48 dp and on-screen assertions pass, along with all 50
main and four layout methods. This establishes correct freshly queried data,
not TalkBack event-delivery timing.

API 29 first observes current-process user0 BOOT_COMPLETED, but exhausts the
original boot deadline during final display verification before app tests.
First artifact `11383921159`, ZIP SHA-256
`7b885833d351854db78d39c69c1bbf441e68b2e29cf4e0a9b408ec53d925b3dc`, retains
that late boot delivery. Its unchanged retry instead has two UI-thread watchdog restarts and no boot
completion; both bounded DropBox queries time out. Retry artifact `11383948994`,
ZIP SHA-256 `b46602f77acf3de272cf1b428d09338224c1629dc84499e5866076e6e9e2983d`,
retains that incomplete startup and cannot authorize release.

API 35 with 16 KB pages first suffers Android `system_server` SIGSEGV in
`NetworkPolicyManagerService.setUidFirewallRuleUL` during APK upgrade, before
main app tests. First artifact `11383163609`, ZIP SHA-256
`ad5ea50df5aad3cb1ea1aaf54f7be1079d855874abd5f5ff50006b3b307533b7`, retains
that platform crash. Its retry passes upgrade and tests 1–23, then Jarvis receives
SIGSEGV while opening the existing memory-organizing dialog in test24. The
current R8 mapping resolves the top JIT method to Compose UI 1.7.6
`LayoutNodeDrawScope.drawDirect`, followed by `NodeCoordinator` drawing. The
fixture mounts `MemoryScreen` alone, with Wisp absent. Independent GMS crashes
in ART `NterpGetShorty` and `ReferenceQueueDaemon` precede it on the same image.
These cross-process failures make runtime/image instability the leading
hypothesis, but neither establish a shared root cause nor exclude every
app/Compose/runtime interaction. The native JIT offset does not identify the
precise failed object or Kotlin line. No speculative app change, JIT/GC toggle,
keep rule or waived assertion follows from this evidence. Retry artifact
`11383833940`, ZIP SHA-256
`9eb4e140979879cb8ee290332b58d786c3e400371d7bc99128b00d9e9a80a860`, and R8
artifact `11382858416`, ZIP SHA-256
`52d77b4f233525e333815b449029b1347c456494c14e6173613ed13a8e7846e0`, retain the
diagnosis. Later tests did not run; the exact same normal APK passes all 50
main tests on API 30. The failed 16 KB gate remains required.

### API 29 startup settings ordering

Build 1038's retained startup receipts observed the actual user0 boot-completion
candidate at 852.595 seconds of the original 900-second boot budget. The two
fresh system-server PID probes then consumed 18.633 seconds, and the three
already-required animation writes consumed another 15.322 seconds. Final
readiness and physical-display verification exhausted the remaining budget
before startup-UI verification could begin. These are command wall times, not
measured recoverable CPU.

The launcher now performs those same three checked writes after successful
unlock and before waiting for real BOOT_COMPLETED delivery. This may overlap
their work with the observed receiver-draining interval, reducing the serial
work after delivery by about 15 seconds on that run's timings. Earlier guest
contention could offset the gain; this does not establish a boot cure or enough
headroom for the guarded startup-UI check. Each 30-second command cap, the
original 900-second shared boot budget, current-PID/native-log proof, final
readiness, physical display, guarded UI recovery, failure diagnostics and full
release controller remain unchanged. Failed or expired settings still block
all later admission and retain teardown evidence. Exact-head hosted validation
is required before claiming any startup improvement or verified release.

Local combined verification passes the architecture guard and all 244 Python
helpers, including failure/expiry at each of the three early settings. No
production app, Wisp visual or acceptance threshold changes in this revision.

### Build 1041 Wisp fixture-arrival observation and platform failures

[Build 1041](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37400442110)
tests head `a7f60827c17d8c2bc3820dbafdc2341f35efc5a5`, merge
`8eed1aae72bcb4ccb5498aee79e415647c8543ea`. Both signed APKs, all 1,032 JVM
tests (zero failures/errors/skips), 244 helpers, recorded speech and native
audits pass. JVM artifact `11385840806`, ZIP SHA-256
`f7baa2ac68790e60a7de69495535b02dc1f43e7e574d79c670e30ea432427cd2`, retains
those test results. API 30, API 35 compact and API 36 phone pass their complete
profiles. The foldable runs all 50 main methods,
with only test49 failing on its initial header `getVisibleBounds()` read with
`StaleObjectException`; later lifecycle/layout phases do not run. Artifact
`11386385468`, ZIP SHA-256
`52d1c2015b73c401a712eeedd764d720620e8547f92ef21db107d269acdfec9d`, retains the
failure. The PNG still shows the setup screen's Ready character while the later
XML has the controlled conversation. The Ready wait could match the previous
composition while `setContent` replaced its accessibility node; the retained
frames and stale-node exception support that race without proving exact scheduling.

The corrected test replaces its original ten-second initial Ready wait with
one shared-deadline observation of the unique fixture text inside its transcript,
the composer and Ready pose. It never remounts the fixture or retries a tap.
Geometry queries reacquire the exact package-owned tags and reset stability on
missing, stale, nonpositive, changed or out-of-range bounds. Standalone discovery
retains its 15-second limit; each size assertion retains a single five-second
limit and a fresh uninterrupted 200 ms stable span. Direct discovery replaces the
old nested 15-second `find()` within the size waiter, and late geometry cannot
pass. Centering, above-transcript placement, 15–35% call expansion, pause/stop
retention, post-end size and repeated-call/draft assertions remain unchanged.
The helper compiles against actual API 35/UiAutomator 2.3.0 and passes 33
exact-source deterministic contracts; independent review finds no blocker.
These targeted checks do not replace the fresh full Android matrix.

API 29 completes its entire startup admission in 670.387 seconds, with 229.6
seconds left in the original boot budget. The current-process boot candidate
arrives at 585.565 seconds, and the existing guarded System UI recovery uses
exactly one Wait. The previous Build 907 APK upgrade and main tests 1–4 pass.
During test5, Android `system_server` dies after `FinalizerWatchdogDaemon`
reports that `ApkAssets.nativeDestroy/finalize` exceeded its ten-second limit.
No app failure is identified before that system death. Artifact `11385798450`,
ZIP SHA-256 `77a93885b77f860eaadef9e80916ae1a463537914abae156c385ab33e5ea2086`,
retains this first complete admission with guarded UI recovery and the distinct
runtime failure. It is
not a passing API 29 profile or proof of reliable startup across future runs;
no watchdog, boot, install or test deadline is extended.

The API 35 16 KB profile passes upgrade and tests 1–4, then `system_server`
receives SIGSEGV at address `0x18` in the platform AppSearch protobuf
`MessageSchema.writeTo` / `IcingSearchEngine.get` path. Artifact `11385169027`,
ZIP SHA-256 `291d9e1dfcf84290c382855a200414faf70bfc3489d60a42fe35defbc0a144b3`,
retains the platform crash. This is another independent system-process fault,
not evidence that Wisp drawing caused the earlier managed-code crashes.
Publication remains blocked by the failed required profiles.

### API 35 simulated 16 KB collector compatibility trial

Stock platform and Jarvis logs in the retained failures use `CollectorTypeCMC`.
The [AOSP kernel fix](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73)
identifies UFFD incompatibility with x86-64 16 KB simulation and specifies CC as
the compatible collector. The supported DeviceConfig flag and reboot are
exercised in [ART's regression test](https://android.googlesource.com/platform/art/+/8222aa2d2df6273da689f0edd3913e8370c0c1c2).
This is stronger evidence for a targeted environment correction than the earlier
stack-only hypothesis; it still requires successful hosted admission and all
app gates. The implementation and evidence contract are in the
[runbook](README.md#historical-api-35-collector-trial).

| Layer | Acceptance and failure cases | Preserved coverage |
| --- | --- | --- |
| Android emulator setup | API 35 simulated 16 KB selects CC through the supported DeviceConfig override and one reboot within the original 300-second boot deadline. Missing/partial ART regeneration, failing odsign, wrong flag/collector, stale boot/process identity or late readiness blocks APK installation; wrong current Jarvis collector blocks journeys. | Same API 35, runtime page size, ARM64 translation, native-loading, upgrade, lifecycle, layout and release gates; SELinux and ordinary permissions; other profiles. |

The initial launch, flag propagation, intentional reboot, complete ART artifact
regeneration and fresh readiness share the original boot budget. Exact CC logs
are retained for the current system server and first-launch Jarvis process.
This does not disable garbage collection, JIT, watchdogs or SELinux, skip any
app assertion, change the device image or widen a deadline. API 29's now-admitted
startup settings remain unchanged; its separate platform finalizer failure
still needs a successful exact-revision run. Physical-device audio/model and
16 KB hardware/performance coverage remain unverified.

Combined local validation passes the architecture guard and all 268 Python
helper tests, including 23 runtime-setup and 27 controller-phase tests. Workflow
YAML parsing, patch-hash verification and whitespace checks also pass. The Wisp
observer's 33 deterministic contracts and actual Android-library compilation
remain separately retained. Independent reviews of both repairs found no
blocking findings; the complete exact-head hosted gate remains required.

### Build 1042 API 30 benchmark near-edge observation

[Build 1042](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37404488065)
tests head `531bb132ac26fee17183bd4f9bd9cb5192766202`, merge
`2e79faffa368d93a3958241029dc94694b3d3270`. All 1,032 JVM tests pass with zero
failures/errors/skips; artifact `11387725265`, ZIP SHA-256
`36f5b6582458944b53fcbf13aa5103d389441add38167b7d77bc4f428de6c552`, retains the
results. API 35 compact, API 36 phone and API 36 Pixel Fold pass complete profiles. Foldable artifact `11387199450`,
ZIP SHA-256 `9148db813deaa7424c49648a710b02b8c8867c7bfcf2b5fae80782816ebb86a6`,
confirms the controlled-fixture arrival correction on its previously failing
profile.
API 30 passes corrected Wisp test49 and 48 other main tests, but benchmark
test45 cannot reveal `benchmark_quality_factuality_FAIL`; later layout phases
do not run. Artifact `11387133676`, ZIP SHA-256
`01d6ed9a90c809e16f173bdd0329302eb65b235cc37bb00237f9401022f77240`, retains
the native screenshot/XML and navigation trace.

The benchmark fixture has no Wisp chrome. Its target initially has positive
bounds `(226,1782)–(378,1794)`, only 30 pixels below the list ending at 1752.
The existing nearby-outside fine-alignment predicate excludes API 30, so the
helper dispatches a 1,064-pixel blind swipe despite that known nearby target.
The trace then shows metric rows below the desired controls, a bottom-edge
reversal, and budget exhaustion after 12 gestures while returning to the top.
No factuality tap occurs. Earlier passing API 30 runs entered this step with
the button already visible; they did not exercise this geometry.

The correction admits API 30 alongside API 35+ to that existing predicate,
retaining its positive bounds, horizontal overlap, distance and one-fifth
viewport cap. The exact failed observation now selects a 90-pixel fine stroke
rather than the blind search stroke. The separate API 35+ edge-fragment rule,
API 29/31–34 behavior, fresh safe/enabled bounds, 300 ms settling, one eventual
tap and 15-second/14-gesture limits are unchanged. This is a test navigation
correction, not a production layout or scoring change. Exact-source validation
passes 29 explicit boundary cases and 8,000 deterministic old/new comparisons;
the extracted navigation helpers compile against actual Android/UiAutomator
classes. Fresh complete hosted verification remains required.

The 16 KB compatibility setup successfully records both flag readbacks, one
intentional reboot, current-boot complete ART compilation (`returned 80`) and
`system_server` selecting `CollectorTypeCC`. It then correctly rejects the
server disappearing after a `HeapTaskDaemon` SIGSEGV (`SI_KERNEL`, address
zero) during boot. Artifact `11387725909`, ZIP SHA-256
`8be407a0dca9faeefe32a15a2c0468038eaac4a5b4532753d2478f441bfad9a1`, retains
the admitted collector and distinct runtime failure. No APK installation or
app journey follows. Selecting the compatible collector is established here;
a stable runtime or full 16 KB pass is not. No further runtime configuration
change follows without supporting evidence.

API 29 again misses current-process user0 BOOT_COMPLETED within 900 seconds.
The first system server fails a 30-second default-permissions request; its
replacement has a confirmed UI-watchdog restart, and the third does not finish.
Artifact `11387264382`, ZIP SHA-256
`9577b4f80fec3961c42bbf3513ef6856bea386c23044a7d0e77c1e43e4e87792`, retains
that failure. Build 1041's complete admission is therefore not a reliable boot
qualification. No app tests run on this API 29 attempt, and publication remains
blocked by all required failed profiles.

### API 29 display-cadence trial after Build 1042

Build 1042 retained native host `Setting vsync to 60 hz`, the `qemu.vsync=60`
boot parameter, and guest `DisplayDeviceInfo` reporting 60.000004 Hz at
360×640 / 140 dpi. Its second system server had a 121,281 ms `android.ui`
Choreographer dispatch; the 02:56:48–02:56:54 ANR sample reports 100% total
guest CPU, including SystemUI 13%, SurfaceFlinger 5.7% and composer 4.5%.
Display work participates in the overload but is not established as its cause.

The isolated trial sets the API 29 software fixture's supported
`hw.lcd.vsync` to 30. Emulator 32 uses it for both its host VsyncThread and
`qemu.vsync`; Android init exposes the latter as `ro.kernel.qemu.vsync`.
The revision-8 vendor composer contains that property and the corresponding
EmuHWC2 display/VsyncThread symbols. The property-aware HWC uses the period
for callbacks and its advertised display configuration independently of
HostComposition. See [emulator property generation](https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/userspace-boot-properties.cpp#325)
and [property-aware HWC](https://android.googlesource.com/device/generic/goldfish-opengl/+/android11-release/system/hwc2/EmuHWC2.cpp#420).

This changes the fixture's frame cadence, keeping physical resolution,
density, microphone/audio configuration, all functional checks and deadlines.
The expected effect is fewer periodic display wakeups and frame opportunities;
CPU savings or improved startup/runtime reliability are not yet measured.
Fresh evidence must show host 30 Hz, `qemu.vsync=30`, and guest display near
30 Hz at the original dimensions/density. Compare wall-clock stalls and CPU,
not skipped-frame counts, which also change with cadence. The full API 29
profile and complete release gates remain required. No new boot probes,
watchdog changes, timeout extensions or app changes accompany this trial.

### Build 1050 software-raster navigation geometry

[Build 1050](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37408280677)
tests head `388430dc4efd9a24e1bfc1ac58d3595c6607a8cf`, merge
`3435dd66cdf239e71f62b7025a190cb8237c0e4c`. API 30, API 35 compact, API 36
phone and API 36 Pixel Fold pass their complete profiles. API 30 artifact
`11388283796`, ZIP SHA-256
`a8a6ffe96b5cbad41c1682b66db7308d00e0cd8730530596b6addb367f134ac4`, confirms
all 50 main and four layout cases. Its factuality control was already visible
and received one tap at `(302,1659)`: this run does not replay the previous
nearby-outside geometry, which remains covered by deterministic helper cases.

API 29 now records host 30 Hz, `qemu.vsync=30` and the corresponding guest display
rate, completes startup in 714.265 seconds with one guarded System UI Wait, and
passes the Build 907 replacement upgrade. Main instrumentation reaches its
existing 900-second deadline with 22 passing methods, four assertion failures
and test27 partial. Tests 1–26's native start/finish spans total 831.863 seconds,
including per-test setup/evidence/teardown; these spans do not isolate implicit
idle-wait costs. Later native execution after the controller deadline is not
accepted coverage. Artifact `11389452621`, ZIP SHA-256
`7f1c09b1a8382e0f1fa8e84564ac3d7144d2f0e0e9b31fab7eedd648447a9bdd`, retains
this incomplete result.

Three failures are deterministic test-coordinate errors: `model_search` is
enabled/clickable at `[14,120][346,169]` and `memory_open` at
`[21,303][339,345]`, but the fixed 24-pixel gutter permits only `[24,336]` on
the 360-pixel raster. The original 1080×1920/420 dpi Pixel 2 and the
360×640/140 dpi software fixture have the same logical viewport; that literal
gutter accidentally triples its logical size. The API 29-only correction scales
reference pixels by `densityDpi / 420`, rounding upward, validating positive
density and rejecting arithmetic overflow. At 140 dpi, 24/12/48 reference pixels
become 8/4/16, preserving the original logical safety margins. All other APIs
retain their literal values.

The same conversion covers duplicate benchmark safety/alignment margins. Its
Copy JSON control begins at 16 dp (about 14 pixels here), so the original outer
24-pixel viewport would reject it regardless of vertical scrolling. That is a
source-derived future blocker, not a benchmark failure observed in this run.
Full-rectangle containment, nonempty bounds, enabled state, actual 48 dp target
requirements, 96-pixel usable-gesture preconditions, viewport ratios, gesture
step counts, deadlines, stability and single-action behavior remain unchanged.
Independent exact-source validation passes 155 explicit cases, 50,000 property
cases and 65,000 unchanged-HEAD comparisons, including conservative rounding,
invalid density/overflow and clipped/empty/offscreen bounds. Full extracted
navigation helpers compile with actual Android/UiAutomator classes; the
architecture guard and all 268 Python helpers also pass. These are local
harness checks, not a completed API 29 Android journey.

Test7's Settings launch is separate: the correct Android intent is recorded,
but the cold Settings screen remains blank at the 15-second observation limit,
with stock Settings/ConditionManager timeouts. Its later populated screen does
not retroactively pass that wait. No Settings assertion or timeout changes, and
no unmeasured speedup is claimed. Fixing the false geometry guards alone does
not establish that every later test fits the suite budget.

API 35 simulated 16 KB again admits complete ART regeneration and CC, then
fails before APK installation with platform `InputReader` `std::bad_alloc`
and a replacement process's NativeTombstone SIGSEGV. Artifact `11388322605`,
ZIP SHA-256 `730fde8f479452d13e4cb0bfd2a86b06e942a1f69c95ee5f3db78c0880c2aa8c`,
retains that failure. This does not establish ordinary memory exhaustion or a
Jarvis defect. The failed required profiles continue to block release.

The separate full-duplex documentation update credits existing simultaneous
capture/playback and bounded speech-verified natural barge-in, with retained
interruption audio. It introduces no voice runtime change or new physical-device
claim; see the [current duplex contract](voice-audio-and-metrics.md#system-level-full-duplex).

### Approved API 29 capacity and API 36 16 KB matrix change

Build 1050 (head `388430dc4efd9a24e1bfc1ac58d3595c6607a8cf`, merge
`3435dd66cdf239e71f62b7025a190cb8237c0e4c`) confirms API 29 host/guest 30 Hz
at the unchanged 360×640 / 140 dpi. Full startup passes in 714.265 seconds;
the previous-APK upgrade passes. Main instrumentation reaches its 900-second
limit with 22 admitted passes, four failed assertions and a partial test 27.
Tests 1–26 native start-to-finish spans total 831.863 seconds; controller startup,
gaps and partial work consume the remainder. Later guest execution after the
client deadline is not admitted coverage. Artifact `11389452621`, ZIP SHA-256
`7f1c09b1a8382e0f1fa8e84564ac3d7144d2f0e0e9b31fab7eedd648447a9bdd`, retains
this result. The larger suite budget cannot itself fix the separate 15-second
stock Settings visibility failure.

Following explicit approval on October 6, API 29 retains every named test and
individual assertion with a 2,400-second main-suite budget and 90-minute job
ceiling. Its startup remains 900 seconds, APK installation 180 seconds, and
upgrade/lifecycle/layout limits are unchanged. All other main suites remain
900 seconds. `instrumentation_timeout` is explicit in each profile and is
validated and copied into same-build reports/receipts; only API 29 software
emulation can declare the increased main/job capacity.

The required 16 KB row moves from API 35 to `36-16k-normal`, using official
stable `system-images;android-36;google_apis_ps16k;x86_64` revision 7 on the
existing KVM runner. Metadata identifies SDK 36, build
`BE2A.250530.026.F3/13894323`, ARM64 `libndk_translation.so`, and
`page_shift=14`; it does not prove runtime health. Actual SDK/page size/ARM64
ABI/bridge admission and the full native-loading, upgrade, main, lifecycle,
layout and final receipt remain required. The historical API 35 collector
workaround is dormant; API 36 uses stock runtime settings and unchanged KVM
budgets. API 35 compact 4 KB remains required. Android 15-specific 16 KB
coverage is explicitly lost by this approved replacement and must not be
reported as fixed or passing. Fresh exact-revision CI is required before any
release or candidate claim.

Build 1050's API 35 16 KB image still fails before APK installation despite
complete ART regeneration and observed CC. InputReader requests
`malloc(0x0101010101010110)` before aborting, followed by a replacement
system-server zlib fault at `0x02020202`; artifact `11388322605`, SHA-256
`730fde8f479452d13e4cb0bfd2a86b06e942a1f69c95ee5f3db78c0880c2aa8c`.
The paired API 35 compact profile passes all phases with no native SIGSEGV or
SIGABRT in its retained log window; artifact `11388838257`, SHA-256
`e6a3f6c6369388cf653b67e27f9018c54586322e8d305006ecd2e0007f1b1443`.
Different jobs, variants and capture windows prevent proving an exact kernel,
image or host cause from that contrast.

The combined density-guard, documentation and approved matrix revision passes
the architecture guard and all 271 Python helpers (91 + 180). The Kotlin guard
source still matches the independently compiled and boundary-tested candidate.
Workflow YAML parsing and whitespace checks pass. These local checks do not
establish API 29 completion or API 36 16 KB runtime health; the new full
same-revision release gate remains required.

### Persistent journal warning must not freeze live Wisp activity

October 6 phone screenshots show Wisp labelled “Task needs attention” and the
separate phone-task dialog reporting “The action journal is unavailable. Phone
actions are paused.” The screenshots do not identify the installed build or
prove why its private journal could not be read. The presentation defect is
independent and reproducible: `WispPresenter.present` returned a generic
`taskError` before current voice/text/reference signals, also preventing the
listening/speaking poses from selecting their real audio envelopes.

The generic error is now an idle fallback. Specific task approval, unresolved
outcome and RUNNING priorities remain intact; current work and genuine fresh
receipts keep their meaning. Listening, thinking, speaking and microphone pause
can appear while the task warning remains visible. Ending a call returns to
attention if the same error is still present. No error state is cleared, no
journal is modified and no phone-action authority is granted by a pose.

Five JVM regressions cover repeated call transitions, microphone versus playback
amplitudes, idle/end attention, concurrent text/reference work, preserved urgent
state priority and fresh receipts. Four of these fail against the original
presenter. The real-state release test49 holds the identical warning StateFlow
in both Wisp and the conversation's phone-task UI through text/reference work
and two call cycles, checks its visibility and detail, and verifies that end
preserves the error and draft. Local validation passes 95 JVM tests across 12
classes, including 17 Wisp tests, compilation of the header against actual
API 35/Compose classes, the architecture guard and all 271 Python helpers.
This is not local execution of the Android journey;
the next full signed, exact-revision gate remains required.

A separate read-only schema review finds a possible downgrade explanation:
released Muse build 1067 (`b29b19bfa12495b537bea691ada124f4501e08fc`) writes
journal schema 3, while this branch's store accepts 1–2 and writes 2 at the
inspected baseline. Both use the same app-private journal filename. That source
mismatch does not prove this phone contains a v3 file. Simply widening the old
reader would discard newer workflow/source-access sections on write and still
reject newer action types. No downgrade conversion, journal reset or unsafe
recovery is included in the Wisp fix. The existing installed-build diagnostic
alone cannot establish the journal schema or swallowed storage-failure cause.

### Build 1068 approved matrix result

[Build 1068](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37423443731)
tests head `be1c099d221335b37fd33eb2cebfb44c50a0d7fe`, merge
`76c57bd344e93a2516993be4b89a268c8d9fd321`. Both signed APK variants,
recorded speech/native audits and all 1,032 JVM tests pass with zero
failures/errors/skips. JVM artifact `11394560690`, ZIP SHA-256
`4ddef6d4f1d689c8bfab71bd71dac318297b2b41c5e113750ec4fa8b38de1eb3`, retains
the results. All five KVM profiles pass, including the required API 36 16 KB
profile with actual SDK 36, `PAGE_SIZE=16384`, `libndk_translation`, all nine
native libraries loaded, 50 main tests and upgrade/lifecycle/layout coverage.
Its artifact `11395087011`, ZIP SHA-256
`bfabf163354d5835e7b1fd6c24094602a0803120f7a313e6a7267286e4d5e087`, verifies
the new profile. Android 15-specific 16 KB coverage remains excluded by the
approved replacement; this is not evidence that its old image is repaired.

API 29 reaches full startup in 753.252 seconds on the same live system server,
with actual 30 Hz and 360×640 at 140 dpi. The previous Build 907 seed then
fails while exporting evidence through MediaStore: `external_primary` is absent
and `/sdcard` unavailable after a retained vold/FUSE mount timeout (`-110`).
Artifact `11394687449`, ZIP SHA-256
`f9d986aab9f1bb26c496f85c57e1f81fe41a3d5521081730f32f6fbd5a787310`, retains
that platform/storage failure. Main instrumentation never starts, so the new
40-minute main budget is unexercised. A waiting barrier alone would not repair
the persistently missing mount; no reset, remount workaround or waived seed
assertion is included. The consolidated receipt fails and publication is skipped.
The separate phone-reported Wisp priority correction requires a new exact-head
run; Build 1068 does not verify that later change.

### Android 11 minimum and Android 10 retirement

Android 11/API 30 is now the minimum supported OS (`minSdk=30`). At the user's
explicit direction on October 6, Android 10/API 29 support, its required matrix
row, dedicated software-emulator workflow/launcher and launcher-only helper
tests are removed. Its earlier 40-minute main-suite/90-minute job allowance is
retired, not transferred to another profile. Historical API 29 failures remain
recorded and are not described as repaired or passing.

The five retained profiles are unchanged: API 30 normal phone, API 35 compact
phone, API 36 normal phone, API 36 compact foldable and API 36 normal true 16 KB.
Each retains KVM/x86-64 with observed ARM64 capability, 300-second startup,
180-second installs, 900-second main instrumentation and 40-minute job ceiling.
Every named journey, upgrade/lifecycle/layout assertion, native-loading proof,
APK/merge binding and final receipt remains required on these supported profiles.
The API 35-specific 16 KB gap remains explicit; actual API 36 runtime 16 KB
verification does not fill that version-specific gap.

Build 1068 completed all five retained device profiles successfully, including
actual SDK 36/PAGE_SIZE=16384/ARM64 translation and all nine shipping native
libraries in the 16 KB profile. Artifact `11395087011`, ZIP SHA-256
`bfabf163354d5835e7b1fd6c24094602a0803120f7a313e6a7267286e4d5e087`, retains
the full 50-main-test, upgrade, lifecycle and layout result. Its API 29 startup
passed in 753.252 seconds but vold's FUSE/sdcard startup timed out, leaving
`external_primary` unavailable. Previous-APK seed evidence failed; main
instrumentation never began. Artifact `11394687449`, ZIP SHA-256
`f9d986aab9f1bb26c496f85c57e1f81fe41a3d5521081730f32f6fbd5a787310`, preserves
that failure. Receipt/publication remained blocked for that revision.

After retirement, local architecture and 172 remaining Python helper checks
pass. The reduction from 271 reflects removal of dedicated retired-software
coverage and its obsolete profile case; no supported Android journey is removed.
A fresh signed APK/manifest and complete five-profile run for the combined Wisp
revision are required before release. Build 1068 is preceding evidence, not a
passing result for the new commit.

The retired API 29-only pixel conversion is also removed from the UI test
helpers. Exact supported-path comparison and compilation against real Android/
UiAutomator classes confirm unchanged API 30+ geometry, deadlines, gesture
counts and stability. The reviewed persistent-warning test49 body is unchanged.

## API 29 graphics boot loop — October 5, 2026

[Build 986](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37236069164)
tested head `9d933b754f0e7124850bde5a289931b4cf9cadb3`. Its API 29 artifact
`11316332939` (ZIP SHA-256
`09f1b72bc51ef80f92c313571af24eab45215593d489ed80a6a845fd50ca98d0`)
retains five matching native crashes: the guest graphics composer dereferences
a null handle in `GoldfishGralloc::getHostHandle` from `EmuHWC2::Display::present`.
Each is followed by a SurfaceFlinger abort and Android restart. The guest's
extension strings lack `ANDROID_EMU_host_composition_v1/v2`; this is a concrete
graphics failure before Jarvis installation, not merely slow app tests.

The inspected API 29 revision 8 image declares `HostComposition = on`. Emulator 32's
[API-level fallback](https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/main-emugl.cpp)
nevertheless disables it below API 32 unless explicitly overridden. Its
[render-control implementation](https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emugl/host/libs/libOpenglRender/RenderControl.cpp)
advertises both host-composition extensions only when this feature is enabled.
The narrow infrastructure correction requests `HostComposition,-HVF,-Vulkan`
for API 29 only. The causal connection to the crash is an inference requiring
fresh hosted validation; no successful boot or speed improvement is claimed yet.

Acceptance: the genuine API 29 ARM64 image must finish boot, retain all four
Binder services, execute input, unlock, deliver user0 BOOT_COMPLETED and pass
the unchanged release controller within the existing 900-second boot and
60-minute job limits. Missing services, crashes, failed journeys or incomplete
same-run evidence must still block publication. Helper regressions preserve the
feature request, disabled acceleration/Vulkan, original deadlines and the
distinction between requested configuration and verified device success. All
other profiles, APKs, watchdogs, app code and release dependencies are unchanged.

### Build 1003 validation and boot-monitor overhead

[Build 1003](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37271709675)
tested head `6ef8a21996b51691b97da8acd7cb5dfae70d81db`, merge
`2ba2ef6abba05265305d861d64266d69db2a88f1`. Both initial and retry API 29 logs
advertise the two host-composition extensions and contain no native composer
crash. Boot flag, all four services, input and unlock succeed. Final user0
BOOT_COMPLETED delivery still misses the unchanged 900-second budget, so no
Jarvis device tests run. Retained API 29 artifact IDs are `11329647808` and
`11330521018`; the retry ZIP SHA-256 is
`c5c46a77273d72611f772891192150ba7748e5cbfe5a27ff2d8b4f956ca63d09`.
The first attempt had an early permission-initialization crash; the retry did
not, yet still timed out during ordered boot delivery. The five other profiles
pass across the latest successful producer attempts, with API 30's intermittent
benchmark-navigation failure passing on its one unchanged-code retry. The
consolidated receipt fails and publication remains blocked by API 29.

The first attempt's boot-broadcast monitor launched 36 guest PID probes with
286.7 seconds of combined command elapsed time despite no completion marker in
the already-local log. This is waiting time, not a measured amount of recoverable
CPU. The next correction scans the bounded native log first, avoiding guest
queries until an exact completion candidate appears. It then performs the same
fresh PID/log/PID validation for the current system_server. The candidate never
authorizes readiness. Regressions reject missing, stale, wrong-user, wrong-tag,
split-line and partial markers, failed reads/probes, process changes and expiry;
the adb-logcat fallback and original deadlines remain unchanged. All 209 Python
helper checks pass locally; a fresh exact-revision device run must measure the
effect and complete the full gate before any APK is called verified.

### Build 1004 CPU saturation and same-runner capacity trial

[Build 1004](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37276324771)
tested head `fe0de66e3fce6d22ca5655afd2c3fe08eb72ad0a`, merge
`626f2349ccda4a155aa2860647175fbd3afc16db`. Native host composition remains
advertised; no composer crash occurs. The monitor makes zero guest PID probes
before a completion candidate. Boot/services/input/unlock succeed, but user0
boot delivery still misses 900 seconds. API 29 artifact `11331751012`, ZIP SHA-256
`aeea282b9fd2af3ec9d93cc41282956a83abb88cb0506ed946b8fecfd0386512`, retains
the complete failed startup evidence.

ANR samples report 98–100% guest CPU utilization with 53–69% kernel work.
The phone service repeatedly ANRs/restarts; after BOOT_COMPLETED posts at
07:36:26 UTC, its receiver times out after 60 seconds at 07:37:55. System-server
work dominates, with sensor, UI and provider work competing for the two guest
CPUs. Graphics compositor crashes and monitor PID polling are no longer the
failure. Early host receipts show three CPUs and no swap; late host contention
has not been measured.

The next controlled trial changes only guest CPU count from two to three on
the same macos-15 runner, using one shared setting for AVD configuration and
the explicit QEMU SMP argument. This preserves API/ABI, image, renderer, RAM,
watchdogs, all readiness checks and deadlines. The reported host count is not
proof of idle headroom or that extra guest concurrency will outperform two cores while
rendering and I/O share the host. Actual kernel CPU admission, complete startup
and the full release controller must pass on the new revision.

All 1,005 JVM tests, four recorded-speech checks, native audits and four other
device profiles passed Build 1004. API 35 compact instead encountered a stock
Pixel Launcher ANR dialog covering Jarvis setup; its unchanged test code failed
to find the obscured model control. Its artifact `11331800414` retains the
screenshot and raw results. Neither this unrelated platform failure nor the
missing API 29 report is waived; the final receipt fails and publication skips.

### Build 1007 three-vCPU outcome and single-vCPU trial

[Build 1007](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37279904852)
tests head `3cb77d9904fefe10a754219e64f2f58ada3e2a29`. API 29's kernel explicitly
activates three processors. Its artifact `11332429923`, ZIP SHA-256
`959957e785832dc64a72a27777ea6e88d200673d0629e0166ad638b5a670053d`, retains
two system-server deaths in permission initialization and SurfaceFlinger boot
completion at 546,330 ms. The corresponding two-vCPU Build 1004 reached that
display milestone at 223,575 ms without those system-server crashes. Both
ultimately fail the user0 boot-delivery barrier, so neither is a passing device.
The comparison does not isolate host scheduling noise or prove a CPU-count cause,
but it provides no evidence that three guest CPUs help.

The next single-variable trial requests one guest CPU in both AVD and QEMU.
This tests whether avoiding guest SMP and leaving more scheduling room for host
rendering/I/O helps software emulation. It retains the same image/API/ABI,
graphics path, memory, watchdogs, every readiness assertion, controller and
all deadlines. No performance or startup success is claimed before exact-head CI.
Four other profiles pass Build 1007; API 35 compact instead fails before device
startup on invalid GitHub artifact-pagination metadata. That independent
infrastructure result is not converted to a pass and publication remains blocked.

### Build 1009 outcome and unused motion-stream load

[Build 1009](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37283283979)
tests head `b559ac44daeaaab8e075f0e3a1d938ebbbd66901`. All five other Android
profiles pass. API 29 admits one processor and reaches initial display boot at
182,636 ms, but the platform watchdog subsequently kills two system-server
processes on blocked UI handlers. It never completes user0 boot delivery or
starts Jarvis tests. API 29 artifact `11334224802`, ZIP SHA-256
`baa49cfebb1f5d6a88c65acbda9639a1dffb8e43e43ec385d5be6af8d2919318`, retains
those failures. Initial display timing alone is not complete boot or a speed win.

Restore the more stable two-CPU baseline. The next load reduction disables five
supported emulator motion flags: `hw.accelerometer`, `hw.accelerometer_uncalibrated`,
`hw.gyroscope`, `hw.sensors.gyroscope_uncalibrated` and `hw.sensors.orientation`.
In Build 1004, the sensor HAL repeatedly used 18–21% of one guest CPU while
SensorService dropped about 67–71 cached events every two seconds. The logs do
not name the active sensor, so the group is a controlled load-reduction trial,
not attribution to one proven faulty sensor. Emulator source exposes these exact
AVD flags through its advertised sensor mask and periodic event stream.

Production, manifests, instrumentation and scenario contracts have no sensor
consumer or sensor-capability gate. The required non-foldable layout test calls
UIAutomator `setOrientationLeft`/`setOrientationNatural` and still requires actual
dimension changes plus call/draft continuity. Those assertions are unchanged.
Light, proximity, magnetic field and remaining sensor profile settings are retained;
helper tests verify this alongside unique generated keys and matching AVD/QEMU
CPU counts. The full exact-head API 29 controller, all other profiles and unchanged
deadlines remain necessary before publication. No physical sensor coverage is claimed.

### Build 1014 completes startup; cold System UI dialog blocks app tests

[Build 1014](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37287508312)
tests head `4cc61034d935b91b21b25b117aabee82fd7d8354`, merge
`70cc1c9ef5144b8cf6052ec44ddcd051d7b3e8e6`. API 29 admits the requested motion
flags, and its native log contains no sensor-cache overflow. Sensor-HAL samples
fall to 13–15% of one guest CPU; these separate runs do not isolate every timing
variable. Crucially, the full current-PID user0 BOOT_COMPLETED receipt succeeds
at 720.328 seconds of the original 900-second boot window. Input, unlock, all
four services and actual physical display checks succeed. Both real Build 907
upgrade phases pass without clearing data during replacement.

The 49-case main phase does not pass: 24 methods fail in setup and the next
method is incomplete when the unchanged instrumentation timeout expires. The
retained screenshots/XML show Jarvis setup behind the Android-owned
`System UI isn't responding` dialog. The only System UI ANR in the native log
predates user0 boot completion and any Jarvis installation; it is not a Jarvis
fatal or a failed product assertion. API 29 artifact `11336613748`, ZIP SHA-256
`247e7eaa0922386c4ad2622a5740583ce2347126af9a24c1ff108b43c053bdfd`, preserves
the failed run. All five other profiles and the host gates pass, but publication
correctly remains blocked by incomplete API 29 verification.

The next launcher correction treats that retained cold-start dialog as a
one-time pre-app recovery. It verifies the exact Android-owned System UI ANR
and Wait control from fresh device evidence, records the action, selects Wait
once and requires clearance plus fresh readiness before controller execution.
An app/other error dialog, ambiguous match, repeated dialog, failed command, emulator
death or expiry cannot authorize the controller. No watchdog is disabled and
no ANR is dismissed inside app tests. Evidence of the original platform ANR is
retained, including resolved stock pre-boot Dialer/media ANRs. Any new ANR after
the boot-completion barrier fails rather than authorizing recovery. The same
900-second startup, instrumentation, lifecycle/layout and
publication gates still apply; only a full new exact-head run can verify it.
The candidate passes 232 local helper tests and the architecture guard, including
strict ownership/history matching, one-input recovery and late/failing probes.

### API 29 remaining sensor workload — controlled trial, exact CI pending

Build 1025, run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37376965136, head `d3c6e041843591e867b8d09e4a68f5c07da40556`, tested merge `40043be0c68b5498c6b5f5d329f29154c7f73e0f`, again exhausted the original 900-second boot budget before actual user-0 BOOT_COMPLETED delivery. The app tests and guarded System UI recovery never started. Artifact `11372978997`, ZIP SHA-256 `cc9077208b89c3abde2412830e7beb0a8f5cd104358546a6de3175a2b0dc1388`, retains one permission-initialization system-server failure, a later System UI ANR, and repeated stock-app ANRs. The sensor HAL still consumed 19–22% of a guest CPU after the five motion sensors were disabled; the native configuration verifies seven remaining advertised sensors. The logs do not identify which sensor subscriptions were active.

The next API 29-only load-reduction trial also disables `hw.sensors.light`, `hw.sensors.proximity`, `hw.sensors.magnetic_field`, `hw.sensors.magnetic_field_uncalibrated`, `hw.sensors.pressure`, `hw.sensors.humidity`, and `hw.sensors.temperature`. Pinned Emulator 32.1.15 maps these supported flags into its advertised sensor mask and stops rearming its periodic sensor timer when no sensor is active. Android 10 SensorService explicitly supports an empty sensor list without starting its polling/ack threads. Sources: https://android.googlesource.com/platform/external/qemu/+/35c71ce5114d90004f9109b25c0dc6434d41014d/android/android-emu/android/hw-sensors.cpp and https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android10-release/services/sensorservice/SensorService.cpp .

This is a supported device variation, not the emulator default or a proven boot fix. Jarvis has no SensorManager consumers, sensor-feature requirements, proximity wake locks or implemented brightness-control coupling. Microphone/audio configuration, other Android profiles and the actual UIAutomator rotation/dimension/continuity assertions remain intact. Platform automatic-brightness/proximity availability changes on this software fixture; physical sensor/device behavior remains outside emulator coverage. Genuine API 29 ARM64, two guest CPUs, 2 GiB RAM, 360x640@140, HostComposition, the 900-second boot/instrumentation budgets and every acceptance assertion remain required.

Cleanup retains `final-sensorservice.txt` through one additional read-only `dumpsys sensorservice` query bounded to ten seconds within the original overall session deadline. It runs after the boot/controller verdict, cannot authorize app tests or make a failed boot pass, and supplements native parsed-configuration receipts. Fresh hosted evidence must establish actual sensor admission, boot completion and the complete release gate; publication stays blocked until then.


### Build 1032 retry: retain earlier Android hang traces

[Build 1032 attempt 2](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37388436317) tests head `cae607b0027ed02f9eb48561e888abdc7ff6b558`, merge `fea42d4a975d6ca33eddd912171206b5ae768b26`. API 29 again fails the original 900-second user-0 BOOT_COMPLETED barrier before app instrumentation. Artifact `11381109655`, ZIP SHA-256 `881f728be345abb2ec2d613869c8ff5e6f9ccc7baf28d1bca911226b7c82cb47`, confirms the sensorless configuration (`No Sensors on the device`, `devInitCheck: 0`) and another system-server UI watchdog restart. The late watchdog stack is in `MessageQueue.nativePollOnce` and `Looper.loop`; it does not identify the earlier stall. A logged DropBox add attempt does not prove that the report was persisted or that its earlier stacks are complete.

After a failed boot verdict only, the software-emulator teardown now captures two separate, read-only Android DropBox tags: `system_app_anr` (at most 2 MiB) and `system_server_watchdog` (at most 1 MiB). Android 10's ordinary shell diagnostic permissions suffice. Its app-ANR traces can include system_server, potentially providing earlier context than the late watchdog snapshot. Separate tag queries are necessary because multiple DropBox search arguments are ANDed. Sources: [DropBox dump/filtering](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/services/core/java/com/android/server/DropBoxManagerService.java#L535), [shell diagnostic permissions](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/com/android/internal/util/DumpUtils.java#L114), [ANR trace selection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/services/core/java/com/android/server/am/ProcessRecord.java#L1427), and [watchdog reporting](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/services/core/java/com/android/server/Watchdog.java#L615).

Each collection is limited to ten seconds within the original session deadline. Output streams directly to capped files; partial bytes and explicit timeout, truncation, exit, or error metadata are retained. Only the launched adb client is killed/reaped when necessary, and the existing owned-emulator cleanup remains intact. The original failed boot verdict is already final. Successful boot/controller paths and controller failures do not invoke this capture. No configuration, recovery action, watchdog, boot/instrumentation deadline, or acceptance assertion changes.

Local architecture checks and all 243 Python helper tests pass, including eleven new cases for byte/time limits, partial output, process cleanup and invocation only after failed boot. Missing or incomplete DropBox entries remain possible. Fresh hosted execution must verify collection and every required release gate; this diagnostic change establishes no APK pass or new startup success.


## Spoken farewell returns to wake listening

“Stop listening” and “Goodbye” end the current call segment while preserving the
user-armed session and its foreground microphone service. Final transcript,
playback interruption (including a verified control without PCM), accepted-action
follow-up and final Gemma caption all use the call-ID-scoped return path. The exact
turn finalizer closes old capture/speech children before the completion callback
starts passive `Hey Jarvis` listening. Explicit End/Stop session still disarms
and shuts down the service. Accepted phone work retains its existing bounded
native-worker ownership; wake capture resumes after that worker drains.

| Layer | Acceptance and failure case | Coverage |
| --- | --- | --- |
| JVM | A verified farewell interruption ends the call even with no PCM; failed recognition, quoted phrases, ordinary requests and stop-speaking keep the call. | `ReplyInterruptionTest`, `VoiceCallPolicyTest`, `CallLifetimePolicyTest` |
| Android lifecycle | Farewell saves the ended call, drains old queued input, keeps the current turn and real foreground service alive, and permits a fresh call ID. A stale old-call farewell cannot end the new call. Explicit End disarms from an active or passive session. | Release `test48` passed Build 930 on API 30, API 35 compact/16 KB, API 36 phone and foldable; the full run was blocked by navigation/installation failures |
| Physical audio/model | After saying “Stop listening”, wait for wake readiness, then say “Hey Jarvis” to start another call, including from a locked/background phone. | Device signoff pending; controlled lifecycle tests do not prove acoustic detection or model inference |

During verification, the pre-fix API 29 job in
[build 916](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37105130242/job/111154281024)
failed before emulator launch: `setup-android@v3` requested its default retired
`tools` package. The ARM64 setup now requests `platform-tools`; the emulator
runner still installs the unchanged required image and executes the complete
release gate. This is an infrastructure correction, not a passing API 29 result.

## Expanded required release checks — verification for every build

Every opted-in candidate must pass the recorded-audio, native-page-size and
expanded Android profile jobs before the exact-run receipt permits publication.
The authoritative device list is `scripts/verification/profiles.json`: minimum
API 29, retained API 30/35, API 36 phone and foldable, and API 35 with 16 KB
pages. Provisioning or ARM64 translation failures are failures, not skipped
compatibility checks. All profiles reuse the signed release APKs.

Observable acceptance:

| Layer | Required behavior and failure case | Preserved behavior |
| --- | --- | --- |
| JVM/audio contracts | Actual recorded PCM retains opening, internal pauses and ending samples; original WAV reaches the native Gemma content DTO after its text instruction. Lost/reordered bytes fail. | Existing capture limits, decoding defaults and complete named regression suite. |
| Real speech models | Pinned Whisper and Moonshine runtimes recognize a checked recording with baseline-scored first/last words and a quiet trailing ending. Missing model/runtime or wrong checksum fails; existing production no-speech policy regressions remain required. | Production model inputs and explicit host-versus-Android limits. |
| Android upgrade | Install the previous published signed user candidate, seed device-local formats, install candidate with replacement, verify conversations, memory, settings, model bytes and durable receipts. Clearing data or losing any record fails. | Signing identity, device-local storage and no real user data. |
| Android recovery/platform | External process death, permission denial/regrant/revocation, and foreground-service controls produce durable, honest, recoverable results; unknown action outcomes cannot become success or duplicate effects. | All existing release journeys and microphone/task ownership. |
| Android UI | Large text retains usable labelled controls; actual fold/unfold changes display configuration and retains state. Missing labels, clipped controls or lost state fail. | Unified chat/call behavior. |
| Native compatibility | Both APK forms have valid ELF load alignment, safe RELRO page protection, and correct direct-load ZIP alignment; a genuine 16 KB device must load shipping native libraries. | Distinct Sherpa/Moonshine native ABIs and compact payload equivalence. |
| Evidence | Required profiles/phase outcomes, baseline/candidate hashes and raw test results agree with the same source/run. Missing, duplicate, failed or skipped checks block publication. | Existing provenance and failed-run evidence retention. |

The upgrade baseline comes from the numbered `audio-pr2-pr6-build.` user
distribution stream, selected below the current workflow build number and
verified against its published asset digest. This is an actual APK update with
inert fixtures; it is not inference from an installed multi-gigabyte model or a
claim that the previous APK's UI created every fixture.

The small recorded corpus establishes a reproducible regression baseline, not
general-public recognition quality. Direct Gemma waveform delivery is checked;
successful Gemma acoustic understanding with the exact model bundle, room echo,
microphone effects, OEM firmware, Bluetooth and physical GPU/thermal performance
remain separately unverified. All four exact-weight host recognition checks passed local calibration against
independently verified OpenSLR recording 1089-134686-0000: Whisper 2/28 word
errors and Moonshine 1/28, with correct first/last words before and after added
trailing silence. Baseline thresholds permit one additional interior word error;
the local measurement JSON explicitly identifies an uncommitted calibration
harness, rather than claiming release verification. The existing PR validation section and exact-run receipt record hosted outcomes
for each candidate, including failures and the source revision actually tested.

## Expanded gate first hosted run and harness repair

[Build 915](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37104292961)
tested PR head `55094bc1d51b74aac8c9d0e8d3a03cd60082bbe4`, merge
`e7d3a516c165655fcab01472812d1a0dbba7a93b`. The four real Whisper/Moonshine
recognition cases passed with the same 2/28 and 1/28 word-error counts as local
calibration. Recorded-audio artifact `11268035264` has ZIP SHA-256
`835cf4d247c4466723dcdd3ea8725d1a8e3bf9d5edcc8c2ae4f2edcc1dd4f755`.
The previous-release job verified published Build 907's normal APK, SHA-256
`b399a97387e541822ede61b56e7282fafcd27b2782e6a790cf746f84450fcac0`.

All 1,003 release JVM tests across 184 suites passed with no failures, errors or
skips, including the three newly required recorded-capture/Gemma-content cases.
JVM artifact `11268080636`, ZIP SHA-256
`93fdeafb9993af800b70d9553c9e1413f20457532e7c5212b9ccf46ad8ac9c9a`,
retains their raw XML.

Release instrumentation compilation then failed at layout line 85's missing
Compose experimental opt-in and upgrade fixture lines 66/69's heterogeneous
SQL bind-array inference. The harness correction adds the explicit opt-in and
`Array<Any>` bind types, retaining every scenario and assertion. The new upgrade
test also makes `MemoryTombstone` an explicit shared release/test ABI boundary;
a narrow keep rule preserves its getter for the independently shrunk test DEX. Android/native
compatibility jobs did not run and publication was blocked. Failed receipt
artifact `11267926618`, ZIP SHA-256
`a81604e10c3d0a7121e577a827cf845f6eb604ab5a2a1c2ef10fbd69e2e8bc30`,
retains the missing-upstream result. A fresh exact-revision full gate is required.

API 29 provisioning was also corrected before device verification. Google's
current API 29 Google APIs and Google Play x86_64 images declare only x86_64/x86,
so they cannot install the shipping ARM64-only APK. The API 29 profile instead
uses a standard Mac ARM runner, the genuine ARM64 API 29 image and explicit
software emulation (`-accel off`), with a 900-second boot and 60-minute job ceiling.
Official [TCG configuration](https://android.googlesource.com/platform/external/qemu/+/f0c183f1cc7456ecd6f3607f2f47893768ae4334/android-qemu2-glue/config/darwin-aarch64/config-host.h)
and [acceleration selection](https://android.googlesource.com/platform/external/qemu/+/f0c183f1cc7456ecd6f3607f2f47893768ae4334/android/android-emu/android/main-common.c)
include this software path; successful boot and release
journeys remain unverified until the next hosted run. The actual device API,
ARM64 ABI, page size and original signed-APK checks remain mandatory. The other
five profiles retain accelerated Linux provisioning; the actual 16 KB image
advertises ARM64 translation.

## Expanded gate second hosted run and provisioning repair

[Build 916](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37105130242)
tested head `24d408a89ecf6739faf0220bb0bbc5bca57d7e44`, merge
`4c7f0ec18f50ca4a11285ea501728da00279225b`. Signed normal and compact APK
production, all 1,003 release JVM tests, real recorded speech and both static
native 16 KB audits passed. Independent comparison of the final release/test
DEX confirmed 111 selected production method and 9 field references match the
shipping APK. The broader Compose boundary audit then identified the Dp method
described below. Actual APK hashes are:

- Normal: `eb1a845b5fbf34b178c686f1ffd1179d2dda74fd4547b5a657ddf9c08c6e1d5c`.
- Compact: `46d43a41c513dad5434a72bc1cfcaf012a392a0c9b253a5de00155cc8852d44a`.

Two profiles stopped before boot on concrete SDK provisioning defects. API 29
job `111154281024` requested the retired `tools` package through setup-android's
default; the correction explicitly installs only `platform-tools`, with the
emulator action owning its emulator/image installation. Foldable job
`111154281037` requested `pixel_fold`, absent from the hosted Tools 12.0 device
registry. Its replacement is that SDK's exact `7.6in Foldable` ID, an official
fold-in/outer-display definition with a 1768 by 2208 inner display, 884 by 2208
folded region and 0–180-degree hinge. Arbitrary spaced or shell-like identifiers
remain rejected. Actual fold/unfold, changed dimensions and retained state
remain required. API 30, API 36 phone and API 35 with 16 KB pages then passed the actual
previous-APK upgrade, all 48 existing journeys and separate-process selection
check. Native loading passed all nine libraries at actual 16 KB page size.
Layout tests failed because the helper ignored names on a control's own
noninteractive children, and the independently shrunk test DEX invoked the
R8-removed `Dp.constructor-impl(F)F` ABI. The correction resolves names only
within the action's own subtree and preserves the narrow Dp API; enablement,
clickability, expected labels, 48 dp targets and visibility remain required.
Supplemental lifecycle checks now run before layout so a layout crash cannot
hide their diagnostic evidence; every phase and the final all-pass requirement
remain mandatory. This run is not a full release pass and publication remains
blocked. Fresh current-head verification must include the concurrent farewell
lifecycle changes retained from the branch.

## Expanded gate Build 920 and SDK diagnostic repair

[Build 920](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37106704224)
tested head `aaf630119260dee0dbccdc522e249bce549fed40`, merge
`849ba56149151a5d603e75c45e2a1add1a672531`. Both signed APK builds,
all 1,005 JVM tests across 184 suites (zero failures, errors or skips), the four
real recorded-speech cases, previous-release preparation and the static 16 KB
native audit passed. All five Linux profiles stopped before emulator startup:
the newly added optional `avdmanager list device` diagnostic assumed the SDK
command was already on PATH. The concurrent SDK-path repair is preserved: it
resolves the command through PATH or the current SDK absolute path. Every
required device test and receipt assertion remains unchanged. API 29
software boot is still unconfirmed. Its intended software mode now also uses
`-feature -HVF`: the official ARM launcher can request HVF despite `-accel off`,
while the Apple Silicon capability probe does not check nested virtualization.
Disabling that feature makes the ARM launcher use the declared TCG path. See the
[feature-gated probe](https://android.googlesource.com/platform/external/qemu/+/f0c183f1cc7456ecd6f3607f2f47893768ae4334/android/emu/feature/src/android/emulation/CpuAccelerator.cpp)
and [ARM launcher](https://android.googlesource.com/platform/external/qemu/+/f0c183f1cc7456ecd6f3607f2f47893768ae4334/android-qemu2-glue/main.cpp).
Successful boot and journeys remain required. This run is not a complete release
pass; publication remains blocked until a fresh exact revision passes every profile.

## Expanded gate Build 922 and readiness repair

[Build 922](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37107489331)
tested head `4a4d9396064e9860b0433bf40a3d31399fcb1968`, merge
`7909b916dc2b0b0b3fd4f6c97b3f8d893d0fb148`. Both signed APK builds,
all 1,005 JVM tests (184 suites, no failures/errors/skips), the four recorded
speech cases and both static native audits passed. API 30, API 35 compact,
API 36 phone and API 35 with 16 KB pages passed all 48 existing journeys,
fresh-process selection, the real 907-to-922 update and every lifecycle phase.
Independent raw-evidence review confirmed the external unrecorded-effect kill,
no-repeat recovery, live microphone revoke/service death, denial/regrant and
notification controls. All nine current shipping ARM64 libraries loaded at an
actual 16,384-byte page size.

The supplemental layout failures are observed test-driver races and semantic
extraction defects. A control's explicit description child already says
`Pause microphone`; the helper incorrectly appended the decorative `Ⅱ` text.
The 200% font screenshot still shows the placeholder, while the following XML
shows the exact entered draft and enabled Send control. The correction prioritizes
explicit descriptions within the same action subtree, waits for the exact draft
and enabled Send, and observes completed callbacks/overlay dismissal before
asserting exact counts. Every expected label, 48 dp target, visible bound,
200% font, saved draft, conversation/call identity and dimension-change assertion
remains mandatory.

The generic foldable image booted with the expected API, ARM64 bridge and page
size, and passed the real upgrade. Two existing selection journeys stopped on
the family chooser after an unsettled search/tap (`test12` and fresh-process
`test90`); their retained PNG/XML show no model-list transition. They now reuse
the existing settled text and enabled-tap helpers and require the model-list
boundary before scrolling to the same exact model. Actual fold/unfold and its
state-retention checks did not run in this failed candidate.

API 29's explicit HVF disable overcame the previous launcher error: its genuine
ARM64 image reached the boot-completed flag under TCG in 535 seconds. The action
then failed before our controller at unlock with `ServiceNotFoundException: No
service published for: input`. A boot property alone is not usable Android
verification. A software-specific launcher must retain emulator/guest logs and
require bounded input/activity/package/window readiness plus successful unlock
before invoking the unchanged full controller; the 900-second boot and 60-minute
job limits remain. Missing services or a failed controller remain failures.

The consolidated receipt correctly rejected this run and all publication jobs
were skipped. Failed receipt artifact `11268648681`, ZIP SHA-256
`c58a1b525b01b9be4d36ef8d5c90b3e247f33adc7962efd0e984739e9e86ea77`,
retains the failed upstream/missing API 29 evidence result. The next revision
requires a fresh complete release gate; no partial pass authorizes publication.

## Expanded gate Build 923 and fold journey repair

[Build 923](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37109843675)
tested head `3c1fa2ee74470f21072d800f91be8493ec717ac2`, merge
`658be06b4091b09f77eebc258cd5b6c97363cd4c`. Both signed APKs, all 1,005 JVM
tests across 184 suites with no failures/errors/skips, all 136 helper tests,
the four pinned recorded-speech cases and both static native audits passed.
Independent final-APK DEX review resolved all 173 selected methods and 11
fields. The normal APK SHA-256 is
`3fefb43c0b7f061eb4dd761a326bbc6ffdd99b0fa06e7bb2e62321f4b9943010`;
compact is `a9e99ef8f2e208f9cbf454716626c591373a3d9317acba1dae643ec0e9577b76`.

API 30 normal, API 35 compact and API 36 phone independently passed the full
profile: all 48 required journeys and fresh-process selection, actual
907-to-923 update, all eight external lifecycle phases, all four layout cases
and all nine shipping native-library loads. Current 200% font/320 dp and
rotation screenshots were inspected. Their controls remain visible and usable;
header and placeholder text wrap heavily at this font scale. This verifies the
tested semantics, target sizes, callbacks and state continuity, not a complete
TalkBack, contrast or visual-design audit.

The fold profile passed the upgrade and 46 of 49 main instrumentation cases.
Its `test11` still tapped the model family before the search/UI transition
settled; PNG/XML show the family chooser. In `test12`, the correct last-model
Choose button was clipped to 104 pixels by the list viewport while the full
48 dp target is 126 pixels. `test24` sought a reopened erase confirmation
before the dialog appeared, and its page-seeking gestures dismissed it. The
narrow repair settles the exact family/list boundary, physically scrolls inside
the list until a freshly acquired full 48 dp target is visible above navigation,
and waits for the exact modal before a single non-scrolling confirmation tap.
Actual selected/restored model IDs are additionally checked in durable
preferences after browser dismissal. The navigation inset uses the API 29
legacy fallback below API 30. All original model, erase and history assertions
remain; real fold/unfold still requires a new successful run.

The 16 KB guest passed the real upgrade and the first 18 main cases, then
Android `system_server` crashed in platform
`AppIdleHistory.getPackageHistory` with SIGSEGV. Its unchanged 4 GB emulator
configuration and identical image fingerprint passed Build 922. No Jarvis
assertion preceded the guest crash, and no evidence supports a production or
RAM change. Failed artifact `11269382996` is retained; actual 16 KB loading
must pass again on the new revision.

The API 29 launcher retained its failed startup evidence for the first time
(`11269778165`, ZIP SHA-256
`b1eb288bf18442f1ac5a24adce7923b091dcb01a675ecab7544dabafbcdcbf24`).
The Google APIs guest failed `PermissionPolicyService` permission initialization,
then a replacement `system_server` was watchdog-killed while waiting on
`installd.createAppData`. Binder services disappeared and reappeared. Although
all four eventually answered, cold input exhausted the remaining boot budget;
there was no observed unlock or controller result. The next profile uses the
official stable AOSP API 29 ARM64 image (`default`, revision 8) instead of the
Google APIs bundle. It still requires actual API 29/native ARM64/4 KB pages,
all services, successful cold input, observed unlock and the unchanged complete
controller within the same 900-second boot and 60-minute job limits. Reduced
startup load is an inference, not verified compatibility or a passing result.

The consolidated receipt validated the three complete phone profiles and
rejected missing API 29/controller evidence, fold failures and the 16 KB crash.
Receipt artifact `11269618551` has `passed: false` and
`release_approved: false`; all publication jobs were skipped. The repaired
revision must complete a fresh full gate before any new APK can be published.

## Expanded gate Build 924 and off-hinge chooser diagnostics

[Build 924](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37111983640)
tested head `ba83b55e6f4b4f3072ba3459d4e95c877f23ef81`, merge
`c558e6502b816716221f7578cdf58f5f04edc3b3`. Signed builds, all 1,005 JVM
tests across 184 suites, all 138 helper checks, the four recorded-speech cases,
static native audits and final-APK ABI checks passed. API 30 normal, API 35
compact, API 36 phone and the true 16 KB profile independently passed their
complete upgrade/main/lifecycle/layout/native checks. All nine shipping native
libraries loaded at actual 16,384-byte pages. Current 200% font screenshots were
inspected. Their tested controls remain usable, with heavy header/placeholder
wrapping at this scale.

The fold profile passed its real upgrade and 48 of 49 main cases. The settled
family and non-scrolling erase-modal repairs passed. The last CodeGemma Choose
target still had only 104 visible pixels instead of its full 126-pixel/48 dp
height, ending at the model-list viewport bottom. Five physical gestures and
two stationary content observations did not expose it. This correctly remains
a failure; lifecycle and actual fold/unfold phases could not follow it.

The official SDK Tools 12 `7.6in Foldable` definition declares a
`884-0-1-2208` hinge area on the 1768-pixel-wide inner display. The test used
exactly `viewport.centerX() == 884` for its swipes. This establishes an avoidable
gesture-coordinate ambiguity, not proven hinge causality or a production
padding defect. Source inspection confirms the bounded weighted model list
already has 32 dp bottom content padding plus final-card padding and safe
drawing/IME insets.

The next repair physically swipes at the quarter-width of the actual list,
away from the declared center hinge, and records bounded before/after
viewport/target geometry and observed movement in logcat and raw instrumentation.
Independent insertion of these non-reserved diagnostic status bundles into
actual passing output preserves all 49 parsed test results. Full 48 dp width
and height, containment, navigation clearance, stable reacquisition, durable
selected/restored model IDs, stationary failure, gesture cap and the 15-second
deadline remain mandatory. A fresh runtime must determine whether off-hinge
scrolling exposes the target; actual end clipping would require a separately
evidenced production fix. API 29's AOSP startup/controller result is assessed
independently by the same run. The fold failure already prevents publication.
Live current-head status and its consolidated receipt remain authoritative.

## Expanded gate Build 925 and dialog-window sizing

[Build 925](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37113785620)
tested head `204b222fbfafd4ac857b8444b0092df2e5bef2c6`, merge
`060aecc3ac97c90702b77831cb31867cac764fff`. Signed builds and static native
audits passed. API 30 normal, API 35 compact, API 36 phone and the true 16 KB
profile completed their full required checks. The 16 KB guest reported actual
16,384-byte pages and loaded all nine shipping native libraries. Both APKs'
native audit was independently recomputed from the same-run signed bytes.

The generic foldable again passed its real upgrade and 48 of 49 main cases;
test12 retained its strict full 48 dp failure. Quarter-width gestures at x=442
actually moved the model list, then two stationary observations left the last
Choose target at `[1434,2020][1685,2124]`: 104 visible pixels instead of 126.
The list and every dialog/content ancestor ended at y=2124. An adjacent Choose
exposed the full 126-pixel target around its normal 105-pixel painted button.
The final target therefore loses 22 pixels to the window boundary; its painted
40 dp appearance does not establish a complete accessible touch target. The
retained PNG/XML and bounded raw geometry distinguish this crop from the
earlier center-hinge gesture ambiguity. Artifact `11271014252` retains the
failure; actual fold/unfold, layout and lifecycle phases were not reached.

The pinned Compose UI 1.7.6 `DialogLayout` ignores ViewRoot measure constraints
when `usePlatformDefaultWidth=false`, substitutes configuration display width
and height, then fixes the native window to the measured child. Its true branch
honors the received window constraints. The narrow production repair enables
that branch and explicitly sets the dialog window to `MATCH_PARENT` width and
height through `DialogWindowProvider`, preserving the full browser width. An
idempotent composition effect avoids redundant window resizing. Safe drawing
and IME insets, list/card padding, all 48 dp/navigation/selection assertions and
the bounded physical gestures remain unchanged. No extra padding or dependency
upgrade is used. A fresh exact-revision full gate must establish that the final
target is complete and that real folded/unfolded controls remain usable; these
generic-foldable results are pending. Build 925's failed receipt continues to
block publication. API 29's framework watchdog killed `system_server`; the
package service then vanished and installation of the prior Build 907 APK
failed with exit 1 after 10.61 seconds. No app instrumentation started, and the
retained screenshot is black. Artifact `11271395102` retains that separate
platform failure; it supplies no app/controller pass.

## Expanded gate Build 926 and foldable display backend

[Build 926](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37115654938)
tested head `d708138c32f1cd389fd50d43e5e09e90eac5d880`, merge
`2e43bc690f953f9cf5518ab42ebdce548158f73e`. The dialog-window repair passed
all 49 main cases on the generic foldable, including durable selection and
restoration. Its last Choose target now exposes `[1433,1873][1684,1999]`, the
full 126-pixel/48 dp height above navigation, within the unchanged full-width
list. Actual PNG/XML confirm the crop is fixed. The fold profile also passed
its previous-APK upgrade, all lifecycle phases, the other three layout cases
and all nine native loads. API 30/35/36 phones and the true 16 KB profile passed
their full required phases; actual 16,384-byte native loading was retained.
Current 200% font screenshots were inspected for all five Linux profiles.

The actual fold/unfold case still failed. Console fold returned success and
Android logged device-state transition 3→1, but LogicalDisplayMapper applied
an identical display layout. Both baseline and failed app content remained
`[0,0][1768,2208]`; the strict 45-second dimension-change assertion failed.
Artifact `11271976850` retains that failure. Console acknowledgement and a
closed posture alone do not establish resizing or continuity across a fold.

Public emulator source at `ae9d18d2b6261179fbd57fffec720a04f7bfb053`
(35.6.3 Canary, March 27, 2025) has empty headless display-region/posture UI
callbacks, while its Qt backend propagates the configured folded area and
lid event. An older source revision has the same distinction. These sources
are not the exact 37.2.12 release used in Build 926; together with the observed
unchanged window they support an infrastructure repair to verify. Only the
fold profile now starts an owned Xvfb display and uses the windowed Qt emulator.
Only `x11-utils` is installed for the missing `xdpyinfo` probe, with bounded
package update/install commands; SDK 37.2.12 bundles the inspected XCB/cursor/xkb
helper libraries, and that plugin's inspected system libraries are already
present. This dependency audit did not cover the separate GUI emulator executable;
Build 927 below records that gap.
Readiness is bounded to 15 seconds, cleanup checks PID ownership, and logs are
attached after the controller. Other emulator options/profiles, all physical
dimension/continuity assertions, insets, padding and deadlines remain intact;
the controller does not substitute a direct window-size override. A new
exact-revision runtime must prove real folded/unfolded geometry and all tests.
That result is pending, and publication remains blocked.

API 29 reached core-service readiness, then cold input took 56.979 seconds and
an unlocked keyguard was observed. Installation of the prior Build 907 APK
exhausted its 180-second timeout; no successful install was observed and the
Jarvis package remained unavailable. Earlier boot logs contain
a fatal `PermissionPolicyService` exception and `system_server` PID 296 ending
with signal 9; SystemUI and phone/dialer ANRs followed during installation.
No later watchdog kill was found in this run. No instrumentation or current
APK installation started. Screenshot and logcat collection also timed out
(60 and 30 seconds); no PNG exists. Artifact `11271673105` retains this
platform failure. Readiness and unlock do not supply a controller pass, and
the strict API 29 requirement remains unchanged.

## Expanded gate Build 927 and GUI emulator dependency failure

[Build 927](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37118011588)
tested head `5b318fa060b19ce33b0549e71715bede5868fe1d`, merge
`f6e243cce9a859703542f6a7acc1e2260f251d78`. The signed producer and static
native gate passed. All four phone profiles completed all 49 main cases, four
layout cases, the actual 907→927 upgrade, process restart and all eight lifecycle
phases. Their source/APK hashes and raw instrumentation agree. All nine shipped
libraries loaded in each profile, including actual 16,384-byte pages in the
true 16 KB profile. The four current 200% font PNGs were inspected; artifact
`11273280050` retains the passing 16 KB profile.

The fold profile's bounded
`x11-utils` installation, owned Xvfb startup and `xdpyinfo` readiness passed.
Its retained display is 1920×2400, and cleanup confirmed the owned server was
gone, a zombie, or no longer the recorded PID owner. These are host setup
outcomes, not app or fold-test passes.

At 11:09:03 the actual SDK 37.2.12 GUI `qemu-system-x86_64` loader failed:
`libpulse.so.0` was unavailable. No guest boot, controller, current-APK
installation or app instrumentation followed; the unchanged 300-second boot
limit expired. Artifact `11272701104` (ZIP SHA-256
`93d8572ae63c88e94d69a831c268537a435c2f4fc480a69f876b273763d3c800`)
retains apt, Xvfb, display and cleanup diagnostics. There is no app report or
folded/unfolded PNG/XML. The failure remains required evidence and blocks
publication. The Qt plugin audit had missed the GUI executable's separate
dependency chain. A complete recursive audit of 53 actual SDK 37.2.12 ELF files
and 29 external SONAMEs against the runner inventory identified `libpulse0` as
the only missing runtime package. The next fold-only repair installs it beside
`x11-utils`; apt resolves its required dependencies. A fold-only pre-launch hook
also retains the installed emulator version and GUI executable/XCB plugin `ldd`
receipts using the SDK's bundled library paths. It runs after the action updates
the emulator, has a 15-second timeout plus a two-second kill grace, and fails
on missing files, unsuccessful commands or unresolved libraries. The action
marks a failed hook red but still attempts boot; this is a loader receipt, not
an early boot abort. All controller
assertions, other profiles, emulator options and time limits remain unchanged.
Actual fold resizing and continuity still require a fresh full run.

API 29 never handed control to the app verifier. Four core-service binders
were observed, cold input succeeded in 102.726 seconds, and the three scoped
keyguard flags were false. The third animation-setting command then exhausted
its existing 30-second command cap (30.012 seconds, exit 124); the shared
900-second startup budget was not exhausted. Earlier boot logs contain a fatal
`PermissionPolicyService` exception, `system_server` PID 235 ending with signal
9, and a SystemUI `KeyguardService` ANR. No later watchdog kill was found.
There was no prior-907 installation, current-APK installation, controller
report or instrumentation. The required API 29 gate remains failed, and its
configuration and thresholds are unchanged. The final receipt is false;
publication was skipped.

## Expanded gate Build 929 and navigation observation repair

[Build 929](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37119723759)
tested head `05fee0be5e4b87c279c872f34fc4fc219a4a7660`, merge
`e43bcc21d1fd385635a727d6ac7b24783206a9f9`. The signed producer and static
native gate passed. All four phone profiles passed their raw 49 main cases,
four layout cases, actual 907→929 upgrade, process restart and eight lifecycle
phases. Their exact APK/test hashes agree with the producer. All nine shipped
native libraries loaded in each phone profile, including actual 16,384-byte
pages in artifact `11273765024`. The four current 200% font PNGs were inspected.

The fold host now successfully installed `libpulse0`, started its owned Xvfb
display, and retained successful SDK 37.2.12/build 16428233 GUI executable and
XCB plugin loader receipts with no unresolved libraries. Display readiness and
ownership-aware cleanup passed. The actual 907→929 upgrade passed, but the main
suite finished with 47 passes and two failures: test 12 exhausted its unchanged
15-second navigation limit after three moving swipes while the accessibility
target remained missing; test 45 exhausted that limit while revealing Copy
JSON. The final test 12 PNG paints the full CodeGemma Choose above navigation,
while the subsequently collected XML still represents an earlier list position
and omits that control. Test 45's final XML exposes an enabled, clickable Copy
JSON at `[42,663][293,789]`, a full 126-pixel target. These observations support
lagging accessibility discovery; they do not prove a renewed production
clipping defect. Artifact `11272859460` (ZIP SHA-256
`27d59b699357832c1dd8dedb50c1d18a28b18aa74f24ec0ba36c7326c657a947`)
retains those failures and GUI diagnostics. Restart, lifecycle and supplemental
layout/native tests did not run after the main failure. No fold/unfold command
or folded/unfolded PNG/XML proves posture coverage in this run.

The pinned [UiAutomator 2.3.0 sources](https://dl.google.com/dl/android/maven2/androidx/test/uiautomator/uiautomator/2.3.0/uiautomator-2.3.0-sources.jar)
show that queries wait for accessibility
quiet and every `UiObject2` getter waits and refreshes its node. The helper's
paired recursive signatures repeat these operations hundreds of times per
swipe. The 500-millisecond quiet criterion is not a fixed delay per accessor,
and the source cannot assign all observed elapsed time to those calls. Found
nodes are refreshed, but child discovery still uses the accessibility cache.

The next revision limits its repair to these navigation observations on API
34 and newer: it saves the configured implicit idle timeout, sets it to zero
inside read/readiness scopes, and restores it in `finally`. Each independent
discovery or viewport-signature sample requires successful public
[`UiAutomation.clearCache()`](https://developer.android.com/reference/android/app/UiAutomation#clearCache()),
available from API 34 and returning whether the cache was cleared. Explicit
settlement waits remain active. The fresh
second model observation reacquires the actual list and navigation bounds;
benchmark configuration is restored before the single physical click and its
original post-click idle wait. API levels below 34 retain their original
behavior. All 15-second deadlines, gesture/stationary limits, stable geometry,
full 48dp targets, viewport/navigation bounds and exact durable selections
remain required. These sequential observations are not an atomic snapshot.
There is no workflow, production UI or API 29 configuration change. API 29 also
failed before current-app instrumentation after a framework watchdog failure
blocked baseline installation. The final receipt is false and publication was
skipped. A fresh exact-revision runtime must still pass all profiles and prove
actual folded/unfolded dimensions and continuity.

## Build 930 installation and navigation repair

[Build 930](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37122256536)
tested head `f82ee6af7031d1a49b43a0e731135fb0020a89ae`, merge
`ca4930cae001e77b9932c723d7e857c5504a09bf`. Signed APK production,
all 1,005 release JVM tests, recorded speech, native page-size audits and the
four ordinary phone profiles passed. The foldable passed the farewell/wake
session test48, but failed the same two navigation cases, test12 and test45,
within their unchanged 15-second limits. Main-suite failure correctly prevented
the subsequent actual fold/unfold phases. The required API 29 and foldable
results remain failed, the receipt is false, and publication was skipped.

Foldable artifact `11274123162`, ZIP SHA-256
`e88ecc69d6c61552f8545632712df2b733b48a7c684cacbe4d1ee31f2396ec96`,
retains 47 successful main cases and two failures. Its inspected test12 PNG/XML
shows the full 48dp Choose target finally exposed after the navigation deadline.
The before/after accessibility observations took roughly 80–320 milliseconds,
while each model swipe took 5.6–6.9 seconds and benchmark swipes took roughly
4.1–4.3 seconds. This narrows the dominant cost to gesture delivery/settlement;
the pinned UiAutomator 2.3.0 controller synchronously injects each swipe step.
The harness now uses twelve steps instead of thirty-five and strokes from 15%
to 85% of the actual viewport, with the benchmark path in its left quarter to
avoid the hinge. Fresh accessibility discovery, explicit settlement, stable
second bounds, full 48dp controls, one physical tap, the 15-second deadline,
fourteen-gesture cap and stationary-edge checks remain required. The complete
journey matrix must still establish that intermediate controls remain reachable.

API 29 artifact `11274417685`, ZIP SHA-256
`5d056e8c19316ebc541b928b35dc96c7b9bfb3d4b6b9507bbcd03766995d8499`,
booted and unlocked but timed out installing the actual previous APK after
180 seconds; candidate instrumentation never began. Guest SDK setup crashes
show the invalid autodetected timezone `Unknown/Unknown`. The software launcher
now supplies `-timezone Etc/UTC`. Its profile uses non-streaming APK installation
to separate file transfer from the package-manager command; this transport
experiment is not a proven explanation of the stall. It preserves the same
signed APKs, replacement upgrade, 180-second install deadline and full checks.
Accelerated profiles retain their existing installation transport. No product
audio behavior, release gate assertion or publication dependency is changed.
A fresh exact-revision full gate is required before handing out a newest APK.

The next head `9a2c3e2c6848fe4d4ee8be9ad0bdc9cb48a4b4cb` passed local
architecture and all 143 Python helper tests, but GitHub could not create a PR
merge candidate after `main` diverged. Its eight incoming commits through
`37f037e384db4c1cefaa7e2c738ef226ae2c0cf5` add the license/demo and change
README and the old foundational workflow. Branch reconciliation retains the
complete audio workflow unchanged, including every expanded gate and PR6
candidate publisher; adopting the older workflow would remove required checks
and require a PR merge that has not been authorized. The incoming license/demo
assets and current documentation are retained. This is a branch merge, not a
merge of PR6 into main. All production, harness and regression blobs from
`9a2c3e2c` remain unchanged; a fresh combined candidate still requires CI.

## Build 936 clipboard-preview navigation repair

[Build 936](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37172234250)
tests head `4f427ed6da260d8ecbca31f2a6364b8568aba93d`, actual merge
`e5a0a76026a5acbee4eb7e898f9ac1bc97ff5570`. The initially reported PR merge
`ed5b40c0fc4d927481bdeb61ed2e7ee98a888458` has the same parents and tree;
the run's workflow and artifact source fields identify the actual tested merge.
Both signed APKs, all 1,005 JVM cases, recorded speech and native 16 KB audits
passed. API 30 passed its complete device gate; the other five device profiles
failed. These results do not establish a clean APK.

API 36 phone artifact `11292107914`, ZIP SHA-256
`24256e51b498efce1141811b5de312abb2e849e47edc339799947b38d779ceb5`,
passed the actual upgrade and 48 of 49 main cases, including test12 and test48.
Test45 failed revealing the completed sample after Copy JSON and store reload.
The inspected PNG still shows Android's clipboard preview at the lower left,
including its dismiss circle near `[273,1598]`. The actual viewport is
`[42,231][1038,1815]`; both physical DOWN strokes start at `[291,1577]`,
inside that visible overlay. Their fresh viewport signatures are stationary.
Before copying, the same sample's navigation succeeded. Clipboard interception
is the evidence-supported explanation; no fling overshoot is demonstrated and
no input-dispatch trace proves exclusive causality. The benchmark path now uses
the right quarter of the actual viewport (`x=789` on this phone, `x=1305` on
Build 936's legacy unfolded profile), avoiding the observed clipboard preview
and central hinge. The new Pixel Fold uses its own observed viewport.
Model-list gestures retain their left-quarter path. At that revision, twelve
steps and a 15–85% stroke,
all deadlines, full/safe/stable bounds, gesture limits and single-tap checks stay
unchanged. Actual folded clipboard geometry is not inferred from this phone PNG;
the main foldable benchmark journey runs unfolded. A full fresh matrix is required.

API 35 true 16 KB artifact `11291824807`, ZIP SHA-256
`e27eecdde5318331400b5043043710cc6ba1c3eb50130e16780fcdf11ab7269b`,
passed the upgrade and main cases01–19 before Android's `system_server` crashed
in the next case's setup. Its native trace shows an ART null-pointer dereference
in `NterpGetStaticField`, through `PackageDexUseProto.dynamicMethod` and
`DexUseManagerLocal.save`; all 38 frames are platform/runtime frames. Subsequent
UiAutomator binder and device I/O failures follow that crash. This supports an
infrastructure classification, with the failed evidence retained. No check or
system-image requirement is removed; the next candidate must pass this profile.

The completed receipt is false and all publishers are skipped. API 35 compact
artifact `11292018480`, ZIP SHA-256
`fb4765c8864bcc58a90290e7a738fd15da85ba034a18563f6cbfeed61ad330c3`,
passed the upgrade, but a Pixel Launcher ANR dialog covered Jarvis throughout
all 49 main-test setup failures. Their test bodies, including test48, never ran.
The inspected first-launch PNG/XML show the same platform modal over the app;
the retained post-upgrade log does not contain the originating ANR stack.
This warrants re-exercising the unchanged compact profile in the next full run.

API 29 artifact `11291878809`, ZIP SHA-256
`f8e9fc3b52b5fc12b209a4d7a0e30e18ee4e4469cf5e3ae57c2e034c3c1f9cf1`,
never reached installation. UTC is correct in the actual command and guest
properties, with no former `Unknown/Unknown` setup crash. Android's default
permission-grant request timed out after its internal 30-second limit, causing
`PermissionPolicyService` to kill `system_server` and restart it. Cold input then
took 127 seconds and the second animation-setting command exceeded its existing
30-second limit. Guest ANR evidence reports high CPU load, including long GC,
without an OOM or disk error. Any host/AVD resource correction still needs actual
boot, installation and the full unchanged controller; a boot flag is insufficient.
The next software launch retains two vCPUs, the same API 29 default ARM64 image,
UTC, renderer and deadlines. It uses 2 GiB guest RAM and the recognized
`vm.heapSize=256M` property instead of the unrecognized `hw.heapSize`, with a
540×960 framebuffer at 210 dpi. This preserves the exact 411.43×731.43 dp
Pixel 2 viewport while reducing raster pixels fourfold. RAM headroom and lower
CPU graphics load are bounded provisioning experiments, not proven fixes or
evidence of memory exhaustion. Read-only host CPU/memory/process receipts have
at most two seconds per command and consume the existing boot/job budget;
receipt failures cannot replace the original boot failure.

Foldable artifact `11291729306`, ZIP SHA-256
`4f655d1c570caa710fbd162de015868ef13c25e92091d57a5bfe11b1ea2a32f7`,
passed all 49 main journeys, both upgrade phases and all eight lifecycle phases.
The new gesture work reaches the valid Choose target with almost ten seconds
remaining, and Copy JSON gestures take roughly 1.6–1.7 seconds. Three layout
tests pass, including the visually inspected 200% font controls and all nine
native library loads. The actual fold command succeeds, but display dimensions
stay `1768x2208` throughout the unchanged 45-second wait; unfold is not reached.
No folded screenshot proves posture coverage. The generic hardware path requires
provisioning investigation, with genuine folded/unfolded dimension changes and
continuity still mandatory.
The next candidate replaces that generic profile with the genuine `pixel_fold`
definition from official command-line tools 23.0/build 16111833. Its catalog
declares a 2208×1840 inner display and 1080×2092 cover display. The fold-only
SDK step verifies the selected version and `pixel_fold` catalog entry and makes
it the runner's `cmdline-tools/latest`; merely adding a versioned SDK directory
would leave emulator-runner using the old catalog. The owned X display, GUI
loader receipts, true fold/unfold commands, 45-second dimension waits, active
call and draft continuity, all six profiles and all other checks remain required.
This is a provisioning correction pending actual folded/unfolded evidence.
Official catalog: https://dl.google.com/android/repository/repository2-3.xml

## Build 937 known-control navigation alignment

[Build 937](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37174425659)
tests head `b54087e327f8bb16569cbff2eda63e05e8ef9b09`, merge
`4cb20365f4d34c5d5ee9be72b16b48ecd58fb024`, tree
`7a625816d662426a6eecd8f205b961fc2e4d0319`. Both signed APKs, all 1,005 JVM
cases, all four recorded-speech checks and both nine-library native 16 KB
audits pass. API 30 passes its complete device gate; the other five profiles
fail. Receipt `11293146777`, ZIP SHA-256
`a70b729a17de1b0491112d5b2fa2da2d040d252bcb05762af40612f3612c8e16`,
is false and all publishers are skipped. No clean APK is established.

API 35 compact artifact `11293740180`, ZIP SHA-256
`cf51a064453202ac781998ed4ffa52df1a7f8d408772e3dacc72064b845ab34c`,
passes the actual upgrade and 48 of 49 main cases, including test12 and test48.
Test45 fails at line2578 revealing `pipeline_benchmark_reference` after the
completed sample is reopened from the restored store. The control briefly
appears after DOWN, then the helper sends a full UP stroke, reaches a stationary
top edge, reverses once and reaches the bottom with 4.875 seconds still left.
The inspected PNG shows lower expanded metrics and the cancelled sample, with
the reference above the viewport; no clipboard overlay or platform modal appears.
There is no retained fatal/ANR marker. Lifecycle/layout phases do not run after
this main failure. The extra missing layout evidence error is a consequence of
the controller's failed main phase, not a completed layout pass.

API 36 phone artifact `11293530650`, ZIP SHA-256
`259284c095b0e9437cdce49c3960c2313eaf0b3eba2cb6a60a21dff7a8b70aab`,
also passes the upgrade and 48 of 49 main cases, including test12 and test48.
Its test45 fails at line2511 on the reference control before scoring, after
opening the completed sample and selecting NOISY. The target exists before a
full 1,109-pixel DOWN stroke sends it out of the visible hierarchy. Later UP
strokes find it again, but another full DOWN stroke loses it; the helper reaches
the second stationary bottom with 4.675 seconds still left. The inspected PNG
shows the expanded card's lower metrics and cancelled sample without a clipboard
overlay. The XML reflects teardown and does not prove failure-time bounds.

These failures support a known-control alignment defect in the harness, rather
than a failed scoring/privacy assertion or a platform retry. Exact target bounds
were not logged, so a specific zero rectangle or exclusive fling cause is not
proven. The correction ignores empty rectangles when choosing direction and
uses a shorter, slower physical adjustment when a positive-size target overlaps
the viewport: at most one fifth of the viewport, 24 injection steps and observed
edge distance plus room inside it. Absent or wholly off-viewport targets retain
the 15–85% search stroke and 12 steps in the same right-quarter path. All full/safe bounds, the 15-second
and 14-gesture ceilings, two stationary observations, one edge reversal,
300-ms stable enabled observation, single tap and test-body assertions remain
required. Navigation receipts now retain before/after geometry and adjustment
mode. The next complete matrix must establish that this correction works.

API 35 true 16 KB artifact `11293031512`, ZIP SHA-256
`5aa148bc6dd3d7638f3c63f3f7045db09a03131e0e3982eca39a891163bf6c35`,
passes the actual 16,384-byte page/ARM64-bridge check, upgrade and 48 of 49 main
cases, including test48. This time there is no retained platform crash/ANR marker.
Test45 passes the restored reference check and reaches reset, then fails its
plain empty-state text lookup at line2582. The inspected PNG and XML show
"Retained benchmarks reset." and zero retained attempts; the Recent samples
header is clipped at viewport bottom1815 and the expected next lazy item is
below it. Reset succeeded; the lookup did not scroll. A bounded read-only reveal
keeps that exact text-presence assertion and the later flush/reload-empty check.
Existing controls retain all tap visibility margins; the final unpadded text
item is read without imposing a click-only inset that it cannot satisfy.

API 29 artifact `11292887329`, ZIP SHA-256
`93cafec1fcc7782fad4bf2649237bc710e1a48397f7130c12347477bff9f3e44`,
confirms physical 540×960 at 210 dpi, the intended RAM/heap/two-vCPU settings,
UTC, native ARM64/API 29, all services and observed unlock. The controller runs,
but the previous Build 907 APK installation exceeds its unchanged 180-second
limit before any journey executes. Guest logs show its speed-profile dexopt
competing with BOOT_COMPLETED receivers; the actual user0 broadcast finishes
well after the earlier boot flag/services observation. Host receipts show no
swap traffic and substantial free memory, so memory exhaustion is not supported.
The API 29-only launcher also retains the version experiment added on head
`082e3e683c30c49c03bc889787ad193184f4cb90`: official Apple Silicon Emulator
37.2.6/build16138043, ZIP 419,847,722 bytes, SHA-256
`ca9eeb7857771de6219591a70b39342ac2d056b7701d1df0d0c719f41260f4a5`.
Its download, checksum/size, safe staged extraction, package metadata and staged/
installed binary version checks share one 600-second provisioning budget. A
failed replacement restores the previous emulator. This preserves the same
guest image, software renderer, resources, real API/ABI/page-size observations,
900-second boot and 180-second install limits and complete controller. It is a
controlled version experiment; Build 937 does not prove that an emulator-version
regression caused its installation failure. Other profiles retain their emulator
provisioning. [Build 939](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37176281538)
tests that head's first navigation, fold-unlock, screenshot and version-pin repairs;
it is not yet a verified APK.

The next launch requires actual BOOT_COMPLETED delivery to user0 for the current
system_server, using filtered current-session logcat and matching process IDs,
before the existing animation settings and final service/display checks. Every
probe consumes the same 900-second boot deadline; install and job limits remain
unchanged. This stronger startup barrier is a contention experiment, not proof
that either installation will finish within 180 seconds.

Pixel Fold artifact `11293521290`, ZIP SHA-256
`f308900af5564eeddbb8c86ba8a80948aec250faaae3a97d363c90b9a21e00f3`,
passes all 49 main journeys, both upgrade phases and all eight lifecycle phases.
The pinned catalog and GUI loader receipts pass. The real fold switches default
display0 from 2208×1840 to the 1080×2092 cover; platform fold policy then shows
dismissible keyguard. The inspected test03 PNG/XML show the lockscreen, and its
composer lookup fails before call/draft continuity assertions. The same activity
instance stops at this point, with destruction only during teardown; fixture
recreation is not established as the cause. The sleeping device then causes the
other three layout setup failures. Folded continuity and subsequent unfold remain
unverified. The next transition wakes and dismisses the disposable keyguard after
the genuine dimension change, observes actual dismissal, and retains all original
continuity assertions within the same transition deadline. No activity relaunch
or fixture reset substitutes for continuity.

The fold's host baseline screencap also carries a multi-display diagnostic prefix
before its PNG signature, while per-test UiDevice PNGs are valid. Host snapshots
now remove the previous remote PNG, write a fresh remote PNG and pull its bytes
separately, retaining command warnings in receipts and rejecting invalid images
or failed commands. The shared 60-second snapshot budget is not renewed between
commands. Positive dimensions, complete PNG chunks/CRCs and a parsed XML hierarchy
remain required. Actual posture proof
continues to use the per-test folded/unfolded snapshots.

## Build 940 metric discovery and fresh hierarchy evidence

[Build 940](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37176861888)
tests head `bea2456b8ca7822d4260dda8230641079d94aa00`, actual checkout
`2a5e594722fe95cdbfa246fe4d6b6c9fea24c036`. The signed producer, 1,005 JVM
cases, recorded speech and native packaging audits pass. API 35 compact, API 36
phone, true 16 KB and Pixel Fold each pass all 49 main cases, restart, both
published-907 upgrade phases, eight lifecycle phases, four layout cases and nine
native-loading checks. Pixel Fold changes 2208×1840 to 1080×2092 and back,
retaining the same conversation, unsent draft and armed controlled call. Its
transition receipts observe dimension change before wake/unlock/composer readiness,
within the original 45-second deadline. This does not establish physical audio or
real Android language-model inference.

API 30 passes its upgrade and 48 of 49 main cases. Test45 fails while searching
for the exact read-only `tts_load_ms: unavailable` label after scoring and human
review. The target is absent from every observed query: six full discovery strokes
reach the lower edge, followed by five strokes back to the upper edge. Fine target
alignment never runs. The PNG shows two attempts and the reviewed ASR score; XML
was captured after fixture cleanup and cannot establish search-time expansion.
No app fatal or ANR is present. Other profiles and the earlier passing API 30 run
show the same literal label; production metric rendering is unchanged.

The explicit API 30 metric-search correction uses overlapping half-viewport
discovery gestures with a stationary endpoint, retaining real injection success,
fresh viewport observations and the same 15-second/14-gesture/edge bounds.
UiAutomator 2.3.0's own scroll gesture holds its endpoint for 250 ms; the public
point-path swipe provides that hold without requiring a Compose scroll event.
Compose 1.7.6's velocity tracker resets when pointer-up is more than 40 ms after
the last tracked movement. This supports a nonflinging search; it does not prove
that the failed run flung past the label. Only this API 30 text assertion opts in;
interactive controls and other profiles retain their existing paths. Every literal
metric, privacy, export, durable score and empty-store assertion remains required.
Primary source archives:
[UiAutomator 2.3.0](https://dl.google.com/dl/android/maven2/androidx/test/uiautomator/uiautomator/2.3.0/uiautomator-2.3.0-sources.jar),
[Compose foundation 1.7.6](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.7.6/foundation-1.7.6-sources.jar),
and [Compose UI 1.7.6](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.7.6/ui-1.7.6-sources.jar).

Host hierarchy capture also removes both previous local and remote XML before
dumping. AOSP's dump command can exit successfully without producing a hierarchy;
accepting an old file would make a snapshot's evidence stale. A fresh well-formed
hierarchy, valid PNG bytes and the existing shared 60-second budget remain required.
This closes a demonstrated command/evidence failure case; stale XML was not
established in Build 940. Fresh full verification remains necessary before release.

API 29 exhausts the original 900-second boot budget while trying to observe
user0 boot-delivery completion for the current system server. The final filtered
logcat read times out; no controller phase starts. A display-read retry cannot
repair this earlier failure and is not included. The exact-build receipt fails,
and all publication jobs are skipped. API 29 remains required in the next full
run; none of the successful profiles can substitute for it.

## Build 940 legacy observation and API 29 renderer follow-up

The final exact-build receipt, artifact `11292929950`, ZIP SHA-256
`771b31d098f5c11d71e3acc8e116c82d7e36748660cbc49e8d4e25c277ca0050`,
rejects Build 940: four of six required profiles are admitted, API 30 has the
failed metric lookup described above, and API 29 never reaches the controller.
All three publication jobs are skipped. The passing profiles do not establish a
clean APK for this revision. External head
`ee65bd8e4e1c43feef0e3e07ba22ce130bdd61b9` preserves these failures and adds
the narrow held metric discovery and fresh hierarchy checks described above;
its Build 942 run remains separate evidence.

API 29 startup artifact `11294101256`, ZIP SHA-256
`b32034c457bc18866947885b6feb1fbff507f44aad38a84af73c0b09606a6815`,
verifies the complete pinned emulator and intended resources. The native guest
stream shows system_server PID 259 killed by the watchdog at 04:45:28 UTC,
blocked in `HardwareRenderer.nSetStopped` on `android.ui`; replacement PID 1506
has the same blocked stack. Neither process emits the actual user0 boot-delivery
completion marker. The final ADB timeout is therefore not evidence of a timely
completion missed by the probe. The current-PID barrier correctly fails. No
installation, call-rearm journey or other controller phase runs on this profile.

The retained renderer selection is GLES `swangle` over Vulkan `swiftshader`,
with ANGLE reporting SwiftShader LLVM 10 and the guest using `skiagl`. The next
API 29-only compatibility trial replaces deprecated `-gpu swiftshader_indirect`
with the documented `-gpu software` selector. Android Emulator release notes
introduce that selector in 36.4.9, before the pinned 37.2.6 version. It selects the
available software backends; actual startup selection remains evidence to inspect,
not an assumed Lavapipe result. The image, emulator pin, native ARM64 execution,
two vCPUs, RAM/heap, physical viewport, stable-PID boot barrier, 900-second boot,
180-second installation, 60-minute job and complete controller remain required.
The blocked render stack motivates this experiment but does not prove backend
causality or a successful boot. Primary references:
[graphics acceleration options](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics)
and [Emulator release notes](https://developer.android.com/studio/releases/emulator#36-4-9).

Benchmark discovery also refreshes the legacy accessibility cache through the
public `UiAutomation.setServiceInfo` API, reapplying the existing service info
unchanged; API 34 and newer retain public `clearCache`. Android 10/11 framework
source clears the client cache before applying that info. This establishes the
mechanism, not that cached nodes caused Build 940's missing metric. Benchmark
observations use zero implicit getter idle with guaranteed restoration, retain
explicit settlement, and may re-observe a missing target once after at most
100 ms within the same 15-second deadline. Before/after discovery receipts
expose bounded semantic row identities, counts and geometry; private text and
metric values are not logged. A ready target returns before unrelated diagnostic
traversal. Full tap bounds, stable enabled observation, one actual tap, gesture
count, stationary-edge/reversal limits, all exact metric/export/privacy/persistence
assertions and test48 remain required. Primary source:
[Android 11 UiAutomation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r1/core/java/android/app/UiAutomation.java).

## Build 943 shared navigation helper correction

[Build 943](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37178650021)
tests head `74f48b0404499530139b9e495cf21f0c84bbf149`, actual checkout
`dde2d7715d80772bc3969984fcf305fd3d597111`, tree
`10c8e01a444b2ecaef180dbe60aa109d7d351ece`. All 1,005 JVM cases and four
recorded-speech checks pass, but release instrumentation compilation fails:
model-list navigation still references `benchmarkViewportSignature` at lines
320 and 340 after the benchmark helper was renamed. No device profile or native
APK audit runs, the exact-build receipt fails, and publication is skipped. This
run provides no result for the API 29 renderer trial or API 30 cache correction.

The correction restores the original signature helper byte-for-byte from head
`ee65bd8e4e1c43feef0e3e07ba22ce130bdd61b9`. Its two model-list callers retain
the original cache and bounded traversal behavior. The new observation helper
remains used only by benchmark navigation. All test bodies, model navigation,
assertions and acceptance limits remain unchanged. Shared definitions and every
benchmark-prefixed caller are checked together; the next hosted instrumentation
compilation and complete release gate remain required.

## Build 942 asynchronous copy-status observation

[Build 942](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37178356592)
API 30 artifact `11295095234`, ZIP SHA-256
`a7c4fa53e0f08ca484c158c0e85062dbbbef43e05c6c459a3761b392928c81d5`,
tests head `ee65bd8e4e1c43feef0e3e07ba22ce130bdd61b9`, actual checkout
`052ef3fe24e7d159d6a286df2da1d42a8eecd2f6`. The upgrade passes and 48 of
49 main cases pass, including test48. Test45 successfully finds the held TTS
metric, then fails the exact post-copy status assertion at line2606. The single
actual copy tap is recorded at 05:11:34.194 UTC; the failure PNG at 05:11:34.257
shows export controls disabled and the earlier scored-reference status. The
hierarchy dump begun at 05:11:34.455 already contains the exact copied status
and enabled controls. Those are successive observations, not simultaneous state.

Production export launches a coroutine and computes its payload on
`Dispatchers.Default` before updating the clipboard and terminal status. UI idle
does not join that work. The narrow harness correction includes the same exact
`Redacted JSON report copied.` text in the existing status selector, so its
bounded navigation observes completion before the unchanged literal equality
assertion. The same 15-second deadline, full visibility bounds, gesture/edge
limits and one actual copy tap remain; no export retry or mutation is added.
Clipboard JSON, privacy, original/reference exclusion, counts, durable scoring
and subsequent reset/reload assertions remain required. The later missing layout
snapshot is collection fallout after main failure, not the earliest failure.
Fresh complete verification is still required.

The separate true 16 KB artifact `11294615919`, ZIP SHA-256
`a9add239d8208ff9662bf7072966f83b24328848c1dda42d3db33aed9fe46e51`,
also passes upgrade and 48 of 49 main cases, including test27 and test48. Its
only failure is earlier in test45, seeking `benchmark_quality_task_FAIL` before
review, TTS and copy. The target is absent through all eleven blind discovery
strokes, with two stationary edges and one reversal; fine alignment never runs.
The lookup exits with 5.661 seconds remaining. No fatal/ANR is retained. The
inspected final PNG shows the scored dashboard and two unreviewed attempts;
subsequent teardown XML does not prove search-time expansion or bounds. This
is not the Build 939 EmojiCompat abort, and neither overshoot nor cache causality
is established. The next candidate retains full acceptance and bounded
before/after row receipts for diagnosis; no tap margin or assertion is relaxed.

## Build 944 bounded quality-control discovery

[Build 944](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37179672067)
tests head `9beef0b0b7ee37227bce5dd1819fceaf6cf2cf26`, actual checkout
`fcf6deeb790a344e579132f04df14001a17cfc09`, tree
`9e4868721dff433da7ba8e44d1871377a0bffa8a`. Release instrumentation compiles;
all 1,005 JVM cases, 187 Python helpers, four recorded-speech checks, both signed
APK builds and native 16 KB audits pass. API 30 and the API 35 compact/API 36
normal phone jobs pass. The full gate remains blocked by the true 16 KB profile;
API 29 and Fold are still running at this checkpoint.

The true 16 KB artifact `11295260609`, ZIP SHA-256
`99d7949ac8a9e2f4a3e7522505012c5be15554135e3917c7285d3f05e7158b66`,
passes upgrade from Build 907 and completes all 49 main cases. Only test45 fails
at the first `benchmark_quality_task_FAIL` lookup; test27 and the spoken-stop
test48 pass. Main failure prevents restart, lifecycle, layout and native-loading
phases, so their missing evidence is secondary fallout, not passing coverage.

Fresh navigation receipts show ten 1,109-pixel/12-step blind gestures in a
1,584-pixel viewport: three downward moves reach expanded metrics, two stationary
observations cause one reversal, and three upward moves return to the summary
before two stationary observations end lookup with 5.115 seconds left. The
second and seventh gestures lack after-observation receipts after stale-node
refresh. Sampled content skips the quality region before metrics; production
places human review before metrics in the same expanded sample. A transient
ByMatcher warning names the correct Task FAIL tag with bounds crossing the
viewport's top edge, followed by failed refresh and null lookup. The inspected
final PNG shows scored recognition and two unreviewed attempts. Teardown XML
does not establish search-time bounds. These observations support a discovery
sampling correction; they do not prove a fling, cache or product cause.

The next candidate opts only the first Task FAIL search into the existing
half-viewport, repeated-endpoint physical gesture. Known-bounds fine alignment,
full safe-tap bounds, 300-millisecond stable enabled observation, successful
injection and exactly one tap remain required. The same 15-second deadline,
14-gesture cap, two stationary observations and one reversal remain. Other
controls and the API 30 read-only TTS opt-in retain their existing behavior.
All scoring, clipboard privacy, export, persistence and reset assertions remain;
fresh full exact-candidate verification is required before publication.

## Build 944 live API 29 boot-completion observation

Build 944's API 29 startup artifact `11295276480`, ZIP SHA-256
`0a16bd2ec467731860270645064faa9feb620d139652fb8be2678e47ca62ae08`,
binds the same `fcf6deeb790a344e579132f04df14001a17cfc09` source and verified
37.2.6/build 16138043 emulator pin. Requested `software` selects Vulkan Lavapipe
(llvmpipe LLVM 21.1.4) and GLES SwANGLE over SwiftShader; the actual backend is
retained independently of the requested selector. Unlike the previous failures,
the native guest stream contains PID 277's exact user0 boot-completion message
at guest time 05:48:53.388, with no watchdog kill. Unlock evidence observes
keyguard dismissal. All fifteen filtered adb logcat dumps time out, consuming
210.721 seconds, while single PID probes continue succeeding. The host reports
the unchanged 900-second boot deadline expired at 05:49:09.488. Retained guest
timestamps do not prove when the host received the marker or sufficient time for
the remaining checks, so no completed startup, install or device pass is claimed.

The correction reads the fresh launch's native guest log live between the same
current PID-before/PID-after probes. A maximum 256 KiB tail excludes incomplete
first/last lines; absent, malformed, truncated, replaced or unreadable input
cannot authorize readiness. Exact INFO ActivityManager user0 completion for the
same single successful PID, a still-running emulator and remaining original
deadline are required. Source, byte bounds and monotonic observation/deadline
receipts are retained. No guest clock or archived marker independently grants a
pass. The renderer, resources, unlock order, final service/display checks,
900-second boot, 180-second install and 60-minute job limits remain unchanged.
The default helper's adb read path is preserved for existing callers; the active
software session uses its owned native capture. Fresh full hosted verification
is required, including actual installation and every named release phase.

Build 944 finishes failed with four fully admitted phone/Fold profiles; API 29
never invokes the controller and true 16 KB fails benchmark discovery. The failed
receipt and skipped publishers prevent a numbered APK release. Build 945 tests
the narrow quality-control search correction but retains the earlier adb boot
read path; its results cannot qualify this subsequent boot-observation change.

## Build 946 explicit API 29 renderer trial

[Build 946](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37181469100)
tests source `51d8ad11f93ffe3b77a3a64de7e2b519c8566a16` from PR head
`011e24715744cf87859c77112866b742e89ab2d1`. API 29 artifact `11296330808`,
ZIP SHA-256 `b4024debb3d1d1738dc6e008a9a8a04e6d0547e93427f2df54bd34fb9b3ba98e`,
contains 136 progress receipts with 34 distinct successful native observations,
and no exact Posting or Finished BOOT_COMPLETED marker in the full native log.
The reader never accepts completion. System process PID 257 is killed by the UI
watchdog; replacement PID 1512 is later killed with foreground and UI handlers
blocked. The latter stacks include user-unlock `IInstalld.createAppData` and
`HardwareRenderer.nSetStopped`. Replacement PID 2348 does not finish boot before
the original 900-second deadline. No install or release controller runs.

The installed 37.2.6 binary's help and the official
[graphics configuration guide](https://developer.android.com/studio/run/emulator-acceleration)
support `swiftshader` for GLES and Vulkan separately from `swangle`, which uses
ANGLE with SwiftShader. The failed launch's `software` selection actually uses
GLES SwANGLE and Vulkan Lavapipe. The next API 29-only trial selects
`-gpu swiftshader` explicitly and retains actual backend receipts. This is a
one-variable compatibility trial; the stacks do not establish graphics as the
sole cause, and startup host receipts do not establish memory pressure. The
image and emulator pin, native ARM64 execution, resources, physical display,
live completion/PID guards, final services, 900-second boot, 180-second install,
60-minute job and full release/publication gates remain unchanged. A fresh
complete signed candidate must pass; no clean APK is claimed for Build 946.

The four completed phone/true 16 KB profiles pass all required phases, including
test48. Fold artifact `11295493528`, ZIP SHA-256
`6f442a809acbc600399546a82b92f9ff9f896d3cb57c0d4c12aaf3f62105a7b7`,
instead retains a System UI ANR modal in the first-launch and later screenshots,
with Jarvis setup behind it. Upgrade passes, but 46 main tests fail their setup
lookup and instrumentation expires while test47 starts; test48, lifecycle,
layout/fold transitions and native loading are not reached. The retained app log
starts after clearing logcat and does not establish the Android ANR's cause.
No Fold/product repair or ANR dismissal is inferred. The next exact candidate
must run the full fresh Fold gate. Build 946's failed consolidated receipt and
all three skipped publishers prevent a numbered release.

## Build 947 compact metric observation correction

[Build 947](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37183356238)
tests source `95e482bba64610afb31e6b88ac61913f7d36b547` from PR head
`3561e46a04441f80bbf31b7a08d8543aa589f1a7`. API 35 compact artifact `11296456202`
has ZIP SHA-256 `6c85929bbd7e47bd09aa10dddce09f31add97b675bbeb53e6314858bcd1f1974`.
The API 35 compact profile runs all
49 main journeys: 48 pass and test45 fails to reveal the exact text
`tts_load_ms: unavailable`. Its real Build 907 upgrade passes. The metric search
performs ten 1,109-pixel, 12-step blind strokes, reverses after two stationary
lower observations, and stops after two stationary upper observations with
4,772 ms of its original 15-second budget remaining. This is not deadline or
14-gesture exhaustion.

Fresh row receipts show the first stroke crossing from early metrics ending at
`gemma_final_caption_ms` at y=1,798–1,815 to later TTS metrics beginning with
`tts_playback_starvation_ms` at y=231–246. On reversal, the next retained
pre-stroke receipt already shows the earlier process, Gemma and endpoint rows;
the intervening post-stroke observation was interrupted by a stale node. The
source's fixed metric order places `tts_load_ms` between these observed regions.
The completed fixture has no TTS measurement, so its value remains the literal
`unavailable`. Selection state belongs to the screen outside the lazy item, and
later TTS rows demonstrate that the sample remains expanded. The final PNG shows
two retained attempts and the saved failed quality review; the subsequent XML
reflects the test's `finally` cleanup and does not prove earlier data loss.

This correction opts only the non-API-30 exact TTS text lookup into the
existing overlapping half-viewport discovery gesture with its 250 ms endpoint
hold. API 30 keeps its existing text observation path. All non-API-30 safe bounds,
cache refresh, explicit idle settlement, 15-second/14-gesture limits and edge
reversal rules remain unchanged. All clickable controls retain their enabled,
stable-geometry and single-tap checks. No production UI, scoring, export,
persistence, profile or publication gate changes. The receipts support a skipped
metric region; they do not isolate fling or cache behavior as its sole mechanism.
The correction is not a passing result. A fresh exact signed candidate
must pass all six complete profiles and the consolidated receipt.

## Build 948 held metric gesture correction

[Build 948](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37184660957)
tests merge `47c9d9cb126e0fbc065df0aa9085f3f31c72f852` from PR head
`51360da06a084c43a8a43cd6e45fd137e17f7117`. All 1,005 release JVM cases,
four recorded-speech cases, signed APK checks and both static native audits pass.
API 30, API 35 compact, API 36 phone and the genuine 16 KB profile pass all 49
main journeys and every separate upgrade, restart, lifecycle, layout and native
loading phase. Their APK and test hashes match this exact source and run.
The true 16 KB run also records recovered off-app platform crashes, including
a SystemUI clipboard overlay crash. Later required Jarvis assertions complete
with no Jarvis fatal or ANR; profile success does not mean every platform
process was crash-free.

Fold artifact `11296544541`, ZIP SHA-256
`ba9139ccd1bb65cc113fc3afc9b19b443f7bca2f6f926b24b025aa8a896a1946`,
passes the real Build 907 upgrade and 48 of 49 main methods. Test45 fails its
exact `tts_load_ms: unavailable` visibility assertion. Two 736-pixel held
discovery strokes move the content but consume 7,033 and 6,937 ms, leaving
36 ms in the unchanged 15-second budget. The target is still below the viewport;
no stationary edge, reversal or target safe-bound rejection occurs. The actual
DOWN-to-UP event-time spans are 6,583 and 6,496 ms. Fresh target queries begin
244 and 246 ms after UP, so the retained trace identifies gesture dispatch as
the main cost of these cycles. It does not isolate the host or platform cause
of each synchronous injection's latency.

UiAutomator 2.3.0 interprets Point-array steps per segment. The three-point
51-step path injects 100 MOVE events, including 50 repeated endpoint events.
The focused correction uses sparse real motion and an explicit 250 ms endpoint
hold for only the non-API-30 exact TTS lookup. Every gesture event must succeed;
deadline expiry or an injection failure rejects the gesture and attempts CANCEL
cleanup for any possibly held pointer. Synchronous Binder injection can return
after expiry; that late result cannot qualify the stroke. API 30 and the existing Task FAIL held gesture retain
their original path. Half-viewport overlap, cache refresh, idle settlement,
safe bounds, the 15-second/14-gesture limits, edge detection and all scoring,
export and persistence assertions remain required. This further focused repair
extends the default benchmark repair budget because the new raw trace identifies
redundant physical input work. Android compilation and full fresh device
verification remain required for the correction.

The inspected failure PNG shows expanded metrics without a platform modal.
Test45 clears its fixture store in `finally`; the subsequent PNG and XML captures
start at 07:32:19.496 and 07:32:19.797 and show different observations. The later
empty-store XML does not prove data loss during navigation. Fold restart,
lifecycle, measured posture and native-loading phases are unreached.

API 29 artifact `11297103192`, ZIP SHA-256
`aac01d43b81fbc29c7e6965226246dd9e35743aa869ce15bbbe8d75bcc2aca7f`,
retains two system-server Watchdog deaths. Final PID 2146 has no observed user0
completion within the original 900-second boot deadline. No APK installation
or app controller runs. Actual Vulkan SwiftShader/GLES SwANGLE matches Build
947's trial, whose real upgrade passed but main suite was blocked by System UI's
ANR modal. A new run must establish a healthy API 29 environment independently.
The gesture correction changes no emulator provisioning or acceptance limits.
Build 948's exact receipt is false, all three publishers skip, and its numbered
release is absent. All six complete fresh profiles and the exact receipt must
pass before a new numbered APK can be published.

## Builds 947/948 Android 10 graphics compatibility trial

Build 947's API 29 artifact `11297002373` has ZIP SHA-256
`14c37aa2a67d6958aeee05a5fe35809e35da6c24846e1ac63f2617ab08ecaf0b`.
The native current-PID BOOT_COMPLETED check and real replacement upgrade pass.
The first-launch PNG nevertheless shows `System UI isn't responding` over
Jarvis setup, and later journey images retain the same modal. The startup log
records a KeyguardService execution timeout before unlock input, with SystemBars
initialization taking 29,820 ms. Main setup lookups fail and instrumentation
expires before test48. Dismissing that platform ANR is not a valid app pass.

Build 948's API 29 artifact `11297103192` has ZIP SHA-256
`aac01d43b81fbc29c7e6965226246dd9e35743aa869ce15bbbe8d75bcc2aca7f`.
Its full native log contains no exact Posting or Finished BOOT_COMPLETED marker.
There are 112 progress receipts and 28 distinct successful native snapshots.
System-server PID 264 is killed by the UI watchdog; replacement PID 1445 is
killed with foreground `Installer.migrateAppData` and UI
`HardwareRenderer.nSetStopped` stacks. Replacement PID 2146 does not complete
boot before the original 900-second deadline. No APK install, controller or
test48 runs. Both candidates request SwiftShader and actually select Vulkan
SwiftShader with GLES SwANGLE; the selector did not change that GLES backend.

The next API 29-only launcher trial adds disabled Vulkan to the existing
disabled HVF request: `-feature -HVF,-Vulkan`, keeping `-gpu swiftshader` and
`-accel off`. The official
[troubleshooting guide](https://developer.android.com/studio/run/emulator-troubleshooting)
documents disabling Vulkan. The installed pin's raw `-help-feature` output is
retained within the existing 600-second provisioning budget, with a 15-second
command limit and no help-text capability gate. Requested disabled features
are recorded separately from the observed backend. This qualified compatibility
experiment does not establish a graphics cause, exact pinned CLI behavior or
the absence of all host Vulkan use; foreground I/O and software-emulation
slowness remain competing explanations.

The exact emulator/image pin, native ARM64 execution without a bridge, resources,
viewport, boot/current-PID/unlock/services checks, 900-second boot, 180-second
install, 60-minute job, all required app/native phases and publication gates
remain unchanged. Neither failed candidate has a clean numbered release. A
fresh signed candidate must complete all six profiles and the consolidated gate.

## Build 952 interactive held discovery correction

[Build 952](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37187161071)
tests merge `77b1d8df8785159a22fbadee1a2e9bb12f562d89` from PR head
`2ca8d324de5cc1ac3a561d5048d7f3b1a581cbab`. Signed normal/compact packaging,
all 1,005 release JVM cases, 197 Python helper cases, four recorded-speech cases
and both static native audits pass. API 30 normal, API 35 compact and API 36
normal each pass all 49 main journeys, both actual Build 907 upgrade phases,
restart, eight lifecycle phases, four layout cases and nine native loads.
The consolidated receipt is false with three admitted profiles; all three
publishers skip and the numbered release is absent.

Fold artifact `11297729163`, ZIP SHA-256
`4954d124fc6ceea7f5e6ae0333ad8e591826c13ae19eceda2e02221d63bc09b8`,
passes the actual replacement upgrade and 48 of 49 main methods, including
test48. Test45 fails while revealing Task FAIL, before reaching the sparse TTS
gesture added previously. Its initial held 736-pixel Point-array swipe takes
7,726 ms; two subsequent 48-pixel, 24-step fine adjustment cycles take 2,318 and
2,385 ms. The last observed target bottom is 1714, outside the unchanged safe
bottom of 1702. The next adjustment starts with 1,896 ms remaining and cannot
authorize a stable enabled tap before expiry. The inspected PNG shows the
review row at the viewport bottom without an ANR modal. The later XML reflects
fixture cleanup and does not establish the same review geometry. Later restart,
lifecycle, layout, real posture and native phases remain unreached.

The focused correction gives `benchmarkClickEnabled` a default-false sparse
held discovery option and enables it only for Task FAIL on API 35 and newer.
It uses the existing successful real 12-MOVE gesture and explicit 250 ms hold;
API 29/30 retain their prior path. Known-target fine adjustments, safe margins,
fresh enabled/stable bounds, exactly one tap, the original shared 15-second and
14-gesture limits, cache/idle handling, edge rules and every test assertion
remain unchanged. Reducing the first gesture's redundant input work can leave
time for fine alignment; only the next complete run can establish a pass.

API 29 artifact `11297928957`, ZIP SHA-256
`c0de7b4f08d3ce41feedacbc4cd5f11a5b64c0f2b6ac72302e614910392f86f5`,
confirms that the exact pinned emulator applies the disabled guest Vulkan
feature. The host GLES backend remains SwANGLE over SwiftShader Vulkan.
Initial system-server PID 266 dies after the stock runtime-permission grant
request times out. Replacement PID 726 posts user0 BOOT_COMPLETED, but no
Finished marker exists in the full native stream or 18 distinct timely native
reads. Cold receivers keep advancing near and after the original 900-second
deadline; the logs do not identify one uniquely stuck receiver. SystemUI and
other platform ANRs remain retained. No APK installation, controller or test48
runs. The graphics trial applied but did not establish timely healthy startup.

True 16 KB artifact `11298331230`, ZIP SHA-256
`57031e7d31be9f1a1015b070e54f6986a1c2f9f66b629c3cdf34ae0eddf5d16e`,
confirms API 35, 16,384-byte pages and the ARM64 bridge, and passes both upgrade
phases and test01. The Jarvis process crashes during test02 evidence capture:
its JIT thread receives SIGSEGV, with retained x86-64 libart frames through
Class::GetDescriptor, ResolveMethod and JitCompile. This is an app process
crash, unlike Build 948's recovered off-app crashes. Test02 does not complete,
the other 47 main methods and all later phases remain unreached. The trigger
is unresolved; native packaging checks do not exclude generated code,
dependencies or earlier native corruption. No JIT/GC override conceals it.
The next fresh signed candidate repeats all six profiles and every release gate.

## Build 954 API 29 host graphics compatibility trial

[Build 954](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37189376357)
tests merge `d669bd4969b3b68990cee288ca0c2f1476a1a118` from PR head
`b84e0b562040a468d67e595c563815de9a8317d2`. Signed normal/compact packaging,
all 1,005 release JVM cases, 197 Python helper cases, four recorded-speech cases
and both static native audits pass. API 30 normal, API 35 compact, API 36
normal, Fold compact and true 16 KB normal each complete all 49 main journeys,
including test48, both actual Build 907 upgrade phases, restart, eight lifecycle
phases, four layout cases and nine native loads. Fold also completes actual
fold/unfold transitions. The earlier sparse Task FAIL correction passes under
the original navigation limits. The true 16 KB profile retains two positively
attributed Google Play services native crashes; no Jarvis fatal or ANR is retained.

API 29 first-attempt artifact `11299282453`, ZIP SHA-256
`ad384b3a00d6d656d550aeafdfbca4c2894fee77b809d4f504ea118c0ac4cc50`,
passes the complete boot barrier and both upgrade phases. A cold stock SystemUI
service ANR precedes Jarvis installation. The retained first-launch and main
screenshots show its blocking "System UI isn't responding" dialog. Main methods
01 through 20 fail in setup; method21 starts but reaches the unchanged
900-second instrumentation timeout without a terminal result. The 20 failed
test bodies and test48 do not run. This failure is not the missing
BOOT_COMPLETED delivery seen in Build 952.

One unchanged infrastructure retry is accepted. Fresh API 29 job `111406997721`
produces artifact `11299493564`, ZIP SHA-256
`30d0fac7e9e75f4fbb9bb34f91e362475171cecc04831b03bbf73a410bd2ac0f`.
Two system-server processes die after stock runtime-permission grant requests
time out at 30 seconds. A third is killed by the actual platform watchdog with
its UI thread in `HardwareRenderer.nSetStopped` through `performDraw`.
SystemUI service initialization and ANR remain retained. The full native stream
contains neither Posting nor Finished user0 BOOT_COMPLETED markers; cached boot
properties do not satisfy the unchanged 900-second delivery barrier. No APK
installation, controller or app tests run. The retry carries the five original
same-revision successes; those profiles are not five new executions.

The final receipt artifact `11299069001`, ZIP SHA-256
`504dd5b733a0dc039be85836c60dc5979155b7d798920e4646e8c141440ba733`,
is false with five admitted profiles. The eligible signed publisher skips;
the other two publishers are ineligible for this PR and also skip. Neither
attempt produces a numbered Build 954 release.

The next isolated compatibility trial changes only API 29's requested GPU
selector from `swiftshader` to `host`. The exact pinned emulator's native help
advertises `host`; a usable host OpenGL context, its actual backend and any
improvement remain unproven. The observed prior GLES backend is SwANGLE over
SwiftShader. Render/UI stalls support investigating graphics, but the grant
timeouts mean graphics is not an established sole cause. Requested and observed
backends remain separate receipts. The emulator/image pin, native ARM64 CPU
emulation, disabled HVF/guest Vulkan, resources, viewport, every readiness check,
all deadlines and every release assertion remain unchanged. No JIT, GC or
watchdog override hides a failure. A fresh signed candidate must rerun all six
profiles and pass consolidation before publication; Build 954 results do not
credit the changed revision. Physical wake-word/audio behavior still needs final
phone signoff.

## Build 958 graphics capability and capture evidence

[Build 958](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37193671520)
tests merge `5f275f9ad3414a0b0d90f9bbf2ea99bf8fce4a92` from PR head
`222ab4f0fdeea69ecb6ab6ecdade21410ff06bed`. Signed normal/compact packaging,
all 1,005 release JVM cases, 197 Python helper cases, four recorded-speech cases
and both static native audits pass. Fresh API 30 normal and API 35 compact
complete all 49 main journeys including test48, both actual Build 907 upgrade
phases without clearing data, restart, eight lifecycle phases, four layout cases
and nine native loads. Their 85 PNG/XML pairs are independently validated.

API 36 normal artifact `11300049235`, ZIP SHA-256
`7684d926764390c25c0fdb50346a543193dcb28a6981abc45dde7bc9155043f6`,
passes both upgrade phases and 48 of 49 main methods, including test48. Test45
fails finding Intent PASS after successfully reviewing Task FAIL. Its fresh
positive target rectangle is only 35 pixels below the viewport, but the
intersection-only alignment decision chooses a 1,109-pixel search stroke.
Subsequent observations show lower metric rows; the bounded search reaches an
edge, reverses once and ends without authorizing a tap. No fatal/ANR header is
retained. The failure PNG is a later summary state, not the initial geometry.
Restart, lifecycle and layout phases remain unreached.

True 16 KB job `111412924858` fails before the controller or APK installation:
the emulator runner's physical unlock command receives input-service
`Broken pipe (32)`. No device artifact is created. The retained completed job
log is 51,901 bytes, SHA-256
`4d2ea83d504c5a3c0f50f62db1a55003cf4d0a19531effe6932d64fb6357cd26`.
This supplies no app, native-loading or test48 coverage; the underlying guest
cause remains unresolved without guest logs.

Fold artifact `11300004844`, ZIP SHA-256
`33b2dc88de754320dfd2d11bf93da3b4c7238fcfaed9e4add48a3ff9433cb906`,
passes all main, upgrade, restart, lifecycle, layout and native-loading phases,
including actual fold/unfold transitions and test48. Final capture overlaps
normal instrumentation teardown and an EmptyActivity closing transition. Its
fresh PNG is a blank white surface with a navigation handle. `uiautomator dump`
exits zero while reporting a null root; the fresh hierarchy file is absent and
the subsequent cat fails. The report correctly remains false. No fatal/ANR
header is retained; this is not another benchmark navigation failure.

API 29 artifact `11301125436`, ZIP SHA-256
`dacfb757b0a65704d879a3fe0d11f4a47000ae74756f62c1c72da841f38d3b07`,
verifies the exact emulator pin and observes the requested host/host backend:
Apple Software Renderer, GLES 2.0. Stock SurfaceFlinger requests GLES 3 and
encounters 13 `EGL_BAD_CONFIG: no ES 3 support` failures and 13 native SIGABRTs.
Initial boot and all four services remain unavailable at the original
900-second deadline. No unlock, broadcast barrier, controller, installation or
app tests run. This is a concrete host graphics capability failure; advertising
the host selector did not establish a suitable GLES context.

Final receipt artifact `11300184994`, ZIP SHA-256
`4d44cc263af2fd0673a8f6afc4bf4c4ae9c9f7b03d24409bc0fbd7a1998fcfad`,
is false with zero admitted variants: the missing true 16 KB artifact prevents
input selection/download. The separate raw API 30/35 passes are retained, but
are not admitted variants in that receipt. The eligible signed publisher skips;
the other two publishers remain ineligible for this PR and also skip. No
numbered Build 958 release exists. Every deadline and release assertion remains
in force; a changed revision requires a fresh signed build and all six profiles.

The then-next controlled harness correction aligns a fresh known target at most
one fine-stroke limit outside a modern Android viewport with the existing
bounded fine gesture. Unknown/distant targets retain discovery; fresh safe
bounds, 300-millisecond stability and the single physical tap remain required.
Final snapshots admit one additional fresh PNG/XML observation only for the
exact zero-exit null-root diagnostic, retaining first-attempt pixels and both
diagnostics inside the original 60-second budget. Other errors, invalid
captures and a second null root still fail.

That successor separately trials official Stable 32.1.15/build 10696886 with
`swiftshader_indirect`. The official archive's indexed version/size/checksum
and adjacent AOSP renderer/CLI/ARM64 TCG sources qualify this candidate; no
local binary or successful runtime is claimed. Staged/installed identity must
still pass the unchanged pin checks. Trailing `-qemu -smp 2` preserves the
requested two CPUs in the older off-HVF path. Memory, raster, CPU emulation,
HVF/guest Vulkan exclusions, native startup-log ownership, boot barrier and
all acceptance deadlines remain unchanged. Actual GLES support, two CPUs,
BOOT_COMPLETED and all app phases must be observed in the fresh run.

## Build 962 supported density and disabled Save discovery

[Build 962](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37196250299)
tests PR head `40b4de5a013605f87a92830b788da327c96fac73`, merge
`5c972bef6f7c651cc9b445232f2997acd7fecba7`, tree
`9e282a2b8a8690ccdc2abd314b4633221fd861c5`. Signed normal/compact builds,
1,005 JVM tests in 184 suites, 203 helper tests, native keyword/page-size
checks and four recorded ASR checks pass. Same-run shipping identities are
normal `b29843cab2efc8431e5bdde3d9c2a23113b39c5fe97c0ea36c2d7174da9932af`,
compact `9895b34afebbbadbb8706076ea45e261939dcd9d03bad8814103d63854d703f0`
and test `75d30d45787bda0cee734cbfed94b8b6ba13b7081e5431e8cab302b776369666`.

API 30 normal, API 36 normal, true 16 KB normal and Fold compact pass all
49 main journeys, two actual Build 907 upgrades without a data clear,
process restart, eight lifecycle phases, four layout methods and nine native
loads. Fold includes actual fold/unfold transitions and retained call/draft
continuity. True 16 KB observes 16,384-byte pages. Its retained log contains
one GMS ReferenceQueueDaemon crash reported twice, not a Jarvis fatal or ANR;
main, layout and lifecycle complete afterward; upgrades had already passed.
Test48 passes on all five profiles
that reach main instrumentation, including the otherwise-failing compact
phone. This proves controlled foreground-service/session contracts; physical
microphone, wake-word inference and real-model behavior still require signoff.

API 35 compact artifact `11301049442`, ZIP SHA-256
`c9e8bd5ecd6e9ca6a890873ebb47f8af2e8f2e8f3851efcb18eb02772ddeba46`,
passes both upgrades and 48 of 49 main cases. Test45 cannot discover the
initial disabled Save-review control before evaluating `isEnabled`. Product
source unconditionally tags the button immediately below the review checkbox.
The fresh checkbox starts clipped at the viewport bottom; the first 1,109-pixel
blind stroke samples below the controls in the metrics, and subsequent search
never observes Save. The retained test45 PNG is the dashboard top after edge
reversal, not an atomic observation of the initial Save target. Later restart,
lifecycle, layout and native phases are unreached. The correction changes only
that initial discovery call on API 35+ to the existing half-viewport physical
held gesture (12 MOVE events, 250 ms); the disabled assertion, later checkbox
and Save taps, fresh safe bounds, 300 ms stability, 15-second/14-gesture limits
and all 49 named cases remain unchanged. This is a bounded discovery trial,
not proof of stale-node or inertia causality.

API 29 artifact `11300944065`, ZIP SHA-256
`bcb560c322912a42e7be321747062314d3ae736fea6f71aa0f7a4d3639da0612`,
verifies the complete pinned 32.1.15/build 10696886 archive, staged/installed
versions and successful GPU/feature help. Native QEMU then rejects LCD density
210 before guest startup. There is no guest BOOT_COMPLETED, installed app,
test48 or actual initialized GLES identity. The wrapper advertises GLES 3 in
kernel arguments; that is not a renderer capability receipt. Its unchanged
`vm.heapSize=256M` request is interpreted as zero and promoted to a 512 MiB
minimum in generated hardware/kernel arguments. Actual guest heap/CPU/runtime
coverage is not established by this failed startup.

The API 29 correction uses supported 280 dpi with 720×1280 physical pixels,
preserving the exact Pixel 2 dp viewport. The unchanged strict physical
size/density receipt rejects the old raster, skin mismatches and any wm
override. This increases pixel count by 16/9 from the prior raster; no speed
gain is claimed. Pin verification, two requested CPUs, RAM/heap request,
SwiftShader, HVF/guest Vulkan exclusions, native log ownership, current-PID
boot barrier and every deadline remain unchanged. Fresh complete execution
must qualify the density and renderer before app coverage can count.

Final receipt artifact `11300724529`, ZIP SHA-256
`c2ebb6321bbd0d0f8ca4be2afee0c5ee6925e28f86d68beed615fb1504fcfc0a`,
is false with exactly four admitted profiles. All three publishers skip;
the numbered Build 962 release and tag are absent. The next changed revision
must pass the complete signed host gate and all six fresh profiles.

## Build 963 startup, export and Fold alignment evidence

[Build 963](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37198084788)
tests PR head `32f8e3d3345a35de35aa28c0f2967af6cf3e6e91`, merge
`562259d9093428fc0d5d6c7d34385b777e853c47`, tree
`22dd0720692937c314afb4e3a66fe92858de046f`. Signed normal/compact builds,
1,005 JVM tests in 184 suites, 203 helper tests, native keyword/page-size
checks and four recorded ASR checks pass. Same-run APK identities are normal
`2c7a206892669e8f474ba678206fa1cefb1421cfcfb20c3e857cc3a5df340dd6`,
compact `a3803a8d91916819f2f0589bc5ead42f33b4a1910ccc2486df3648867abb7111`
and test `132c11a5b4105e37e862b8afc723185f4227d68f3876b1633d1822603c19a504`.

API 30 normal and API 35 compact each pass all 49 main journeys, both actual
Build 907 upgrades without clearing data during update, process restart,
eight lifecycle phases, four layout methods and nine native loads. Compact's
initial disabled Save is discovered with one actual held half-viewport
gesture; its disabled, later physical Save and persistence checks pass.
These are two full raw profile passes, not admitted consolidated variants.
Test48 passes on all four profiles that reach main instrumentation; the
controlled session/foreground-service result does not qualify physical audio.

API 36 normal artifact `11302362080`, ZIP SHA-256
`ff43ab245519f091f5dec825a1aaa5b0a94bcf14e005e09f8fe943bfdd5f8527`,
passes both upgrades and 48 of 49 main cases, including test45 and test48.
Only test09 fails, in its evidence export after the test body: delayed
MediaProvider PACKAGE_DATA_CLEARED processing orphans the newly inserted
Downloads row before openOutputStream, which rejects URI 41 with a
SecurityException. Both operations use targetContext. There is no retained
exported test09 PNG/XML; subsequent phases are unreached. Broadcast delivery
or provider-thread idleness alone does not establish MediaService work-queue
completion, so neither is substituted for successful evidence export.
The next main-journey export trial uses public API 31 shell stdin to transport
the original captured bytes into the unique Downloads folder, avoiding an
app-owned MediaStore row on API 31+. A bounded nonblocking transfer requires
a fresh path and an exact success/size/SHA-256 receipt; duplicate paths,
missing bytes, timeouts and mismatches fail. Both descriptors close on exit.
API 29/30 retain the existing MediaStore export. The per-file 30-second transfer
cap remains inside the unchanged instrumentation/host deadlines. Actual
Android shell transport and collector-readable PNG/XML still need fresh CI.

Fold artifact `11302617151`, ZIP SHA-256
`e7b80b47240db9dece531073cc35e2b8c67b91458bd98cd3ad9e665ee20b8305`,
passes both upgrades and 48 of 49 main cases, including test48. Test45's Copy
navigation exhausts its unchanged deadline. Four upward discovery strokes
eventually expose Copy wholly above the viewport, followed by fine strokes
that reveal 49-, 74- and 99-pixel edge fragments. The final full-height control
still extends 27 pixels above its scroll viewport. No overshoot, stationary
gesture, disabled control or overlay cause is established. The next bounded
alignment trial uses the existing one-fifth fine cap for a positive small
rectangle intersecting and touching the directional viewport edge, only for
modern physical-tap navigation with horizontal containment. Unknown, distant,
wholly outside, read-only and API 29/30 navigation retain their original
gestures. Fresh safe/enabled bounds, 300 ms stability, one physical tap and
15-second/14-gesture limits remain required; actual timing is unverified.

True 16 KB fails before controller execution: emulator-runner observes the
boot property, then input keyevent 82 fails with Broken pipe. The action
terminates before app installation and produces no device artifact. Guest
crash absence cannot be inferred from its stdout. The next revision streams
INFO-and-above guest diagnostics only for this profile; this changes no wait,
input retry, boot budget or gate and is not a startup cure.

API 29 artifact `11301877291`, ZIP SHA-256
`88ddc6a3e662c34b51c5e7843ecda60c21220f613673f0ca8d0ef75cf3c5aa20`,
verifies the pinned 32.1.15 archive and accepts 720×1280 at 280 dpi. The kernel
activates two CPUs. Guest rendering still initializes ANGLE with SwiftShader
and reports GLES 3; the pin does not establish a direct-only renderer. A stock
permission-service timeout is followed by system-server restarts, composer
null dereferences, SurfaceFlinger aborts and SystemUI failures. BOOT_COMPLETED
never arrives; Jarvis is never installed. Startup host memory reports 84%
free and no swap, with no successful late sample; resource exhaustion is not
established. Linux x64 emulator launcher source rejects API 28+ ARM64 guests;
a bundled ARM64 QEMU binary does not establish supported wrapper admission.
The next controlled trial changes only the physical raster to 360×640 at
advertised 140 dpi, preserving the exact physical dp extent with one-quarter
of Build 963's pixels. Density resources, rounding and insets still need the
full layout gate. The first permission timeout may remain; no cause, speed
gain or stable-boot cure is established. Pin, CPU/RAM/heap request, renderer,
HVF/guest Vulkan exclusions, startup observation and all deadlines are retained.

Final receipt artifact `11302072065`, ZIP SHA-256
`bcfa20d8ce7d6e4a2b201c482e48300748932ba572df59e25d7ddaa6068a53f6`,
is false with zero admitted profiles. Missing true-16-KB evidence aborts input
selection, so downstream missing host/profile inputs do not contradict their
raw passes. All publishers skip; no numbered Build 963 release or tag exists.
The next changed revision must pass the full signed gate and all six profiles.

## Build 900 Gemma audio submission review

The latest benchmark export contains counts and timing but excludes transcript,
reply, prompt and PCM text/data. The supplied Voice Call diagnostics for
`21c42f96-44e0-4197-91b2-a87e547b85ba` show Whisper recognized
“Tell me about our solar system.” Gemma received 102444 retained WAV bytes in
both answer and isolated caption submissions, but its answer denied audio
capability. Native encoder timing is unavailable; these logs prove submission,
not successful acoustic understanding. A prior caption refusal was also present
as a user statement in history, so historical context can reinforce the failure.

`audioMessageContents` now sends text followed by original audio in one message,
matching Gemma 4's model-specific modality order:
https://ai.google.dev/gemma/docs/core/model_card_4#4-modality-order.
The JVM acceptance test checks the order and byte-exact WAV retention and rejects
empty audio. Both answer and isolated caption paths use this helper. No sampling,
personal vocabulary or decoding settings are changed. Actual E2B audio encoding,
recognition and absence of refusals remain physical-device/real-model checks;
this change is not proof of the root cause or an accuracy gain.

Moonshine CALL_FILTERED now uses the same 1200 ms onset and contiguous phrase
policy as Whisper, retaining internal pauses and all PCM until turn finalization.
RAW_DIAGNOSTIC continues to bypass the external filter and uses native VAD.
The shared gate's byte-order/reset/final-only tests cover input preservation;
native Moonshine word accuracy and added decoder work need device measurement.
The reviewed microphone profiles retain their general-purpose PCM16 mono 16 kHz,
AEC and explicit noise-suppression policy; no user-specific tuning is introduced.

## Initial Whisper complete-phrase capture checkpoint

Whisper base.en keeps 1200 ms of idle pre-roll, then every PCM sample from
confirmed onset through endpoint/finalization. This replaces Whisper's 240 ms
pre-roll and 320 ms tail gate, which removed longer internal pauses and quiet
ending sounds. Live/background and final-only Whisper share the phrase policy;
At this initial checkpoint Moonshine retained its existing gate; the Build 900
review above extends the same policy to its call path. Model, quantization, decoding, capture
profile preference, endpoint timing, segmented long-turn bounds and microphone
lease/cancellation contracts are unchanged.

JVM acceptance: `ExternalSpeechGateTest` checks byte-exact opening/internal
pause/ending order, exclusion of long idle audio and reset behavior;
`AsyncWhisperSessionTest` checks final decoding includes audio arriving while a
partial decode is busy. Existing default-gate, idle-no-inference, worker cleanup
and segmented-transcriber regressions remain required. Android integration uses
the existing exact-revision release sandbox; no new UI journey is introduced.

Microphone review: both profiles request PCM16 mono at 16 kHz, session-bound
AEC for call echo requests and explicit NS enable/disable with actual enabled,
control and status diagnostics. Speech clarity uses VOICE_RECOGNITION/NS off;
Call noise reduction uses VOICE_COMMUNICATION/NS on. Existing profile choices
are preserved. Device processing can still differ from requests; code review
does not establish acoustic superiority of either profile. Real Fold 6 beginning/
ending-word accuracy and latency require phone comparison before claiming an
improvement. Exact release CI is pending for this change.

Build 902 (PR head `62008b1`) retained a compile failure for an Int/Long
pre-roll duration mismatch; `9375faa` corrects the type. Build 903
[run 37089409509](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37089409509)
compiled and ran 1000 JVM tests with one stale telemetry-fixture failure:
`finalAndRecoveryMeasureTheirOwnInputWithoutIdleCaptureAudio` expected 13760
submitted samples from the old gate, while the new contiguous input correctly
submitted 56000 (1200 ms pre-roll + 100 ms speech + 2000 ms ending + 200 ms
independent recovery). All 14 phrase/gate worker tests passed. The telemetry
fixture now requires the exact 56000 samples/3500 ms denominator, preserving
work-time assertions and idle exclusion. No production change or relaxed
acceptance was made for this repair. Failed unit artifact `11261513405`, ZIP
SHA-256 `0d87c8eb5ddd703b74b4310ef431fdebb3a414c4c8664c45497a934e1f3ba0e0`,
retains the raw XML failure; Android/publication was correctly skipped.

## Repository maintainability refactor

The repo-wide maintainability work preserves the published Build 885 behavior
baseline. `ed30650` is the initial collaborator extraction checkpoint; completion
requires typed voice/conversation stages, a composition facade with narrow ports,
an audited whole-repository boundary map with justified exceptions, accurate
newcomer setup/navigation/test guidance, and the exact final revision's full
release gate. See [modular-refactor.md](modular-refactor.md) for the explicit
A1–A8 criteria and [architecture](../architecture/README.md) for current owners.

Smaller files or passing baseline evidence do not establish completion. Preserve
all behavioral, persistence, native and release-test contracts and every named
journey. Remaining real-model/physical-device coverage is reported separately from
architectural acceptance; no voice-quality or latency improvement is implied.

The current combined checkpoint is documented in [combined-audio.md](combined-audio.md). It supersedes the historical candidate/count statements below. The executable contract now preserves **48** named journeys: common test01–29, Audio test30–33, Memory test34–39, Tools test40–44, pipeline benchmark test45, conversation export test46, audio settings test47 and test90. Combined release verification is required; standalone Build 861/863 receipts do not validate this tree.

## Hosted build performance repair

Build 879's two attempts exhausted the 45-minute job limit. The first diagnostic repair in [Build 880](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36953091161) completed both signed APKs after providing a 60-minute investigation ceiling, explicit 4-GiB Kotlin heap, two Gradle workers, task caching, and trusted PR cache writes. It did **not** improve cold compilation: release assembly/tests took 44m 01s. Both emulator jobs subsequently failed test47 before settings rendered: the independent test DEX called a reshaped LazyColumn method (NoSuchMethodError). The prior 46 journeys passed; the crashed process did not run test90. Publication was correctly skipped.

Retained artifact `11205369238`, SHA-256 `4ee00583fcda10e60de938e1528508f213c5f6e885cc5436c4d09c78fa9ccc0d`, supplies the actual breakdown: main Kotlin compilation 2,095.178 s; JVM backend 2,004.688 s; analysis 57.335 s; GC 24.193 s. The JVM test task took only 20.724 s, R8 took 150.303 s, native Sherpa 229.317 s, and compact assembly 48 s. The four-core/16-GiB host had no reported swapping. This establishes backend code generation as the dominant bottleneck; it does not yet isolate an individual Kotlin optimizer.

The second repair tests `-Xno-optimize` on the production release Kotlin task alone, bypassing optional compiler bytecode optimization while retaining R8 optimization/minification, native Release builds, and every validation task. The large voice coroutine is a suspected contributor; actual measured reduction is required before calling this a speed fix. Bounded compiler thread/heap samples help isolate any remaining slow backend work.

The trusted PR job also preserves Gradle execution history and production Kotlin incremental state/outputs under a branch-and-build-configuration cache key. It excludes APKs, test reports, signing material and configuration-cache state. Gradle still validates current source/classpath inputs, regenerates build metadata, and reruns missing test outputs; the same-run APK digest/receipt gates remain required. Missing state or changed build configuration starts a cold compile. Cold and incremental timings must be distinguished.

[Build 881](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36957056657) completed signed assembly and JVM checks in 27m 58s (compact 31s), versus 44m 01s in Build 880. Performance artifact `11207481872`, SHA-256 `0d65d6f1227bbacffd15809b124420917cd68b403de1530ae3ec19a7be376273`, records a cold main compile of 1,318.261s, backend 1,291.337s, JVM test task 14.815s, R8 100.055s and Sherpa 164.563s. All sampled busy compiler stacks repeatedly show `performUninitializedAfterResumeVariablesAnalysis` / `ResumeDependentFrame.merge`: mandatory coroutine state analysis remains dominant despite disabling optional optimization. Runner/caching differences also affect the comparison, so the elapsed reduction is not attributed to the flag alone. The first incremental-state cache was saved after this run; a subsequent exact-head run must establish whether that state avoids recompiling unchanged Kotlin. Build 881 predates the lazy-layout ABI repair and is not the verified release candidate.

The release/test ABI repair preserves the two lazy-layout owners directly referenced by the test DEX: `androidx.compose.foundation.lazy.LazyDslKt` and `LazyListScope`. This is a build/test compatibility repair, with the original test47 rendering path and every assertion unchanged. API 30 evidence `11207155078` SHA-256 `ead2bbba776d4f45ddbdda8ca15a156384800aa88e24e98ff7d723e2e17add4e` and API 35 evidence `11205499932` SHA-256 `4b26422666b8a89ce900a534125dc57acd9ad2c238cc52b0183df82f70855a5e` retain the failed stack. No settings screenshot was produced because rendering crashed; the screenshot-collection error is a consequence, not an independent failure.

The profiler's busy stack matches upstream Kotlin issue [KT-83372](https://youtrack.jetbrains.com/issue/KT-83372), fixed by [JetBrains commit 4c2db10](https://github.com/JetBrains/kotlin/commit/4c2db10f09cfac89d9e6ad1472982b1c311539c1): coroutine resume-state analysis scales poorly with many suspension points. The project pinned Kotlin/Compose compiler 2.3.0; stable 2.3.21 contains the corrected analysis and DFS implementation (verified in its source). The targeted repair updates both compiler plugins to 2.3.21, removes the inconclusive `-Xno-optimize` experiment, and restores the original 45-minute job ceiling. Ordinary Kotlin/R8 optimization and all release gates remain enabled. This build-configuration change intentionally selects a new cache key and requires a fresh cold compile plus the full exact-head gate; timing and clean APK publication remain pending. No voice-state-machine refactor or acceptance relaxation is part of this repair.

[Build 884](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36960452654) proves the compiler repair on a cold compile: main Kotlin task 130.006s, backend 19.867s (versus 2,004.688s in Build 880), analysis 74.053s, JVM tests completed. Artifact `11207788210`, SHA-256 `0ae813e886d99fb20ce19b7370929de06e613e1b45b9bef0a2bc6b48261a862c`, retains the phase report. The build then failed R8 parsing at the new lazy-layout rules because their explanatory comments mistakenly used `//` instead of ProGuard's `#`. This separate syntax repair changes only those three comment prefixes; the two keep rules and all acceptance checks remain unchanged. Build 884 produced no verified APK and publication was blocked. A fresh exact-head full gate remains required.

Acceptance remains all release JVM/native/helper checks, both signed APK variants, all 48 journeys per emulator and the exact-build receipt before publication. No assertions, scenarios or release dependencies are relaxed. Physical voice, real model inference and Fold 6 performance still require phone validation.

## Build 874 follow-up: voice reliability, direct audio and conversation metrics

Source changes add unfinished single-word/phrase-loop detection before Piper receives a runaway suffix. One bounded repair retains a valid opening and reports a repeated repair loop without continuing speech. Pure JVM cases cover token-boundary splits, interrupted valid openings, late callback fencing, ordinary repetitions, long prose and formatted code. Capture resources reopen for a changed Speech clarity/Call noise reduction profile; a bounded playback-reference tail protects ordinary command handoff from echo-only text. This is conservative lexical rejection plus existing device AEC, not demonstrated acoustic cancellation or improved Fold 6 WER. Genuine distinct interruption words remain eligible.

Voice input settings add **Gemma audio understanding** and optional **Whisper live captions**. The same recording feeds Gemma directly; captions cannot replace its input or authorize phone actions. Final Gemma transcription runs after answer generation and replaces the paired saved user message. Caption failures do not invalidate complete audio. Unsupported models and recordings exceeding the explicit 28-second capture safety limit reject with a visible explanation, never silently truncate. This experimental mode currently answers questions; phone actions remain in speech-recognition mode. Piper continues producing the spoken answer.

Benchmarks now retain conversation linkage, full metric/status definitions, native SDK telemetry when exposed, resource snapshots and explicit capacity/disk failure status. Atomic per-attempt storage retains 90 days, up to128 MiB/100,000 attempts, and migrates v1 without silent rolling eviction. Reply-footers open conversation-scoped JSON/CSV copy/save/share; call details open call-scoped observations. `test46` checks reply navigation, persisted scope and redacted export; `test47` checks persisted audio mode/caption toggle without model loading. Both are registered with all previous release journeys. JVM storage cases cover more than500 records, capacity refusal, migration, expiry, partial-write/reset durability and missing values.

Build 875 (run 36923978989, PR head 4e4c841) failed release compilation at `AudioTurnCapture.kt:45`: the configurable `Int` capture limit was passed to a `Long` buffer parameter. This is a product compilation defect; it is corrected with an explicit widening conversion, without changing the limit or acceptance checks. No JVM/emulator pass or APK publication is claimed for that run. Exact revised CI is required before publication. Emulator results establish UI/storage/routing contracts only. Live Gemma/Whisper inference, physical self-echo, barge-in, capture accuracy, longer-request handling and speed differences require actual model weights and phone testing. See [voice-audio-and-metrics.md](voice-audio-and-metrics.md) and [pipeline-benchmarks.md](pipeline-benchmarks.md).

## Build 866 noisy input repair and pipeline benchmarks — exact CI pending

The supplied restaurant call proves literal Moonshine errors, unrelated reference selection, failed correction/source follow-ups and unsupported Gemma claims. It does not include Whisper acoustic ground truth or establish a measured 40% WER. Both recognizers shared forced communication-source/noise-suppression requests. The new default requests VOICE_RECOGNITION and explicitly disables controllable noise suppression while retaining the duplex speaker route and AEC request; the prior communication/noise-filtered profile remains selectable for a paired phone comparison. Actual source, client/device format, effect support/control/enable result and implementation are logged. Device preprocessing can remain outside app control; lower WER is a phone acceptance check, not a unit-test conclusion.

Whisper now bounds decoder background using the same acoustic window as Moonshine. Already-classified queued silence no longer defers finalization; weak or unclassified potential speech still does. Acoustic sample levels, clipping, near-silence, VAD energy contrast, worker work, submitted samples and backlog are observed without claiming speaker identity, confidence or true SNR. Exact work scopes distinguish asynchronous Whisper batch decoding from Moonshine native API wall time, exclude queue waits/model loading and count overlapping/recovery input per actual submission.

Corrections replace a subject while retaining its substantive question; pronouns and source-only requests retain that question. Explicit year retraction removes the ASR year. Full-subject/question relevance rejects the murderer/comedy/partial-name sources in the log. Malformed speech with no relevant evidence uses the existing factual failure response instead of supplying unrelated quotations to Gemma. No Taco Bell alias, answer or acoustic word substitution is hardcoded.

Pipeline benchmarks cover capture, acoustic/ASR worker phases, endpoint delay, context/memory/reference work, attachments, model setup, each native submission/prefill/retry/transcription/tool pass, visible text readiness, Piper synthesis and playback proxies, executor receipts and process/device resources. Outcomes retain cancellation/errors/rejections/no speech; native callbacks remain separate from tokens. Exported JSON/CSV includes build/source/device/model/configuration provenance, comparable groups, missing-aware distributions and small-sample labels. Verified original-ASR WER/CER and independent human task/intent/factuality verdicts are explicit review steps. The 500-record/2-MiB atomic archive excludes prompt/transcript/reference text and PCM. Temporary hypotheses are generation-fenced on erasure; scoring runs off the UI thread.

New controlled release `test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence` verifies reference consent/scoring, independent quality, missing/native-token gaps, environment labels, redaction, persisted cancellation/scoring and reset. JVM coverage includes reference-follow-up regressions, capture/background/finalization policies, actual worker accounting, scorer alignment, schema/statistics, atomic byte bounds, captured observer ownership and terminal collector fencing. All existing 45 journeys remain required. Read [pipeline-benchmarks.md](pipeline-benchmarks.md) for metric definitions and reproducible phone/resume criteria.

Limits: no installed real-model inference, physical mic/effects/restaurant improvement or Fold 6 performance is qualified by local JVM/emulator fixtures. Accepted executor tasks, follow-up/interruption capture and report audio have separately owned, linked receipts; they remain outside the main command's ASR/TTS receipt. Follow-up ASR fields describe the final capture attempt; retry/readiness observations remain separate. Preconfirmation keyword/natural probe work stays in interruption diagnostics and is excluded from the final-ASR work counter. Native exact token IDs, native multimodal encoder timing and acoustic audibility remain unavailable. PStack Work Port planning was applied; the requested Terra route is unavailable in this host. Actual inherited agents implemented and independently reviewed the work; no provider-billing or fully coordinated PStack receipt is claimed.

Build 867 run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36810059233 compiled production and JVM test sources, passed native keyword/helper checks, then rejected test45's missing experimental Compose semantics opt-in at line 2131. JVM execution, signed APK completion, emulators and publication did not pass. Repair 1 adds the same narrow API opt-in used by existing journeys; no assertion or acceptance criterion changes. Failed receipt artifact `11140425993`, SHA-256 `dd018531fd613a276815c8c7bb55d6aa8428c5b9ec8eb71682606e0eec536f6f`, remains retained. Exact repaired-revision release verification is required.

Build 868 run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36812658278 passed all 894 JVM tests, native/helper checks, signing and normal/compact APK assembly. API 30 passed all 45 existing journeys but test45 could not find the visible reference dialog's `pipeline_benchmark_reference_score` resource ID: a separate dialog semantics root did not inherit the activity's resource-tag setting. Repair 2 enables resource-tag semantics on the benchmark screen and both dialogs, matching the existing Memory UI pattern; all test45 selectors and assertions remain intact. API 30 evidence artifact `11141255299`, SHA-256 `6e0610da1130ebd98b1ea2d016acd1b594ba82156f8fc12fca7d58038d0e5c8b`, remains retained. These results do not qualify the repaired revision; the complete exact-head gate must pass again.

Build 869 run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36815009876 passed all 894 JVM tests and signed normal/compact build checks. Both emulators passed all 45 existing journeys; test45 passed reference scoring, independent quality, redacted export, and durable-score assertions, then failed at the restored-screen reference control. Its screenshot still displayed the previous composition's copy status: `setContent` reused the original remembered selection, so the fixture's next click collapsed the already-expanded sample. Repair 3 is an explicit harness correction: key the rendered composition by store identity to model fresh process UI state, while preserving every assertion, including unavailable restored transcript and durable reset. API 30 artifact `11142001356` SHA-256 `d2e2ee8f10b29aefa5277fbc0c9cd1767079921ed6e34e1209175c49e2bb17a5`, API 35 artifact `11142006308` SHA-256 `aab354ce42713d450dcad6d295864a968be6f6d71c8894df8116cbf51d594c44`, and failed receipt `11141608357` SHA-256 `32074330ecd4e3fc82ec7e6d33ae65f6c5c1bfe21df1f5bb937298f0deeb1622` remain retained. Publication was correctly skipped. This is not a pass for the repaired revision; exact full CI must run again.

Build 870 run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36817635224 again passed 894 JVM tests, signed build checks, and all 45 existing journeys on both variants. The new journey exposed two specific test-driver defects: API 35 accepted a Save-review node completely outside its scroll viewport and tapped Android navigation; API 30 searched downward for an export control above the current view until its enabled-control deadline expired. Retained logcat records prove the misplaced tap and unsuccessful search; they do not show an application save/export failure. One additional bounded harness correction is scoped to the benchmark journey: intersect scroll-parent viewports, reveal controls before a single stable tap, seek header controls from the top, and await the actual quality update before reading it. Existing global helpers, all expected results, and production behavior remain unchanged. This narrowly justified correction extends the default three-attempt repair budget for an independently diagnosed harness defect; exact full verification remains required. API 30 artifact `11143810511` SHA-256 `fe7ed9e8ad5b1f1dc2fbc0fe3ec874cf6c1ea50fbcc65d10d263e30366a19d5e`, API 35 artifact `11143540934` SHA-256 `10e83722a031519bee5ca72a602ca5ce5e2f5b84e02aeaa5fdc2c98fc8af0ef6`, and failed receipt `11143165927` SHA-256 `951f75be58ba50070428e8bf88687f2fbfc01311b93ff9d848138a33fc4c4b71` remain retained. Tested merge `1549bc095a79e1f8d1f910db7b537ceb6753c75d`, PR head `3bd86181233b673bb785f3078201d46c92cc1d32`; publication was correctly skipped.

Build 871 run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36821931028 passed 894 JVM tests, both signed variants, and every one of the 46 API 30 journeys. API 35 passed the 45 existing journeys and the new reference/quality assertions, then failed when the benchmark-only helper could not find `tts_load_ms: unavailable`. Its populated screenshot ends below that row, while logcat records 14 forward gestures without edge recovery. The subsequent XML reflects fixture cleanup, so it does not establish metric omission. Two independent reviews classified the remaining failure as the same navigation defect: use controlled overlapping scrolls, observe the scroll container's boundary result, and reverse once when necessary. One final full run is permitted to complete this narrowly established harness correction, with the original 15-second/14-gesture limits, visibility checks and every expected result preserved; another failure requires a bounded handoff rather than further automatic repair. API 30 artifact `11144698426` SHA-256 `175a35f59737adfd13959c0a50287edbbf562d534db2e4217fdaf49479bf9982`, API 35 artifact `11144422576` SHA-256 `650db25578c62e6a7152528b08cef54bef1e5baf98f7ec368802431370762d54`, and failed receipt `11144399186` SHA-256 `6b3ef4935fae69ba71a30cfb43f1f5e8af786a2e023f24c4eadd788aedbbfa6f` remain retained. Tested merge `c65e39c67c2104a348064544829c359a957f3c60`, PR head `bd470cf178ba1a6d401b11101c432eb6c83c17ae`; publication was correctly skipped. This does not verify the next revision or physical noisy speech.

The production-parent test27 verifies Memory-to-Chat/Voice navigation and exact Tools approval UI while the single chat and active voice overlay remain alive. The source-branch original test names below map by suffix to their renumbered combined methods. Historical failed-run evidence and device/model limitations remain applicable.

Update this file when adding a feature or learning a reproducible regression. A listed gap is not passing coverage.

## APK publication

Every opted-in successful push or same-repository PR build publishes both signed APKs
after the release build, API 30 normal/API 35 compact journeys and consolidated receipt
pass. Feature branch pushes use a numbered release and identify their branch in its name.
The Build 849 regression was an event-condition skip, despite passing verification;
the corrected workflow retains all verification dependencies. Build 851 (`ca99845`) passed
and published both APKs in `v0.1.0-build.851`. Failed or skipped verification cannot publish an APK.

## Durable phone-action foundation (M1a)

`ToolTaskLedger`, `FileToolTaskStore` and `JournaledActionPipeline` persist ordered groups,
steps, attempts, typed receipts, grant provenance, exact approval choices and bounded progress
events. Dispatch and approval consumption are one transaction. The direct app fast path and
model-directed text/voice path admit the same frozen native plan. Startup and foreground recovery
recheck schema, exact scope, expiry, dependencies, conversation and unlock; no model is loaded
for native recovery. Revised targets invalidate old choices. Disabling a routine pauses only
its dependent unfinished steps. `DurableToolTaskTest` adds scope/revocation/expiry, forged/stale
approval, atomic rollback/racing claims, question presentation, ordered resumption, migration,
retention and cancellation coverage alongside the existing journal and voice tests.

Release `test30_phoneActionJournalPreservesReceiptsAndFencesUnknownEffects` uses the real
Android volume executor and app-private storage. It verifies a persisted typed receipt,
reopening/recovery fences, and corrupt storage preventing another volume effect. Reopening
the file is a controlled restart simulation, not an actual process kill during a side effect.
The separate-process `test90` selection journey remains unchanged. Exact-revision JVM,
normal/compact builds, API 30/API 35 sandbox and consolidated receipt are required on this revision.

`test31_taskRecoveryAndExactApprovalPreserveAndroidEffects` exercises real Android volume/battery,
ordered file reopening and denied/schema-changed approval without another effect.
`test32_taskPanelShowsExactChoiceAndReconcilesWithoutRetry` exercises the production task panel
with controlled approvals/results, checks the exact volume target, durable decline and unknown
acknowledgement without another execution. Its executor is a fixture; it does not load weights.

Limits: fourteen catalog tools (read_battery, open_app, set_volume, media_control, open_website, open_settings, navigate, screen_observe, screen_tap, screen_scroll, screen_type, create_reminder, show_schedule, post_notification), one to three ordered steps, two-minute native relevance window,
256 recent completed attempts and a 512-attempt / 1 MiB hard cap. Unfinished groups and unresolved
unknown effects are protected from pruning; full protected capacity blocks new effects. Existing
chat receipt retention continues. Routine grants are executor foundations, not a workflow editor
or scheduler. D11 adapters remain unavailable, and cannot be admitted into native grants.
The general background/model supervisor, notification outbox, proactive messaging and continuous
multi-bubble speech remain later milestones. Actual process death during Android effects,
real-model choice, physical audio and device performance remain unverified.

## Conditional and multiple-action repair — exact CI pending

The reported `if my battery is less than 60 open Facebook` now produces a bounded
current-battery predicate and one literal app request. A fresh typed Android battery
receipt determines whether to run or skip the whole plan. Up to three directed actions
accept comma, and, and then separators and execute in order through the durable ledger
without a Gemma function-call round trip. Failure/cancellation stop later steps; false
conditions finish as explicit skips rather than fabricated execution receipts. Conditions
are checked at execution time; unfinished conditional groups pause after process restart.
This is an immediate request, not a background trigger or general workflow scheduler.

`ConditionalActionPlanTest` checks the exact report, comparison boundaries, unless,
suffix conditions, comma sequences, final-voice source guard, unsupported/invalid input,
typed readings, no model bypass, failure/cancellation, explicit repetition and restart
fencing. `test33_conditionalAndCommaPlansUseRealAndroidWithoutModelCalls` reads actual
Android battery, executes volume → battery → Settings, and checks a false predicate
leaves volume unchanged. Earlier single/multiple-action tests remain required. Runtime
diagnostics include the build, literal request, predicate, decision and actual receipts.

Build 857 failed test32 on both emulator variants: the task dialog displayed its buttons
but omitted their resource test IDs in its separate Compose semantics root. The repair
enables tag export inside that dialog; no assertion or acceptance threshold was removed.
API 30 evidence is retained from artifact 11080861694 of run 36676486374. New exact-head
JVM/native/build, both emulators and receipt are required before publication. Physical
ASR/TTS, Fold 6 behavior and model-generated calls remain unverified.

## Build 859 background dispatch and report repair — exact CI pending

The supplied 859 call proves the battery comparison and three-step parser worked: battery
78 matched below 80, and the later plan was volume → Facebook → battery. The app launch
was converted to a notification before Android evaluated it, and the failure stopped the
battery step. New action sessions also re-imported every earlier terminal task in the call;
Gemma subsequently claimed the failed sequence was complete.

The executor now uses the system-bound selected assistant when ready and otherwise submits
the explicit normal activity request. Hiding Jarvis's screen alone no longer forces a
notification or stops a sequence. Background fallback receipts say “Requested opening”:
Android's void startActivity API can silently block a launch, so dispatch is not proof of
an actual foreground transition. Actual exceptions and missing apps remain failures.
Diagnostics distinguish visible, selected-assistant and background-request routes, selection,
visibility and submission. The settings button requests the standard assistant role before
manufacturer settings fallback; no per-command notification interaction is introduced.

Terminal report scans and final chat summaries use only the current action session's task
IDs. In-session interrupted reports stay pending until actual completed playback; already
reported earlier requests are not resurrected by the next command. Short status follow-ups,
including the logged “Well”, use the latest runtime-owned executor outcome before model
streaming. Different conversations and intervening unrelated user turns do not reuse it.

`ContinuousActionSessionTest` covers old/new sessions, completed delivery, interrupted
report retention and rejected admission. `PhoneActionStatusReplyTest` covers failed,
successful, skipped and unrelated follow-ups. New registered release `test34` binds the real
assistant on the disposable Android device, hides Jarvis beyond the recent-foreground grace
period, then executes volume → Settings → battery and a second background launch without
a notification tap. It asserts Settings really becomes visible, reads actual battery/volume,
checks the assistant route and preserves the missing-app failure. System assistant settings
and volume are restored. API 30 and API 35 must both pass on this revision. Real Gemma,
physical microphone/speaker and Fold 6 Android 16 launches remain user-device checks.

Build 862 retained a test-harness failure on both APIs: test34's first background launch
and Settings screenshot succeeded, then its cleanup called ActivityScenario.recreate while
Jarvis was STOPPED behind Settings. ActivityScenario timed out waiting for RESUMED and
masked the journey result. The correction removes that unnecessary cleanup recreation;
the existing @After still closes the scenario, and no side-effect/assertion/gate is removed.
Failed evidence is retained in run 36771927618, API 30 artifact 11125096760 and API 35
artifact 11125217008. A fresh exact-revision build and both full emulator suites are required.

## Planned tools epic

The [tools implementation plan](../plans/tools-implementation-plan.md) defines milestones M0–M8 and planned acceptance T01–T25 from the [completed design interview](../plans/tools-interview-decisions.md). These checks are not implemented or passing coverage. In particular, automatic memory will intentionally change the manual-review default described below; preserve historical tests/evidence and add explicit migration coverage when implementing M6. Extend this map with exact test names and run evidence as each milestone is built.

### M0/M1a typed native-tool contract — JVM checks pending exact CI

`MobileToolCatalogTest` covers the shared catalog's LiteRT schemas, strict decoder
types/ranges and the removed `open_app.package` parameter. Strict `set_volume.level`
accepts only a genuine JSON integer 0–100; numeric strings remain a legacy-decode
compatibility behavior and cannot cross the side-effect boundary. It also checks that
the disabled LiteRT SDK callback cannot return a fake success. `ActionTurnRunnerTest`
covers a positive pass budget, initial-generation accounting, no post-limit inference,
and a batch completing inside its limit. `MobileActionPipelineTest` distinguishes
validation rejection, Android denial and unknown completion while preserving
cancellation propagation and zero effects for invalid requests. These are new JVM
checks pending hosted CI for the exact published SHA; Android executor journeys,
model-generated calls, new M1 commands, screen control, workflows and device/model
coverage remain pending.

## Existing acceptance

| Area | Existing logic checks | Release emulator checks | Remaining device/model checks |
| --- | --- | --- | --- |
| Setup and model browsing | ModelCatalog, ModelGuide and ModelGuidance JVM tests | First-run setup, disabled model check, empty search, browse/cancel without selection | Successful download/import and real model load |
| Model selection | Catalog resolution/compatibility logic; persistence tested on the emulator | Choose another model, Activity recreation, separate-process restart | Switching between loaded engines |
| Tool calling | ActionIntentRouter, NativeToolJourney, MobileActionValidator, MobileActionPipeline and ActionTurnRunner JVM tests (strict intent/argument validation, ordered batches, failure boundaries, explicit repeats, replay deduplication, permission/service failure results, no automatic retry, preserved cancellation/programming errors) | Battery equals Android state; valid volume changes Android; invalid input preserves volume; missing app reports failure; Settings opens on screen; malformed/unsupported requests preserve Android volume; repeated volume requests preserve target state; three-action turn runs battery → volume → Settings; invalid plan has no Android effects; partial failure retains completed executor receipt/effect; duplicate model pass does not replay volume; natural Settings → battery request bypasses lookup; literal unknown retry app fails before battery and rejects Facebook substitution | Real model emits correct calls; end-to-end text/voice model integration; physical voice behavior; background launch/notification flow; ambiguous apps/actual permission combinations |
| Continuous accepted-action voice | Pinned Build 761 `AcceptedActionQueue`, `ContinuousActionSession`, `VoiceActionControl`, `VoiceCallStore` and runtime ownership checks | `test20` FIFO follow-up while action runs; `test21` speech interruption preserves tasks; `test22` scoped cancellation preserves completed receipts; `test23` end-call retains unspoken results | Intended acceptance is bounded FIFO work and typed/voice follow-ups; build 768 passed its exact Android CI; the merged revision requires fresh CI. Real weights, microphone/ASR/TTS/acoustic behavior and physical Fold 6 behavior remain unverified |
| Conversations | ConversationHistory, context/policy and prompt-builder JVM checks; controlled `ConversationScreen` surface check | `test27` keeps an active call across Voice/Chat/Memory navigation and ends only through explicit End, goodbye, or stop-listening; quiet captures retain the call, typed follow-up is bounded, and queue-full is explicit | Acceptance requires exact-run receipt review. Real weights, generated replies, microphone/ASR/TTS and physical behavior remain unverified |
| Native MemoryOS wiki | `MemoryPolicyTest`, `MemoryStoreTest`, `MemoryOsTest`, `MemoryRetrievalTest`, and bridge checks cover bounds, restricted-content exclusion, review state, lineage, deletion, persistence and quoted packets | `test24` drives the production Memory modal, Review approval, Wiki search/page/source, organization, links/backlinks, correction, deletion and recreation; `test25` proves finalized text/voice proposals stay pending until approval and approved quoted context reaches the prompt builder; `test26` refreshes approved context after correction/erase | Pending, rejected, superseded, deleted, and expired records never enter the wiki. Recall is conservative local history only; real weights, ASR, microphone, cloud retrieval and physical behavior remain unverified |
| Voice lifecycle and reply metrics | VoiceSessionController, ConversationHistory, LiteRtVoicePrefillSession, BargeInGate/NaturalBargeIn, PiperTextStream and ReplyMetrics checks cover per-reply JSON persistence, late playback after the next turn/restart, raw native timing before hidden-channel filtering, ASR interruption and sentence-first Piper submission | `test29` renders two distinct per-message metric footers and reloads persisted history | Physical Fold 6 route selection, Bluetooth, acoustic behavior, real-model timing and thermal behavior remain unverified. TTF-SW uses AudioTrack head progress as a playback proxy, not microphone acoustics. |
| M1d task/conversation scheduling | `SilentWorkModeTest` (silent-work enter/exit, wake phrase, answer window, control passthrough, session gate) and `M1dTaskSchedulingTest` (scheduling policy, D24 stop phrases, stop router, approval→session admission, changed-target invalidation, dispatch eligibility, cancellation by identity, status projection, model-proposal parking) | `test59` panel approval admits the screen session and dispatches exactly (stale approval denied, finished group releases the lease and hides the overlay); `test60` conflicting screen task queues behind the lease instead of stealing it; `test61` progress/finished notifications are posted with a bounded visible-notification check; its attempted DND setup is conditional and does not establish acoustic silence; `test62` admitted work survives call end and the finished group releases the lease, hides the overlay and projects completion | Real-model proposal of screen mutations, physical Fold 6 screen behavior, real accessibility-service enablement, microphone/wake-word acoustics and on-device approval UX remain unverified |
| M1e device validation (permission/lock handling, regression) | `M1eDeviceValidationTest` (first-source access remembered, within-family auto-exposure, denial/revocation blocks dispatch and approval claim, dispatch never overwrites denial, out-of-family scope refused at admission and by the file store, source-access file round-trip, capability denial at admission and mid-flight, lock classification, gated owner recognition, NEEDS_UNLOCK without effects, stale-callback rejection, crash-before/after-dispatch reconciliation without repeat) | `test63` source-access denial/revocation blocks dispatch on every adapter with a truthful receipt (approval claim blocked too; tampered cross-family scope refused); `test64` locked device hands sensitive actions off to unlock while the non-sensitive battery read dispatches through the real Android adapter (owner recognition gated; real keyguard state); `test65` cross-family T01 regression: invalid args for every M1 tool family are rejected before any adapter runs (volume unchanged, no screen effects); `test66` crash before/after dispatch recovers to an unknown outcome, stale callbacks are rejected and unknown mutations are never repeated | Physical Fold 6 lock behavior, real permission-revocation UX, on-device speaker-verification measurement (owner recognition stays gated until measured), real-model tool selection and physical-device performance remain unverified |
| M2 reusable workflows and triggers | `M2WorkflowsTest` (versioned step-graph validation, typed result bindings, deterministic conditions, waits, bounded adaptive steps, plain-language preview, draft/enable/revise lifecycle, routine-grant exact-limit reuse, disable pauses affected work, conversation capture, restart dedup, daily/DST scheduling, exact-alarm honesty, missed-run relevant/irrelevant/uncertain evaluation with receipts, coalescing, engine suspend/approval/user-question outcomes, effort budgets, schema-3 file round-trip and tamper refusal, settings projection) | `test67` routine occurrence completes under a reusable routine grant with exact limits (changed limits rejected at reuse and at admission; approval waits block dependents only; disable pauses the routine's unfinished work while unrelated waits are untouched); `test68` conversation-created workflow shows its plain-language summary and cannot schedule until explicitly enabled, captured drafts start disabled, and a revision never mutates the running occurrence's pinned version; `test69` reminders target the requested time, flexible routines use windows, exact-alarm mode is reported honestly against the real Android alarm service, daily triggers resolve forward across the spring-forward DST gap, and reboot recovery never replays a trigger or duplicates an occurrence (redelivered claims cannot double-fire); `test70` missed runs evaluate to relevant/irrelevant/uncertain with persisted decision receipts, duplicate missed slots coalesce to the latest, and bounded adaptive effort asks the user instead of repeating completed work | Real-model proposal of workflow steps, physical Fold 6 alarm delivery while the app is closed, real notification/location trigger listeners, on-device approval UX for screen-step branches, and physical-device timing remain unverified |
| M3 ecosystem integrations (AppFunctions discovery, MCP) | `M3EcosystemTest` (provider identity/wire names/families/scope caps; strict nested type conversion with no coercion and exact error paths; discovery diff and alias invalidation on update/uninstall; collision-safe aliases; task-relevant selection; dispatcher grant recording, typed results, paid purchase confirmation, model-exposure gate, dependent journeys with output bindings; MCP URL policy, protocol parsing incl. SSE tolerance, guided setup against fakes and a real loopback HTTP server, free-only default enablement, schema-change blocking, disconnect, auth-failure states, credential hygiene with secrets never in configs; provider-grant file round-trip and tampered cross-provider scope refusal; honest settings rows) | `test71` runs the real AppFunctions platform probe with ordinary app access (access method labeled, honest empty-state note), validates the controlled dependent-function journey with output bindings, collision-safe aliases, strict nested argument conversion with zero dispatch on invalid input, and update/uninstall invalidation, and proves the T08 provider-grant discipline (first-source grant remembered per provider family, revocation blocks every adapter, an independent provider keeps working); `test72` runs guided MCP setup with a custom URL against a real loopback HTTP server through the real transport (free-only default enablement, secret never in the config), proves paid tools need explicit enablement and still return purchase confirmation instead of dispatching, and exercises schema-change blocking with re-review, auth-failure denied state and explicit disconnect with honestly unavailable calls; `test73` proves provider grants can never broaden (out-of-namespace scopes dropped), denial blocks the adapter with a truthful receipt, the model-exposure gate stays structurally shut until M7, and the settings projection carries honest provider rows | Real third-party AppFunctions providers on a physical device (broad consumer access is platform-gated; the probe reports this honestly), real remote MCP servers over the network, on-device Android Keystore credential behavior, and physical-device timing remain unverified |
| Reminder bug-fix slice (voice/text remind me, schedule view, receipt-gated replies) | `ReminderPlanTest` (bounded reminder grammar incl. relative times and the documented bare-hour PM convention, accept/reject routing, strict-decode parity, reminder workflow definition validation, ledger write success/failure receipt-gating, honest empty state, past-time rejection before any write) and `FinalVoiceToolGuardTest` (spoken reminder allows matching message/time, rejects mismatches; schedule view allowed) | `test74` proves a text remind-me request parses to a Ready create_reminder plan, dispatches through the real Android executor, writes a real ledger entry with an armed alarm, and show_schedule lists it; `test75` proves an empty schedule renders the honest empty state | Real-model selection of the new tools, physical Fold 6 alarm delivery while the app is closed, and on-device notification audibility remain unverified |
| Packaging | Native ABI, Piper callback, compact APK equivalence checks | Signed normal and compact variants installed/launched | Device GPU/NPU compatibility and resource limits |

Historical checkpoint (superseded by the current combined contract): the device contract was `scripts/verification/scenarios.json`, backed by `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`. All thirty-three named methods must finish successfully; skipped methods are failures. `test01`–`test24` remain the existing setup, tool, multi-action, and manager journeys; `test25` covers finalized text/voice memory approval and prompt context, `test26` correction/erase refresh, and `test27` the controlled production ConversationScreen state contract; `test25`–`test27` are controlled UI/memory-prompt checks, not microphone or model end-to-end tests. `test28` retains the existing VoiceNavigationPolicy call-ID contract, `test29` verifies two distinct assistant-message metric footers and persisted reload, `test30` checks the separate call action, unified chat and retained dictation mic, and `test31` checks dictation. `test90` remains the process-restart selection check. The exact combined revision requires fresh Android CI; APK/build status comes from its exact run receipt. JVM/native tests run independently in the build job.

Historical checkpoint (superseded by the current combined contract): the device contract was `scripts/verification/scenarios.json`, backed by `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`. All thirty-six named methods must finish successfully; skipped methods are failures. `test01`–`test23` retain setup, tool, and multi-action acceptance. `test24` is the complete production Memory wiki journey: Add modal rejection, controlled finalized capture, actual Review approval, Wiki page/search/source, organization, linked pages/backlinks, correction/deletion, pending/rejected exclusion, erase confirmation, and recreation persistence. `test25` covers finalized text/voice memory approval and prompt context, `test26` correction/erase refresh, and `test27` the controlled production ConversationScreen state contract; `test24`–`test27` use controlled capture/model inputs where required and are not microphone or real-model end-to-end tests. `test28` retains the existing VoiceNavigationPolicy call-ID contract, and `test29` verifies two distinct assistant-message metric footers and persisted reload. `test90` remains the process-restart selection check and intentionally leaves the selected model for the controller's process-restart check. The exact combined revision requires fresh Android CI; APK/build status comes from its exact run receipt. JVM/native tests run independently in the build job.

Historical checkpoint (superseded by the current combined contract): the device contract was `scripts/verification/scenarios.json`, backed by `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`. All thirty-five named methods must finish successfully; skipped methods are failures. `test01`–`test24` remain the existing setup, tool, multi-action, and manager journeys; `test25` covers finalized text/voice memory approval and prompt context, `test26` correction/erase refresh, and `test27` the controlled production ConversationScreen state contract; `test25`–`test27` are controlled UI/memory-prompt checks, not microphone or model end-to-end tests. `test28` retains the existing VoiceNavigationPolicy call-ID contract, and `test29` verifies two distinct assistant-message metric footers and persisted reload. `test90` remains the process-restart selection check and intentionally leaves the selected model for the controller's process-restart check. The exact combined revision requires fresh Android CI; APK/build status comes from its exact run receipt. JVM/native tests run independently in the build job.

The merged audio-pr2/tools contract (audio-pr2 import, October 2026) is `scripts/verification/scenarios.json`, backed by `app/src/androidTest/java/com/battlesbudz/jarvis/v2/verification/ReleaseJourneyTest.kt`. All seventy-six named methods must finish successfully; skipped methods are failures. `test01`–`test24` remain the existing setup, tool, multi-action, and manager journeys; `test25` covers finalized text/voice memory approval and prompt context, `test26` correction/erase refresh, and `test27` the controlled production ConversationScreen state contract; `test25`–`test27` are controlled UI/memory-prompt checks, not microphone or model end-to-end tests. `test28` retains the existing VoiceNavigationPolicy call-ID contract, and `test29` verifies two distinct assistant-message metric footers and persisted reload. `test30` checks the separate call action, unified chat and retained dictation mic, and `test31` checks dictation review with draft preservation on cancel. `test32` proves an installed model and a completed download survive store recreation; `test33` proves downloading does not select or dismiss the current model. `test34`–`test36` cover the SQLite migration (history preserved and erase across reopen, corrupt-migration and failed-erase data safety, legacy byte-limit and serialized separate writers). `test37`–`test38` cover the source archive (explicit history retained with expiry without fact loss; V1 upgrade rejects secrets and rolls back failure). `test39` proves reference PDF extraction and the pending acknowledgment use release code. `test40`–`test44` are the tools M1b/M1d journeys already absorbed by audio-pr2: the durable phone-action journal, task recovery/exact approval, panel reconciliation, conditional/comma plans and background-assistant app launch. Launch journeys independently require real Settings visibility while retaining submitted/unconfirmed receipts; dependent steps stop at an unconfirmed opening. `test45` proves pipeline benchmarks score the original ASR, persist and export redacted evidence; `test46` proves reply-metrics exports cover only their own conversation across reload; `test47` proves the audio-input choice and display-caption toggle persist without loading models; `test48` proves a spoken call end retains the wake session and an explicit stop disarms. `test49` retains Wisp throughout chat, memory, tool states, journal failure, and repeated call start/pause/stop/end, with observed geometry and preserved draft. `test50` dispatches the `media_control` verbs through the real Android executor; `test51` proves a text "pause music" request parses to a Ready `media_control` plan and dispatches (regression for the Fold 6 hallucinated-success report); `test52` opens the Wi-Fi settings screen and verifies it appears; `test53` dispatches `open_website` with an honest receipt and rejects dangerous URL schemes; `test54` dispatches `navigate` with an honest receipt and rejects blank destinations. `test55` proves the real Android tree-walking extraction against the live UiAutomation window tree and the service's manifest declaration; `test56` proves verified-target tap dispatch (unadmitted and stale tokens never dispatch, wrong-kind targets rejected, release hides the overlay); `test57` proves manual-touch pause and idle resume re-observing the changed screen without a countdown; `test58` proves honest unavailability receipts when the accessibility service is not enabled. `test59` proves the M1d approval-UI wiring: panel approval of a screen task admits the screen-control session grant for its group, the ledger claim consumes the approval atomically with dispatch eligibility, a changed target invalidates the prior approval, and a finished group releases the lease and hides the Stop overlay. `test60` proves a conflicting screen task queues behind the lease instead of stealing it while independent work runs. `test61` checks bounded progress/finished notification posting; its DND setup is conditional, so DND activation and acoustic silence remain unverified. `test62` proves admitted work survives call end and the finished group releases the lease, hides the overlay and projects completion with ordered receipts. `test63` proves source-access denial/revocation blocks dispatch on every adapter with a truthful receipt (the approval claim path is blocked too; first-source access is remembered; a tampered cross-family scope is refused). `test64` proves a locked device hands sensitive actions off to unlock while the non-sensitive battery read dispatches through the real Android adapter, against the real keyguard state, with owner recognition gated. `test65` is the cross-family T01 regression: invalid args for every M1 tool family are rejected before any adapter runs, with volume unchanged and no screen effects. `test66` proves crash before/after dispatch recovers to an unknown outcome, stale callbacks are rejected and unknown mutations are never repeated. `test67` proves a routine occurrence completes under a reusable routine grant with exact limits (changed limits rejected at reuse and at admission; approval waits block dependents only; disable pauses the routine's unfinished work while unrelated waits are untouched). `test68` proves a conversation-created workflow shows its plain-language summary and cannot schedule until explicitly enabled, captured drafts start disabled, and a revision never mutates the running occurrence's pinned version. `test69` proves reminders target the requested time, flexible routines use windows, exact-alarm mode is reported honestly against the real Android alarm service, daily triggers resolve forward across the spring-forward DST gap, and reboot recovery never replays a trigger or duplicates an occurrence (redelivered claims cannot double-fire). `test70` proves missed runs evaluate to relevant/irrelevant/uncertain with persisted decision receipts, duplicate missed slots coalesce to the latest, and bounded adaptive effort asks the user instead of repeating completed work. `test71` proves AppFunctions discovery with the real ordinary-app platform probe (access method labeled, honest empty-state note), the controlled dependent-function journey with typed output bindings, collision-safe aliases, strict nested argument conversion with zero dispatch on invalid input, update/uninstall invalidation of exactly the affected aliases, and the T08 provider-grant discipline (first-source grant per provider family, revocation blocking every adapter while an independent provider keeps working). `test72` proves guided MCP setup with a custom URL against a real loopback HTTP server through the real transport — free-only default enablement, the secret never in the config — plus paid tools requiring explicit enablement and still returning purchase confirmation instead of dispatching, schema-change blocking until re-reviewed, an explicit auth-failure denied state, and explicit disconnect making calls honestly unavailable. `test73` proves provider grants can never broaden (out-of-namespace scopes are dropped at record time), denial blocks the provider adapter with a truthful receipt, the model-exposure gate stays structurally shut until M7, and the settings projection carries honest provider rows with labeled access. `test74` proves a text "remind me" request parses to a Ready create_reminder plan and dispatches through the real executor into a real ledger entry that show_schedule lists; `test75` proves the empty schedule renders its honest empty state. `test90` remains the process-restart selection check and intentionally leaves the selected model for the controller's process-restart check. The exact combined revision requires fresh Android CI; APK/build status comes from its exact run receipt. JVM/native tests run independently in the build job.

Artifact consumers share the retry-safe selector and direct-ID downloader in `scripts/verification/artifacts.py`. For each requirement it binds run and SHA, selects the latest completed producer attempt, filters to artifacts in that producer's created-time window, and fails closed unless exactly one newest candidate remains. The downloader verifies the selected ZIP's declared size/digest and safely restores the expected flat or artifact-namespaced layout without forwarding the GitHub token to storage. Prior failed-attempt artifacts stay available for diagnosis. Run 751 replay reproduces the lower-ID case and selects artifact `10707864350`; focused helper and receipt checks cover the plumbing, while a new CI run is still pending. This is verification plumbing evidence and does not change the app or establish a green run 751.

For each new feature, write down the entry screen or API, expected state change, a negative case, persisted effects, test level, and evidence path. For new tools, keep validation, Android execution and real-model tool selection as distinct checks. Never turn a canned tool request into a claim that model inference passed.

Build 760 evidence is historical only: it does not validate the current `feature/memory-conversations` candidate or the new Memory/conversation surfaces. The pinned Build 761 accepted-action queue is the reuse target; exact Android CI for the current candidate is pending. The new acceptance requires FIFO follow-ups, speech-only interruption that preserves accepted work, scoped cancellation that preserves completed receipts, fresh report retry and durable ended-call outcomes without replay, and an intake capacity of four pending typed messages plus one bounded control slot, while the accepted worker/report path has at most three outstanding requests of one to three actions; overflow must be explicitly rejected. Controlled native calls plus a real Android executor are executor-contract checks, not real model, ASR, acoustic, or Fold 6 evidence.



Acceptance added for the tool-failure repair: denied/service-failed execution returns an honest failure, performs no automatic retry, and permits a later independent action. Cancellation and unexpected programming errors still propagate. These failure modes use injected JVM executors, not claims of actual Android permission denial. The multi-action journeys use controlled calls with the real Android executor; this remains executor-contract coverage and does not claim microphone, ASR, TTS, weights, or physical-device validation.

Model evidence acceptance: 18 specified removals resolve old preferences safely to E2B; 72 retained entries have evidence bound to hash/backend/context. Requested experiments cannot become starting options. Known-issue text appears on browser cards and selected-model setup before download; Zamba2 2.7B requires acknowledgement before selection. `test10` verifies the reboot warning and cancellation preserving selection; JVM `ModelCompatibilityTest` checks classification and stale evidence. Real-model startup/thermal behavior is unverified by these checks. Existing downloaded files are not automatically deleted.

Model-picker usability repair: compact summaries keep reported issue warnings and unsupported-use limitations visible; detailed evidence and resource explanations open only on request from both setup/settings and the family browser. Experimental means unverified, not a reported failure. Memory warning colors depend only on memory risk (JVM regression). The full-screen browser explicitly handles system/IME insets and reserves list-end padding. Release `test11` checks optional details without changing selection; `test12` scrolls to CodeGemma at the end of Gemma, checks the full Choose target is above navigation and selects it. Extra `last_model_button` and `model_details_open` screenshots support visual review. Physical Fold 6/Android 16 layout remains a final user check; CI covers API 30 and 35. Stale speaker-learning UI is removed; the runtime already disables speaker identity. Phi mini purpose copy follows Microsoft's publisher card: https://huggingface.co/microsoft/Phi-4-mini-instruct.

Multimodal/tool access: chat accepts one locally retained image or short WAV attachment per message according to the exact catalog bundle flags. Images are bounded-read, normalized to at most 1536 px and stripped of original metadata; audio is validated as up to 30 seconds of 16 kHz mono PCM WAV. Attachments survive conversation reload and never enter action routing as instructions. Text-only conversions stay text-only. FastVLM's existing vision bundle is enabled. All catalog models are offered Jarvis's existing fourteen catalog tools on explicitly authorized action turns using the bundle's native LiteRT template; automatic SDK execution stays disabled. Tool availability is not a model training or reliability certification. JVM tests cover input rejection, persistence and community-model tool configuration; release `test13` exercises real Android image preparation, resizing and corrupt-file rejection. Full chat picker/send, real image/audio understanding and model-generated tool calls still require installed weights/device testing. Current LiteRT API guidance: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md. Screen control (`screen_observe`/`screen_tap`/`screen_scroll`/`screen_type`) is implemented behind the accessibility-service grant with verified targets and a session-scoped grant; no live video or new tool installation is implied.

MemoryOS acceptance: the Memory screen is reachable from setup/model selection and its Add control opens a modal. Each finalized proposal enters Pending; pending and rejected records are absent from Wiki/search until an actual Review approval. Approval creates category/topic pages with inspectable sources; manual organization persists and `[[Topic]]` links create backlinks. A correction records a superseding approved record only after review, and correction/erase refresh the index. Erase-all requires confirmation, cancellation leaves records visible, and Activity recreation reloads the saved local ledger. Restricted financial/identity examples are rejected with an error, while corrupt/unavailable storage is surfaced as an error rather than an empty history. `test24`–`test27` are release journeys; focused JVM/controlled tests are logic and UI-contract coverage. Runtime fence, expiry, and delivery correctness require exact combined-tree tests and receipt evidence; Android process death, model-generated recall, semantic retrieval, network/cloud backends and physical-device/model checks remain outside these journeys. The finite caps, lexical matching and conservative restricted-content checks are bounded implementation behavior, not a general safety proof.

Build 767 API 30 retained a `test24` failure after one reject tap: the log records a scroll at 09:42:41.645, then `memory_reject` found at 09:42:42.326 and a tap at `(368,869)` at 09:42:42.447; the retained screenshot still shows the pending row. That does not establish a clipped target or a MemoryOS defect. The release harness now requires two fresh, safe, unchanged accessibility-bounds samples before one mutation tap, without replaying a mutation. Build 768 passed both emulator variants with this harness repair; the merged revision must rerun both.

## Combined MemoryOS acceptance

Build 768 (`649f58c`) passed 685 JVM tests and all 28 named journeys on API 30 normal and API 35 compact, with a passing consolidated receipt. It is historical parent evidence. This integration retains the original navigation-policy journey as `test28`, bringing the merged contract to 30 journeys per variant, and adds a focused approved-memory/reference-routing regression. CI must verify the exact merge commit before handoff. Only `feature/memory-os-v2` is advanced.

Conflict resolution uses the build-768 persistent-call, input ownership, delivery-fence, release ABI, and stable-tap implementations. Existing memory review/correction/erase and accepted-action journeys remain. Approved matching personal recall suppresses automatic factual lookup and retries, but explicit lookup/confirmation still wins; memory never changes phone-action authorization. A revoked native prefill is excluded rather than intentionally taking an exception fallback. Physical-model/audio/Fold 6 checks remain outstanding.

Unified chat and floating voice call: the composer keeps its dictation microphone and adds a separate phone action. Tapping the phone action starts a voice session and opens the waveform call surface as a dismissible overlay over the same chat. Minimize, Back, and outside dismissal hide the overlay without ending the call; the active call can be reopened, while End call remains explicit. The text composer and message history remain the same conversation. Release `test27` and `test30` cover controlled UI state, busy gating, opening/minimizing/reopening and retained microphone affordance. Physical-device overlay feel, real audio and model behavior remain unverified until device signoff.

Chat voice input replaces the WAV file picker with a local recorder for every chat model. **Stop** transcribes through the selected Moonshine/Whisper engine and appends editable text to the draft without sending. **Send** transcribes for chat and submits that text together with the original PCM as a validated WAV audio attachment through the existing chat path. Raw-audio Send requires a model with audio support and no other attached file; text-only models still support Stop-to-text. Recordings are bounded to the existing 30-second audio-message limit, then await the user's Stop/Send choice. Cancel/Back/backgrounding discard unfinished recording; microphone cleanup runs on success, failure and cancellation. Navigation/send/attachments are locked during recording, and voice input is disabled during active calls. `test31` uses a controlled recorder with Compose/permission integration to check Stop, draft append, Cancel, recognition failure, explicit text send, audio capability guards, audio plus transcript send, persisted paired history, failed-Send draft retention and exact WAV payload preservation. Real microphone and ASR/model behavior remain physical-device checks. Both Stop and Send may download the selected ASR model on first use.

Build 778 retained a test-harness failure in `test30` on both API variants: an unconditional Back after UiObject2 text entry finished the activity when no keyboard was open. The API 30 failure screenshot shows Android Settings, and the trace fails before the first swipe while looking for `conversation_swipe_area`. All other 30 journeys passed. The two new composer journeys now dismiss the IME through InputMethodManager without navigating Back; gesture assertions are unchanged. Build 778 evidence: https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36078314962 (API 30 artifact 10841862135, SHA-256 e60511efbf2138ab0bf31d41851fd8e41508568d5670b75d640283043acff56a). Exact revised CI is required.

Build 779's controlled dictation fixture crashed before user interaction because the separately shrunk test DEX called a removed `androidx.compose.runtime.SnapshotStateKt` facade (API 30 artifact 10841328634, SHA-256 c75563e74769f60fdd3b3304da9735df36d7227dc2cbacd3260a71efd89977e7; run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36078916045). The fixture now renders the text-only and audio-capable model cases directly instead of collecting a test-owned StateFlow inside the test DEX. All draft/cancel/error/capability/audio-payload assertions remain; no shipping Compose keep rules are broadened for this fixture.
The same ABI audit of build 780's R8 mapping showed `AttachmentPolicy` merged away while the new test DEX directly invokes `validateAudio`. A narrow keep preserves that existing validator boundary; it does not disable shrinking or alter WAV validation. Mapping artifact 10841559561 has SHA-256 30b20ba1de4cb1f1c66228d7c09ca96a5443773d7f8cec1a682615c609fc716a.

Build 781's swipe fixture then reached its first state assertion: the screenshot shows Chat active and the UI XML exposes `chat_tab` as `checkable=true, checked=true, selected=false`. Material segmented buttons expose checked state to UiAutomator. The test now checks `isChecked` for each unchanged tab-state assertion, rather than the unrelated `isSelected` field. API 30 artifact 10842780320 has SHA-256 c1e55538c67c79fbec9989092b2bc27beeeb3efd47e844bb8babec9349ad717a; run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36080730026.

Build 784 passed all 32 journeys on API 35, including both new features. API 30 passed the recorder journey but read the tab immediately after changing the fixture's busy StateFlow, before Compose applied it. Its retained XML already shows `voice_tab enabled=false` by screenshot capture. The test now waits for that same disabled condition before swiping; it retains the rejection assertion and does not retry the mutation. API 30 artifact 10843531357 has SHA-256 e7b1ae32789b78c3dce806bfe7930f17e7ece25c59c28e074946e2a782f5fef3; run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36082407330.

Build 785 phone feedback: Stop worked, but an audio-only Send received a model refusal and had no chat transcript. The retained voice call itself was recorded on build 774; the later audio-file submission is the relevant new evidence. Its `audioBytes=0` field was a diagnostic defect: attachedAudio was passed to generateAudio but omitted from that summary. The revised Send submits recognized text and the original WAV as one message, saves the transcript with the playable attachment, and labels memory capture as voice. Empty/failed recognition sends nothing and preserves the existing draft. The composer has one mic icon and replaces text controls with Cancel/Stop/Send while recording. `test31` covers UI/persistence/errors; AudioMessageInputTest checks the actual SDK multipart contents (original WAV plus accompanying text), not model inference. New exact CI and physical-model verification are required; build 785's green fixture checks did not establish real model audio understanding.

Model download/detection repair: installed bytes and verified usability are distinct. Setup labels app-owned files as installed even if metadata needs recovery, re-verifies the selected file off the UI thread, and retains hash enforcement. Publisher-specific filenames are accepted for explicit import only when they match the selected catalog hash; exact non-generic aliases are also considered in Downloads. Android scoped storage can still require the system picker for files the app cannot access. `test32` uses tiny controlled files to cover import, new-store detection, metadata recovery, offline reuse, corrupt-file rejection and installation of a complete checkpoint without contacting the host. It does not establish real-model inference or multi-gigabyte physical-device behavior.

JVM `ModelDownloadRecoveryTest` covers interrupted parallel checkpoint retention/resumption, reuse of completed ranges, a final one-byte append, complete assembled-file reuse, wrong Content-Range rejection, truncated sequential transfer/resume, non-range fallback and stale checkpoint identity. Transfers validate ranges and exact size, preserve checkpoints on failure/cancellation and check cancellation while copying. The worker preserves download/assembly/verification phase names rather than labeling the hash pass as a repeated download, throttles persisted progress and observes the current unique-work request rather than an older terminal result. Newly installed download metadata is committed before completion is reported. Exact signed-release CI is required for this revision.

Background download acceptance: Download leaves the selected model unchanged; an installed current model remains available to text and voice while a different model transfers. Choose is a separate explicit action. Only one unique setup worker runs at a time, with visible progress/cancellation; a second request does not silently observe another model's result. Transfers own their model's file rather than the global native-runtime lease, and their failure/completion/cancellation cannot release a voice owner's lease. Selection of an installed alternative is allowed while a previous selection downloads; using/deleting a file that is currently transferring remains blocked. Background downloads skip voice-resource preparation and never initialize or test the current engine. `ModelOperationGateTest`, the extended real-storage `test32`, and separate Download/Choose `test33` cover these controlled contracts. The stalled-network JVM test requires cancellation to finish within ten seconds and retain resumable bytes. It is not real-model concurrent inference or physical acoustic coverage.

Floating voice acceptance: a 100 dp waveform orb and opaque compact phase/pause/end controls occupy less than half the chat width. Contour strokes scale with the rendered diameter. Live user recognition appears inline in the conversation, and completed turns remain in the existing history; the composer retains its draft and dictation affordance. `test30` now observes armed state reactively and checks active controls, bounded overlay width and non-overlapping live transcript bounds. The fixture uses the app's dark palette, and screenshots require visual inspection on both API variants. These checks preserve existing busy/start/draft/call-lifetime assertions. The independently shrunk test DEX preserves the narrow StateFlow Compose facade and browser/profile contracts it calls. Exact new signed-release CI and device signoff remain required.

Build 838 retained a compilation failure before producing an APK: the stroke-scaling edit malformed the 1.3 dp ribbon expression in `VoiceOrb.kt:95`. The compiler rejected it in `compileReleaseKotlin`; native keyword/helper checks passed and Android journeys/publication were skipped. Evidence: https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36646700422, build job 109671224341, PR head `67a602e`, tested merge `36d311f`. The expression is corrected without changing waveform geometry or acceptance checks. Build 839 had already started with the same defect; the corrected revision requires a new exact release run.

Build 840 compiled the app and both test sources, then passed 714/715 JVM tests. The stalled-transfer fixture failed its checkpoint assertion at line 148 after only 22 ms: it cancelled when the server had sent bytes, before the client necessarily persisted them. This is a test synchronization defect, not lost saved download bytes. The fixture now waits for the downloader's positive persisted-progress callback before cancellation. The ten-second cancellation deadline, nonempty checkpoint, exact resumed payload and all other checks remain. Retained evidence: https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36647730261, unit artifact `11070042120`, SHA-256 `7bc71bd9acf83b648d318f6bfc151d2baa5310eeba715d72ad95a0553379cd91`, PR head `36762a2`, tested merge `6fc5e35`. Android/publication were skipped; the corrected fixture and gate/recovery suite pass all 11 tests locally on both Java 17 and Java 21. The corrected harness requires exact signed-release CI.

## Memory wiki repair recovery

Recovered the final wiki repairs from the interrupted compilation workspace: preserve legacy event fingerprints through organization and erasure, keep all topic links, retain erase-all for pending/rejected-only ledgers, and explicitly return Memory to Chat or Voice without ending a call. Existing release journeys now exercise these navigation and ledger paths. JVM regressions cover legacy replay/tombstones, placement suggestions, and search beyond 50 memories. No local tests were run during recovery; the exact pushed revision requires the GitHub Android APK workflow and both emulator variants before it can be called verified.

## Build 775 verification repair

Run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36074570506 at `5211b8f246c6bd441c08499f68320d013d8f88bd` passed 713 JVM tests and both signed APK builds. Both emulator variants ran all 30 journeys; test24 and test27 failed. Retained artifacts: API 30 `10839224113`, API 35 `10840760161`, JVM `10840500290`.

The test24 screenshot shows an open Add-memory dialog, while its XML has no exported resource IDs for its controls. Dialogs use a separate semantics root, so every Memory dialog now exports its existing test tags at that root. The test and its assertions are unchanged. Test27 failed at `mutableStateOf(false)` with `NoClassDefFoundError: androidx.compose.runtime.SnapshotStateKt`; the release mapping/test DEX confirm that its facade was removed while the test still invokes it. A narrow keep preserves the facade and inherited mutable-state factory ABI. This is verification accessibility/release-harness repair, not a relaxed acceptance gate. Both complete emulator suites must rerun on the new exact revision.

## SQLite Memory OS checkpoint (2026-09-30)

The production Android singleton now opens `noBackupFilesDir/memory-os.db` through `SQLiteMemoryStore`. Migration imports validated schema-1/schema-2 JSON records, revisions, source keys, review states, correction lineages, wiki placement, generation and tombstones in one transaction. It validates the imported snapshot before retiring the legacy JSON and its owned temporary files. A durable marker prevents stale legacy data from being re-imported after erase. Unsupported/corrupt data fails closed. Updates persist only changed rows and generation together; SQLite secure-delete and rollback-journal mode avoid a long-lived WAL copy of deleted payloads. This is logical app erasure, not a forensic storage guarantee.

`test24` now mounts the real Memory screen against SQLite and retains every prior UI assertion. New named release scenarios `test30` verify migration, correction selection, reopen and stale-source suppression; `test31` verifies corrupt migration rollback, injected SQLite erase-write failure with unchanged rows/generation, successful erase replay suppression, and unsupported schema rejection; `test32` verifies payloads exceeding the old 1 MiB ceiling and concurrent independent writers. All 33 methods per emulator variant are required. Existing JVM store tests remain the legacy codec/lifecycle regression suite.

The implementation keeps the current 500-record/2,000 combined record-and-tombstone safety caps and snapshot-based lifecycle API. It does not claim unbounded scale, semantic recall, automatic extraction, source-archive retention, Android process-death memory UI coverage, or real-model/Fold 6 validation. Exact candidate status comes from the fresh signed build, both sandbox jobs and consolidated receipt.


Build 842 at `10e1d7875776e4374f3b6de660fb0562711e6a53` retained failures in test24 and test27 on both variants. API 30 original artifact `11073298861` and R8 mapping `11073436799` were inspected. Test24 reaches the text editor, then the harness sends Escape even when ACTION_SET_TEXT has not opened an IME; the retained hierarchy/screenshot shows the dialog has closed before finding `memory_propose`. The helper now dismisses only a visible input-method window. Assertions, tap stability and deadlines remain intact. Test27 reports the removed ModelStore `isUsable$default` ABI; mapping also shows reshaped JarvisApp and VoicePlaybackFrame signatures used later by the same fixture. Narrow keeps preserve those shared methods/constructors. This is an explicit harness/shared release-ABI correction, not reduced acceptance. Fresh complete verification remains required.


Build 844 passed 713 JVM tests, native/helper checks and both signed builds. Retained API 35 artifact `11074610392` (original ZIP SHA-256 `c4af585354dad3b415a8981854a6a314ea596dc1aa89056a2255ccbc8c1c9626`) shows SQLiteException during database initialization, causing test24/test30/test31/test32 to fail; test27 retains the older shared-ABI failure. SQLite's `secure_delete=ON` returns a result row, whereas Android `execSQL` accepts only statements without results. The production fix uses `rawQuery` and verifies that secure-delete actually became enabled. The rollback/migration/capacity assertions and all 33 named tests remain required. References: https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase and https://www.sqlite.org/pragma.html#pragma_secure_delete . Fresh complete release gates are required for the corrected candidate.


Build 846's retained API 35 artifact `11074632660` (original ZIP SHA-256 `905ca97407faab71bf15287db86a3a72711e8653bfb72e3c451fce69d8a94f19`) confirms the default-method repair advanced test27 to composition. It then crashed on removed `ModelStore.$stable`; the test DEX field table also references `TtsModelStore.$stable`. Both generated integer fields are now explicitly retained. The audit covers the test DEX's external app stability-field owners; the other referenced owners already have full class keeps. All assertions and 33 named tests remain intact. This final shared-ABI repair accompanies the secure-delete production fix; exact new CI evidence is required.

Build 847 at `cbc207d0d6588be0d76f18755a99d8408ff1da1b` passed the signed build; retained API 35 artifact `11075080949` (original ZIP SHA-256 `b85d6747fead2a16849be4c2056ad6536ed89c83cc7b42637556ea6fcb8b9e38`) confirms test24 now creates and approves SQLite-backed memories. Its next failure is the Wiki tab's edge-touching bounds (`[0,441][360,567]`), rejected by the unchanged 24-pixel safe-bound requirement. The inspected screenshot/XML shows an enabled Wiki tab and Review (0). The production tab row now follows the same 16-dp horizontal gutter as the search field/content. Test27 still crashes on the stability field described above, preventing later storage tests from running. These two explicit repairs are the third repair candidate; full fresh verification remains required.


## Source archive acceptance checkpoint

SQLite schema 2 adds a protected, explicit-history-only source archive on the existing canonical database. `MemoryArchivePolicyTest` covers short/late secrets, useful health/financial/family text, finalized-input bounds, original retention time, opaque IDs/fingerprints and capture failure. `test33_sourceArchiveRetainsExplicitHistoryAndExpiresWithoutFactLoss` proves that ordinary context excludes episode text, erased facts remain suppressed while explicit source history remains until 90 days, lock prevents source disclosure, expiry purges text without deleting approved facts, and reopen/replay cannot renew retention. `test34_sourceArchiveUpgradesV1RejectsSecretsAndRollsBackFailure` verifies additive v1 migration, secret exclusion before writes, duplicate/conflict handling, archive-write rollback, default-deny access and a lock change during search. All 35 journeys on both variants and the complete signed JVM/native/receipt gate must pass for this addition.

Production finalized Chat/Voice capture is connected to this archive boundary; it still uses deterministic proposals and manual approval for facts. No source text enters ordinary recall or model context. The explicit history API has no user-facing search screen yet. The existing separate ConversationHistory copies have not yet received the new retention/secret policy. Full app-wide privacy, source-badge/output invalidation, extraction, duplicate-source deletion suppression, encryption qualification, external adapters and real-device/background scheduling remain unverified or unimplemented; archive policy fixtures do not establish those capabilities.


Build 850 at `90a83a8e6f4a2c96936965dbb3e05e6bb724be5f` passed its signed build. Retained API 35 artifact `11077508561`, original ZIP SHA-256 `8d80a5914be00a5ee051c71b885fede00d1b91baa193b4faf797a5ef8d89ac17`, ran all 33 tests: migration/reopen and corrupt migration/failed erase tests passed; test24 failed on a stale node during consecutive search-to-article/detail taps, test27 rejected edge-touching Memory navigation bounds, and test32 failed on `SQLiteDatabaseLockedException` during `PRAGMA journal_mode` at database open. Inspected screenshots/XML show the approved correction article and an enabled Memory navigation item at the screen edge.

The follow-on archive candidate keeps all assertions and stable-tap thresholds: wait for the article boundary and use the existing stable tap helper for the two correction taps; align both bottom navigation bars with the content gutter. Canonical-path locks serialize opening, configuration, transactions and close across store instances in the app process, while SQLite remains the transaction boundary. This specifically addresses journal configuration happening before transactions. Cross-process writers are not qualified. The user explicitly requested continuing the next memory implementation while CI ran; this follow-on source-archive candidate gets a fresh bounded verification cycle rather than claiming the failed SQLite-only release passed. No failed APK is published.

Build 852 at `8cc837d272fe63ecf9ff5a4964fae07f73079034` passed all 719 release JVM tests (including six archive tests), native/helper checks and signed builds. Retained API 35 artifact `11078878440`, original ZIP SHA-256 `8e2c477add6831cc0bc871d96845b23b4c8f9b9bba977fa521c53f1709359300`, ran all 35 journeys: 34 passed, including test27 and all five SQLite/archive integration cases. Test24 reached its last pending/rejected-only erase case but remounting retained the nonblank `persistent amber tea` search. The inspected screenshot/XML shows the expected empty search state, which intentionally has no top History tab. The fixture now explicitly clears the search before its final remount, as it does for earlier tab transitions. Every assertion and safe-tap bound remains intact. This is archive-cycle repair attempt 1; a fresh complete signed gate is required.


Build 852 API 30 artifact `11079367702`, original ZIP SHA-256 `a1eb23979ac2f7439a792c0318dced162932a7888ab1b8ead742b33fbc73d3c4`, ran all 35 journeys. It shares the final search-state test24 failure and passes both new archive scenarios, but test32 times out at its unchanged 60-second future deadline. The earlier open-lock exception is gone. Validation currently recompiles the same identifier, metadata and restricted-content regexes for each field of each record; cache these immutable patterns once without changing their contents, options or 2,000-character bound. Use a fair reentrant lock per canonical database path to prevent queued writers from being overtaken, preserving opening/configuration/transaction/close serialization. These production efficiency changes accompany the explicit test24 state correction. All eight writers, generation/count assertions, 60-second deadline and 35 named journeys remain required. Emulator timeout clearance is not a physical-device latency qualification.


Build 856 at `5017f8728346a5e84912bc2e0ab49f151ca48a8f` passed all 719 JVM tests, native/helper checks, signed builds, and every API 30 journey plus separate-process restart. API 30 artifact `11080756839`, original ZIP SHA-256 `41764041731f51f70df9875be53b5c67dcd13d38b7b2edaa6e973f09f4d4a119`, confirms the unchanged large-payload/eight-writer 60-second check passed. API 35 artifact `11080382797`, original ZIP SHA-256 `d67548fde7f6621ff4a7b552c6d816aa781328bf98eb28cb95416db4fa2049d3`, ran all 35 journeys: only test24 failed, with `StaleObjectException` at the second Atlas tap (line 871). The retained screenshot/XML shows the correctly opened Atlas article and enabled fact card; it is the same rapid search-to-article/detail harness issue previously repaired for Indigo. All five SQLite/archive integration tests passed on both variants. The failed consolidated receipt blocked publication.

Archive-cycle repair attempt 2 applies the existing stable physical-tap helper to the remaining direct Wiki journey navigation taps, with an explicit article boundary between the two Atlas and Sapphire taps. Article navigation and tabs use the existing 16-dp content gutter so the unchanged safe-bound checks can exercise them. All assertions, two-sample settling, deadlines and 35 named journeys remain required; no mutation is retried and no API 35 failure is treated as a pass. Fresh full signed verification is required. The next privacy integration must cover ConversationHistory, saved VoiceCallStore transcripts/titles, and stored diagnostic prompts/evidence as well as the new archive.


### Conversation reliability repair (candidate)

The bounded post-memory-cutoff dialogue window now retains up to 128 entries. A separately budgeted capsule quotes recent user assertions and requests in both initial and repair prompts, including after compaction. It is conversation context, not approved memory. Memory mutation/erase boundaries still exclude the old transcript and clear summaries and capture receipts.

Finalized capture completes before typed generation. Explicit remember acknowledgments use the actual capture receipt and distinguish pending, approved, excluded, conflicted and failed capture. Pending facts remain excluded from approved packets. Natural category recall (for example, “what fruit do I like?”) stays local and retrieves approved preferences when needed. Capture outcomes and exact text prompt submissions are recorded in diagnostics.

Factual recipe requests retrieve evidence before generation. Short acronyms retain a declared user domain, negative corrections recheck the preceding factual question, and title/body relevance is required before a source is included. Full article passages can be selected beyond the introduction. Public user-supplied HTTPS HTML/PDF references are byte-bounded, redirect-checked and parsed as untrusted quotations. General live business/promotion discovery remains unavailable; a relevant result does not establish current hours or offer eligibility.

Text and speech share sentence-level repetition and conservative evasive-answer checks, including a single bounded read-only repair. These checks cover known wording patterns, not arbitrary semantic equivalence or all model hallucinations. Existing tools remain authorized only by the final current request.

New JVM regressions: TurnContinuityTest, AnswerQualityPolicyTest, ReferenceEvidencePolicyTest, MemoryCaptureAcknowledgmentTest, plus additions to TurnOrchestratorTest, ConversationPromptBuilderTest and ConversationHistoryTest. Existing memory/privacy/repetition/action cases remain required. New device `test35_referencePdfExtractionAndPendingAcknowledgmentUseReleaseCode` verifies PDFBox assets/text extraction through the signed shrunk app, malformed-document rejection, and the storage-backed pending acknowledgment. All 36 named journeys remain required on both variants.

Phone acceptance: start an empty thread, state a fruit preference, continue for 30 exchanges, ask several category-recall variants; confirm any pending proposal separately. Repeat the KNF/FPJ/WCA exchange, including a supplied official PDF and negative corrections. Repeat the coffee follow-ups at 11 p.m.; no business, offer or hours should be invented. Compare typed and spoken answers and verify diagnostics distinguish capture, evidence selection and repair outcomes. Erase memories and confirm pre-erasure context is not recalled. Actual Gemma inference, public network/PDF retrieval and acoustic delivery require phone testing; controlled JVM/emulator fixtures do not qualify them.

PStack public planning and pinned companion 0.10.0 are used with the available Astra High independent review route. Its requested Terra implementation route is unavailable in this host; root implementation is untracked and no fully coordinated/provider-authenticated receipt is claimed. No PR creation or merge is part of this repair.


The first independent review (6c3ba83) requested five concrete repairs: preserve typed formatting, resolve URL-only references using the preceding post-cutoff question, prefer substantive correction questions, stop domain inheritance at explicit topic changes, and allow uncertainty when a requested personal attribute is absent. The follow-up preserves exact typed separators/indentation and fenced code, adds orchestrator-to-supplied-PDF fixture coverage, bounds domain inheritance to the current topic span, and removes the overbroad personal-evidence rejection. Capture receipts now carry event/epoch provenance, are consumed once per conversation, and reject late insertion or consumption across approval/erase barriers. The memory delivery fence still governs publication after receipt selection. Root implementation remains untracked by PStack; independent review records findings without claiming provider-token billing data.

Build 860 (`dc222f62d9d24d6b11f784d16d2be8c2015439f9`, Actions run `36766948917`) failed release shrinking on PDFBox's absent optional `com.gemalto.jp2.JP2Decoder`; emulator and publication jobs did not run. The repair suppresses only that optional class warning, as documented by [PDFBox Android](https://github.com/TomRoush/PdfBox-Android#reading-jpx-images). Reference extraction reads embedded text, with no image rendering or OCR. All JVM tests and 36 named journeys remain required; the new signed-release PDF extraction scenario is unchanged. A fresh exact-head full gate is required.


Build 872 (run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36826577446, PR head `61764d2deb1323418c575c6c398c6a61d20ec5e9`, tested merge `82ecb6a9a246d4995acf92cc5c97490efb42dc10`) passed 894 JVM tests, signed normal/compact builds and all 45 existing journeys on both emulator variants. The new benchmark journey failed before its first sample tap: UiObject2.scroll produced no accessibility event, returned false, and the helper prematurely treated two unsuccessful gestures as both list boundaries. Both screenshots remained at the dashboard summary. The failed receipt blocked publication. Justin explicitly requested fixing this blocker and delivering a test APK on October 1, renewing the bounded repair cycle. The benchmark-only helper now uses physical swipes, fresh visible-content signatures, and two observed stationary gestures before one reversal. All 15-second/14-gesture limits, viewport/stability checks, single-tap behavior and scoring/export/persistence assertions remain required. Exact new hosted release checks must pass; no noisy-phone accuracy improvement is claimed. The local command service disconnected during this repair, so source changes use the connected GitHub API and validation uses the existing hosted pipeline.


Build 873 (run https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/36835285342, PR head `08e054e02318e99441ad18d65d5d48fc9226d296`, tested merge `78725d4dc3b5afcc8e729bc96a8a9e8468db3f3a`) passed 894 JVM tests, native/helper/signing checks and both APK builds. API 30 passed all 46 journeys and restart; artifact `11150292570`, ZIP SHA-256 `8ab8e0ddb6e2bea6e708384431f0d3c52e3bc35eed9f15a5b85deb5b60290095`. API 35 attempt 1 was covered from its first test by a Pixel Launcher ANR; screenshot/hierarchy show the launcher dialog over the correctly rendered Jarvis setup. The permitted same-revision infrastructure retry passed test45 and 43 other journeys, but exposed two older test-driver races: test24 immediately called the scroll-capable confirmation helper before its reopened erase modal appeared, then those gestures dismissed it; test42 checked the effect counter less than 5 ms after a busy-device tap. Retained attempt-2 artifact `11151495770`, ZIP SHA-256 `3d9110e68b395f54bb7864091aad3c729a7996e6296f1abdaf1571ea463f647c`. All reports/raw instrumentation and relevant screenshots/logcat were read. Candidate 2 in the renewed cycle adds explicit modal closure/arrival boundaries to test24 and existing stable single taps plus completion-node disappearance to test42, with unchanged 15-second limits and all effect-count, persisted-state and no-retry assertions. No production behavior or acceptance criteria are removed. A fresh full signed gate is required; the requested APK handoff remains pending. CI waits dominate elapsed time; the specific newly evidenced harness correction continues within the authorized phone-test task.

## Conversation metrics access and token totals — October 2, 2026

| Acceptance | Evidence boundary | Failure case / preserved behavior |
|---|---|---|
| Copy metrics exports every retained attempt for the chosen conversation and redacted saved reply totals | JVM export tests; existing release test46 extended | Another thread leaks, absent timing becomes zero, transcript is copied |
| Metrics opens export viewer after a voice call ends; reply footer still works | Release test46 | Hidden diagnostics-only entry or stopped call clears access |
| Visible response token totals persist through text replacement and call receipts | JVM history/controller and codec tests | Old total survives a replacement or estimate is called exact |
| Large copy reports stay complete | UI size guard; save/share existing journey | Silent clipboard truncation |
| Screenshot baseline is attributable | Original files, hashes and seven-row CSV | Duplicate rows or claimed build/model provenance not shown |

Exact-revision signed JVM/native/build and both emulator gates are required before claiming a verified APK. Phone performance and acoustic onset remain outside emulator coverage.

Pre-existing build 891 (run 37084878313, head `03a6147494a3685a5e309ae34de5d8e7e1c493c8`) failed Kotlin compilation at JarvisRuntime line 601: inferred `runVoiceTurn`/lazy `voiceTurns` types formed a recursive cycle through the restart callback. The metrics change inherited this source. Explicit `VoiceTurnRunner` property and `Unit` function types break inference without changing runtime behavior. Failed logs remain in Actions; a fresh full gate is required.

Build 893 (run 37085585128, head `60d8d6d48e28c5a131b8c1acd290ccea99b27e07`) compiled and ran 998 JVM tests. Only `AcceptedReportPlaybackTest.releaseFailurePropagatesAfterJoiningButCannotSkipDetachOrRepeatCleanup` failed: XML records `expected same IllegalStateException` versus a same-message coroutine stacktrace-recovered exception at line 102. This is a harness identity assumption, not a failed cleanup assertion. The test now requires matching message and the exact original failure anywhere in the cause chain; writer-joined, release-once, detach and repeated-close assertions are unchanged. Native and helper checks passed; APK/Android publication was blocked. Retained unit-test artifact 11260279745, SHA-256 `49e9a2e33506c7605b4752b56b2b70c84a352cf799d138c9a7d6e08e607f4a89`. A fresh full gate remains required.

Build 896 (run 37086240616, head `e977b5726e6400f8e39fbc6cd73128257435fe63`, tested merge `40427fdf763965329f938c1b2eb8f8eeb4c94599`) passed 998 JVM tests, native/helper checks and both signed APK builds. API 35 passed all 48 journeys; API 30 passed 47, including the extended metrics export journey, but test24 failed its category/topic persistence assertion. Retained API 30 artifact 11259839816, ZIP SHA-256 `4d26838008a7eb983ea8e81ac641a6d237a42d172414afd1ac3b6262fc18ae88`. The organize-dialog screenshot/hierarchy shows Projects still checked and Knowledge unchecked after the helper returned. Knowledge bounds `[750,776][960,902]` extend beyond the horizontal row `[183,776][897,902]`; the helper checked only screen bounds and returned after dispatch, without confirming selection. The third bounded repair reveals the entire category chip within its row and requires its checked state before returning. No product changes or persistence assertions are removed. Publication remains blocked until a fresh full gate passes.

Review of that third harness repair found that `BySelector.checked(true)` mutates its receiver. The selected-state query uses `By.copy(target).checked(true)` so a timed-out tap does not change the selector needed to find an unchecked chip on the next attempt. The eight-attempt limit, checked-state requirement and every persistence assertion remain intact. This is a refinement of the same category-selection repair; the final combined revision still requires its full gate.

### October 6: Muse integration, shared-journal compatibility and live Wisp text

Candidate combines audio `483345b5d6e6e55a8b79cf8ae7d7b9639c3a6592` with Scout's
stable Muse `9f965badaeab20dcd5973ddb7d55d5846f64ca3c`. It retains Android 11/API 30,
the five required profiles including actual API 36 16 KB, and every existing
900-second main-suite limit. No main-branch merge or new PR is part of this work.

Acceptance additions:

- Shared journal (JVM): six fixtures serialized by original schema-1, legacy
  schema-2, Muse schema-2 and build-1067 schema-3 writers migrate through the
  integrated codec, then are read by the immutable build-1067 reader. Workflow
  definitions, occurrence resume time/path, completed steps/results, source
  denial/revocation, approvals, grants, events and receipts remain intact.
  `ToolTaskVersionCompatibilityTest` also fences interrupted native effects,
  missing/coerced authority fields, unknown schemas/fields, write failure and
  both standalone/grouped phone source permission paths. Unknown execution
  semantics fail closed without resetting or rewriting the journal.
- Journal status (Robolectric/JVM): `PhoneTaskCoordinatorTest` separates load
  errors from failed cancellation/admission/completion writes. A successful
  reload clears only the former; completion-write failure says the effect ran
  and was not confirmed, rather than claiming it never started.
- Workflow ownership (JVM): `DurableTaskRecoveryTest` fences cold-alarm entry
  until both synchronous startup recovery passes finish, never repeats recovery
  over live owners, and leaves a failed recovery closed. Workflow-linked phone
  groups are excluded from ordinary chat resume/cancel/projection. Interrupted
  running attempts remain unknown; queued workflow steps are paused, not replayed.
- Screen approval (pure JVM and Robolectric): the final bridge mutation binds
  the expected content generation to the same root and retained target used for
  dispatch. Full raw text/description and every traversed node are hashed;
  prompt labels remain short. More than 200 nodes, depth 25, 65,536 hashed UTF-16
  units, missing children or traversal failures yield no dispatchable generation.
  Tests swap same-window content after the first check and retain target identity
  for tap/type/scroll; unchanged positive controls still work. A mutant removing
  only the final-generation comparison fails three regressions. Android
  accessibility has no atomic cross-app transaction; this closes the observed
  extra-read gap without claiming atomicity against arbitrary platform races.
- Locked reminders (JVM and Robolectric): only a due, live RUNNING occurrence
  pinning an enabled definition may post its already-authorized private
  notification while locked. Source and OS notification permissions still gate
  it, other locked phone actions remain blocked, and notification/channel use
  VISIBILITY_SECRET with no DND bypass. `LockedReminderDeliveryTest` and
  `ReminderNotificationPrivacyTest` cover this exception. Acoustic/DND sound
  behavior remains a physical-device coverage gap.
- Wisp transparency (JVM/Compose/Android test49): operation-bound public text
  follows real model/reference/tool activity, accepts safe producer-authored
  progress without reading answer or reasoning streams, and ignores stale or
  superseded updates. Idle has no status text. Routine activity uses read-only
  durable-boundary observation; unrelated conversation data stays isolated.

Local focused tests and independent source review are evidence for these
boundaries, not the signed release gate. The exact merged revision must still
pass release compilation/JVM/native checks, all 76 named Android journeys,
upgrade/process/platform/layout phases on all five profiles, and the consolidated
receipt before its APK is called verified. Physical audio, actual model tool
selection, OEM accessibility and device performance remain unverified.

Build 1092 (`f295e666`, tested merge `72ec83b1`) passed 1,377 release JVM tests
across 207 suites, signed normal/compact builds and native/recorded-speech checks,
but failed the actual 1086→1092 upgrade on all five profiles before main journeys.
The preserved prior-APK seed is a terminal-only schema-2 receipt with all later
optional authority fields absent. The new strict reader incorrectly rejected
that existing supported legacy shape. Repair 1 permits only completely unannotated
terminal receipts in schema 2 with no groups, approvals, grants, events or active
question. Schema 3, active work and partially missing authority remain strict.
The upgrade harness and every assertion remain unchanged. Two focused regressions
pin read/no-replay/migration and unsafe look-alike rejection; fresh full CI is
required. Failed evidence includes API30 artifact `11400344618` (ZIP SHA-256
`7ac6b5d276c4924561ef726781d854caac4e9fa69beddbdc5fd53bd08bcfb16b`), Fold artifact
`11400274972` (`a7f82fca0ca5db2d7a39a7ae0c7ab4de3dbb05ee1a0fe9e892e488284fccbddc`),
and actual 16KB artifact `11401335891`
(`4663ae1d3b22de1195d04021a6d602f630e2ebcf89b21caabdeec0afe6de3355`). No APK from
this failed candidate is called verified or used as the new upgrade baseline.

Build 1096 (`4672942c`, tested merge `df5519d7`) confirms the journal repair on
all five actual 1086→1096 upgrade paths. It passed 1,379 JVM tests in 207 suites,
both signed builds, native and recorded-speech checks. The Fold profile passed
all 76 main journeys (793.5 seconds), real fold/unfold, layout, lifecycle and
platform phases. API35 compact and API36 actual16KB passed all 76 main journeys,
including the new Wisp text and Muse cases; their landscape layout check exposed
a clipped pause control. API30 and API36 phone passed 75/76 main journeys, with
only the existing benchmark navigation test failing. Publication was skipped.

Repair 2 keeps the failures separate:

- Harness discovery defect: the unchanged benchmark helper/test block (same
  SHA-256 `c849ea31d6321f30dbe0b4a82674f1a11378d7e0861f67e9f6c2ffcd65c7b93c` as
  baseline 1086) made blind long swipes that skipped controls just below the
  viewport. API30 skipped Intent PASS after a low-positioned Task FAIL control;
  API36 skipped the disabled reference control after restoring the sample.
  Only those two lookups opt into the existing overlapping held discovery.
  No benchmark product code, safe-bound/stable-enabled check, exactly-once tap,
  disabled-reference assertion, 15-second/14-gesture limit or main-suite timeout
  changes. API35's passing trace starts the latter search just 16 pixels higher,
  explaining why the same large swipe happened to reveal it there.
- Product layout defect: after real rotation the call-overlay slot was 424 px
  (161.5 dp). Existing 104 dp composer clearance left 57.5 dp for a vertically
  stacked status plus End and Pause actions. Pixels and XML show missing Pause
  text and a clipped End target; refreshing accessibility could not fix the
  missing space. The production overlay now measures its unchanged controls:
  it keeps the 112 dp vertical pill when that fits and uses a single horizontal
  row in short, sufficiently wide slots. Names, callbacks, live-session End
  behavior, Wisp placement and composer clearance remain unchanged. A focused
  Compose regression covers the measured landscape slot and portrait return;
  all original device accessibility/layout assertions remain required.
  Six real Compose/Robolectric native-graphics cases pass, covering the measured
  compact slot, speaking at normal and 2× font, live-session End, paused-state
  rotation/portrait return and narrow large-font portrait. They require complete
  label glyphs without ellipsis, nonoverlapping targets of at least 48 dp and
  the original 104 dp composer clearance. The original fixed-column overlay
  fails five of those six unchanged tests. The focused run also exposed and
  corrected Stop reply's 40 dp visual target with an explicit 48 dp minimum.
  The SDK-free architecture and all 177 helper checks pass. Full signed release
  and device verification remain required for the exact repaired head.

Retained 1096 evidence: API30 artifact `11402048093`, ZIP SHA-256
`b7eed353e641b12a01434f241055672c8fbaec325a19683f9b7d9c6cae061344`;
API36 `11402203278`, `4176e2b3ff614874b32f445204adcd2364e68f404d23ddb840673e3cbf52b991`;
API35 `11402133509`, `6d0592084ab4f730f83999859f7295331da04e4583724f40d448bb0249001e95`;
16KB `11402547679`, `c7f4b64aee1428f23f921e731156c1b4231e4c3aa1f2f99c551106aa1da4fa1a`;
Fold `11402868152`, `147ced55a96eb2dd177649bbdc5b8a2f015c1de98da6fdf1bd436655688b4b41`;
failed receipt `11402678147`,
`e284970559c96076538719860e2a43857d37fbae98df28444175d380c3800be5`.
Phone and Fold Wisp public-progress/idle PNGs were visually inspected: the activity
line fits below Wisp while busy and is absent while idle. These are controlled
progress fixtures and remain candidate evidence, not a fully verified release.
The next repaired head must pass the complete unchanged five-profile gate.

### Shared audio/Muse head reconciliation (October 6)

The follow-on integration includes the five Muse commits through
`ca2550d733c69813a39fe4643335778cd95de711`, descending from the previously merged
`9f965bad`. Both histories are retained by a real two-parent merge. The only
textual conflict was in the app composition: retain the single conversation/Wisp
frame and activity observer while adapting the simplified settings signatures.
The audio branch's five required profiles, Android 11 minimum, actual 16 KB test,
76 named journeys, 900-second main deadline and all action-safety fixes remain.

Reconciliation preserves existing user choices rather than silently adopting
newer settings defaults:

- Keep the opt-in keyboard microphone handoff service registered, matching its
  still-visible setup control. This does not enable accessibility automatically.
- Keep both persisted microphone processing profiles and recorder-identity
  behavior. The new caption dropdown offers Off, Moonshine and Whisper. Existing
  Whisper/on and Off choices migrate; an Off selection made by an older APK is
  respected when returning to this version. Selecting an engine synchronizes the
  legacy Boolean. Off continues to avoid caption-recognizer loading; captions
  remain display-only and never authorize phone actions.
- Audio evidence remains opt-in, bounded in memory, and explicitly clearable.
  Exports identify truncation and fixed limits instead of claiming a whole-call
  recording. The existing per-stream/total/event limits are not expanded.
- Compact model details distinguish observed first-token latency from estimated
  decode throughput, show metric-specific sample counts and update from the
  observed sample flow. Compatibility warnings and optional publisher/resource
  evidence remain available; opening or dismissing details does not select a
  different model.

The real Android caption journey keeps its established test/evidence names and
now operates every dropdown choice through UI before rebuilding the screen.
Upgrade verification continues to require the previous APK's persisted Off
choice. Focused JVM and Compose regressions cover migration, retained controls,
bounded recording, empty/populated/live model samples and dismissal behavior.
Their exact results and the full signed/device gate must be verified before this
combined head is considered released.

Build 1098 attempt 1 retained two additional failures: API35's stock Pixel
Launcher ANR modal blocked all main-test setup; Fold passed its actual locked
battery/sensitive-action checks but sampled keyguard state immediately after
asynchronous PIN cleanup. Logs show successful credential removal, keyguard exit
and subsequent unlocked state. The unchanged retry retains that failed evidence;
no passing status is inferred from the diagnosis. This follow-on candidate also
waits for the same unlocked condition using the existing fixed cleanup deadline,
then keeps the original assertion. It repeats no PIN submission or action and
does not extend the main-suite budget. API35 attempt-1 artifact `11405113553`, ZIP
SHA-256 `93e6d6f9865e1ae7562d2a473e875c7458481ecad70f0e20af474b0b269d02e8`;
Fold `11406388122`,
`c0ceaf954e7f2dad61d0248a53c32c6af60d091ea13b26d44ed3ad0c6f43b7c5`;
receipt `11406952952`,
`d31d6f11ab6fd944feed52ceb23c34cb3da758db37c73437826b296dcec52be5`.

Build 1098 is now the verified numbered release baseline:
[release](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.1098),
[run](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37447496353).
Head `ee2128c0dc620bd8429722364b78df2ec0616936`, tested merge
`f4f373aeba492e91726c41769c21c46d0a7939e2`. Attempt 2 finished successfully with
1,385 JVM tests in 208 suites, both signed variants, all five profiles' 76 main
journeys, 1086→1098 upgrades, lifecycle, layout/fold and native-loading phases.
The receipt explicitly selects the passing retry artifacts for API35/Fold and
the three original passing profiles; it does not substitute failed reports.
Receipt artifact `11408046743`, ZIP SHA-256
`1bd2b73a2670d0bc1a00786844d986170dc9f754f47734602f8c5dfb131fbdac`.
Published normal APK SHA-256
`befb872a14ec57ec9e527bf67fd8e711259d64db3d844d0e31c20341de403b06`;
compact `104447d9e2b095b83819a798d2c34d7867a919833ef3bb36355c5f1c2b5b3f05`.
Both asset digests match the same-run receipt. Phone/Fold Wisp progress and idle,
and API35/actual16KB rotated controls, were visually inspected. Physical audio,
real-model behavior and device-performance signoff remain separate. The newer
shared-head settings reconciliation above is not covered by this earlier gate.

Focused pre-publication evidence for the settings reconciliation: 29
settings/export/profile/policy checks (including seven production Compose UI
cases) and 54 existing AudioTurnCapture/RoutedAudioInput regressions pass;
13 model-details checks (eight metrics and five production Compose UI cases)
pass. Tests exercise real retained ZIP entries/bytes, no-recognizer direct audio,
recorder replacement on profile changes, and live model-detail sample updates.
Standalone Compose runs required test-only official AAR string resources; full
packaged themes/resources and release shrinking still require hosted acceptance.
These local results do not replace the next exact-head release gate.


### Final tool-evaluation history reconciliation

The final synchronization candidate also retains Muse commit
`15f3dd6ca493de80b8aa3d66d8afbfe62f8f4003`, a direct descendant of `ca2550d7`.
Its four added files provide 32 canonical fixtures covering all 14 native catalog
tools, an exact strict-decoder scorer, a runner interface/fake, and eight JVM
regressions. The commit message's fixture count was 33; the inspected source
contains 32. No production runtime, UI, approval, executor or release-gate entry
point uses this package yet. A passing score means fixture agreement after strict
decoding, not source permission, current screen approval, successful device
execution or demonstrated real-model accuracy. The runner comment now states that
boundary accurately instead of assigning gating/dispatch to the scorer.

All eight new tests pass against the current production decoder/catalog. The
focused compile uses the exact extracted ActionRequest data carrier and full
LocalModelEngine/decoder/catalog source, with no model weights or Android effect
adapter. Real on-device model execution remains a later runner implementation;
these fake-backed tests must not be reported as measured model reliability.
Some fixtures are not ordinary-chat journeys: notification posting is scheduled-
only in the catalog, fixed reminder times can be past, and equivalent URLs or
tool choices intentionally fail exact comparison. A future real-model assessment
must account for those contexts rather than treating every mismatch as a model
failure. The common merged head still requires its exact signed release and
five-profile gate.

### Build 1100 retained failures and test-only repairs

Build 1100 (`a49562b8`, tested merge `2d323740`) passed both signed builds and
1,413 JVM tests in 213 suites, but did not pass the device gate or publish.
Its real previous baseline was released Build 1098. API35 failed before candidate
installation because startup memory initialization raced the seed's assumption
that an empty disposable install has no database file. The observed maintenance
worker and startup timing explain the race; a specific boot-receiver delivery was
not logged. The seed now opens one SQLite write transaction and accepts only a
pristine schema-0 database or the exact known empty schema-2 contract. It rejects
unknown versions/objects/columns/indexes, nonpristine metadata and any retained
memory/tombstone/source data. The identical fixture is inserted atomically without
deleting or resetting a database. All 29 embedded temporary-database admission,
preservation and concurrency checks pass using API35 native SQLite; the existing
required seed journey also executes them. Phone-journal fixture bytes and
candidate preservation assertions remain unchanged.

The other profiles completed the real 1098 upgrade. Test47's popup was visibly
open with Off, Moonshine and Whisper base.en, but its separate Compose window did
not export child test tags as Android resource IDs. The journey now selects the
exact visible next label, which differs from the anchor value and the prefixed
recognizer buttons; actual taps, persisted choices, UI reopening and timeouts
remain required. No production dropdown behavior changes.

API30 also passed the old test64 credential predicate while an unlocked-padlock
PIN bouncer still covered the app, causing later setup failures. Fold lost its
active UI after the same phase; its black capture does not establish an identical
visual cause. Cleanup now clears the known fixture PIN once, observes that the device is no longer secure, issues the existing
dismissal once, and then observes unlocked state, the keyguard UI gone and the
owned setup screen accessible. The original locked-action
assertions remain, with stronger cleanup assertions. No keypad retry or action
replay is introduced; all polling shares one 10-second cleanup deadline and
the 900-second main-suite cap is unchanged. Framework calls retain their own
platform timeouts. Exact hosted verification is still
required for these framework/UI synchronization changes.

Retained artifacts: API35 `11408554842`, ZIP SHA-256
`87f002cd8cc371102dbdb082c51aa99bef337e66dad2e691c42864f59eb10083`;
API36 phone `11410646085`,
`9ea2e7f2bc6b32348068b5ac83a8f2e90b4630e509cab4c46429c8d14eaf3225`;
actual16KB `11410841088`,
`e6e5f49d6b4261085e7d2dc13aee7c4a603f9a27c239da0f80b48ccddf256598`;
API30 `11411051165`,
`91c67b536ccbd73a8800bfaa81c9279084e4bd9a5fa0a33d1398878d14af51e8`;
Fold `11410457443`,
`e56de1d0c17ab35cd86d0d2fcdfd303ceb3053fbde6930ca13e50b04657a6b9e`;
failed receipt `11410002487`,
`7607614dde4b5295fab83973b3e73ba39c2a904277d3c783655c02a1eb796efc`.

### Build 1102 release-instrumentation ABI and dismissal follow-up

Build 1102 (`3708cd6a`, tested merge `fa9a6662`) passed 1,421 JVM tests in 214
suites, both signed builds/audits and all five real 1098→1102 upgrade phases. This
confirms the transactional seed repair on the required profiles. Publication was
skipped because the main gate still failed.

Test47 reached the repaired popup but invoked `AsrEngine.getLabel()` across the
independently optimized app/test APK boundary. The optimized application did not
expose that callable getter at the expected ABI, causing `NoSuchMethodError`.
The journey now pairs explicit expected UI labels (Moonshine, Whisper base.en,
Off) with the same enum values. It still clicks each visible option, checks the
persisted choice and reopens the screen. No production keep rule, optimizer
setting, debug APK substitution or assertion removal is used.

API30 and Fold also demonstrate that the single shell dismissal did not reliably
remove keyguard. The new readiness assertions correctly fail instead of reporting
cleanup success while later journeys are obscured. The next candidate uses one
[activity-owned keyguard dismissal request](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,android.app.KeyguardManager.KeyguardDismissCallback))
after the known fixture PIN is cleared. It obtains the current Activity only
inside [ActivityScenario.onActivity](https://developer.android.com/reference/androidx/test/core/app/ActivityScenario#onActivity(androidx.test.core.app.ActivityScenario.ActivityAction)):
no retained Activity reference, forced RESUMED state, show-when-locked override or
second dismissal request. Callback success/error/cancellation is diagnostic;
observed nonsecure/unlocked state, hidden keyguard and visible owned setup remain
the final authority. The same polling deadline and all original action assertions
remain. This framework change needs fresh exact-head API30/Fold/device evidence;
a source review is not proof that dismissal succeeded.

Retained failed-run artifacts: API35 `11414466366`, ZIP SHA-256
`57dec9bbaf5af5b8682999070d77ae4f886459227de6aaf30d8be8a6910178db`;
API30 `11414367268`,
`f3662b271404b0b826636b80cd970bbb5505cfdf72b56ab1f5a5d2fc12d4d14e`;
API36 phone `11414061627`,
`d7272a6da03b66b250da2e0dd55524333d83ed8603fa529ccb552a0799f72c0a`;
Fold `11413673303`,
`34a6fe76aec4defdab73fd47d94c94da4ddb756016e454ba8bcf8e872be0a60c`;
actual16KB `11413187238`,
`ebf76bddc218aa4c676dbce7f7c6969983a7f37d902f61ec0a5f7ec3a311c908`;
receipt `11414388237`,
`16af1d7b38d55a791dd3bcba324c656836bdb0e2d162b59f058cfa0e19fee8a7`.

### Build 1103 API30 authenticated fixture cleanup

Build 1103 (`63cb1a22`, tested merge `16d26e31`) passed 1,421 JVM tests, both
signed builds/audits, all five upgrade phases, and the complete API35, API36,
Fold and actual 16KB profiles. The explicit caption-label repair now passes.
API30 alone still fails test64 cleanup: Android accepts the Activity-owned
dismissal request, but its callback remains pending and the retained PNG/XML
show a focused PIN keypad beneath an unlocked padlock after credential removal.
The final observed-keyguard assertion correctly blocks publication.

The next instrumentation-only correction authenticates the known disposable PIN
before clearing it. It requests the credential UI once, positively observes the
enabled focused SystemUI PIN field and expected keypad controls through the
framework accessibility snapshot, and submits one hardware-key sequence. There
are no per-digit idle waits, coordinate guesses or repeated PIN submissions.
Both actual lock states must become unlocked before the credential is cleared;
a separate assertion records that ordering. The fixture PIN is still removed
once on failure, without treating removal as successful authentication.

This ordering follows Android 11's
[PIN input handling](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r1/packages/SystemUI/src/com/android/keyguard/KeyguardPinBasedInputView.java)
and [security-screen transition](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r1/packages/SystemUI/src/com/android/keyguard/KeyguardSecurityContainer.java):
a showing PIN screen needs the authenticated transition; changing the stored
credential alone does not establish that transition. The existing ten-second
polling deadline, all real locked-action assertions, final nonsecure/unlocked/UI
readiness assertions and 900-second suite cap remain. Framework calls retain
their own platform timeouts. Exact-head Android verification is still required.

Failed API30 artifact `11416466950` has ZIP SHA-256
`7756f9179a3f746aae73c9622f95e694315d89ef2210d1ac6c4ff9734dd764fb`;
failed receipt `11417500689` has ZIP SHA-256
`70b4921e5a7159be44a5a2f4809b1874e1bdd11e611572d39fc4309df15ee9a9`.


### 2026-10-07: real-call startup asset and visible-error repair (pending hosted verification)

A physical-phone report on Build 1161 identified a startup `FileNotFoundException`
for `gemma_streaming/structural-literals.bin.gz`, before any Gemma submission. Both
retained signed APKs confirm that packaging instead installed the decompressed
`structural-literals.bin` (1,068,632 bytes). The recipe file and notices were
present. All five existing emulator profiles had passed because native loading
did not exercise the actual encoder asset reader; those earlier results do not
establish successful real-model call startup. Build 1168 changes only diagnostic
infrastructure and retains this app defect.

The repair names the unchanged 134,002-byte gzip payload
`structural-literals.bin.gzip`, keeping its pinned compressed SHA-256 and the
reconstructed encoder hash unchanged. Both signed APKs must now pass an exact
asset inventory, byte/hash, bounded gzip-decode and notice check before upload;
the final binder independently reopens them and binds that receipt to their
exact identities. The native-loading device journey also calls the production
asset reader through the installed application's `AssetManager`, including the
bounded gzip decode. It does not download weights or claim model inference.

A terminal startup error is retained for its conversation after the voice
overlay closes, with explicit dismissal or clearing on retry. Actual cancellation
and stale call/turn callbacks do not publish errors or stop a replacement call.
Post-wake call identity is registered under the controller monitor before
checkpoint/diagnostic/microphone failures can escape; a pre-existing replacement
is never adopted. Resource release remains with the existing finalizer.

Current local evidence: 6 real-source asset-reader JVM checks, 5 voice UI state
checks on the release Kotlin 2.3.21/coroutines 1.9.0 versions, and 85 SDK/packaging
helper checks pass. The new gate rejects both actual Build 1161 APKs. Ten
production-runner and two additional screen regressions are source-ready; their
full release compilation/execution, the repaired signed APKs, installed-asset
journey, and physical-model call startup remain pending. Failed preparatory
compiler launches (missing `javac` executable and unavailable `--release` symbol
archive) are retained; the successful check uses the installed JDK compiler
module with Java 17 source/target and does not replace Android API compilation.
The real artifact-store recovery check also passed against the existing pinned
2,588,147,712-byte official bundle: the old APK's missing-asset failure left its
partial cache, then the corrected assets reconstructed exactly 103,668,112 bytes
with SHA-256 `d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b`.
The valid cache was reused without reopening assets. This file/hash-only check
used a separate reviewed 256 MiB Java heap / 512 MiB whole-tree RSS / 2 GiB
admission profile, with one CPU, 90 seconds, 1 GiB reserve and checked cleanup.
No model inference, Android execution or physical call-start result is implied.
The strict hosted numerical oracle and all existing release gates remain intact.

### Reminder fixture clock repair (October 7, 2026)

Build 1180 ([run 37643215975](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37643215975))
failed `ReminderPlanTest.ledgerWriteSuccessProducesReceiptAndListsTheReminder`
after the fixture's October 7, 16:00 reminder passed the ledger's real clock.
This is a test-clock defect: the coordinator used fixed October 6, 10:00 while
three test ledgers used the wall clock. All now share the fixed fixture clock;
the requested date and every success, failure, scheduling and listing check remain.
Unmodified production ledger code reproduced one occurrence just before the
deadline and none at/after it with explicit clocks. The shared clock scheduled,
listed and allowed exactly one due claim. That probe and the existing M2 workflow,
task-ledger, persistence and approval suites passed 83 source-only JVM checks with
Kotlin 2.3.21 and retained real dependencies, without stubs. The full repaired
`ReminderPlanTest` and exact-revision release/Android gates still require hosted
execution; these local checks do not verify an APK or actual Android alarms.


### 2026-10-08: fixed microphone processing and in-place profile migration

`SpeechCaptureProfile` now exposes only Call noise reduction. Fresh preferences,
legacy `speech_preserving`, empty/unknown IDs and malformed stored types resolve
to `communication_noise_filtered`; reads normalize only `voice_input.capture_profile`.
Existing Call noise reduction selections are retained and repeated reads do not
rewrite a canonical value. This is an in-place setting migration, without clearing
voice preferences, conversations, memory, model selections or downloaded files.
Settings shows the fixed profile and no microphone-profile selection buttons.
This supersedes the historical two-profile settings descriptions above.

The profile requests communication input and noise suppression. Existing source
eligibility, duplex speaker/focus routing, recorder ownership and AEC requests are
unchanged: in particular, the runtime's API 30/non-duplex path retains its existing
VOICE_RECOGNITION fallback. Actual device effect availability remains diagnostic.
The retained profile combines source and effects, so this change does not isolate
noise suppression or input source as a cause or establish universal acoustic
quality across phones. VAD, speech thresholds,
endpoint timing and weak-onset policy are unchanged.

Acceptance: `SpeechCaptureProfileTest` covers default, legacy, current, unknown and
repeat resolution. `SpeechCaptureProfilePreferencesTest` covers actual Android
preferences, malformed types, repeated reads/context reopening, and preservation
of other preference values and retained fixture files. `VoiceInputSettingsTest`
checks fresh/legacy settings, absent selector buttons and repeated screen reopening
while retaining caption choices. Context reopening is not process-death evidence.
The existing prior-APK replacement-install fixture now seeds `speech_preserving`;
the candidate verifies canonical profile selection/persistence before all unchanged
conversation/model/memory/journal assertions. A narrow release keep rule preserves
that app/test ABI. The existing externally killed lifecycle seed flushes the
normalized setting before termination; recovery checks the persisted ID before
app relaunch, then repeats selection. These Android cases are source-ready and
still require the hosted run; physical microphone behavior remains separate. All exact-revision release JVM/native,
normal/compact APK packaging, and five required Android profiles remain required;
focused JVM checks alone do not verify an APK or physical voice capture.

### 2026-10-08: retained SDK consumer receipt layout

[Build 1186 attempt 2](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37741623563/attempts/2)
retained passing evidence for all 1,535 JVM tests, five Android profiles and the
host-quality prerequisite, but the final SDK receipt binder failed. The consumer
ZIP keeps `streaming-sdk-manifest.json` at its root and the exact producer's
provenance and `source-receipt.json` together under `streaming-sdk-input/`.
The old binder found the nested provenance but assumed a root-level receipt.
The historical failed release receipt remains failed; this is a harness-layout
repair, not a new Android/model pass or approval of those APKs.

The binder now accepts exactly that retained layout or the existing flat layout,
requires one adjacent provenance/receipt pair, and rejects missing, duplicate,
mixed or unrelated paths, file/ancestor symlinks and traversal. Exact producer
digests, semantic source equality, reviewed source/patch, run/source/repository
identity, both APK checks and strict quality/oracle gates remain unchanged.
`test_release_binding.py` covers both ZIP layouts through the complete binder,
byte-preserving retention, tampering/foreign identities and failed CLI receipts.
The exact CI-layout regression fails against the prior binder; all 113 Python
SDK helper tests pass with the repair. No workflow upload layout, app or native
source changes are needed. Fresh exact-revision hosted release verification and
final binding remain required; physical audio/device coverage stays separate.


## Whole-call portable benchmark export (October 9, 2026)

Requested acceptance: share every retained call/conversation attempt for pipeline
comparison, rather than one reply or an oversized full-prompt diagnostic paste.
`Copy report` freezes a metrics-only text snapshot and offers numbered parts of
at most 8,192 UTF-8 bytes including report identity/part headers. Concatenating
part bodies recreates the exact text, with no silent truncation. `Save TXT` uses
SAF CreateDocument with text/plain and a .txt filename; `Share TXT` supplies a
FileProvider content URI, ClipData and read grant. Existing full JSON/CSV exports
retain their schemas and data. Full exact-prompt diagnostics remain separate.

The text report includes every retained attempt (beyond the UI's latest 50),
per-attempt outcomes, explicit shared availability catalogs (unlisted raw keys are
unavailable), stage offsets, deduplicated provenance/model/config
references, and submission purposes/outcomes. Complete-turn median/p95/p99 and
n/missing counts are computed only within existing comparable groups; failed or
cancelled observations stay in raw rows and outcome counts, never zero-filled.
Submission groups also separate model/purpose/warm-state/outcome. Small-sample
tails are labelled exploratory. Defined speech-end/recognition/submission endpoints,
filler exclusion and playback-head versus acoustic evidence stay explicit.

`PipelineBenchmarkTextExportTest` covers 160 turns, frozen collection isolation,
correlation-preserving provenance deduplication, empty/missing/nonfinite data,
build/model/config group separation, cancelled/error attempts, privacy, token
estimates, non-ASCII/surrogate boundaries, exact numbered-part reconstruction and
unchanged JSON/CSV. `BenchmarkExportStateTest` covers the synchronous rapid-repeat
gate and preserving a currently open clipboard report. Existing Android release journey test45 is extended, without
removing any assertions, to cover copying adjacent parts, picker cancellation,
repeated TXT saves/shares and full-scope text preservation across pending-picker
scroll/recomposition/cancellation. Controlled screen replacement verifies that an
old picker URI never receives a newer report and exporting can restart. A controlled registry
returns picker outcomes while the production CreateDocument contract and real
ContentResolver/FileProvider are used. Transport checks verify replacement writes,
Unicode, MIME/filename, content URI/read grants and six still-readable successive
shares. Unique files replace the old four-file eviction rule; shares older than
24 hours may be cleaned up on subsequent export. No permissions/dependencies were
added. Exact crossing APIs are retained for the independent release-test DEX.

The export owner, coroutine scope, launchers and clipboard dialog live above the
LazyColumn. Scrolling cannot dispose pending payloads. Configuration recreation
intentionally aborts pending exports and displays an interruption notice; a fresh
non-restored launcher namespace isolates late results. Large payloads never enter
Activity saved state. A synchronous gate rejects rapid duplicate requests before
button recomposition.

Local evidence: focused production Kotlin/JVM tests and Android/Compose API
compilation only. Full Gradle/JVM/native/R8/APK and the exact-revision Android
matrix remain release gates, not replaced by these checks. The added Android
interactions use bounded condition waits and no fixed extra delays; their actual
matrix duration is not known until hosted execution. No Samsung Files/ChatGPT
picker compatibility, physical Fold 6 behavior, real-model or acoustic success is
claimed. Receiving-app selection/delivery is outside the controlled fixture.

Build 1243 (`48f99336b5784ea36a143fc9817d217138dba085`) exposed a
separate test45 selector defect on all five profiles: the tagged clickable
`pipeline_benchmark_part_copy` parent has empty text, while its child TextView
contains `Copy part 2`. The test now requires that exact text as a descendant of
the exact tagged control before clicking it. The part-2 clipboard assertion and
all existing waits/deadlines remain unchanged. The export status selector still
combines ID and text on one node because its production `Text` owns both.
Fresh local exact-method Android/Compose/UiAutomator API compilation passes;
18 host checks using actual UiAutomator 2.3.0 matcher bytecode cover the five
retained hierarchies and reject wrong, missing, hidden or unrelated labels.
Those host checks use an XML-backed node adapter, and the compile fixture uses
signature-only navigation/activity helpers and generated BuildConfig constants.
They do not establish device execution, runtime duration, R8 or APK verification;
the exact repaired revision still requires the full release gate.

Build 1244 passed the corrected selector and reached the existing repeated-save
intent checks, which exposed that pinned AndroidX Activity 1.10.0 CreateDocument
omits `CATEGORY_OPENABLE`. TXT/JSON/CSV now use one production transport factory
that adds this category while preserving the superclass action, MIME, title and
result semantics. The original test45 assertion and complete method remain intact.
Its existing transport probe additionally checks repeated/recreated contracts for
all three formats, cancellation and successful URI results. Fresh real-framework
host checks pass 78 assertions; a category-removal negative control fails as
expected, and exact test45/production UI API compilation passes. [Cause, dependency
provenance and verification limits](benchmark-document-contract-2026-10-09.md)
retain the evidence. Exact-revision full release verification is still required.

Build 1247 reached all 82 main journeys on API 30; test45 failed at the independent
release-test ABI after 81 other passes. Exact app/test DEX inspection found both
the typed `CreateDocument.getSynchronousResult(Context,String)` and Uri-returning
`parseResult` absent after R8 folded their bodies into erased bridges. The probe
now types its initial and recreated production contracts as
`ActivityResultContract<String, Uri?>`, matching the already-kept registry ABI
for all three contract methods. Every existing assertion and transport operation
remains intact; production code and keep rules are unchanged. Separate host
production/test compilation, 78 exact probe assertions, missing-method and
OPENABLE negative controls, and the helper suites pass. [DEX evidence, source
binding and limits](benchmark-export-erased-abi-2026-10-09.md) retain the diagnosis.
The exact integrated revision still requires the complete signed/R8/five-profile
Android gate; these host checks do not verify picker or ContentResolver I/O.

### Native frozen-pause capture (2026-10-09 source checkpoint)

- Native-only immutable prefix proposal, separate full-WAV fallback, raw completed
  VAD coverage certificate after capture join, one-attempt spent-encoder fence and
  bounded reversible tail: `NativePauseCaptureTest`, `NativePauseAudioTurnCaptureTest`.
- Fresh completed-caption source-window mapping and protected native endpoint
  eligibility: `CompletedEndpointCueTest`, `NativePauseEndpointPolicyTest`.
- [Contract and limitations](native-frozen-pause-capture-2026-10-09.md). Existing
  legacy350/900/1500/1800/3500 paths, quiet/short/echo safeguards and endpoint/speech
  timing identities remain covered. Source JVM checks do not verify APKs, native
  models, physical acoustics or the sub-second playback goal.

## Native pause speculative-response admission (9 October 2026)

Owners: `NativeVoiceSpeculation`, `SpeculativeResponseCoordinator`,
`NativeSpeculativeAudioDriver`, `NativeSpeculativeResponseBackend`,
`ConversationModelSession.beforeNativeMutation`, and the capture-owned
`NativePauseCapture` certificate. See [contract and acceptance](../native-speculative-response.md).

JVM acceptance: no TTS/audio/action/history/memory publication before endpoint and
exact final prompt/PCM identity; live confirmed streaming without a full-draft
wait; raw-VAD-certified capture boundary and complete-original-WAV fallback;
resume/correction/typed/stop/call replacement fencing; late callbacks; exact-once
promotion; byte/character/callback/deadline limits; checked native cancellation,
rollback and model-lease quarantine. First/follow-up native prompt tests run the
actual routing, context preparation and assembler, including adoption/cutoff
changes and the ordinary null-receipt instruction. Final-answer timing cannot
inherit a rejected candidate's clocks or audio receipt. Promoted native history
is discarded and rebuilt from confirmed app history.

Host mock/logic and source-bound compilation are not an APK verification or a
latency measurement. Required remaining gates: release JVM/native checks and all
Android sandbox profiles on the integrated revision, then matched multi-turn
real-model/Piper/physical-audio measurements. The target remains final spoken
chunk to first actual answer playback below one second; no such result is claimed.

## Smart Turn production-session host check (9 October 2026)

The unchanged C++ Session matches the pinned official frontend/model probability
bit-for-bit on eight existing public/synthetic fixtures. The original frontend
tolerance and all probability expectations remain unchanged. Negative/recovery
controls and owned-child cleanup passed; downloaded weights/runtime and generated
arrays were removed after independent review. See the [source-bound results and
limits](smart-turn-host-validation-2026-10-09.md). This adds host model execution
evidence, not Android/JNI, physical-device latency, endpoint calibration or
default-on admission.

## Caption reset failure under coroutine recovery (9 October 2026)

Build 1246 reached 2,152 release JVM tests and exposed one exception-chain-depth
assumption in `PostAnswerCaptionContinuationTest`. The unchanged original suite
reproduces the same failure with Gradle-style assertions or explicit coroutine
debugging; disabling recovery removes it. The corrected test retains the required
`NativeReleaseFailure`, requires the exact native root exception, and additionally
checks completed caption work, closed listener, one reset and no success event.
All 11 focused tests pass in five assertion/debug/recovery configurations.
Production cleanup and the caller's quarantine/rethrow path are unchanged.
[Reproduction, source hashes and coverage limits](caption-reset-recovery-2026-10-09.md)
retain the failure evidence. Exact-revision full release gates remain required.

### Benchmark picker replacement acknowledgement (October 9, 2026)

Build 1248 (`13a62a8374610fefda9b93c3213c559a9f97bef7`) exposed the same
unacknowledged Compose replacement through two byte states: API 36 actual 16 KB
read the old report at test45's second stale-destination check; compact Fold
read an empty file at its first. The fixture's unchanged screen tag could still
belong to the old owner after `setContent`. An exact-generation `SideEffect`
receipt now establishes completed disposal/registration before delivering the
held old result, using the existing five-second observation bound. All 57
assertion lines, both stale-URI checks, successful fresh save and all prior
waits/scenario deadlines remain required. A narrow R8 keep preserves the new
three-argument SideEffect instrumentation ABI; production Kotlin is unchanged.
Six deterministic actual-runtime host cases, including both precommit failures,
eight repeated committed replacements, fresh same-scope admission/frozen payload
and callback/IO cancellation boundaries, pass alongside 11 existing export JVM
tests and 109+82 helper tests. [Evidence and explicit host limitations](benchmark-export-recreation-2026-10-09.md)
retain the failed-run and dependency bindings. Fresh exact-revision complete
signed/R8/five-profile Android verification remains required.

### Build 1254 Fold main-suite capacity (October 10, 2026)

Build 1254 ([run 38013605978](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38013605978),
source `cb8664b855c01c85e6b267f1bf980c2e86b260ed`) retained Fold artifact
`11656527587`: the real build-1190 upgrade passed, then 76 of the 82 main
journeys passed before the controller stopped the main instrumentation command at
900.02 seconds. Their measured durations sum to 894.318 seconds; test77 had
just started its activity setup and reported no terminal outcome. No skipped
test or incomplete phase is treated as passing. Process restart, lifecycle and
layout phases were not reached on Fold.

The Memory History readiness repair succeeded: the selected tab and expected
row appeared in 203 ms with zero preparatory swipes. Its full test24 passed in
148.769 seconds; the export test45 passed in 100.068 seconds and Wisp test49 in
88.219 seconds. The 72 cases completed in both builds 1252 and 1254 were
22.031 seconds faster in aggregate. This supports a main-suite capacity
shortfall, not an observed permanent hang in test77. It does not prove that
the unfinished tests or later phases will pass.

The original 900-second aggregate limit was introduced by `55094bc` when the
contract contained 48 journeys; the current contract contains 82. Following
review of the measured Build 1254 results, only `36-foldable-compact` now gets
1,200 seconds for the main instrumentation invocation. The exact named API 36
compact Fold configuration is checked before admitting that allowance; all
four other profiles keep 900 seconds. This supersedes the earlier blanket
900-second statement, including the speculative capacity warning in the
October 9 Smart Turn wiring record. It changes neither product performance
acceptance nor any individual assertion or readiness deadline.

All 82 named journeys, raw terminal outcomes, per-test PNG/XML, true previous-
APK upgrade, process/permission recovery, actual fold/unfold, layout and native
page-size checks remain required. The 300-second boot, 180-second install,
180-second phase, 300-second layout and 40-minute outer job limits are unchanged.
There is no sharding, test retry, production-code change or model execution in
this repair. Build 1254 failed and remains failure evidence; a fresh exact-
revision full release gate is still required before APK publication.

Local validation passes all 111 build/helper tests and 82 verification helper
tests, including exact profile allowances, rejection of a borrowed Fold budget,
all 82 main-test names and unchanged upgrade/controller phase budgets.


### Build 1254 model-choice callback observation

Build 1254 ([run 38013605978](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38013605978),
source `cb8664b855c01c85e6b267f1bf980c2e86b260ed`) retained API 30's
`test33` failure: the immediate selection count was zero after one Choose tap.
The captured button was enabled and fully visible. Pinned UiAutomator 2.3.0
injects click events asynchronously; that return does not acknowledge the UI
callback. The fixture now waits for Download delivery and for Choose's terminal
dismiss callback, then checks all original assertions on the Activity's main
thread after the synchronous handler. Each callback wait uses the existing
15-second UI allowance and fails on timeout. The ActivityScenario boundary is
not covered by that callback timer. There is still exactly one tap per action,
with exact selection/dismissal counts, and the chosen model ID is also checked.
No product, navigation, scenario or suite-budget change is made. Nineteen
source-bound host checks cover delayed, missing, wrong and duplicate callbacks,
and the changed method compiles against cached Android/Compose/UiAutomator APIs
with explicit app/ActivityScenario signature seams. These checks do not run the
actual UI or establish an Android pass; the changed release gate remains pending.

### Build 1255 Smart Turn Disable observation (October 10, 2026)

Build 1255 ([run 38022969808](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38022969808),
source `d196ce72022f47a4c240682d6ca25f1c742646d5`) retained API 30's
`test81` failure: the preference was still true immediately after one Disable
tap. The log records a tap on its non-clickable text child; the existing tagged
parent was enabled and clickable. The failure screenshot starts 37 ms after
the UiObject2 click log. Because the fixture restores the preference in `finally`
before failure capture, that screenshot cannot prove whether the callback ran.
The same source passes this journey on API 35 compact and API 36 normal.

The production Disable handler synchronously updates the in-memory preference,
then switches its Compose state to the Enable label. The test now preserves its
Disable-label assertion, taps the existing tagged parent exactly once, and
awaits that exact Enable label through the unchanged `find` helper before the
original preference assertion. Its condition wait remains 15,000 ms; this is
not a strict wall-clock cap because an individual framework lookup/idle call
can overrun it. Missing delivery or missing UI completion still fails. All default-off, disabled-control, recreation,
preference, input-mode, caption-mode and cleanup checks remain intact. No retry,
sleep, production change, model invocation, scenario change or suite-budget
change is introduced.

Thirty-five source-bound host cases reproduce the old immediate-read failure
under delayed dispatch and validate the repair, including missing-handler/UI,
failed-persistence, lost-on-recreation, stale-render and changed-mode controls.
The exact changed method and unchanged production settings UI compile against
cached Android/Compose/UiAutomator APIs, with explicit ActivityScenario,
navigation and model-setup seams. This is harness/API evidence, not a fresh
Android, disk-persistence, minification or APK verification pass. The full
exact-revision release gate remains required.


### Build 1256 panel-approval completion observation (October 10, 2026)

Build 1256 ([run 38027820843](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38027820843),
source `43df0eca06530d893922b3bcafb01134d7c07c50`) retained API 30's
`test59` failure at the Stop-overlay assertion. Its preceding exact-tap,
group-lease, successful-ledger and consumed-approval assertions passed. The
log records the Approve click at 06:29:28.623 and failure-evidence capture at
06:29:28.671 UTC. The screenshot still shows the pending panel, but neither
that frame nor the log establishes the exact callback interleaving.

The fixture's UI callback first dispatches the fake tap, then commits the
receipt, sets the Stop flag and publishes the journal. Previously the test
observed only the earlier, unsynchronized tap list from the instrumentation
thread. That list does not acknowledge completion or publish later writes.
The corrected fixture signals a latch only after the whole callback publishes
the journal, awaits it for the existing 10-second allowance, then checks all
original assertions and performs lease/overlay release on the Activity owner.
The latch establishes visibility of earlier callback writes; the owner boundary
keeps its mutable state access and cleanup serialized. The ActivityScenario
boundary is separate from the callback timer, so this is not a strict overall
wall-clock cap. Missing or interrupted completion still fails. The panel opens
once and Approve is clicked once; no retries, sleeps, production changes,
assertion removals, scenario changes or suite-budget changes are introduced.

Source-bound deterministic host checks and cached real Android/Compose API
compilation are recorded in [the repair evidence](panel-approval-completion-2026-10-10.md).
They do not establish actual Android input delivery, Compose rendering,
accessibility overlay behavior, R8 compatibility or a verified APK. Build 1256
remains failed evidence; a fresh full release gate for the exact repaired
revision is required.

## Video-call snapshot synthesis (October 30, 2026)

Owner-approved design (2026-10-30): on a video-call turn, when the user
finishes asking, attach ONE retained camera frame to Gemma together with
the full object track list from the last 15 seconds, so the model grounds
its answer in the scene instead of step 4's label summary alone (build
1612's paste: 12 minted tracks reached the prompt as three toilets).

Acceptance checks and their JVM coverage:

- The newest dispatched frame (JPEG bytes, capture timestamp, pixel
  geometry) is retained by `LatestServerVisionResult`, replaced wholesale
  per frame, recorded by `CallServerVisionObserver` before the
  vision-server gating (the frame serves the local model, not the
  server), and dropped by the same `clear()`/`reset()` lifecycle as the
  live detect entry — toggle-off and call end can never leave a stale
  frame readable as current (`LatestServerVisionResultTest`,
  `CallServerVisionObserverTest`).
- A snapshot turn (video toggle on, live detect result fresh, retained
  frame fresh, engine image-capable) replaces the summary block with
  every track whose last-seen age is at most 15,000 ms — present or not,
  one-frame tracks included — each rendered with its `#id`, label, latest
  box in frame pixels and last-seen age, newest first, under a 2,400-char
  budget that drops the oldest tracks first and counts the omission. The
  framing states the boxes are pixels in the attached snapshot's own
  frame, and keeps the quoted-data/not-instructions discipline
  (`CallVisualContextTest`).
- The image attaches exactly once, at turn finalization: the incremental
  session appends it after the final question text and before decode
  (`IncrementalVoiceInput` → `VoicePrefillSession.appendImage` → the
  pinned SDK session's `InputData.Image` prefill); retries and the
  incremental-failure fallback regenerate with the same image on the
  conversation route, never a silent text-only downgrade
  (`IncrementalVoiceInputTest`, `LiteRtVoicePrefillSessionTest`,
  `TemplateVoiceSessionTest`, `ConversationGenerationTest`).
- Honest fallbacks: video off, no frame, stale frame, a non-vision
  engine, or a direct-audio turn each proceed with step 4's summary block
  (or none) and a diagnostics line naming the reason; the per-turn
  `Call snapshot: attached=…` line reports frame age, frame bytes, track
  count, window and omissions (`CallVisualContextTest`,
  `ConversationGenerationTest`).
- The engine for a turn prepared with the video toggle on is created
  vision-enabled through the existing reuse/rebuild check in
  `VoiceTurnPreparation`, keyed like `audioEnabled`; repetition
  suppression and the refusal backstop see image turns through the same
  `ConversationInput` as text turns (call turns keep `imageUri == null`,
  so the repetition guard still applies).
- Preview telemetry: the overlay's result age is computed from a fresh
  wall-clock read and clamped at zero (build 1612 printed
  `lastDrawResultAgeMs=-682` from the 1-second tick clock)
  (`PreviewOverlayTelemetryTest`).

JVM tests pin behavior only; that the pinned LiteRT-LM session accepts
the image on device, and that Gemma's answer grounds in the attached
frame, remain Fold 6 phone proof (a "what do you see?" against a scene
whose detector labels alone mislead, e.g. the car infotainment screen
detected as a cell phone).
