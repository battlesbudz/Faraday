# Panel-approval callback completion

The narrow test59 correction follows the retained API 30 failure in
[Build 1256](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38027820843),
source `43df0eca06530d893922b3bcafb01134d7c07c50`. Local base
`42ae2bf80fd31c5e9bb278995764d4a7bf7327b6` has the same Git tree
`06173553854d081f6a8e07cc0bb2bd6fbdd9dafa`.

## Observable contract

One Approve click must dispatch exactly the approved target once, retain the
correct group's screen lease, record SUCCEEDED, consume its exact approval and
show the Stop overlay. A changed target must invalidate the old approval. On
completion, releasing the correct group must clear admission and hide Stop.
Missing callbacks and incorrect effects must fail. These are controlled Android
UI/integration checks; fake screen effects do not verify a real Accessibility
service overlay, real-model selection or a physical phone.

## Failure and repair

The retained run reaches the Stop-overlay assertion after all earlier dispatch,
lease, ledger and approval assertions pass. The click log precedes failure
capture by 48 ms. No retained callback trace proves the exact runtime ordering.
Source inspection shows a valid failure schedule: the UI callback has appended
the tap and saved the receipt, while the instrumentation thread observes those
earlier effects before the later overlay assignment. The unsynchronized fake
bridge fields provide no completion signal for later callback operations.

The callback now counts down a one-shot latch after journal publication. A
successful 10-second await supplies a Java happens-before edge from every
preceding callback write. Assertions and lease/overlay cleanup then run through
the existing ActivityScenario owner boundary. An exception before acknowledgement
cannot release the latch. A missing callback times out; an incorrect completed
callback still reaches the unchanged assertions and fails there. The owner
boundary is additional framework synchronization, not part of a strict overall
10-second wall-clock bound.

No product source, fake bridge implementation, callback action order, target,
original assertion, scenario list or suite timeout changes. There is one panel
open and one approval click; no action retry or extra sleep is added.

## Local evidence and limits

The complete evidence packet accompanies this local candidate. It contains
source-bound baseline/repaired host fixtures, the fault-injection driver, cached
API compilation, exact source/dependency hashes and full command logs.

- The changed method, its unchanged find/fake helpers, PhoneTaskPanel and 28
  unchanged production files compile against Kotlin 2.3.21, Android 14,
  UiAutomator 2.3.0, Compose 1.7.6, Material3 1.3.1 and Activity 1.10.0.
- The compile uses explicit ActivityScenario/evidence and unrelated assistant
  launch signature seams; selected ActionTurnRunner and ReminderScheduling
  declarations are extracted verbatim. It is not a complete Gradle/APK build.
- Thirteen deterministic host cases run against those fresh production classes.
  The baseline fails at the retained Stop-overlay assertion when the callback is
  held after receipt completion and before overlay assignment. Under the same
  schedule the repair is observed waiting in the real CountDownLatch.await and
  then passes after callback completion. Host scheduling hooks are confined to
  the extracted fake bridge and explicit input/activity seams.
- Missing and throwing callbacks fail at the unchanged 10-second bound.
  Duplicate callback delivery cannot dispatch twice; duplicate/wrong target,
  wrong lease, failed dispatch, missing Stop and failed hiding remain failures
  under the original assertions. The successful repaired cases release the
  correct lease and hide Stop on the UI owner.
- All 111 build/helper tests and 82 verification helper tests pass, preserving
  every named scenario and release profile requirement.

The exact repaired revision still requires the full signed/minified Android
release gate on all five profiles. No local result here establishes a new
Android pass, a verified APK or release readiness.
