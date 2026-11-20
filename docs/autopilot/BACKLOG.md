# Autopilot backlog

Ordered by priority. Autopilot takes the first item whose status is `todo` and
whose blockers are clear. Each item lists its acceptance check; "device" means
it can only be closed by Justin on the Fold 6 (see `DEVICE_CHECKS.md`).
Status: `todo`, `doing`, `ci` (pushed, waiting on CI), `device` (CI green,
waiting on Justin), `done`, `blocked` (say why), `dropped` (duplicate or
not needed; say why), `audit` (must be checked against existing code by V0
before anyone works on it).

Autopilot may add, split, reorder within a goal, or refine items as it learns.
Only Justin changes goal order.

## G1 — Sub-second voice

| ID | Item | Acceptance | Status |
| --- | --- | --- | --- |
| V0 | Audit G1 against existing code (V2–V7) | Done 2026-11-20: every V2–V7 row below cites the code it checked; none left in `audit` | done |
| V1 | ~~One latency budget per voice turn~~ | Dropped: duplicated existing TTFT/TTF-SW, `LiveComparison` and `PipelineBenchmark` stage timings. Built in 95d8aa67, reverted in b56f07dd | dropped |
| V2 | CI latency trend for the recorded-speech fixture. Exists: `Recorded speech recognition` job runs `scripts/check_recorded_audio.py` (pinned Whisper on Linux CPU, WER/first/last-word gates, per-check `elapsed_ms` in `recorded-audio/report.json`). Missing: no stored baseline, no comparison, timings not in the job summary. It is not the Android turn path, and shared-runner timing noise is unmeasured, so a hard >20% gate would flake. Slice 1: write `elapsed_ms` + stored baseline delta to `$GITHUB_STEP_SUMMARY`, warning only. Slice 2: make it a gate once ≥5 runs show the noise is well under 20% | `scripts/test_recorded_audio.py` covers baseline compare (faster, slower, missing baseline); summary table in the job; existing quality gates unchanged | todo |
| V3 | ~~Cut dead waits at end of turn~~ | Dropped: already met. `AdaptiveTurnEnd` ends a complete question that has been stable for 300 ms at 350 ms (`complete_and_stable`; `AdaptiveTurnEndTest`). Hesitation stays 3500 ms, unfinished 1800 ms, uncertain 1500 ms. `NativePauseEndpointPolicy` cuts settling/no-caption to 650 ms only on clean native pauses (`NativePauseEndpointPolicyTest`). Smart Turn has a 250 ms request deadline (`SmartTurnShadow`, `SmartTurnCaptureObserver`) with a 300 ms capture decision deadline. Reopen only if V7 device data shows `speech_end_to_endpoint_decision_ms` dominating (e.g. 1100 ms `transcript_settling`) | dropped |
| V4 | ~~Start speech on the first complete clause~~ | Dropped: already implemented. `PiperTextStream` releases the first clause (≥4 words/24 chars, `openingClauseEnd`) and then uses `TARGET_CHARS = 320`/`MAX_CHARS = 640`. Logged as `piper_text_policy version=first-clause-v3` (`PiperVoiceOutput`). Tests: `FirstPiperClauseTest`, `PiperTextStreamTest` | dropped |
| V5 | Integrate the Omni stack. Exists: `feature/lfm3-omni-u8` = U0 (LiteRT 2.3.0 beside LiteRT-LM) + U1 (multi-file bundles) + U8 (diagnostics, `docs/verification/lfm3-omni-device-proof.md`), last CI green on dce0853e (run 43817066973), 1 commit behind `main` (a048eec0). Missing: U2–U7 are each a single commit rebased separately onto b651022d (`-u2` OmniRuntime core, `-u3` audio input + test45 fix, `-u4` speech output, `-u5` call integration, `-u6` duplex barge-in gate, `-u7` tool protocol). None of them are in u8, so `OmniRuntime` on u8 is still the U0 stub with no callers, and the `recordOmni*` diagnostics are never called. Slice 1: merge `main` and U2–U7 into `feature/lfm3-omni-u8` in order, resolve conflicts, get CI green. Slice 2: write the duplex gap list | CI green on u8 with U2–U7 merged; gap list written here | todo |
| V6 | Barge-in stop latency, missing parts only. Exists: `BargeInGate(stableMs = 300)`. `ReplyVoiceCapture.confirm()` marks `VoiceTurnTrace` `INTERRUPTION_CONFIRMED` and `PLAYBACK_STOP_REQUESTED` at the same moment, then calls `stopSpeaking()`. `EndpointingSummary` records `playedMsBeforeStop` but prints "stop latency after speech start: not recorded". Missing: the interruption speech-onset edge and the edge when playback actually stops (audio track halted), so there is no onset→stop number | JVM test with fake clock/audio: onset, confirm and halt edges recorded; `EndpointingSummary` prints the onset→halt ms; missing edges stay "not recorded", never 0; existing barge-in tests unchanged. Device check for real echo | todo |
| V7 | Device run on the Fold 6 (E2B and E4B) reporting the existing per-reply TTFT/TTF-SW and pipeline benchmark stage timings | Device check V7-B | device |

## G2 — Snake one-shot

| ID | Item | Acceptance | Status |
| --- | --- | --- | --- |
| S1 | Automated snake eval: a script that takes generated game source, builds it, and scores the playable checklist from GOAL.md G2 | Python/JVM tests with a known-good and a known-broken snake | todo |
| S2 | Run the eval against the current harness with real E4B on a CI host (LiteRT-LM CPU) if feasible; otherwise record why and fall back to device | Baseline pass rate recorded in LOG.md | todo |
| S3 | Harness improvements driven by S2 failures (templates, plan-then-code, compile-error repair loop) | Pass rate improves; no regression in existing coding-workspace tests | todo |

## G3 — Phone agent

| ID | Item | Acceptance | Status |
| --- | --- | --- | --- |
| P1 | Inventory current accessibility/virtual-display/screenshot/tap code and MCP exposure (MCP runtime dispatch is currently disabled) and write the gap list here | Gap list committed | todo |
| P2 | Hidden virtual display: launch a target app on an internal display and capture screenshots from it | Emulator journey in the release sandbox | todo |
| P3 | Expose tap/type/screenshot/launch as Android MCP tools with validation and approvals | JVM validation tests + emulator journey | todo |

## G4 — Agent's own computer

| ID | Item | Acceptance | Status |
| --- | --- | --- | --- |
| C1 | Inventory the Linux sandbox, terminal and browser; write the gap list here | Gap list committed | todo |
| C2 | Agent-driven test run inside the sandbox with output returned to the agent | Emulator journey | todo |
| C3 | Agent-driven browser inside the sandbox that Justin can also open | Emulator journey + device check | todo |
