# Short multi-action phone turns

This change lets one explicit request name up to three existing phone actions in order:

1. read the battery;
2. set media volume to a level from 0 through 100 (for example, “30” or “30 percent”);
3. open an installed app by name.

Examples include “Read battery, then set media volume to 30 percent, then open Settings.” The same action plan is used for completed text requests and completed voice transcripts. Natural battery wording such as “tell me what my battery percentage is” is covered by the bounded grammar, including the anchored retry prefix “I said, can you …”. An immediate “Yes” or “open it” confirmation can resolve a specific app offer/request from the latest turn; stale history is ignored. Voice additionally requires every planned clause to pass the final-transcript guard and agree with the confirmed request. Images and audio attachments are ordinary model turns and do not enter this action planner.

`ActionTurnPlan.parse` splits only explicit `and`, `then`, and `and then` sequences. It rejects conditional requests, more than three clauses, unsupported clauses mixed into a plan, and invalid volume values before dispatch. A plan is ready only when every clause is one of the three existing contracts. A non-action question remains ordinary conversation.

Before any side effect, the native response must pass strict validation for the next planned step:

- `read_battery` has no arguments.
- `set_volume` has exactly `level`, represented as an integer string from `0` to `100`; user text accepts plain numeric units, optional percent markers, and supported number words in that range, with no substring or “last number” recovery.
- `open_app` has exactly `app`, with a nonblank name.
- Unknown tools, malformed JSON, extra keys, wrong order, and mismatched values reject the batch.

The runner dispatches accepted requests in plan order. Each Android result becomes a receipt containing the request and the executor's success/message result; the coordinator does not claim success without that executor result. A failed executor result stops later steps; receipts already returned remain in the outcome, and the Android side effect that already happened is not undone. Permission and service failures are reported as failed results without automatic retry. Cancellation is propagated at the pipeline boundary, stops later actions, and leaves completed receipts durable and visible in the conversation history record. The repaired Android journey injects cancellation after the first real Android action; JVM tests also cover cancellation before dispatch.

A model replay of a completed request is matched to its existing receipt and is not dispatched again. An explicit repeated step in the user's plan is a separate planned step and may execute twice. This distinction prevents replayed native output from repeating a side effect while preserving deliberate repeated requests.

The Android journeys use simulated `ToolCall` objects and the real `AndroidMobileActionExecutor`. Build 760 is historical evidence for the earlier path; it passed `test14`–`test19` on both API variants, verifying Android battery state, media-volume state, Settings launch, invalid-plan preservation, partial failure, natural routing, and duplicate suppression. They do not verify real model weights, real model tool selection, physical Fold 6 behavior, or acoustic voice capture. The pinned Build 761 accepted-action queue is the reuse target. Current candidate Android CI remains pending; controlled journeys do not validate real weights, microphone/ASR/TTS, acoustic behavior, or physical Fold 6 performance.

## Test map

| Check | Test | Level | Status / evidence |
| --- | --- | --- | --- |
| Three ordered actions: battery → volume → Settings | `ReleaseJourneyTest.test14_threeActionTurnUsesRealAndroidState` | Android instrumentation; simulated model output + real executor | Build 760 historical pass on both variants |
| Natural Settings request opens then reads battery without lookup | `ReleaseJourneyTest.test18_naturalActionRoutingOpensSettingsThenReadsBattery` | Android instrumentation; controlled calls + real executor | Build 760 historical pass on both variants |
| Retry-prefixed literal `fistbook` fails before battery; Facebook substitution rejected | `ReleaseJourneyTest.test19_retryLiteralUnknownAppStopsWithoutBattery` | Android instrumentation; controlled calls + real executor | Build 760 historical pass on both variants |
| Invalid plan leaves volume unchanged | `ReleaseJourneyTest.test15_invalidActionTurnPreservesAndroidState` | Android instrumentation; real executor | Build 760 historical pass on both variants |
| Failed app stops later steps while completed volume remains | `ReleaseJourneyTest.test16_partialFailureKeepsCompletedAndroidAction` | Android instrumentation; real executor | Build 760 historical pass on both variants |
| Cancellation stops before the second action; completed receipt remains visible and durable; duplicate model pass does not replay completed volume | `ReleaseJourneyTest.test17_cancelledAndDuplicateActionTurnsDoNotReplay`; `ActionTurnRunnerTest.actualCoroutineCancellationAfterReceiptSkipsLaterAction`; `ActionTurnRunnerTest.actualCancellationBeforeFirstEffectHasNoReceipt`; `ActionTurnRunnerTest.cancellationAndRetryCapNeverReplayCompletedEffects` | Android/JVM | Build 760 historical Android pass; JVM coverage retained |
| Ordered batches, strict mismatch rejection, explicit repeats, replay deduplication, failure stop | `ActionTurnRunnerTest` | JVM | Included in focused host checks; exact current count is intentionally not part of this acceptance map |
| Immediate app confirmation and final voice agreement | `ActionTurnRunnerTest.authoritativePlanKeepsImmediateOffersButRejectsStaleHistory`; `FinalVoiceToolGuardTest` | JVM logic | Included in focused host checks; real voice/model remains unverified |
| Shared text and voice plan boundary | `ConversationRuntime.kt`, `ActionIntentRouter.kt`, `FinalVoiceToolGuard` | Integration / real voice | Implementation path inspected; model, microphone, and history persistence remain unverified |

## Continuous accepted-action voice (candidate; Android CI pending)

The accepted-action mode keeps one runtime-owned FIFO worker and a call-owned listening pump. Its intake can hold four pending typed messages plus one bounded control slot. The accepted worker/report path admits at most three outstanding requests (running, queued, or awaiting result reporting), with one to three existing actions per request, and runs requests in admission order. Overflow receives an explicit not-accepted response. Only `read_battery`, `set_volume`, and `open_app` may enter the existing native `ConversationRuntime`/`ActionTurnRunner` path. The queue freezes the validated plan and source text at admission, retains completed executor receipts, and deduplicates replayed callbacks by stable utterance ID; a fresh utterance repeating the same words remains a new request.

Ordinary barge-in, `stop`, `stop speaking`, `wait`, and `hold on` silence the current speech attempt while accepted tasks continue; quiet captures retain the call. Only explicit End, goodbye, or stop-listening ends it. `cancel that action`, `cancel the current action`, and `cancel all actions` are explicit scoped controls; they stop targeted unfinished work and preserve completed receipts. Cancellation cannot undo a synchronous Android operation already begun. A full composer keeps its draft. End call detaches speech delivery while bounded accepted tasks may finish into the original saved record; any unprocessed input is visibly cancelled. A fresh report attempt can retry undelivered results without rerunning actions, and it does not auto-replay work in a new call. Accepted Android receipts remain durable.

The accepted follow-up examples are “But actually can you open up youtube for me” and “And then open up Facebook after that.” They are explicit actions and queue FIFO. Conditions such as “after the download finishes”, `if`, `when`, and `unless` remain rejected. This work preserves existing model support and the noFunctionGemma boundary and does not add simultaneous text-during-call UI.

`test20_followupQueuesWhileAcceptedAndroidActionRuns`, `test21_speechInterruptionPreservesAcceptedAndroidActions`, `test22_explicitCancellationKeepsCompletedAndroidReceipts`, and `test23_actionResultsSurviveEndCallWithoutReplay` are added sources pending Android CI. They use controlled native `ToolCall` outputs and the real Android executor; they do not verify Gemma weights, ASR accuracy, acoustic barge-in, Bluetooth, thermal behavior, or physical Fold 6 performance. Exact Android CI is pending; this document defines behavior and coverage boundaries, with the exact run receipt as the authority for completion.

## SHORT Fold 6 checklist

Use a physical Fold 6 only as a product check after the continuous-action Android CI evidence is available. Build 760’s Facebook-plus-battery result is historical evidence for the earlier path, not proof of continuous listening or current-candidate behavior. Record the exact app revision and model state.

- [ ] Text example: send “Read battery, then set media volume to 30 percent, then open Settings”; confirm the three actions occur in order and the reply reports executor results.
- [ ] Voice example: say the same three-action request; confirm the final transcript authorizes the same plan and no action is repeated.
- [ ] Run a three-action request with a missing app in the middle; confirm the first action remains completed, the missing app reports failure, and the third action does not run.
- [ ] Stop/cancel mid-sequence; confirm completed actions are not replayed and the user-visible receipt/history records the completed prefix. The automated cancellation and receipt checks are added; repeat this on the physical Fold 6 for product behavior.
- [ ] Ask an ordinary battery question such as “What battery technology is used?”; confirm it stays ordinary conversation and does not invoke a phone action.
- [ ] During an accepted action, speak “stop speaking” or barge in; confirm only TTS delivery is silenced and accepted work continues. Then use an explicit scoped cancel and confirm later steps stop while completed receipts remain.
- [ ] Admit two follow-ups and end the call; confirm FIFO task results remain in the original saved record and are not replayed into a new call.

Real model weights, acoustic recognition, fuzzy app-name correction, physical audio capture, and Fold 6 performance are outside emulator evidence. The repair preserves a literal unknown app name and does not claim that “fistbook” means Facebook. A simulated model output plus a real Android executor is an executor check, not a real-model tool-selection result.
