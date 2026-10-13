# PR 1: Local two-model Android foundation

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

## Scope

This PR establishes the native Android shell and the first real local inference
boundary for the confirmed local model:

- Gemma 4 E2B is the primary conversational model.
- Gemma 4 E2B emits native structured tool calls for phone actions.
- LiteRT-LM is the Android inference runtime.
- Kotlin owns typed action validation and Android execution.

Model binaries are intentionally not committed to the repository. The app
accepts the Gemma model path from the future setup/model-delivery flow.

## User-visible acceptance test

After merge, build and install the APK. On a clean install it opens a guided
model setup screen so the user-supplied Gemma `.litertlm` file can be imported
privately. After the file passes the local smoke test, the app opens a simple
black-box Jarvis chat screen with no model names, implementation details, or
settings exposed in the main experience. Returning users go directly to that
chat screen while their verified local Gemma model remains available.

## Automated acceptance tests

- The LiteRT-LM adapter initializes a selected local model off the UI thread,
  maintains a conversation, streams generated text, and reports time to first
  token.
- The action-pipeline test sends a structured request shaped like
  Gemma's native tool output, validates it, and passes it to a recording
  executor.
- Invalid model output is rejected before it can reach an executor.

## Included

- Kotlin + Jetpack Compose Android shell
- LiteRT-LM Android dependency and streaming adapter
- Local model catalog for Gemma 4 E2B
- Replaceable local model interface
- Typed mobile action contracts
- Native mobile-action and executor boundary
- End-to-end action-pipeline contract tests
- Confirmed Galaxy Z Fold 6 benchmark record
- PR scope and acceptance documentation

## Deliberately deferred

Model download delivery, broader native tool-schema decoding,
full assistant-session wiring, speech and Kokoro, wake-word detection, EYE VUE,
Bluetooth, memory persistence, and broader real Android action coverage will be
separate focused PRs. The battery and volume executor is present as a safe
native boundary, but it is not yet connected to live model output.
