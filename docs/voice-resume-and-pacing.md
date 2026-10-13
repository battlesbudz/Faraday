# Voice resume and pacing follow-up

The Moonshine phone run recognized the user's opening words and corrections, with first partials in 274–639 ms. This is an encouraging device test, not a scored accuracy benchmark. Moonshine is now the default when no ASR preference exists; saved selections and Zipformer comparison records remain intact.

## Resume

History resumption captures an immutable selected record, disables repeat taps, waits for cancelled voice/conversation jobs to retire their native resources, saves any lingering active call, then creates a new session containing the historical transcript and task status. The original record is retained. The screen displays the restored transcript and automatically arms listening. No unfinished tool is executed by resumption. Recoverable errors appear in the history screen and diagnostics; cancellation still propagates.

The supplied logs contain no crash stack for resumption. The changes address reachable uncaught selection/lifecycle exceptions and overlapping teardown; the actual device crash still requires an end/history/resume test.

## Speech pacing

The supplied long-answer run spent 136,667 ms synthesizing 102,296 ms of PCM (RTF 1.336), with repeated playback underruns. A two-second queue cannot absorb a sustained 34-second deficit. Larger phrases or another concurrent CPU engine cannot be assumed to improve this throughput.

Playback now selects a stable speed once per answer using the first phrase's measured synthesis/audio duration: `clamp(0.9 / RTF, 0.85, 1.0)`. Android PlaybackParams stretches the existing audio with pitch 1.0; the synthesis settings and single native owner are unchanged. Unsupported routes fall back to normal playback. There are no mid-answer speed changes. Existing bounded startup headroom remains, avoiding a large new initial delay.

At 0.85 speed, 102 seconds of PCM lasts approximately 120 seconds. With unchanged synthesis speed this would reduce, but not eliminate, the long-answer deficit. Actual phone performance and perceived voice quality must be measured. The first phrase is only a predictor of subsequent synthesis cost.

Diagnostics now include playback speed and estimated PCM supply-gap milliseconds, calculated from the elapsed time between writes minus remaining queued playback. This excludes initial buffering and terminal drain; it is an estimate, not acoustic loopback measurement. Raw synthesis RTF remains comparable with older runs.

Android reference: https://developer.android.com/reference/android/media/PlaybackParams

## Validation

44 focused host JVM tests cover resume cleanup ordering, duplicate attempts, failure/retry, cancellation, context preservation, pacing bounds, ASR preference persistence, and existing capture/inactivity behavior. Android CI additionally compiles both APK variants, runs unit tests, and verifies isolated ASR native library packaging. Device playback and history resumption remain user acceptance checks.

The previous build's 3-second turn pause and 20-second call inactivity policy are retained. The user's Moonshine log was from the older 1.2-second endpoint configuration.

## Phone feedback: cap time stretching at 10%

The 0.85 pace sounded robotic in the user's device test. The lower bound is now 0.90 (at most 10% slower), still with unchanged pitch and a stable speed per answer. The earlier 0.85 calculations above describe the preceding build. This is a listening-quality preference, not a synthesis-speed improvement.

Final-transcript-to-first-text timing now also records replies delivered exclusively through the completion callback. Previously those guarded/tool replies showed `unavailable` despite producing text and speech. A nonblank streamed token still wins if it arrives first.
