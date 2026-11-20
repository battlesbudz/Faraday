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
| V0 | Audit G1 against existing code: for V2–V7, search the voice telemetry (`ReplyMetrics` TTFT/TTF-SW, `PipelineBenchmark` spans incl. `first_piper_text`/`first_piper_pcm`, `LiveComparison` reports, `VoiceTurnTrace`, the `Recorded speech recognition` CI job, Smart Turn/endpoint code, barge-in code). For each item write what already exists (file + metric names) and mark it `dropped`, rewrite it to only the missing part, or set it back to `todo` | Each V2–V7 row cites the existing code it checked; no item left in `audit` | todo |
| V1 | ~~One latency budget per voice turn~~ | Dropped: duplicated existing TTFT/TTF-SW, `LiveComparison` and `PipelineBenchmark` stage timings. Built in 95d8aa67, reverted in b56f07dd | dropped |
| V2 | CI latency replay: run the recorded-speech fixture through the production turn path on the CI host and fail on a >20% regression of the stages CI can measure | New CI step with stored baseline; fixture timings in the job summary | audit |
| V3 | Cut dead waits at end of turn: audit the 1500 ms uncertain / 3000+ ms hesitation windows and Smart Turn fallback; shorten where the endpoint model is confident | JVM tests: confident complete question ends ≤ 350 ms after silence; hesitation still waits; resumed speech still restarts | audit |
| V4 | Start speech on the first complete clause instead of waiting for a full passage, while keeping Justin's 320-char passage policy for the rest | JVM tests on passage planner; needs Justin's OK before changing the opening policy (add a device check) | audit |
| V5 | Bring `feature/lfm3-omni-u8` up to date with `main` and green in CI on its own branch; list what it still needs for a duplex call | CI green on the Omni branch; gap list written into this backlog | audit |
| V6 | Barge-in timing: log confirmed-speech → playback-stop; target ≤ 300 ms | JVM test of stop path with fake audio; device check for real echo | audit |
| V7 | Device run on the Fold 6 (E2B and E4B) reporting the existing per-reply TTFT/TTF-SW and pipeline benchmark stage timings | device | audit |

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
