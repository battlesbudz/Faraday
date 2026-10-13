# Smart Turn on the Whisper text path

## Observable contract

With the pinned optional Smart Turn model installed and enabled, ordinary
Whisper-to-text voice capture (CPU or optional QNN with CPU fallback) may use a
fresh COMPLETE/CONTINUE verdict over retained PCM. This uses the existing call
worker, eight-request turn budget, two-request pause budget and 250 ms deadline.
It does not change the model, threshold, inference window or cancellation owner.
The existing preference disables both paths. Comparison trials, sealed correction
captures and playback-tail/unknown-onset guards remain excluded as before.

Smart Turn observes speech and scores an eligible pause; it does not finish ASR.
An accepted early Whisper endpoint always calls authoritative final recognition.
The final result, including committed segments, remains the text request. Only
eligible unsegmented Gemma-direct capture may skip display-only final captions.
The benchmark configuration `smart_turn_capture_route` distinguishes `whisper_text`
from `gemma_direct_audio`; the existing observer mode identifier remains stable.

Whisper segmentation does not break PCM continuity or disable the model verdict.
Soft boundaries retain all committed/final words. Hard boundaries retain the
existing overlap validation, uncertainty issue and complete-PCM recovery. A
conflicting final hypothesis still honors the existing conservative final-text
margin before recovery. This change does not make an uncertain seam authoritative.

The collector still checks call/revision freshness, raw possible speech, strong
speech, quiet-speech corroboration, exact model/raw coverage, recording completeness,
hardware backlog and finalization drain. Short/weak speech cannot spend COMPLETE.
CONTINUE keeps its classified-quiet cap. Missing, busy, thermal-blocked or expired
work falls back without adding a mandatory wait. Speech arriving during final
recognition reopens the same logical turn and retains the new ASR segment and PCM.
Recovery now repeats that drain check: speech arriving during a full-recording
retry also remains in the same request, rather than arriving after a stale seal.
Explicit stop and cancellation keep their prior authority and cleanup ordering.

## Changed owners

- `VoiceTurnPreparation` creates the optional observer for ordinary Whisper as
  well as Gemma-direct capture, with unchanged call/job ownership fences.
- `VoiceTurnCaptureFactory` permits Smart Turn on the text route while retaining
  the comparison and playback-tail gate. Native speculation and caption-retirement
  gates still require Gemma-direct mode.
- `AudioTurnCapture` permits PCM verdicts after Whisper segmentation, and limits
  final-caption skipping to unsegmented Gemma-direct capture.
- `VoiceInputSettings` describes both supported call paths and required final
  Whisper text. Preference keys and native interfaces are unchanged.

## Verification

The [source-bound host receipt](evidence/smart-turn-whisper-2026-10-12/host-receipt.json)
records actual production Kotlin files, verified dependency hashes and commands.
The [JVM result](evidence/smart-turn-whisper-2026-10-12/junit.log) passed 218 tests
covering capture, Smart Turn workers/observer, native pause, capture-first transfer,
final-verdict rejection, ordinary capture and transcript accumulation. New cases
exercise early final-text preservation, soft/hard segmentation and recovery,
CONTINUE/resumption, deadline/missing/blocked fallback, stale call/raw revision,
audio arriving during finalization or full-recording recovery, unfinished final text, short speech and
explicit stop. Existing playback/quiet/cancellation cases now cover both paths.
The combined closure also includes the new preparation/Smart Turn overlap metrics
and benchmark JSON/CSV round-trip suites.
No model inference or acoustic truth is supplied by these synthetic fixtures.

`python3 scripts/dev.py check` passed the architecture guard, 124 helper tests
and 101 verification-helper tests. Its native frontend checks use weight-free
fixtures; it is not the full native release gate.

One newly added hard-seam test initially assumed that COMPLETE bypassed the
existing final-text uncertainty margin. Capture correctly remained open. The
[failed result](evidence/smart-turn-whisper-2026-10-12/junit-hard-seam-initial-failure.log)
is retained. The scenario now explicitly requires that guard before checking
full-PCM recovery. No production guard, deadline or acceptance threshold changed
to obtain the passing result.

Independent review then identified a genuine recovery/drain ordering gap. The
new continuation-during-recovery regression failed before repair with an accepted
endpoint while hardware audio was pending. The
[failure](evidence/smart-turn-whisper-2026-10-12/junit-recovery-drain-before-fix.log)
is retained. Repeating the existing drain predicate after recovery fixes that
gap without changing thresholds, ownership or uncertainty policy. The final
regression requires the continued words and every PCM byte in the same request.

Not run locally: complete release Gradle/R8/APK build, Android runtime composition,
Compose rendering, native release gates or Android sandbox. The environment has
JDK but no Gradle/SDK/emulator/signing setup. Exact-revision hosted verification
is required before an APK can be called verified. Real Whisper/Smart Turn
accuracy, physical audio/interruption/echo and phone latency remain separate
device acceptance checks; synthetic 65 ms inference is not a phone measurement.

## Existing Gemma-direct caption coupling is unchanged

This extension does not certify complete isolation of display captions from
Gemma-direct endpoint policy. Existing partial text updates `turnEnd`; quiet
recognition can corroborate acoustic evidence; completed caption cues can guide
eligible native pause timing; and required final caption text can defer a shorter
endpoint. Those pre-existing branches remain in `AudioTurnCapture`. Smart COMPLETE
still skips captions only on its already-eligible native path. Removing the
remaining caption/control coupling is a separate behavior change and is not
claimed by this Whisper Smart Turn implementation.
