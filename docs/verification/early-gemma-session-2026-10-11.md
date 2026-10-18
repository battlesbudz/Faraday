# Early Gemma session and fixed-policy prefill — 2026-10-11

## Scope and acceptance

For a direct Gemma audio turn, start microphone capture, then create the ordinary
Conversation and prefill only the immutable voice-response instructions while
capture remains active. Use the same exclusive turn/model lease after all required
resets. Retained native encoding may begin only after preparation and checked idle
complete. A cancelled/replaced owner must not leave a reusable prepared child;
failed native drain remains quarantined. Confirmed history, retrieved memory,
current-user text and audio remain in the final user submission. Existing memory
revision/delivery fencing, tool dispatch and caption authority are unchanged.

This is an **experimental prompt-role refactor**: the unchanged fixed instruction
text moves from the leading part of the user message to the model's supported
system turn. Only an exact full policy prefix is removed; arbitrary/compact prompts
are not rewritten. Template support is established below, but model-answer and
acoustic equivalence are not claimed. Ordinary text/image/tool conversations do
not receive this voice preface. The configuration diagnostic names the policy
`gemma4_fixed_voice_system_v1_experimental`.

## Supported SDK and template evidence

The pinned [SDK Config](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
accepts `systemInstruction` and `prefillPrefaceOnInit`. Its Engine maps that content
to SYSTEM; the native [conversation implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/924e79c91542761242244e4f1651851f822e4cbb/runtime/conversation/conversation.cc)
synchronously prefills the nonempty rendered preface during creation. Setting the
flag with an empty preface would not implement this optimization.

The exact catalog E2B source, revision `6e5c4f1`, has a 11,995-byte embedded Jinja
chat template at header byte 16,538. The first 65,536 bytes were fetched with HTTP
Range (206, full size 2,588,147,712). Template SHA-256 is
`02b3091acf53c0b722e3db0c7a1b4980363edcc2d85549dafa339ff5dbfff629`.
An actual Jinja render confirmed that the system-only rendered prefix
`<bos><|turn>system\nFIXED_POLICY<turn|>\n` is the exact byte prefix of a
system + dynamic user text/audio + model-generation rendering. This establishes
formatting/prefix compatibility, not generated-response equivalence. No custom
chat delimiter injection is used in the app: SDK Conversation still formats and
injects audio, in the existing text-then-audio content order.

The pinned E4B header at revision
`1fc8912676889ed3aeec478c92c1e239bed08928` returned the same template hash. Its remote
full-size header was 3,654,467,584 versus the catalog's 3,659,530,240; this discrepancy
was reported separately and the catalog was not changed by this work. Header-only
inspection is not full-weight identity verification or a successful model load.

API compilation uses the **reviewed sealed-audio SDK**, not generic Maven 0.16:
- Build 1265 run `38051846104`, source `0a4343d6c08d5668b74343cebe58f3fe80bf2d21`.
- Artifact `11670462064`, `jarvis-streaming-sdk-aar-38051846104-1`.
- Artifact ZIP SHA-256 `18878e3bff10101f5c1ae42288c0d81009dadb9bb8c4cab1a1592c438bcfb9d3`.
- Extracted classes SHA-256 `dbb65a34071c5b16c0abbf20c69d83140b3d4ddf6f19d96ef91a3d503ebdf98e`, matching its retained provenance.

## Ownership, cancellation and capture

`CheckedConversationLifecycle` owns the precreated child. It is marked busy during
creation/prefill/checked idle. Cancellation after synchronous creation disposes
that exact child before returning; failed drain quarantines it. The prepared child
can be reused exactly once without a history reset. A speculative draft preserves
only an unused prepared child; a used conversation or rejected draft still follows
the existing reset/rollback path. Ordinary prompt/tool transitions invalidate the
fixed-preface configuration as necessary.

`EarlyGemmaPreparationGate` serializes native encoder creation behind this work.
It polls owner validity every 25 ms; explicit capture cancellation revokes and
wakes it immediately, and later completion cannot revive it. This is a bounded
cancellation-observation interval, **not** an invented timeout for synchronous
SDK prefill. The turn/model owner remains held until native work actually returns.
No concurrent Conversation prefill and native audio-encoder compute is introduced.
The microphone and bounded PCM queue remain active. The existing 960,000-byte
accepted PCM ceiling is unchanged; no shorter utterance-duration cutoff is added.

## Measurements and interpretation

New inference phases separate lazy conversation preparation, measurement-start to
send, and send to first callback/raw text. Existing end-to-end callback metrics
retain their original origin. Early preparation records a separate session plus
fixed-prefill duration and completion stage; encoder preparation wait is separate.
No unsupported split of session allocation versus internal fixed-prefill time is
invented. Last-user-submission native prefill/token telemetry must not be presented
as including a separately prefilled system policy.

Retained-worker append and seal observations report busy state, append-call count,
cumulative/max/last append wall duration, seal wall duration, and current/peak queued
PCM duration. Text-free per-completed-call diagnostic events retain the individual
observations, while benchmark metrics retain latest/cumulative values. An append
may only buffer samples: these are **API wall durations, not individual validated
graph steps**, and include all candidates. Existing reviewed native step/row timing
receipts remain the source for actual committed steps. These counters exclude LLM
multimodal prefill. They therefore help distinguish retained encoder work/backlog
from the separately measured SDK submission/prefill phase, without claiming the
full phase is an encoder cost or introducing a periodic sampler.

## Can audio features be prefilled incrementally while speaking?

Not through the current checked Kotlin Conversation path. The reviewed SDK accepts
an immutable sealed audio artifact only after complete PCM identity, end-of-audio,
validated rows and checked encoder close. It does not expose transactional appends
of provisional audio rows into a live Conversation user message or safe rollback
of those rows. Genuine incremental audio-to-LLM prefill requires a new reviewed
native/API contract for template state, feature commits and cancellation/rollback;
this change does not fabricate one. The existing certified frozen-pause speculative
submission is the supported way to begin an earlier complete request. Fixed-policy
prefill is separate and contains no audio or dynamic context.

## Verification status

Focused host results and compile coverage are recorded after the final rerun below.
The required release JVM/native/package gates and Android sandbox must still run
for the integrated revision. No physical device, microphone acoustics, real-weight
inference, latency gain, semantic equivalence or thermal improvement is certified
by these host tests. Compare the new phase metrics on the same physical device and
model before attributing any improvement to preparation or fixed-prefill reuse.

### Final focused host run

Kotlin 2.3.21 targeting JVM 17, coroutines 1.9.0 and JUnit 4.13.2 compiled the actual
changed LiteRtLmEngine, ModelConversationConfig, native lifecycle/speculative
adapter, ConversationModelSession, retained worker and sealed capture adapter
against the reviewed SDK classes above. Actual prompt builder, model catalog,
benchmark, memory-fence and session/backend dependencies were included. Host-only
support comprised Android-all 14, Gson 2.13.2, a declaration-only AndroidX Keep
annotation and the unchanged ActionRequest data declaration extracted from its
larger action-executor source. No fake SDK classes or generic Maven replacement
were used. The Android capture orchestration/Compose application was not compiled
as a complete app locally; that remains the integrated release build gate.

**58 tests passed** in the final focused run (0 failures):
CheckedConversationLifecycleTest, GemmaVoicePrefaceTest, ModelConversationConfigTest,
NativeInferenceMeasurementTest, EarlyGemmaPreparationGateTest,
RetainedPcmEncoderWorkerTest and EarlyGemmaInputModeTest. Coverage includes stale
owner/cancel/drain/quarantine, exact policy-prefix handling, nonempty fixed-policy
SDK config, phase-clock separation, observer failures, full existing 30-second
PCM buffering while preparation is gated, cancellation/wiping before encoder
allocation, and direct-audio to typed follow-up history reseeding. The real-owner
mode tests bypass native Engine construction only to inject a checked fake native
lifecycle; they do not claim JNI or model execution.

Independent review exposed and repaired two issues before handoff: input-mode
reset initially occurred after higher-layer history accounting, and a 256-event
queue would have rejected actual 100 ms packets at about 25.7 seconds. Mode changes
now occur under the session owner's mutation fence before prompt assembly and
clear its accounting; unprepared hidden mode changes fail closed rather than
silently deleting used context. Every successful confirmed-history reset clears
the fixed-preface flag. Gemma's lazy channel event bound now accommodates even
minimum-size PCM16 packets within the unchanged byte budget; generic worker
queue defaults remain unchanged. A 300-packet regression proves the original
30-second ceiling, its exact overflow error, zero native allocation on cancellation
and wiping of all transferred packet arrays.
