# Smart Turn native endpoint control — 10 October 2026

Smart Turn now controls eligible native-audio turn endings. An exact current
COMPLETE result permits the existing 350 ms completion margin; CONTINUE holds
an otherwise early endpoint until resumed speech or at least 3500 ms of real
sampled and raw-classified quiet. This is
an implementation/host-tested candidate, not evidence of phone accuracy or a
verified release APK. The previous shadow-only behavior is historical.

## Model semantics and product policy

Model/frontend/runtime pins are unchanged in `third_party/smart-turn/manifest.json`.
The pinned Pipecat
[local analyzer](https://github.com/pipecat-ai/pipecat/blob/7597e0c2f84fc05a31dd636daa9d91bb8405e9ff/src/pipecat/audio/turn/smart_turn/local_smart_turn_v3.py)
uses strict probability greater than 0.5 for COMPLETE; equality is CONTINUE.
This is upstream classification, not a calibrated Jarvis confidence threshold.

Jarvis policy starts endpoint-mode inference at 350 ms of acoustic pause (the
existing complete/stable margin), with a 250 ms snapshot-age publication deadline,
500 ms minimum attempt spacing, at most two requests per acoustic pause, and at most eight
accepted requests per turn. The rolling model window remains eight seconds;
full current-turn PCM ownership remains with capture. One CPU thread and one
call-retained ORT owner are retained. Request cancellation is cooperative: overdue
work loses authority immediately but may still consume CPU until it returns.
No replacement owner starts while the old worker is draining. The deadline is
not a hard CPU-time guarantee.

CONTINUE permits one bounded reassessment after at least 8,000 additional real
retained samples (500 ms), subject to the same spacing, current revision, deadline
and eight-request total. Ready callbacks or wall-clock gaps do not count as new
audio. The prior CONTINUE remains effective while that refresh is pending, busy,
blocked or unknown; only a fresh valid COMPLETE can shorten it. A second CONTINUE
retains the 3500 ms quiet fallback. The collector clamps effective silence to the
minimum of capture-timestamp delta and completed raw quiet samples after both the
last raw speech-risk boundary and the last confirmed speech sample. Timestamp
gaps and model-ready wakes add no quiet evidence. The current collector boundary
must also have exact raw coverage: any admitted unclassified suffix prevents the
CONTINUE fallback, even if enough earlier quiet frames exist. Final drain preserves
that verdict while canceling/fencing pending model refresh work; resumed raw speech
still revokes it. Producer classification-in-progress and hardware drain are checked
for both model COMPLETE and CONTINUE-cap proposals, with captions on or off.
This cap is a conservative product limit, not evidence that every incomplete
phrase requires 3.5 seconds. It is quantized by real 512-sample VAD frames and
capture reads, not a hard 3.5-second wall deadline when samples stop arriving.

Controlled comparison with speech ending at 300 ms: a first CONTINUE at 765 ms
can be corrected by a second snapshot at 1200 ms and COMPLETE at 1265 ms. This
ends 965 ms after speech with two requests, versus the single-assessment policy's
the same sampled-quiet fallback with one request. Repeated CONTINUE falls back at
4000 ms total (3700 ms after speech) with 100 ms reads and only two requests.
At 3800 ms, the last completed raw frame covers only 3476 ms after confirmed
speech; 3900 ms has enough quiet but still has an unclassified suffix. The 4000 ms
boundary is fully classified. Across all eight phase alignments of existing
1600-sample/100-ms reads against 512-sample/32-ms raw frames, speech ending at
300, 400, 500, 600, 700, 800, 900 and 1000 ms has fallback delay respectively
3700, 3600, 3500, 4200, 4100, 4000, 3900 and 3800 ms. Thus the current framing
adds 0–700 ms beyond the quiet requirement; no framing change or universal 3.5s
wall bound is claimed. Android's existing assembler emits these full reads.
A regression injects a 5000 ms capture-timestamp jump after CONTINUE and still
requires the same real quiet PCM: it ends at timestamp 9000 with all 64,000
samples retained. Timeout/blocked
refresh and resumption during refresh are tested. This trades at most one extra
inference for a chance to correct an early negative, without polling indefinitely.
Eight separate resumed-speech pauses that resume before refresh still each receive
a prediction. If refreshes spend the whole-turn budget sooner, remaining pauses
explicitly fall back to ordinary endpointing. Phone accuracy and CPU costs remain
unmeasured; these traces inject model outcomes and scheduler delays.

## Ownership and ordering

The model receives an immutable copy of the exact retained snapshot, never a
second microphone cursor. The collector offers it before optional caption work.
A conflated worker-ready event wakes that same serialized collector without
appending/replaying PCM or advancing acoustic evidence. Current call/job,
turn/capture generation and speech revision must all still match.

COMPLETE covers every byte of its own snapshot, including any partial Silero
frame. Any PCM appended after that snapshot must be wholly classified raw quiet
before it can extend the verdict. Unknown/weak raw speech, real resumption,
backlog, quiet-ASR dependence, incomplete/segmented audio, or playback-tail risk
cannot borrow its permission to shorten capture. Resumption revokes the old
verdict before any subsequent endpoint check. Hardware/producer drain applies
even with captions off. Final admitted audio remains intact; there is no recorder
pause, artificial padding, truncated suffix or assignment of received unknown
speech to a new turn.

Gemma speculation does not cancel an admitted Smart Turn request before its
bounded result/deadline. Pending work does not extend the existing fallback
endpoint. A COMPLETE verdict survives speculative preparation/final drain only
for its current acoustic revision. This adds no Gemma owner or model lease. When the ready event both admits
speculation and completes capture, speculation has no earlier pause-window head
start; any overlap is only subsequent finalization/cleanup. Endpoint and
speculation savings must not be summed as independent improvements. A model
COMPLETE event cannot spend/freeze the encoder unless the independent existing
raw certificate can cover that snapshot; model-covered partial Silero frames
still permit endpointing while leaving the encoder unspent.

Eligible model-authorized native endings skip a new final caption decode and
cannot be vetoed by display-only caption text. The existing caption owner still
closes/joins before its recognizer lease is released; an already-running caption
decode may contribute cleanup latency. ASR-text mode, quiet speech, playback echo
and segmented/guarded routes retain their required final recognition. This is not
a claim that all caption loading/processing or cleanup is now asynchronous.

## Setup and evidence

The new `smart_turn_endpoint_enabled` preference defaults true. The old shadow
flag is deliberately not interpreted as an opt-out from this new feature.
An explicit disable remains. Settings show installed/missing state and provide
a separate 8.7 MB download. Setup verifies the pinned hash; native construction
also verifies the immutable model bytes on its worker. Capture performs only the
existing availability lookup, never a network request or synchronous hash/load.
Missing, initializing, busy, blocked, expired or exhausted work reports bounded
fallback and leaves ordinary voice available.

Endpoint diagnostics include model verdict, exact model/current sample
boundaries, actual decision timestamp, speech-end timestamp, route/eligibility,
the effective silence evidence versus capture-timestamp silence, and whether
the ready event caused admission. Worker frontend/inference timing
is recorded separately. Follow-up rows retain their correlation to the current
capture and no longer label actual control as shadow-only.

Host scheduler fixtures use the real capture/worker with synthetic classifier and
model outputs. With 100 ms reads and speech ending at 300 ms, the first snapshot
is 11,200 samples at 700 ms:

| Injected model latency | Actual endpoint | Speech-end delay | Final retained samples |
| --- | --- | --- | --- |
| 65 ms, before next read | 765 ms | 465 ms | 11,200 |
| 120 ms, one later quiet read | 820 ms | 520 ms | 12,800 |
| 220 ms, two later quiet reads | 1600 ms | 1300 ms | 25,600 |

The last case retains the valid prediction but waits for complete classification
of later PCM. This remaining phase-dependent delay is explicit; these are
scheduling assertions, not measured Android model or end-to-end answer latency.
All endpoint timestamps precede capture return, outstanding caption cleanup,
answer generation and playback; they are not answer-playback latency.
A 251 ms result cannot shorten the ordinary endpoint. Same-time ready/PCM order,
irregular chunks, incomplete/continued speech, quiet speech, old call/revision,
worker busy/cancellation, unavailable setup, whole-turn retention and budget
exhaustion have focused tests.

The companion Android test probe uses the official hash-pinned model as temporary
CI/emulator input, actual JNI inference on frozen synthetic fixtures, and the
same typed classification/endpoint adapter. Its result must remain separate from
natural-language endpoint accuracy, physical audio and Fold latency. Release
build, shrunk DEX linkage, all sandbox profiles and that real-model probe must
pass on the final combined revision before any APK is called verified.

## Frozen host checks

The [portable host receipt](evidence/smart-turn-endpoint-2026-10-10/host-receipt.json)
binds all source/dependency hashes and commands for 99 passing JVM tests, including
24 model-endpoint capture tests. The [reproduction driver](evidence/smart-turn-endpoint-2026-10-10/run_host.py)
requires an existing hash-matching dependency cache and never downloads anything.
SDK-free checks passed 111 Python helper and 101 verification-controller tests,
including 19 model-input ownership, preflight cleanup, strict scalar result and
fail-fast probe lifecycle checks.
The separate [boundary compile summary](evidence/smart-turn-endpoint-2026-10-10/boundary-compilation-summary.json)
checks full actual changed runtime/factory/lifecycle files against real Android
APIs with explicitly declared unchanged collaborator signature fixtures. It is
compile evidence only, not runtime or APK verification. Development failures are
retained separately and did not cause acceptance-oracle or workflow relaxation.

## Independent cap-edge repair

The earlier frozen candidate `94a8a9bb` failed independent adversarial review:
3,508 ms of earlier classified quiet could end capture with 72 newly loud samples
still unclassified. A second case deferred at a fully classified cap boundary,
dropped CONTINUE during finalization, then fell through to ordinary timing on a
new unclassified tail. Those failed traces are retained in the review evidence.

The corrected fixtures require exact current raw coverage, retain CONTINUE through
final drain, and then prove liveness: the loud partial suffix is followed by
confirmed resumed speech in the same retained WAV, and a fresh valid COMPLETE
ends it at 4373 ms. The drain case runs with captions both on and off and reaches
a later ordinary endpoint with the full original WAV intact. All eight regular
read phases also reach a legitimate fully classified endpoint.
