# Latency next slice report

## Changes and rationale

- `app/src/main/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilder.kt`: shortened the voice-only supporting-context wrapper by 58 characters in the representative wrapper (`201 -> 143`); it still labels quoted material as data, denies instruction/tool authority, prevents overriding the current message or corrections, and closes the block. Added a compact voice tool-result format. In the example unknown-completion receipt (`Open app` / `open_app` / `Launch requested.`), the rendered boilerplate falls from 693 to 470 characters (223 fewer). It retains request/tool/status/Android result, faithful status reporting, the sent-but-unconfirmed distinction, the no-blanket-background-refusal rule, and the unlocked/foreground/system-assistant guidance. The existing nonvoice receipt format is unchanged.
- `app/src/main/java/com/battlesbudz/jarvis/v2/conversation/ConversationGeneration.kt`: selects the compact receipt format for voice-audio and call-owned turns only; all other turns retain the existing format. Tool eligibility and execution are untouched.
- `app/src/test/java/com/battlesbudz/jarvis/v2/ai/ConversationPromptBuilderTest.kt`: pins voice supporting-context framing and the compact action receipt's required fields/rules and size reduction.

## Audit findings

1. **Repeated prefill:** retained direct-audio call turns submit only the current message when their own call context remains resident. Existing `ConversationCallSessionReuseTest` pins the delta, fresh full prompt, reset-and-reseed retry, and text-mode behavior. Transcribed voice uses `IncrementalVoiceInput`: it pre-appends the static prefix and stable transcript words while listening; final submission appends only the remainder when the hypothesis and prefix remain valid. It rebuilds once when the transcript revises or the prefix changes. I found no repeated static prefix on that successful path. Reuse remains scoped to the existing call identity and resident engine checks; no cross-conversation reuse was added.
2. **Prompt diet:** changed only voice supporting-context framing and voice tool-result boilerplate. Tool-selection prompts, model tools, action guards, memory contents, continuity/capture evidence, and the full voice behavior rules remain intact. Memory and context records themselves can still be large; changing their selection or truncation would need separate behavior evidence.
3. **Clause-level speech start:** already wired. `ConversationGeneration` streams safe opening text through `VoiceRepetitionGuard`; `OrdinaryVoiceReplyStage` sends each token to the speech channel; `PiperVoiceOutput` uses `PiperTextStream`, whose opening path releases `openingClauseEnd` as soon as its safety floor is met (currently four words and 24 characters). Existing `PiperTextStreamTest` and `FirstPiperClauseTest` cover this. No change was needed.

## Deliberately not done

- Did not rebuild persistent sessions or shared-engine composition.
- Did not remove tools from voice turns or change endpointing.
- Did not alter ASR/model/native bridge, memory selection, tool policy, or speech boundary thresholds.
- Did not run Gradle, build an APK, access network services, or publish artifacts, per task instructions.

## Tests and checks

- Added `voiceToolReceiptIsShortAndPreservesEveryOutcomeRule` to pin compact voice receipt content and reduction.
- Updated `finalVoicePromptPreservesListeningPrefixWithLateQuotedContext` to pin the compact but still fenced supporting-context wrapper.
- Existing `ConversationCallSessionReuseTest` pins per-call session reuse and reset/reseed behavior; existing clause tests pin early speech release.
- Ran `git diff --check` successfully. No JVM/Gradle tests were run in this environment.

## Risks and owner review

- Prompt character counts are literal character counts, not model token counts. Actual latency impact depends on tokenizer, prefill behavior and the selected model; the action receipt saves the most where a voice turn executes a tool.
- Review that the compact status language remains clear for all `ExecutionResult.Outcome` values, especially unknown completion and needs-unlock. The status mapping itself remains unchanged.
- This does not address the measured final-transcription wait or prove a phone-side reduction in first-token latency. The recognized audio path still depends on capture/ASR completion and model-device behavior.

## On-phone proof for Battles

Run a voice call turn that performs a phone action with an unconfirmed result, then inspect the captured inference/tool-result prompt to confirm the shorter authoritative receipt preserves the correct status and foreground/system-assistant guidance. Compare before/after `first_token`/submission timing on the same device, model, utterance and thermal state. Also capture a normal spoken answer and confirm Piper begins after the first eligible short clause; compare the existing voice diagnostics for final-ASR and first-token durations. Repeat a follow-up in the same call and verify session-reuse diagnostics show a resident context; start a new call and verify it begins fresh. This repo-only change has no phone measurement yet.
