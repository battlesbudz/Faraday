# Historical Piper voice profile comparison

> Retired on 16 September 2026. These controls, runners and profiles have been removed. Do not use the instructions below for the current app. See [current settings and diagnostics](current-diagnostics.md).

Open **Voice and response speed**. Piper Northern English Male is the only voice.
Choose 2/4 threads, 1.0/0.9/0.85x playback, and 40/60/90/160/320-character openings
or full-reply buffering. **Test selected voice** runs three fixed texts twice.
**Compare Piper profiles** runs 24 profiles at 1.0/0.9x: 144 runs including repeats.
The 0.85x profiles are individually selectable. Tests never change saved call
settings; explicitly choose **Apply to voice calls** to save a profile.

The default call profile uses natural passages around 320 characters, capped at
640. Faster opening targets 160, with the existing complete-sentence release after
750 ms and at least 60 characters. Later passages use 320/640. Full-reply mode
waits for input completion but retains the 640-character synthesis cap.

Input arrives four characters per 32 ms after model readiness. Loading/downloading
is outside response timing. The second pass reverses case order. Playback speed
changes preserve pitch. Thermal status is recorded but never stops tests.

Each run saves exact text/hash, model, profile, suite/pass, provenance, timings,
requested/actual speed and failure details. **Copy this text run** exports one
immutable run; **Copy whole suite** exports its saved suite. Browse saved runs to
inspect older Kokoro/Paul measurements, which retain retired labels. Keep 624
benchmark records separately from 40 recent call measurements.

P1/P2 use versioned Piper packs and sample-rate-correct capture/replay; see
[supported stack](supported-model-stack.md). Compare matching text/profile/build
and thermal conditions. Underruns include drain events; software timing and RTF
do not prove acoustic intelligibility, accent consistency or successful barge-in.
