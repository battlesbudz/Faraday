# Checks only Justin's phone can do

Autopilot adds a check here when an item is CI-green but needs real weights,
real audio, or Fold 6 performance. Keep each check under five minutes.
Justin: reply on the autopilot PR with the check ID and the result (paste the
copied diagnostics/latency record). Autopilot reads PR comments every run.

| ID | Build/commit | What to do | What to send back | Status |
| --- | --- | --- | --- | --- |
| V7-A | 95d8aa67 (V1) | Withdrawn: V1 duplicated existing telemetry and was reverted | — | withdrawn |
| V7-B | Latest green `Android APK` build of `feature/autopilot` (2f239771 or newer; run [44794792874](https://github.com/battlesbudz/Faraday/actions/runs/44794792874)) | With the model already warm, start a voice call on E2B and ask 5 short questions (e.g. "What's two plus two?"). Then do the same on E4B. After each call, tap **Copy diagnostics** on the call screen. Open **Pipeline benchmarks** and tap **Copy whole report** | Both Copy diagnostics texts (TTFT, TTF-SW, the endpointing summary) and the benchmark report (`speech_end_to_endpoint_decision_ms`, `first_piper_text`, `first_piper_pcm`, `speech_end_to_first_answer_playback_ms`) | open |
