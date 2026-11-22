# Faraday autopilot goal

Justin's end state, in his words: a phone-resident agent that can do what paid
cloud coding agents do today. Everything runs on the phone. The agent may use
the internet for tools, but user data is never uploaded or stored in the cloud.

Each goal below has a pass/fail target. A goal is done only when its target is
met with the evidence named here (CI evidence for logic, Justin's Fold 6 for
real-model, audio and device-performance claims).

## G1 — Sub-second voice call (current priority)

- Target: median under 1.0 s from end of the user's speech to first audible
  answer audio on the Fold 6, warm model, ordinary short question; p90 under
  1.5 s. Measured by the per-reply latency record, not estimated.
- Duplex: the user can interrupt mid-reply; playback stops within 300 ms of
  confirmed speech; echo of the agent's own voice never interrupts.
- Route: either the LFM3-Omni branch (`feature/lfm3-omni*`, single audio model)
  or the Gemma E2B/E4B pipeline, whichever reaches the target first. Keep both
  buildable until Justin picks one.

## G2 — E4B one-shots a snake game

- Target: from a single plain request ("make a snake game"), Gemma 4 E4B in the
  coding workspace produces a game that builds, previews, and is playable
  (snake moves, eats, grows, dies on collision, score shown) with no follow-up
  prompts, in at least 4 of 5 attempts.
- Evidence: an automated harness eval that scores the generated game, plus
  Justin's device run with real E4B weights.

## G3 — Autonomous phone agent

- Target: the agent completes multi-step tasks in other apps (for example "open
  Settings and turn on dark mode", "send the last photo to <contact> in
  Messages") on a hidden internal virtual display, using screenshots, taps and
  text input exposed through the Android MCP tool surface, with approval for
  risky actions.
- Evidence: emulator journeys in the release sandbox for the mechanics; Justin's
  device run for real-model task success.

## G4 — The agent's own computer

- Target: the agent owns a Linux environment on the phone (the existing
  sandbox/terminal) with a browser it can drive, run tests in, and that Justin
  can open and use himself.
- Evidence: emulator journey that starts the environment, runs a test command,
  and drives a browser page; Justin's device check.

## Non-negotiables

- No user data leaves the phone except explicit tool use (web search, fetch,
  user-added MCP servers); private chats stay fully offline.
- Follow `AGENTS.md`, `docs/architecture/README.md` and
  `docs/verification/README.md`. Never weaken a test or gate to make work pass.
