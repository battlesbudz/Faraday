# Build 709 false interruption repair

17 September 2026, `audio-pr2`, existing PR #6.

## Phone evidence

Call `e2bdb824-5d7c-4214-ae6c-dd9de339ceb4` successfully generated text-only
Gemma replies. Natural interruption then accepted `is brilliant.` and later
`tracks` from an otherwise echoed sentence. The resulting final transcripts
(`This guy is blue due to Rayleigh'` and a passage about the Earth's atmosphere)
were fed back as user messages. Both speaker checks reported MATCH around 0.85.
This is evidence of failed echo discrimination, not evidence that the owner spoke.
There were no keyword hits responsible for these interruptions.

The natural path already required a nonempty ASR result, but treating any unmatched
token as a real owner word was insufficient. An ASR substitution can look like a
word, and similarity to a learned speaker centroid alone did not reject Piper.

## Changes

- VAD and volume only nominate audio for recognition. They do not pause, duck,
  stop playback or hand off the floor. Empty results, punctuation and known sound
  captions (including cough, sneeze, wind, traffic and yelling) cannot authorize
  natural interruption.
- Compare each complete ASR clause to contiguous playback text using bounded
  edit-distance matching. Near-echo sentences do not yield isolated edge words.
  Apply the same check to final correction text before it reaches Gemma or tools.
- Select up to three seconds of actual synthesized Piper samples in memory,
  resampled to 16 kHz. On an eligible word candidate, use the existing speaker
  extractor to compare candidate windows to both the learned owner and Piper.
  Cache playback embeddings until the reference changes; do not train on them.
- Every candidate window must meet the existing owner threshold (0.65) and exceed
  its playback similarity by at least 0.08. Missing playback embeddings when a
  reference exists fail closed. `PLAYBACK_ECHO` includes ambiguous owner/playback
  matches; it is a rejection policy, not proof of microphone ground truth.
- Preserve single-word eligibility, the 250 ms minimum probe, and both existing
  `stop` and `Hey Jarvis` keyword paths. No multiword command requirement or new
  silence/stability delay is introduced.
- Log owner and playback scores, the required margin, rejected speaker decisions,
  and the recognized words that actually authorized a natural stop.

No new models or dependencies are added. The playback reference is bounded,
in-memory only, and uses the already installed speaker embedding extractor.

## Verification and limits

111 focused JVM tests pass, including the reported `tracks`/Rayleigh transcripts,
sound captions with VAD true, playback rejection despite a high owner score,
preserved single-word interruptions, final echo exclusion and keyword behavior.
Android release assembly and packaging run in CI for the published replacement.

Phone acceptance remains required: leave Jarvis speaking without talking, then
try coughs/background noise, then separate short words such as No, Yes and I,
and finally Stop/Hey Jarvis. Capture the new score evidence if any false stop or
missed owner word occurs. The 0.08 owner/playback margin is a conservative initial
policy and has not yet been measured on the phone. It can reject an ambiguous
mixed owner/Piper fragment; it must not be represented as perfect speaker ID.
ASR can hallucinate words from noise, so recognition text is necessary but is not
by itself proof that a physical word was spoken. The code prohibits acoustic-only
stops; acoustic classification accuracy still requires device evidence.

## Build 710 follow-up: compare the correct playback interval

Call `84dfae4e-b22b-4efd-8cce-b1c54f645499` rejected `appears blue` and
`The suggestion` as playback echo, but accepted a 600 ms `And she's` candidate
roughly 12 seconds into the first answer. Owner similarity was 0.766, playback
similarity 0.443. Its final text, `And she's scattered the short.`, became the
next user prompt. Without the microphone recording/user confirmation, those
scores alone cannot establish whether the candidate was actual owner speech.

Code inspection found the negative reference always used the first three seconds
of each passage, regardless of current playback. Short-fragment speaker
comparisons were therefore being made against different speech content. This is
a concrete reference-selection defect; fixing it does not itself prove the
remaining acoustic failure is solved.

The follow-up stores bounded playback-head checkpoints on the same monotonic clock
as microphone capture. Each recognized candidate carries its capture timestamp
into the speaker check. The selected negative window ends at the observed head
at that timestamp, includes a 200 ms earlier allowance for route/reverberation,
and spans at most three seconds. It never includes queued future audio. Existing
PCM arrays for at most three passages are referenced in memory; no microphone
recordings or additional model files are persisted. Cross-passage windows are
supported. Missing samples or a playback-position checkpoint older than 250 ms
produce an unavailable reference and fail closed rather than using an old opening.

Diagnostics include `referenceSource=playback_head`, the frame interval,
`capturePositionAgeMs`, and `referenceAudioMs`. The existing 0.65 owner threshold,
0.08 owner-versus-playback margin, single-word eligibility and keyword paths are
unchanged. Five regression tests cover late-passage selection, delayed decoding,
passage boundaries, stale/missing checkpoints and clock regression. Device echo
rejection and owner-word acceptance still require phone verification.
