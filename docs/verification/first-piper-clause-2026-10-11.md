# First meaningful Piper clause, October 11, 2026

## Requested behavior and ownership

The first useful part of a spoken answer may reach Piper before its complete
sentence has arrived. Subsequent passages keep the existing longer-context
320-character target and 640-character native-work bound. This is the requested
behavior, not a claim to reproduce an independently verified Aura implementation.
No verified Aura source was available for an exact comparison.

`SpeechTextBoundaries` owns the shared, pure text-boundary policy.
`VoiceRepetitionGuard` remains the owner of checked answer publication, duplicate
references and repair accounting. `PiperTextStream` remains the owner of native
passage boundaries. Neither receives model, microphone, playback or session
ownership.

## Opening boundary contract

- The first accepted opening can end at a real comma, semicolon or colon,
  followed by observed whitespace, with at least six words and 32 preceding
  characters. No punctuation or answer text is manufactured.
- A conservative English heuristic requires a recognized finite predicate and
  holds dependent introductions, unfinished connectors, explicit negation,
  relative-clause wording, quoted/parenthesized fragments and control/markup
  shapes. Ambiguous cases retain the ordinary full-sentence path. This is a
  deterministic heuristic, not grammatical parsing, semantic validation or a
  guarantee against a later model correction.
- The repetition guard runs the existing publication and duplicate checks before
  releasing the opening. A rejected opening suppresses the rest of that sentence;
  the next accepted sentence can still use the first-clause slot. The complete
  first sentence remains buffered for its full policy check, duplicate references
  and sentence-based repair budget. The already-published prefix is not emitted
  twice and remains a duplicate reference if its suffix is cancelled or rejected.
- Only the first accepted output receives the clause policy. Later sentences
  continue through the existing guard, and Piper groups them to its existing
  target. Final flush, full-reply mode, formatting-preserving text, runaway-loop
  cancellation and emergency oversized-token bounds remain supported.
- Publication and Piper use the same abbreviation, initial and split-decimal
  handling. Whitespace lookahead at a clause boundary prevents a callback split
  from turning a partial token into an opening.
- There is no 750-ms deadline: accepted first boundaries release immediately;
  elapsed time cannot make an unfinished fragment eligible.

## Observable acceptance checks

All new checks below are JVM logic tests in `FirstPiperClauseTest`:

1. A meaningful comma, semicolon or colon opening crosses both publication and
   Piper gates while the sentence is still incomplete.
2. Callback splits of 1, 2, 3, 7, 31 and whole-response character lengths preserve
   every word; later passages still reach the 320-character target without
   exceeding 640 characters.
3. Unfinished, dependent, negated, relative, non-predicate, parenthesized and tool
   fragments remain buffered. Large elapsed time cannot force them out.
4. `Dr. A. Smith` and token-split `3.14` stay intact through both gates.
5. Policy-rejected and duplicate first candidates cannot leak their suffixes;
   the next valid candidate still streams early. Completed repeated sentences
   remain suppressed.
6. Split hidden-channel and tool markers are tested through the existing
   `GemmaSessionText` filter. This does not move protocol filtering into Piper.
7. Formatting-preserving text and explicit full-reply mode keep their contracts.
8. Repair still counts complete sentences rather than clause callbacks. Barge-in
   cancellation cleans the generation owner, discards an unfinished suffix and
   rejects late callbacks. Interrupted prefixes remain repetition references.
9. Cancellation thrown by a completed-sentence callback keeps prefix cleanup safe.
10. First text and simulated first rendered audio remain independent diagnostics;
    terminal turns cannot revise first-audio timing.

## Verification performed

The exact four source/test hashes below compiled with Kotlin 2.3.21, JVM target 17,
and ran on the available JDK 21 host with JUnit 4.13.2 and coroutines 1.9.0. No
production source was replaced by a test stub. All **90 tests passed** across:

- `FirstPiperClauseTest` (14 new tests)
- `PiperTextStreamTest`, `VoiceRepetitionGuardTest`, `VoiceRepetitionRepairTest`
- `VoiceTurnTraceTest`, `SpeechDeliveryLedgerTest`, `SynthesizedSpeechPcmTest`
- `PlaybackClockTest`, `PlaybackDrainTest`, `PlaybackBufferPolicyTest`
- `NativeAudioQueueTest`, `CallModelSlotTest`

The native-queue and model-slot cases exercise existing backpressure,
cancellation/release ordering and model-lease ownership with host fakes. The
architecture dependency check and `git diff --check` also passed.

| File | SHA-256 |
| --- | --- |
| `SpeechTextBoundaries.kt` | `a86c80b21c8c73384572007ee681146a935363a0537de512d550ca42c4c46dbe` |
| `PiperTextStream.kt` | `872e8b06419c3dcda9fed2c71d812d18c0354f248b9303c86fba984e30814957` |
| `VoiceRepetitionGuard.kt` | `1dac440020c055f5f2913847e512306eb97805fd67ad6126ca4468362a1c4209` |
| `FirstPiperClauseTest.kt` | `e1cd9aff28e1d08e2534c4bac3fba6edf7df05fa3364075f68a3785fde618940` |

## Still required

The host lacks Gradle, Android SDK, adb/emulator and release signing configuration.
The focused compilation and tests are not the complete release JVM/Android gate
and do not establish a verified APK. Exact integrated-revision hosted release,
minification/native ABI and Android sandbox checks remain required.

Piper model synthesis, on-device first sound, natural pauses/prosody, underruns,
physical barge-in, microphone/Bluetooth behavior, thermal behavior and performance
remain unmeasured. No latency reduction in milliseconds is claimed. First actual
answer-audio diagnostics must remain tied to AudioTrack head progress beyond the
first non-silent PCM frame; text release, native submission and synthesized PCM
availability are not substitutes. The existing playback callback follows that
condition and is not changed by this text-boundary patch. AudioTrack head progress
is a playback proxy, not an acoustic microphone measurement.

## Integrated Piper API compilation

A subsequent focused compile included the complete production `PiperVoiceOutput`
and its 30-file production dependency closure, including the Android route,
playback, synthesis, model-session and new boundary classes. It compiled against
actual Android-all 14 and Sherpa 1.13.7 jars with no replacement source stubs.
The compiled `PiperVoiceOutput.kt` SHA-256 was
`2b982cb7ee0cf4319ecbc25367b0e7f63eff1b1e4ae688d2d71209cc26964ad4`.
All source hashes remained unchanged during compilation. The only warning was an
existing unnecessary safe call on the non-null `engineLease` branch.

This checks the real Piper API and Kotlin integration, including the actual
first-passage length used by diagnostics. It does not execute Android audio,
Sherpa JNI or model weights, and does not replace release/minification checks.

Existing timing edges are distinct: native `firstCallbackMs` / `firstRawTextMs`
come from native callbacks; guarded `ConversationReply.postToken` marks
`first_reply_text_ready` synchronously before posting to the UI; Piper starts its
own first-text clock when its collector receives checked text; first audio still
requires actual playback-head progress. Unguarded fallback paths can also use the
generic text-ready event, so an optional separately named `first_guard_release`
benchmark mark at the guard's emit callback would disambiguate that edge without
changing guard behavior. This optional mark is not part of this patch.

The integrated production PiperVoiceOutput and its 30-file dependency closure also
compile against actual Android-all14 and Sherpa1.13.7 APIs without source stubs.
`first_guard_release` records the first nonempty checked voice fragment before
queued delivery-fence admission; native callback, Piper receipt/PCM and actual
playback-head events retain distinct origins. It is not an audible-onset claim.
