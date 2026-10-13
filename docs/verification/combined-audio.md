# Combined Audio, Memory OS and Tools checkpoint

Justin authorized direct integration into `audio-pr2` on September 30, 2026, without a new PR or closing/deleting branches. The source heads are:

| Source | Imported head | Standalone evidence |
| --- | --- | --- |
| Audio | `b54bb98bd1d0330d83a1ef381b0e7eb298a24863` | Build 841: release/JVM and API 35 passed; API 30 failed the browser-dismiss counter in test33 |
| Memory OS | `cd7280cfbdd15e54bf3eb5f243c0a3fffdb6d4de` | Build 861: 746 JVM tests, 36 journeys on each variant, signed APK publication |
| Tools | `4b4e6b94b6eadcaced0823eae8cf7fd57e15eb6c` | Build 863: 764 JVM tests, 35 journeys on each variant, signed APK publication |

Parent build results are historical evidence, not verification of this combined revision. Only `audio-pr2` advances. Memory OS, Tools and main remain at their imported heads; no PR is opened, merged or closed.

## Resolution and observable acceptance

- Keep Audio's single chat, live floating call overlay, separate phone/mic buttons, dictation/audio-note submission, persistent metrics, and model download/recovery code.
- Add the Memory entry point to that chat. Memory's Chat/Voice destinations return to the same conversation with an active call overlay, without starting another call or ending the current one. The production-parent release journey exercises both return paths.
- Keep SQLite migration, archive retention/lock checks, wiki/review/correction/erasure, reference PDF extraction, correction/repetition checks, and truthful pending-memory receipts. Finalized typed or sent-audio memory capture finishes before response generation; audio notes retain voice provenance. There is one capture per submitted message.
- Wire the durable phone-task journal and exact approval controls into the same chat and shared runtime. Preserve task recovery, cancellation/unknown-effect fences, conditional/ordered action execution, assistant background launch and action-session report scoping.
- Retain both the Memory continuity/capture prompt context and Tools' task-group/step ownership. Memory never grants action authority.
- Carry Memory's retained IME helper correction: an accessibility text replacement must not send Escape when the keyboard is absent. Audio's Download/Choose test retains every assertion.
- Preserve every source branch's named release journey. The unified map is common test01–29, Audio test30–33, Memory test34–39, Tools test40–44, and separate-process test90: 45 total. Renumbering changes names only; no test is dropped or skipped. test27 also exercises a pending Tools approval in the shipping parent while returning from Memory during an active overlay call, declining it without effects or call termination.
- The controlled Memory-return fixture awaits its consumption callback explicitly. The composer and overlay already exist in unified chat, so their visibility alone does not prove a new return request was consumed. Build 864 exposed that synchronization race on both variants; the callback count, overlay visibility and call-end assertions remain intact.
- The phone-task panel inherits the chat navigation's 12 dp horizontal inset. Build 865 reached the shipping-parent task control with an enabled button at the screen edge; the existing safe-tap check correctly rejected those bounds. The layout carries the inset into the combined chat; no tap-safety threshold or journey assertion changes.
- Preserve the union of release class-loader/worker/PDF keep rules and gated APK publication. The historical branch-deletion workflow only triggers on its original `feature-tools` path event and cannot run from this Audio integration.

## Verification and delivery

Local quick checks: the complete 45-method scenario map must match the Kotlin methods; every original method suffix must remain present; both Python helper suites must pass; source must contain no merge markers and `git diff --check` must pass. Android/Gradle are not installed in this session, so release JVM/native compilation and signed API 30 normal/API 35 compact verification run on the existing hosted workflow.

The candidate requires the exact revision's signed normal/compact builds, all JVM/native/helper checks, both complete emulator suites and the consolidated byte/test receipt before publication. No parent APK is the combined APK. Read the run, receipt and relevant screenshots, and refresh the destination head before handoff.

PStack Work Port planning and the pinned 0.10.0 companion's review usage ledger are used. Its Terra coding route is unavailable on this host. Integration is performed by the root session; no fully coordinated Terra implementation receipt, authenticated provider-model identity or token/billing measurement is claimed. Independent Astra High review and hosted CI remain separate evidence.

Fold 6 signoff still covers real selected-model inference, ASR/TTS/barge-in, Android 16 background launches, concurrent inference/downloads, and physical latency/thermal behavior. Automatic extraction, EmbeddingGemma/hybrid recall, temporal graph, external source adapters, general screen control, workflows and proactive scheduling remain future milestones; importing the checkpoints does not implement those plans.
