# Continuous reply listening and quiet speech

> Historical implementation/evidence below. For current selected voices, ASR admission, gain, acknowledgement and interruption behavior, use [Current voice pipeline and remaining acceptance](voice-pipeline-current.md). Later user decisions supersede these experiments.

**Current behavior:** keyword interruption supersedes the continuous-ASR experiment
below. See [keyword interruption](voice-keyword-interruption.md).

This change follows Fold 6 reports of low-level speech missing recognition and replies
repeatedly stopping despite queued PCM and no supply gaps. Those logs support, but do
not alone prove, false interruption probes. The old code explicitly paused playback
for a 700 ms echo probe and could repeat that after a one-second cooldown.

## Playback and interruption

The pause-probe API is removed. Reply capture now uses AEC when Android supplies it,
plus continuous Moonshine recognition. Both while preparing and while playing a reply,
a candidate needs recent acoustic evidence and recognized words stable for 300 ms.
There is no acoustic-only cancellation shortcut. A conservative English prefix check
requires a control, request, question, or correction; incidental phrases such as
“It is” and “We should know” do not qualify. During playback the words must also
differ from the recent spoken reply. The gate inspects clauses throughout the bounded
mixed transcript, removing contiguous echoed phrases before looking for a request.
It keeps that reference during playback underruns and preserves the stability timer
when a request grows or more echo is appended. This fixes the reported Facebook
request being hidden by the trailing story words. These text rules can miss indirect interruptions;
they are not a trained intent classifier.
Known reply words and isolated recognition substitutions are rejected. A short stop
command can confirm when it is not itself part of Jarvis's recent speech. This is
conservative text-based echo rejection, not a claim of complete acoustic echo cancellation.
Repeating Jarvis's exact words may not interrupt it; device testing must check this
tradeoff and real interruption accuracy. The explicit stop control remains available.

A probe recognizer collects at least 500 ms of audio per pass. After a costly pass,
a wall-clock recovery interval (its measured cost, bounded to 250–1500 ms) leaves
CPU time for synthesis instead of immediately decoding queued microphone backlog.
All PCM is retained in order, with an eight-second bound; exceeding it reports a
listener failure rather than silently dropping speech or cancelling the valid reply.
`barge_asr_budget` records audio duration, work time and recovery time. This can add
interruption latency and requires on-device comparison; it is not a throughput claim.
The recognizer resets after ten seconds of audio to bound context. It closes before the lazily
loaded correction recognizer starts, so only one Moonshine model is resident in this
listener. Six seconds of in-memory pre-roll retain the user's onset during recognition.
No speculative recognition executes tools. Native ownership and the existing confirmed
turn/tool guards remain in place. Extra recognition CPU can affect synthesis throughput;
phone tests must check underruns and long-answer smoothness as well as barge-in accuracy.

## Quiet speech

A bounded gain stage precedes VAD, ASR and retained Gemma audio. It targets PCM RMS 900,
caps gain at 8x for normal listening and 3x for interruption listening, preserves
digital silence, and immediately reduces gain for loud peaks.
It does not decide whether noise is speech. Moonshine's streaming VAD threshold is
explicitly 0.3. Silero's strong speech confirmation remains 0.5 over three frames.
Weak scores of at least 0.15 require a recent, stable transcript containing at least
two words; scores below that or stale words do not qualify. This permits corroborated
whispers without treating quiet microphone activity by itself as a command.
These are initial tunings requiring recordings on the Fold 6, not a universal whisper guarantee.

## Cancellation and diagnostics

Unexpected cancellation of an armed call now joins native/microphone cleanup before
rearming, with a shared maximum of two audio recovery attempts. Intentional stop
(disarm first), process shutdown, and saved-call replacement do not use that restart.
The original cancellation cause in the supplied excerpt remains undetermined. New
logs include phase, cause and recovery attempt; saved-call replacement labels its
cancellation. Retention grows to 64 important and 100 recent events so microphone
reopen messages are less likely to erase the cause and the reply's interruption events.

## Phone checks

1. Whisper a question after the ready cue, then repeat at normal volume.
2. Ask for a paragraph and remain silent: speech should continue without periodic probe pauses.
3. Interrupt with “stop”, then with a different request such as “open YouTube”.
4. Stop the session deliberately and verify it stays stopped; resume a saved call.
5. Rustle clothing while Jarvis is preparing a reply and while it speaks: the reply
   should continue. Then deliberately say “actually, open settings” in both phases.
6. Copy call diagnostics if any whisper, false interruption, unexpected stop or gap remains.
   `barge_candidate_rejected` records why words did not confirm an interruption.
   The header includes the running APK version; each new call records its creating build
   separately so a restored older call is distinguishable.

Host tests exercise repeated echo rejection, fresh/changed interruption words,
recognizer handoff without overlapping native owners, quiet amplification, loud-peak
protection, and weak-VAD corroboration. Android CI verifies compilation and packaging;
it cannot substitute for acoustic tests on the phone.
