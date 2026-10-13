# Native speculative voice response

Implementation on the audio working branch, 9 October 2026. This is a candidate
latency optimization, not a measured sub-second result. The capture half is
specified and tested in `NativePauseCapture`/`NativePauseEndpointPolicy`.

## Observable contract

A clean native pause can freeze one explicit candidate recording at the capture
collector's boundary while the microphone keeps listening. Its complete PCM
count/hash, capture generation, call/turn, policy version, and exact full prompt
identify the candidate. This is an explicit new request-boundary policy. It is
not numerical equivalence to encoding a later, longer WAV.

The complete original recording and reversible tail remain available. A final
certificate exists only after producer and collector join, independent hardware
backlog checks, and complete raw-VAD coverage of every excluded sample. Strong,
weak, uncertain, masked, or unclassified continuation invalidates the candidate;
queue emptiness and gated VAD alone cannot certify silence. Existing short stable,
hesitation, unfinished-phrase, echo, follow-up, explicit-stop and capacity guards
remain. Undecidable cases use their ordinary endpoint and full recording.

At an admitted proposal, the existing external encoder seals the exact frozen
PCM. The same count/hash/output-count checks and checked native close precede
Gemma inference. Its owner never receives another PCM append or reset. The
ordinary resident Gemma engine and the turn's existing exclusive model lease
run one tool-disabled Conversation through `beginTurn`/`finishTurn`/`awaitIdle`.
There is no second engine, KV rewind, early phone action, memory capture, history
publication, TTS synthesis, queued audio or playback.

## Preview, confirmation and rollback

`ConversationContextPreparation.previewDirectAudio` reads a current approved
memory snapshot and the actual post-cutoff history used by final routing. It
assembles the same voice prompt, continuity and null-capture-receipt instruction.
It does not adopt a memory token, consume a receipt/cutoff, fetch references,
change persistent history, or mutate the native conversation.

Final routing and context preparation remain authoritative. A turn-local
`ConversationModelSession.beforeNativeMutation` fence cancels and joins the draft
before any required reset or close (including memory adoption, cutoff or context
compaction). An unchanged context can keep generation running. Only the ordinary
input-generation boundary may compare the exact final prompt and the independently
certified frozen PCM identity, release held tokens once, and stream the remainder.
Normal answer filtering, repetition policy, memory publication fences and Piper
playback still apply. Promotion does not wait for full response completion.

After native generation drains, speculative Conversation history is always reset.
A promoted answer therefore reports `nativeConversationContainsTurn=false`; the
next turn rebuilds from confirmed app history. Retries preserve ordinary input
ownership and report their own native history. If a candidate is invalidated or
does not match, its native cancellation/drain and reset finish before the existing
`generateAudio` path receives the unchanged complete original WAV. This fallback
uses fresh ordinary audio encoding; it does not resume the spent external encoder
and can cost latency. A failed checked drain quarantines the encoder/engine and
retains the model lease and necessary artifact ownership. It never authorizes
fallback, owner destruction, replacement inference, or automatic rearming.

## Bounds and observations

Production admits at most one candidate per utterance, with no candidate queue.
The reusable coordinator permits at most two attempts, but this native adapter
uses one because it cannot resume a sealed external encoder.

- Unconfirmed generation deadline: 1,500 ms, including frozen encoder completion
- Held output: 4,096 UTF-16 code units, 16,384 UTF-8 bytes, 1,024 estimated tokens
- Native callback mailbox: 64 messages; held callback mailbox: 64 messages
- Confirmed streaming remains bounded to 32,000 generated characters and the same
  bounded callback backlog; it does not inherit the draft's short deadline
- Speculative TTS/audio storage and playback: zero bytes
- Retained native capture remains within the existing 28-second production limit

Overflow fails closed; it cannot drop input or silently retry after output has
been published. Late callbacks, owner replacement, Stop and cancellation cannot
revive an invalidated candidate. Diagnostic observer failures do not authorize
or interrupt native cleanup.

Benchmarks keep candidate launch, first held token, invalidation, drain,
confirmation, promotion selection, first released token, final reuse, fallback and
actual answer playback as distinct events. `speculative_promoted` only selects a
candidate; `native_speculation_first_released_token` means nonempty exact-match
output reached the normal streaming port, and `native_speculation_reused` means
the final result succeeded. Existing first-reply-text/playback markers remain
the authority for filtered answer text and audible output. A certified capture
alone never counts as a promoted answer.
Original native submission/first-token timestamps are replayed only with the
first released exact-match token. A rejected or empty failed draft cannot become
the final answer's TTFT or native audio timing receipt. Inference durations retain
their native launch origin; promotion does not manufacture zero TTFT. Prepared
inference labels apply only to the promoted answer pass, not later retry passes.

A 300 ms proposal and 650 ms accepted endpoint provide at most 350 ms of head
start, less encoder seal/close and scheduling overhead. To meet speech-end to
actual answer playback under 1,000 ms at that endpoint, the remaining speech-ready
text, Piper startup/buffering and playback path together must fit within roughly
350 ms. This arithmetic is an acceptance budget, not a measured capability.

## Evidence and remaining gates

JVM regressions exercise exact prompt/PCM identity, resumed speech at multiple
stages, cancellation/drain failures, stale callbacks, all confirmation gates,
no pre-confirm publication, one-time promotion, bounded backlog/deadline, live
promotion without full-answer wait, full-WAV fallback, and native history reset.
Actual routing/context/assembler tests compare first and follow-up native turns
and verify memory adoption/cutoff resets. The first run caught and repaired a
missing null-memory-receipt prompt section; the failure is retained in the local
verification record.

Local host tests use fake native inference against the reviewed SDK API classes;
they do not execute Gemma, the encoder graph, Piper, an Android device, or physical
microphone/speaker/Bluetooth paths. Source-bound adapter compilations and syntax
checks supplement, but do not replace, the exact integrated revision's release
JVM/native and Android sandbox gates. No model, SDK pin, oracle, tolerance, model
weights, private PCM or private conversation export changes are part of this work.

Matched physical-device acceptance must include multiple successive turns,
first-turn memory adoption, ordinary conversation, a pause inside a sentence,
correction/resume, quiet speech, partial VAD frames, echo/tail backlog, typed-input
handoff and Stop. Measure the final detected speech frame to the first non-silent
actual answer playback-head event, excluding filler and acknowledgments. Report
eligibility, discarded work and cancellation cost alongside TTFT, thermal state,
Piper startup, playback gaps and answer quality. Physical acoustic latency and
model quality remain unverified until those measurements exist.
