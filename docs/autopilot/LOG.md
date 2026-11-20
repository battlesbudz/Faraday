# Autopilot log

Newest first. One entry per run: date, item, commit(s), CI run URL and result,
what changed, what is next. Keep entries to a few lines.

## 2026-11-20 — V1 reverted as a duplicate (manual session)

- Justin pointed out per-turn latency breakdowns already exist. Confirmed:
  `ReplyMetrics` (TTFT, TTF-SW), `LiveComparison` (speech end → final
  transcript / first token / first audible frame), `PipelineBenchmark` spans
  (`first_piper_text`, `first_piper_pcm`, playback). V1 only re-bundled them.
- Reverted 95d8aa67 and 0e646a8c (2fe79dc1, b56f07dd). Kept the release-journey
  flake fix 10077c54.
- Process fix: RUNBOOK step 3.2 now requires an existing-work search before any
  item; V2–V7 are `audit` until V0 checks them against existing code.
- Next: V0.

## 2026-11-20 — Review fix + CI flake (manual session)

- 0e646a8c: `FINAL_TRANSCRIPT` is marked only when ASR already produced a transcript;
  direct-audio and fallback-transcription turns leave it null (Devin Review finding).
- 10077c54: `test30_phoneCallOverlaysUnifiedChatAndKeepsVoiceNoteMic` failed on
  `30-phone-normal` in main run 44785186686 with `StaleObjectException` reading the
  live caption; the test now matches the caption in the selector.
- Next: confirm the 10077c54 runs are green before starting V2.

## 2026-11-20 — V1 latency budget per voice turn (status: ci)

- Commit: 95d8aa67. CI: [push run 44788050203](https://github.com/battlesbudz/Faraday/actions/runs/44788050203),
  [PR run 44788040453](https://github.com/battlesbudz/Faraday/actions/runs/44788040453) — in progress at log time.
- Acceptance checks: (1) fake-clock JVM test records all six stages once with
  correct deltas; (2) repeated marks are ignored; (3) failure case: missing
  stages and the total stay null, never 0, and reversed deltas are null; (4)
  Piper `first_piper_text`/`first_piper_pcm` edges map to their stages and one
  `Voice latency budget` record is published per turn; (5) existing behaviour
  intact: benchmark stage marks, TTF-SW and `speech_end_to_first_answer_playback_ms`
  unchanged, recorded-audio receipt validation unchanged; (6) the recorded-speech
  job prints the budget table (speech end → final transcript only on the host).
- Local: `python3 scripts/dev.py check` passed; no Android SDK on the autopilot
  box, so Kotlin compile/JVM tests rely on CI.
- Environment note: the Devin git proxy returned 403; fetch/push worked via
  `git -c credential.helper='!gh auth git-credential'` against github.com with
  `GIT_CONFIG_GLOBAL=/dev/null`.
- Next: check both runs; on green mark V1 `device` (V7-A) and start V2.
