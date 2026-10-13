# Video foreground notification serialization — 9 October 2026

## Observed failure and bounded inference

[Build 1240](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37912134109),
source `18ef4d118c2e5ce44a795871a2bb430d3c7c4a29`, ran all 81 main journeys
on the API 36 normal phone (4 KB pages), with one failure in unchanged test80.
After the service instance was null and the existing 1,500 ms wait elapsed,
ID 482 still read `Video idle — starts with your next call`. Artifact
`jarvis-verification-36-phone-normal-app-release`, ID `11610887921`, has archive
SHA-256 `bd021c190e89bb5f78709a8c963d27693d46555a3a14e148d13e9188b8224ddf`.

The final farewell refresh is a no-op after `instance == null`. The evidence
does not prove which earlier callback posted, that the notification disappeared
and reappeared, or its eventual lifetime beyond the assertion. A missing-enqueued
record warning also occurs in passing profiles. The source-supported schedule
below is a concrete uncovered race, not a claimed replay of that historical run.
The earlier closed-owner and explicit-cancellation repair remains necessary;
its synchronous sink could not establish cross-system queue ordering.

## Platform boundary

Pinned Android 16 AOSP commit
`99b01a65cc4c104933788b3143285ab6bae65827` establishes:

- [`ActiveServices`](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/am/ActiveServices.java)
  lines 1842–1852 and 4668–4671 resolve the exact component/service token;
  lines 2716–2719 post updates even when already foreground. Teardown removes
  the lookup at 6400–6402 and requests cancellation at 6427 before app stop
  delivery at 6505.
- [`ServiceRecord`](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/am/ServiceRecord.java)
  lines 1580–1595, 1686–1688 and 1708–1731 queue foreground post and cancel
  through the same AMS handler before entering NMS.
- [`NotificationManagerService`](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/notification/NotificationManagerService.java)
  lines 8416–8433 apply foreground policy before eventual enqueue at 8583.
  App cancellation excludes foreground-flagged notifications at 4219–4231 and
  9452–9459; system cancellation has authority to remove them. Lines 11149–11160
  preserve NMS queue ordering.

A direct `NotificationManager.notify` can therefore acquire its foreground flag,
then enqueue after system cancellation. A later app cancel cannot remove that
flagged record. The app's publication lock alone does not order these separate
AMS/NMS operations.

API 30 retains the same token admission and handler ordering: pinned Android 11
commit `1d9b9ab57d844b18b3b1b4297725141e7788109b`,
[`ActiveServices`](https://android.googlesource.com/platform/frameworks/base/+/1d9b9ab57d844b18b3b1b4297725141e7788109b/services/core/java/com/android/server/am/ActiveServices.java)
1028–1040, 1565, 2402–2405 and
[`ServiceRecord`](https://android.googlesource.com/platform/frameworks/base/+/1d9b9ab57d844b18b3b1b4297725141e7788109b/services/core/java/com/android/server/am/ServiceRecord.java)
831–849, 930–932, 947–969. These source-family findings do not attest the exact
emulator or any OEM binary.

## Repair and ownership

`VideoForegroundNotification` stores only the successfully accepted startup
foreground type. Both startup and every later status update invoke the existing
service's three-argument `startForeground(id, notification, acceptedType)`.
There is no raw-notify fallback, manifest-default overload, refresh-created
service, or automatic type promotion. Camera capture requires current camera
permission and an accepted camera type. A service admitted as specialUse remains
camera-ineligible even after a later permission grant.

`VideoNotificationLifecycle` still serializes publication with revocation. A
runtime admission rejection closes publication before invoking its failure
callback outside that lock. A separate cleanup-started flag permits the existing
close path to run once after revocation. The Android adapter queues video-only
cleanup on Main, removes foreground state, retains the explicit final cancel and
replacement-owner guard, then stops only this video service. All app-owned
terminal decisions use close-before-stop, so queued capture commands cannot
pass admission while awaiting `onDestroy`. Already-admitted main-thread camera
work finishes before queued rejection cleanup; the publication lock never wraps
camera work. Runtime removal/stop failures are logged and contained so they
cannot kill independent audio via an uncaught coroutine exception. Fatal errors
are not misclassified as recoverable admission rejection.

Capture generations, farewell liveness, call identity, audio ownership and
controller/binder unresolved-detach ownership are preserved. The close path does
not rewrite `CLEANUP_PENDING` or a retained capture identity to successful idle;
registry removal checks the exact controller owner. There are no delayed or
repeated cancellation attempts.

## Local verification and remaining gates

- Focused host suite: 116 tests, including all prior 102 cases and 14 added cases.
  Production foreground/type and publication owners are used by a two-queue
  model. The legacy direct route leaves a protected orphan under the deliberately
  paused schedule. The repaired route has no orphan across 256 deterministic
  handler schedules, both sides of stop admission, duplicate/closed callbacks,
  and replacement tokens. App/system cancel authority is distinct; audio ID 481
  remains untouched.
- Startup denied/rejected camera fallback, later permission grant, unchanged
  accepted type, rejected refresh taxonomy, terminal capture admission,
  idempotent cleanup, cross-thread lock release, and real-controller unresolved
  detach are covered. An `AssertionError` remains observable.
- A shipping-source wiring test binds the model to the actual typed foreground
  closure, owner route, accepted-type camera predicate and terminal stop path.
  Six controlled mutations must fail: restore raw shipping notify, remove the
  terminal capture guard, forget the accepted fallback type, omit rejection
  revocation, skip cleanup after revocation, or run rejection cleanup under the
  publication lock. These are regression sensitivity checks, not Android runs.
- The complete changed service and pure collaborators compile against a retained
  real Android 14 framework API and real Kotlin/coroutines. Peripheral AndroidX,
  activity, permission, voice-flow and CameraX signatures are explicitly stubbed.
  Compiled service bytecode contains the three-argument foreground invocation
  and no `NotificationManager.notify` invocation. This is API-signature coverage,
  not full Gradle/R8 compilation or Android execution.
- Architecture checks, 102 general Python helpers and 82 verification helpers
  pass. The host CameraX extraction still excludes Android constructors, YUV
  conversion and actual platform binding.

Release test80, its timings/assertions/permission setup, all scenarios, workflows
and native-oracle files are unchanged. Local checks do not verify a repaired APK.
The exact combined head still requires the signed/minified release build and
complete five-profile Android gate, including unchanged test80. Physical camera,
audio, device performance and actual model inference remain unverified locally.
