# Capture-first repair and review gates — 9 October 2026

Status: repaired local candidate awaiting independent re-review. Not an APK or
an integration approval. The first candidate `e784dbbce98bfe5a2f49e4bb887aa30f840b6827`
remains immutable failed-review evidence. Its earlier receipts are historical.

## Independently confirmed issues and repairs

1. A fresh raw onset could overtake a completed old farewell before the retained
   speech observer ran. The publication fence now has independent raw offered,
   classified, risk, resolved and ordered-consumer progress watermarks. Every
   transferred prefix and newly offered source sequence starts unclassified.
   Partial/unknown VAD coverage cannot resolve it. Quiet VAD running ahead of
   ASR is insufficient: acknowledgement of the next frame proves the prior
   frame's ordered retention/ASR callbacks completed. An unrelated rejected
   candidate cannot clear a newer raw risk. The existing `.15` weak-risk boundary
   and existing acoustic acceptance policy are unchanged.
2. Missing final text could allow an old farewell to close the call ahead of a
   valid native-audio input. ASR-disabled accepted onset revokes immediately;
   accepted complete ordinary native audio also revokes at its final handoff
   without an ASR-text prerequisite. Known echo remains rejected. Native audio
   completeness/capacity still uses `GemmaAudioInputPolicy.retainedAudioIssue`.
3. Caption checkpoint/memory work held the ingress publication lock. Raw ingress
   and transfer now perform CAS-only state changes. An observed publication claim
   remains revocable until a final CAS inside the bounded queue/call identity
   commit. Raw/typed input between claim and commit prevents all old effects.
   After a farewell commit wins, that old call's gate rejects later admission.
   Persistence occurs outside ingress/queue locks. Checkpointing saves the latest
   matching call record, never a captured old snapshot over newer input/call;
   memory receives only the immutable source identity accepted at that commit.
   A storage failure cannot leave a committed farewell's input gate armed.

No raw callback calls the controller, storage, a decoder, or a model lease. Raw
transfer telemetry records in-memory benchmark configuration, not a preferences
write under the byte-ownership lock. Logical recording readiness remains separate
from old ASR drain, new ASR readiness and next Gemma/encoder admission.

## Echo-verdict lifecycle and retained-byte contract

Applying echo rejection after `AudioTurnCapture.stop()` could strand an unknown
late raw tail behind the corrected hold. A default-false, synchronous
`rejectFinalCandidate(finalText, onset)` hook now evaluates only the existing
already-final overlap/tail policy after current finalization/recovery and before
terminal completion. It is wired only for capture-first follow-up. It performs
no decode, refresh, await, model acquisition or threshold adjustment.

Rejected echo reuses the existing candidate-discard/reset-and-continue branch:
- The same physical recorder, retained borrower, raw producer and logical reader
  stay alive. Ordered late frames remain owned by that capture.
- Existing SmartTurn shadow invalidation, native-pause invalidation, retained PCM
  discard notification, bounded onset tail and segmentation/reset contracts run.
- The finished ASR stream closes before its replacement opens under the same
  model session. This is not a second resident Whisper model.
- Rejected final fields return to pending/empty. Already-final farewell checking
  cannot mistake a retired rejected candidate for the new input's evidence.
- New speech arriving during finalization first encounters the existing endpoint
  drain guard. It remains part of the same complete request; an older echo verdict
  cannot discard it before the current final result is resolved.
- A quiet echo rejection can release the old caption backstop without further
  speech. Cancellation or caller timeout still joins the exact decoder and never
  opens a replacement after the owner has been canceled.

The hook defaults to false for every existing caller. ASR-disabled and missing
final-text native inputs retain ordinary audio admission. The ASR-disabled bridge
retains the existing post-playback boundary rather than replaying an unverifiable
pre-playback overlap. No framing change, gain change or new recognizer/model is
introduced.

Before editing, `AudioTurnCapture.kt` was verified byte-identical to frozen
SmartTurn `0ec040030e52101add4e046b566f241eee23d02d`: Git blob
`9a60cd85fa91cb58cf01067344b22f9b3eb0ab34`, SHA-256
`87413e6279d6705704dad0ce5c2bb08f95e3b237add632fc4d36d6e19173f342`.
Integration must apply only the rearm deltas onto the admitted complete SmartTurn
candidate, never publish local Kotlin-only foundation `06f3311` wholesale.

## Verification and remaining gates

Fresh checks: **109 focused JVM tests**, **45 actual-owner JVM tests**, **20 repeat
runs with zero failures**, **13 Python tests** plus architecture boundaries, and
**143-source Android API compile attempt 020** all pass. Its receipt SHA-256 is
`8dcfb4bf5f6e6a7bffac5d5a3b5bdd8228baa7650ee850a72d8b6d1219cd1436`.
Every current source byte matches its receipt.

Source-bound receipts are in
[receipts/capture-first-rearm-repair-2026-10-09](receipts/capture-first-rearm-repair-2026-10-09).
The focused suite covers final evidence/intent, exact tickets, raw risk, slow
checkpoint and claim-to-effect races. The owner suite executes actual recorder,
session, bridge and capture code with synthetic hardware/native callbacks. It
covers the nine-second byte-exact retained request and the new finalization/late
raw/echo/reset, no-further-input, missing-text, ASR-disabled, timeout and
cancellation cases. Native callback fixtures do not establish acoustic quality.

The final boundary compile includes all 143 actual source files and every changed
production boundary, with cached real Android 14 APIs and Kotlin 2.3.21. Native
model-side fixtures remain explicit; no APK/JNI/model/device execution is claimed.
An unchanged large `AudioTurnCapture` triggered Kotlin backend StackOverflowError
with the default compiler stack. Failed output was preserved; reruns use an
explicit 8 MB compiler stack, with no production-source workaround.

Adversarial fixture failures were retained or summarized in the local evidence:
- An initial test assumed echo rejection would precede the existing late-speech
  endpoint drain. The actual source correctly deferred that endpoint. The repaired
  fixtures separately verify quiet echo rejection during stream close and full
  same-request retention when real speech arrives during final decode.
- An initial cancellation fixture released native finalization before its stop
  coroutine had established cancellation. It now explicitly cancels the structured
  owner before release, and asserts no replacement stream opens.
- Segmented finalization legitimately replaces a sealed ASR stream sequentially;
  the retained-byte fixture checks one active stream at a time and full WAV bytes,
  rather than incorrectly requiring one stream object for the whole capture.

Independent re-review of the final immutable repair, especially liveness and
retained-byte ownership, remains required. Full integrated release JVM/native,
APK/R8, named Android sandbox, real microphone/speaker echo and device/model
latency gates remain pending. No subsecond or physical-device claim is made.
