# Idle native-caption finalization — 9 October 2026

## Behavior and limits

Ordinary streaming Gemma E2B audio capture can now omit a **new endpoint-final
Whisper decode when the existing caption worker is already idle**. The original
retained audio still enters the existing native encoder/Conversation path.
Whisper's live previews remain display observations. This optimization does not
turn a provisional preview into finalized recognition, a control, an action,
memory, or a saved user transcript. The existing separate post-answer Gemma
caption pass remains unchanged.

This is a partial scheduling optimization, not complete Whisper independence.
Cold model preparation, live/segment decoding, already-busy finalization, and
serialized cleanup can still delay the turn. There is no measured device-latency
claim, fixed 650 ms endpoint change, second recognizer, detached native owner,
new PCM queue, model-weight change, or native numerical-oracle change.

## Eligibility

The runtime enables the optional capability only for ordinary direct-audio
Whisper capture with the existing retained native-PCM observer. Comparison trials,
recorded corrections, typed/text-ASR routes, other recognizers, and active barge
capture retain their existing route.

At the existing trailing-silence proposal, all of these must hold:

- Complete retained audio, with no pending endpoint
- At least 240 ms of the existing confirmed strong-speech evidence
- No candidate-local quiet-ASR evidence used for onset or speech-end extension
- No playback text to match, or a known onset strictly later than the last
  playback end plus the existing 350 ms echo tail
- No committed ASR segment or segment issue
- Whisper admission can atomically prove that prior native decoding has already
  completed, no failure is recorded, and the stream has not been sealed/closed

Unknown playback timing, overlap, the exact 350 ms boundary, short/quiet speech,
unsupported or busy workers, segmented requests, incomplete audio, limits and
explicit stop retain final ASR. Existing follow-up acceptance, lexical echo
rejection, final interruption verification and correction behavior are preserved.

`AdaptiveTurnEnd` cues and thresholds are unchanged, including the Whisper
900 ms no-text override, complete/stable 350 ms, settling 1100 ms, uncertain
1500 ms, unfinished 1800 ms, and hesitation 3500 ms. The 96 ms onset confirmation,
hardware/producer drain, retained pre-roll, native PCM seal/count/hash, cancellation,
checked close, quarantine and delivered-history contracts remain unchanged.

## Ownership

`StreamingTranscriber.retireIdleCaption()` is optional and defaults to unsupported.
False leaves ordinary finalization available. `SegmentedTranscriber` forwards it
only before any segment has committed. `AsyncWhisperSession` serializes admission,
retirement, final/recovery submission and close. A successful claim seals admission
without submitting inference; later accept/final/recovery cannot implicitly reopen it.
If hardware drain reveals confirmed continuation, the paired
`resumeRetiredCaption()` operation restores that same still-owned stream and its
original PCM. It never commits an empty ASR segment. An ordinary finalized or
closed stream cannot use that operation. Silence-only drain keeps the explicit
skipped-final-ASR status; an explicit-stop/capacity endpoint restores guarded
finalization. The original acoustic drain check is not weakened.

Capture still stops and joins the exact acoustic producer/collector, then closes
its recognizer. The same single Whisper model lease is returned before the reply
listener's existing warm interruption probe can borrow it. Native calls never
outlive a released recognizer. Busy workers retain the previous waiting barrier;
there is no hidden switch to unverified keyword interruption or readiness gap.

No asynchronous caption publication is introduced. A retired preview therefore
has no late callback that can mutate a newer call, turn, or generation. Existing
call-fenced live previews and native-generation checks remain in force.

## Timing and diagnostics

`caption_finalization` records `reason`, `newFinalDecodeSkipped`, `finalAsrStatus`
and the strong-audio duration. `final_asr_status` and
`caption_finalization_reason` are also attached to the turn benchmark.

`skipped_idle_caption` means that no final ASR text exists for that capture;
`finalTranscript` stays empty. It does not claim zero earlier ASR work. Existing
recognizer-work metrics retain actual partial/final/recovery invocation counts.
Endpoint decision time remains the accepted acoustic proposal, before recognition
work, and native timing retains its original clock/count contract.

## Verification

New deterministic JVM checks cover:

- Clean idle versus legacy capture with identical retained PCM bytes/SHA-256,
  sample count, candidate generation and sealed native content
- No new final decode, no invented final text and no post-retirement scheduling
- Held partial/final decoding, immediate refusal of idle retirement, legacy
  finalization and return-before-warm-probe/following-turn reuse
- Worker failure, concurrent admission/retirement/close, and call closure while
  JNI is held, with exactly one release and no release under decode
- Short/quiet onset/quiet extension, text-ASR, busy/unsupported, playback-tail and
  unknown timing, incomplete/limit, explicit-stop/no-speech, pending endpoint and
  segmented exclusions
- A hardware-arrival race after successful retirement, with strong/quiet
  continuation retaining the original ASR PCM/owner, and silence-only drain
  retaining honest skipped-final-ASR status
- Identical adaptive cues/thresholds, cancellation cleanup and no late caption

The focused host suite also runs the existing ASR, long-utterance, echo,
interruption, correction, delivery and native-worker regressions unchanged.
The focused suite passes 263 tests; the 28 new worker/capture cases also pass
five repeated runs. Architecture checks and the existing 102 root/82 verification
Python checks pass, and all 655 Kotlin source/test files parse without errors.
A separate boundary compile covers the actual capture factory, capture/model
owners and adapters plus the exact changed preparation/recognition expressions
against cached Android 14 API types. Native-model and unrelated construction
collaborators are compile-only declarations; the complete runtime classes and
packaged native ABI still require the hosted build.

These tests use controlled backends; they are not real-model or acoustic evidence.
The exact-revision release JVM/native/helper, signed APK and five-profile Android
sandbox gates remain required. Physical audio, model accuracy, OEM behavior,
thermal contention and actual speech-end-to-answer latency remain unverified here.

The first implementation failed two injected drain-race regressions: continuation
created an empty committed ASR segment, and silence-only drain mislabeled skipped
recognition as finalized. The reversible, still-owned retirement contract above
repairs both; the failure evidence and passing rerun are retained with the local
verification receipts. No existing acceptance threshold was changed.
