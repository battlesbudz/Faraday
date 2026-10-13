# Wisp transparency implementation queue

**Created:** 6 October 2026. **Status snapshot:** 6 October 2026, 09:40 UTC.
**Scope:** implement the useful recommendations from the
[delivered assistant-character research](../research/wisp-character-research.md)
after the current integration has cleared its release gate. This documentation
change queues work; it implements no new feature and does not start another build.

The active integration remains `audio-pr2` under existing
[PR #6](https://github.com/battlesbudz/Jarvis-OS-V2/pull/6). Its integration owner
controls sequencing and publication. Finish that work before starting this queue;
do not open a parallel PR, bypass acceptance, or mix this queue with a separate
audio/native review. Existing approvals and permission boundaries continue to
apply to any future screen, notification, external-service or task action.

## Status and evidence

| Item | State at intake | Evidence and limit |
| --- | --- | --- |
| Persistent Wisp character and real-state poses | **DONE — verified baseline** | [Build 1086 release](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.1086), [run](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37428497275). PR head `483345b5d6e6e55a8b79cf8ae7d7b9639c3a6592`; tested merge `5e639f53feaf09a71e7495553c230d47349fa80e`. This establishes the prior release baseline, not the newer features below. Physical audio, real-model behavior and phone performance still require their own device evidence. |
| Dynamic public activity text, safe tool summaries and hidden idle text | **IMPLEMENTED — unreleased candidate** | Present in candidate head `4672942c27d941b5858aebaeab733ba6e47a77c3`, tested merge `df5519d74351c4d4db62d7f2beabddc2c41ccd9c`, [Build 1096](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37441980620). Its API 30 upgrade and 75 of 76 main journeys, including Wisp test49, passed at intake; benchmark test45 failed and remaining device-gate phases were not yet verified. The full candidate is not a verified release. |
| Tap Wisp for activity details; unified task controls; logical-task concurrency and recovery UX | **TODO — queued after integration** | W01–W04 below extend existing owners rather than replace the journal, admission or authority systems. |
| Accessibility/privacy acceptance across the new surfaces | **TODO — required within every slice** | W05 below is a release condition, not optional polish. Existing safeguards must be preserved. |
| Screen-session visibility, goals/reminder surfacing and richer expression | **TODO — later, dependency-gated** | W06–W08 build on supported, verified capabilities only. Research examples are not capability promises. |

These build numbers are a historical snapshot. Consult PR #6 and the
[feature acceptance map](../verification/features.md) for subsequent repairs and
exact-head evidence; do not relabel Build 1096 as passing when a later build passes.
The current integration gate is the immediate prerequisite, W00.

## Preserve the product contract

- Keep one recognizable, persistent Wisp. The character observes work; it does not
  own a call, dispatch an action, grant permission or create a second task runner.
- Show a short, task-specific sentence during real work or a real waiting state.
  Text is an ordinary bounded string; finite motion categories do not restrict its
  wording to a fixed list of status labels.
- Hide the line and its accessibility node when idle. Keep required Android
  microphone/service indicators independent. Do not restore permanent “Ready” text.
- Preserve the current distinction between an unresolved task and a generic
  storage warning: approvals/unknown outcomes remain accessible; persistent storage
  warnings stay in their task panel/banner and must not freeze live Wisp activity
  or produce permanent idle text.
- Use actual operation boundaries and receipts. Requested, queued, running,
  waiting, cancelling, cancelled and succeeded have different meanings. Do not
  invent progress, success, percentages, deadlines or animation-driven stages.
- Treat microphone capture, playback, conversation work and accepted task work
  as separate observations. Stopping speech or ending a call must not silently
  cancel accepted tasks; task cancellation must target the intended identity.
- Describe public actions and results only. Never surface private reasoning,
  prompts, raw token streams, raw arguments, credentials or hidden diagnostics.
- Preserve existing model/microphone leases, generation fences, durable dispatch,
  source permissions, exact approvals, lock handling and unknown-outcome safety.

## What already exists in the candidate

Do not reimplement these features while working through the queue:

- `WispPresence`, `WispPresenter` and `WispCharacter` already separate app-level
  observation, deterministic presentation and native drawing. Capture/playback
  use their real owners; fast actions are not slowed for animation.
- `ConversationActivity` and `AgentActivityMonitor` already provide public progress
  leases with monotonic sequence fencing and nested reference-read cleanup. The
  monitor is ephemeral and does not establish durable multi-task execution.
- `ActivityText` already bounds public text, rejects obvious unsafe patterns and
  permits per-tool safe summaries. This is not a general PII classifier; safe
  producer metadata remains required.
- Idle, locked and background activity-text suppression, two-line wrapping, a
  polite live region and system motion-scaling support already exist. Final
  integrated acceptance must still cover their real Android behavior.
- Wisp already prioritizes approvals/unknown outcomes and counts relevant active
  **journal attempts**. That is a useful starting point, not proof that the count
  always represents distinct user tasks or covers all forms of background work.
- `PhoneTaskPanel` already has exact approval/decline choices, cancellation for
  eligible pending attempts and an unknown-outcome acknowledgement. The runtime
  already owns task scheduling, recovery and workflow/reminder facilities. Their
  presence does not establish a Wisp-linked activity timeline or unified controls.

Source navigation: [architecture](../architecture/README.md),
[Wisp presentation](../../app/src/main/java/com/battlesbudz/jarvis/v2/ui/WispPresentation.kt),
[Wisp UI](../../app/src/main/java/com/battlesbudz/jarvis/v2/ui/WispPresence.kt),
[public activity](../../app/src/main/java/com/battlesbudz/jarvis/v2/presentation/AgentActivity.kt),
[task panel](../../app/src/main/java/com/battlesbudz/jarvis/v2/ui/PhoneTaskPanel.kt),
[task projection](../../app/src/main/java/com/battlesbudz/jarvis/v2/actions/TaskProgressProjection.kt).

## Ordered work queue

### W00 — finish current integration acceptance

**State:** prerequisite in progress outside this documentation change.

Retain the candidate's failed evidence, complete the current repair/verification
cycle and record the final exact head, tested merge, release and coverage limits.
Do not call the newer activity-text implementation shipped based on Build 1086 or
a focused Wisp pass. The integration owner controls when W01 can begin.

### W01 — open useful activity details from Wisp

**State:** TODO. **Depends on:** W00. **First implementation slice.**

Make Wisp an accessible entry point to a compact activity/task surface. Start with
existing journal/progress projections and safe public events. Show the selected
task, real lifecycle state, timestamped observable operations, verified results,
partial results and useful source links when those links are actually available.
Show an honest empty state instead of inventing history for ephemeral work.

Keep raw executor receipts and private task content out of the compact public
header. The unlocked details surface may expose only task information the user is
allowed to inspect, with deliberate redaction and bounded retention. If richer
public history needs persistence, extend the existing journal contract with a
reviewed migration; do not persist model reasoning or silently create another log.

**Owners:** `ui/WispPresence`, `ui/PhoneTaskPanel`, the existing UI navigation owner,
`presentation/AgentActivityMonitor`, `actions/TaskProgressProjector` and journal
projections. Any new responsibility must be added to the architecture map.

**Acceptance:** tap/keyboard/TalkBack opens details for the correct conversation
and task; repeated taps do not stack dialogs; Back/Close restores focus and draft;
rotation/fold/recreation do not start work or replay completion; source links have
verified provenance; no raw private metadata appears in the header or lock screen.

### W02 — make controls clear and task-specific

**State:** TODO. **Depends on:** W01; reuses existing authority and control paths.

Expose only controls supported by each live state: review approval, supply input,
cancel an eligible task, inspect an unknown outcome, or retry where an existing
safe policy permits it. Keep “Stop speaking,” microphone pause, “End call” and
“Cancel task” visibly distinct. Route commands to stable task/attempt identity and
generation, never a label, list position or whatever work happens to be newest.

A cancel request is not confirmed cancellation. Preserve known completed effects
and partial results. Unknown external outcomes require reconciliation before a
fresh action; acknowledgement is not permission to replay. Do not add a universal
Retry button for mutations or show Pause/Resume when no executor supports them.

**Owners:** existing `PhoneTaskCoordinator`, task scheduling/stop routing,
approval ledger, `PhoneTaskPanel`, and call controls. Presentation delegates;
it never writes dispatch authority directly.

**Acceptance:** two similar tasks cannot receive each other's control; repeated
clicks/stale approvals cannot duplicate effects; new targets invalidate approvals;
call end leaves accepted work intact; late completions cannot revive cancelled or
superseded attempts; unsupported controls are absent or clearly unavailable.

### W03 — represent concurrent work as distinct tasks

**State:** TODO. **Depends on:** W01; shares task identity with W02.

Define and test the user-facing unit as a logical task/group before changing the
current attempt-based suffix. Several steps or retry attempts from one request
must not inflate “other tasks.” Preserve separate rows and states for independent
tasks, workflow work and the selected conversation. Show an accurate count only
for work the runtime can actually enumerate.

Keep approvals/failures discoverable while Wisp reports live audio or a selected
task. Switching chat or selected task must not retarget an in-flight operation.
Concurrent UI does not authorize a second model engine or overlapping screen
mutation; retain exclusive leases and resource scheduling.

**Owners:** `WispPresenter`, `ToolTaskJournal`, `TaskProgressProjector`, existing
scheduling/accepted-work owners and the W01 details projection.

**Acceptance:** one three-step request counts as one logical task; two tasks retain
independent state and controls; stale events cannot alter another run; foreground
speech does not hide a pending approval; counts fall only when the defined state
actually changes; cross-chat privacy and origin identity remain intact.

### W04 — make interruption and recovery understandable

**State:** TODO. **Depends on:** W01–W03 and the current journal compatibility work.

Connect Wisp details to existing durable recovery results after process/activity
recreation, unlock and reconnect. Reattach only to confirmed live owners; do not
restore an old ephemeral “Searching…” sentence as proof that work resumed. Show
paused, awaiting-resource, failed or unknown states and the next permitted action.
Preserve previous results and timestamps without replaying success animation.

Do not add automatic retries or change journal migration/recovery policy in a UI
slice. Any needed runtime correction belongs with its owner and its own tests.
Avoid “Reconnecting” unless an actual supported reconnect is underway.

**Acceptance:** process loss during a side effect yields the correct unknown or
reconciled state; no duplicated external action; a locked device cannot reveal
private details or resume forbidden work; network loss cannot leave productive
text running forever; app recreation cannot turn ended work back into running.

### W05 — verify accessibility and privacy in every slice

**State:** TODO acceptance extension. **Depends on:** each affected slice, not on
completion of W04. This work is part of W01–W04's definition of done.

Preserve a stable, bounded one- or two-line public status area without a marquee,
typewriter or layout jumps. Keep motion optional and information equivalent when
motion is disabled. Coalesce noisy updates; important results must not wait for an
animation. Use one meaningful polite status node, with decorative art excluded
from duplicate announcements and labelled controls large enough to operate.

Audit producer allowlists, safe source links, unlocked details, notification
visibility/public replacement content and logs together. Do not rely on a text
regex to redact every sensitive subject. Approval text belongs in the appropriately
protected review surface. Sensitive task subjects must not appear on a lock screen.

**Acceptance:** 320 dp width, 200% font, landscape/fold and TalkBack remain usable;
zero motion and a mid-animation scale change preserve state; idle removes the
status semantics node; focus returns after dismissal; announcements do not flood;
lock/unlock/background transitions reveal no sensitive subject or stale label.

### W06 — explain optional screen context and takeover

**State:** TODO, later. **Depends on:** W01–W05 and verified supported screen-session
capabilities from the existing tools integration.

Expose whether screen context is actually shared, what session owns it, when the
user has taken control and how to stop sharing. Reuse the existing screen session,
permissions and approval system. A read observation, a proposed tap and an executed
tap must be distinguishable. A character pose is not proof of screen access.

**Acceptance:** sharing starts only through its supported authorized flow; revoking
or stopping it takes effect and clears stale status; user takeover pauses the
relevant automation; returning to Jarvis does not silently reacquire access;
controlled fixtures are not described as third-party app or device validation.

### W07 — surface goals and reminders when their delivery is reliable

**State:** TODO, later. **Depends on:** W01–W05 and the existing workflow/scheduling
milestones; do not build a competing scheduler.

Consider a goals/scheduled-work section using existing workflow/reminder records.
Differentiate scheduled, due, running, missed and delivered. Respect enablement,
permissions, timezone, quiet-time policy and user control; a saved goal is not
permission for every future external action. Revalidate current tools-plan status
before choosing this slice, because older plans predate the current integration.

**Acceptance:** restart/timezone/clock changes do not duplicate occurrences; missed
or blocked delivery is visible; disabling future work leaves truthful prior
receipts; quiet-time and notification behavior have direct evidence.

### W08 — refine character expression after behavior is trustworthy

**State:** TODO, later. **Depends on:** W00 and the core transparency/control work.

Tune restrained listening reactions, verified-outcome expression and optional
customization using the existing drawing owner. Preserve the recognizable Wisp
and the user's hidden-idle-text requirement. No new inference call is needed just
to phrase each status. Any later generated wording must use verified public facts,
reject stale updates and retain deterministic safe fallbacks.

**Acceptance:** motion never substitutes for success/failure text or task evidence;
audio-linked motion follows real capture/playback; lifecycle/reduced-motion behavior
and frame cost are measured; cosmetic changes do not cover the transcript or controls.

## Verification and completion rule

For each implementation slice, define observable success/failure cases before
editing and extend the existing closest JVM/UI tests. Reuse `AgentActivityTest`,
`WispPresentationTest`, the journal/approval/scheduling/recovery regressions and
release test49; add new named journeys only with the corresponding scenario
contract update. Update the [feature map](../verification/features.md) when actual
acceptance changes. This planning-only patch changes no acceptance threshold.

Follow the [verification workflow](../verification/README.md), including applicable
release JVM/native/helper checks, signed normal/compact builds, every required
Android profile and the consolidated exact-revision receipt. Inspect screenshots
and accessibility semantics for layout claims. Keep failed evidence. Emulator
success does not establish real-model accuracy, physical microphone/speaker or
Bluetooth behavior, acoustic silence, third-party integration support, or Fold 6
performance. Record those limits and retain device signoff.

A queue item moves to **IMPLEMENTED** with source and focused regression evidence,
to **VERIFIED** only with its required exact-revision gates, and to **RELEASED** only
with a confirmed release link. Preserve historical build outcomes and keep the
unimplemented items open. The integration owner publishes through the existing
branch/PR; this queue grants no independent permission to open or merge a PR.
