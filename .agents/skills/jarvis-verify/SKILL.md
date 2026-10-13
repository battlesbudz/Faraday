---
name: jarvis-verify
description: Build, exercise, diagnose and repair Jarvis Android changes using release tests, a disposable emulator and retained evidence. Use when implementing Jarvis features, investigating regressions, adding tool calling, or preparing an APK for Justin's final signoff.
---

# Jarvis development verification

This is a development workflow run by Codex/ChatGPT Work. Jarvis does not receive coding-agent tools.

Read `AGENTS.md`, `docs/verification/README.md` and `docs/verification/features.md` from the repository root. Use the existing branch and PR permitted there. Never replace release testing with a debug APK or claim an emulator verified physical audio/model behavior.

## Before implementation

1. State the user's desired behavior as observable acceptance checks, including a failure case and an existing behavior that must remain intact. Classify each check as JVM logic, Android integration, UI journey, or real model/device.
2. Locate the actual code and current tests in the feature map. Inspect the current PR head and working tree; preserve other changes.
3. Choose the smallest relevant tests and add coverage for new behavior. For new Android journeys, add the method to `ReleaseJourneyTest` and `scripts/verification/scenarios.json` together. The named-test contract rejects missing/skipped tests.

## Test, diagnose, repair

Use the hosted runner if this environment lacks Android SDK/KVM. Pushing authorized commits to the current PR triggers `Android APK`, which builds signed release variants, runs JVM/native/helper checks, then runs `Android sandbox`. Use the GitHub connector in Work or `gh` where installed to read runs/jobs/logs/artifacts. Do not assume a shell here can boot an emulator.

After every push, find the run for that exact head. Record the run URL, PR head, tested merge SHA, job conclusions and artifact hashes. Wait for all required jobs. A successful old run or a successful build without sandbox results is not a pass. Download/read `report.json`, `instrumentation.txt`, screenshots/UI XML and relevant logcat for failures. Inspect the screenshots visually when judging layout. Publication jobs already wait for the sandbox; do not bypass their dependencies.

Default to at most three repair attempts for a feature and a 60-minute investigation budget. Classify the failure first:

- Product regression: fix the application, rerun the failed scenario and full release gate.
- Test/harness defect: explain the evidence and make a separate, explicit correction to the harness. Never remove checks or lower thresholds to make the product pass.
- Infrastructure failure: retain the failed evidence and retry once when appropriate. Do not change application code to conceal missing ABI, SDK, credentials or device access.

Use `scripts/verification/repair.py` with `local_gate.py` when a signing environment and disposable emulator are already available. The bounded loop records every attempt and refuses automatic edits to acceptance infrastructure. Its repair command may invoke an installed, authenticated Codex CLI; it does not install or authenticate Codex or silently provision compute. On hosted CI the active Codex/Work session performs diagnosis and pushes repairs; GitHub Actions itself is not an unattended coding agent.

## Extend the test world

For a new tool, separately verify intent/argument validation, executor side effects, failures, and the model's decision to call it. Fake executors belong in JVM tests. Real Android executors run on the disposable emulator. Only call an end-to-end model tool test verified when actual weights generated the request. Keep production accounts and external side effects out of fixtures.

Preserve evidence before resetting or stopping a device. The controller provides launch, snapshot, tap, swipe, text and back controls for investigation. Add new scenarios and known limits to the feature map as the app evolves.

## Handoff

Return the existing PR, verified commit/run, concise changes, passed checks, remaining unverified behavior, and the release candidate. Justin does the final product signoff. Do not merge, claim physical-device validation, or describe an incomplete run as passing. If budget/access blocks progress, report the specific failing job and retained evidence, not a request to manually test every intermediate APK.
