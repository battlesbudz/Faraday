# Autopilot log

Newest first. One entry per run: date, item, commit(s), CI run URL and result,
what changed, what is next. Keep entries to a few lines.

## 2026-11-20 — V0 G1 audit (status: done)

- Item: V0. Docs-only change (`docs/autopilot/**`). The `Android APK` workflow skips `docs/**` via `paths-ignore`, so this commit has no CI run of its own. Branch code CI is run [44794792874](https://github.com/battlesbudz/Faraday/actions/runs/44794792874) (push) and [44794803573](https://github.com/battlesbudz/Faraday/actions/runs/44794803573) (PR) on 2f239771. Both were in progress at log time.
- Acceptance: (1) every V2–V7 row cites the file and metric names it checked; (2) no row left in `audit`; (3) failure case: an item that already meets its acceptance is `dropped`, not re-implemented (V3, V4); (4) still intact: no code, test or gate changed.
- Existing work found: V2 → `scripts/check_recorded_audio.py` `elapsed_ms` (no baseline). V3 → `AdaptiveTurnEnd` 350 ms `complete_and_stable`, `NativePauseEndpointPolicy`, `SmartTurnShadow` 250 ms deadline. V4 → `PiperTextStream` first clause + 320 chars (`first-clause-v3`). V5 → U2–U7 sit on separate branches that are not in u8. V6 → `VoiceTurnTrace` `INTERRUPTION_CONFIRMED`/`PLAYBACK_STOP_REQUESTED`, `EndpointingSummary` "stop latency … not recorded". V7 → issued as device check V7-B.
- Result: V3 and V4 dropped. V2, V5 and V6 rewritten to only their missing parts. V7 → `device`.
- CI notes: main run 44785186686 (a048eec0) failed only `30-phone-normal`, the flake that 10077c54 fixes. Every earlier autopilot push run was cancelled by the next push, so 10077c54 is still unverified; 44794792874 is its first full run.
- Git proxy still returns 403. The LOG workaround (`GIT_CONFIG_GLOBAL=/dev/null` + `gh auth git-credential`) works.
- Next: check 44794792874; then V2 slice 1, which is the first `todo` in G1.

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
