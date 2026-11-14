# PR #27 release compile fix

## Root cause

`ConversationGeneration` passed `receipt.result.succeeded` (a `Boolean`) as the
fourth positional argument to `buildToolResultContext` and also supplied the
named `voice` argument. The overload that accepts `voice` takes
`ExecutionResult.Outcome`; the legacy Boolean overload has no `voice` parameter,
so neither overload matched the call.

## Fix

The call now passes `receipt.result.outcome`, which is the actual
`ExecutionResult.Outcome` property. This keeps the voice receipt selected when
`invocation.voiceAudio != null || invocation.callOwned` and preserves the
existing outcome-specific faithful-status mapping, including unknown completion
and needs-unlock. No prompt text, status mapping, or test expectation changed.

## Call sites checked

A repository-wide Kotlin search for `buildToolResultContext(` found:

- `app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationGeneration.kt:431` — the sole production call site; fixed to pass `receipt.result.outcome`.
- `app/src/main/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilder.kt:113` — typed-outcome overload declaration.
- `app/src/main/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilder.kt:160,165` — legacy Boolean overload and its delegation; it maps `true` to `SUCCEEDED` and `false` to `FAILED`.
- `app/src/test/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilderTest.kt:54,56,133,145,149,157,159` — test calls use typed outcomes or intentionally exercise the legacy Boolean overload. None combines a Boolean argument with `voice`.

No other mismatched call sites were found.

## Files changed

- `app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationGeneration.kt`
- `FIX-REPORT.md`

## Verification

- `git diff --check` — run for this fix.
- Gradle/build/test checks — not run, as instructed.
- No network, PR, or merge actions were performed. Changes remain uncommitted.
