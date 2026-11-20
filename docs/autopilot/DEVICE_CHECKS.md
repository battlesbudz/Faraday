# Checks only Justin's phone can do

Autopilot adds a check here when an item is CI-green but needs real weights,
real audio, or Fold 6 performance. Keep each check under five minutes.
Justin: reply on the autopilot PR with the check ID and the result (paste the
copied diagnostics/latency record). Autopilot reads PR comments every run.

| ID | Build/commit | What to do | What to send back | Status |
| --- | --- | --- | --- | --- |
| V7-A | 95d8aa67 (V1) | Install the build, start a voice call, ask 3 short questions on warm E2B (then E4B), tap Copy Diagnostics | The three `Voice latency budget turn=…` lines | waiting for CI |
