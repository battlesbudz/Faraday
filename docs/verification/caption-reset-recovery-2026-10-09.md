# Caption reset failure and coroutine recovery

## Acceptance and classification

A checked native reset failure must escape as
`PostAnswerCaptionContinuation.NativeReleaseFailure`, retaining the exact native
failure as its root cause. The caption child must finish and the next-input
listener must close before the one reset attempt. A failed reset must never emit
`caption_native_reset_complete` or return a successful caption result. These are
JVM lifecycle checks; existing normal completion, accepted-input cancellation,
timeout, owner replacement and parent-cancellation cases remain in the same suite.

[Build 1246](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37989500077)
tested `9a9ad5e2262c3eb70cbef747bda505e317521541`. Its release JVM task ran 2,152
tests and failed only
`PostAnswerCaptionContinuationTest.failedCheckedResetEscapesInsteadOfReportingSuccessfulHandoff`.
The assertion expected the immediate cause message `native owner quarantined`
but observed `Caption native release/reset failed`. This is a test assumption
about exception-chain depth, not evidence that native failure was swallowed.
The original CI XML is retained with SHA-256
`592686a05a29d892bc579f418d0b420b51b8ae6ac74514f4e22e6d805385ce24`.

## Controlled reproduction

The unchanged production helper and all 11 original tests were freshly compiled
with the pinned Kotlin 2.3.21 compiler, coroutines 1.9.0 and JUnit 4.13.2 on JDK 21.
Separate JVMs controlled assertions, coroutine debugging and stacktrace recovery:

| JVM setting | Original suite | Corrected suite | Probe recovery |
| --- | --- | --- | --- |
| Default JVM | 11 passed | 11 passed | Disabled |
| `-ea` | Same single CI failure | 11 passed | Enabled |
| `-ea -Dkotlinx.coroutines.debug=off` | 11 passed | 11 passed | Disabled |
| `-Dkotlinx.coroutines.debug=on` | Same single CI failure | 11 passed | Enabled |
| `-ea -Dkotlinx.coroutines.stacktrace.recovery=false` | 11 passed | 11 passed | Disabled |

[Gradle 8.10.2's Test constructor](https://github.com/gradle/gradle/blob/v8.10.2/platforms/jvm/testing-jvm/src/main/java/org/gradle/api/tasks/testing/Test.java#L189-L190)
enables assertions. The repository does not override that setting or these
coroutine properties. This explains why a direct JVM check without `-ea` missed
the release-test failure; the focused reproduction emulates the relevant Gradle
settings but does not execute Gradle itself.

[Coroutines 1.9.0's debug configuration](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/jvm/src/Debug.kt#L58-L72)
enables debug mode under JVM assertions and enables recovery by default when
debugging. Its [exception-copy implementation](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/jvm/src/internal/ExceptionsConstructor.kt#L19-L56)
can invoke this exception's `Throwable` constructor with the original exception.
An actual-source probe observed these chains:

- Recovery disabled: `NativeReleaseFailure` → exact native exception.
- Recovery enabled: recovered `NativeReleaseFailure` → original
  `NativeReleaseFailure` → exact native exception.

The probe inspected runtime debug/recovery flags, exception types and object
identity. Every mode retained the same root object, completed cleanup, and threw
instead of returning a result. No dependency or JVM setting is changed by the fix.

## Correction and preservation

Only the test changes. It catches the same required outer exception type and
checks its fixed message, then requires the exact injected native exception at
the root. This is stricter about native failure identity than matching a message
at a fixed depth. It additionally requires the caption job to be complete when
reset starts, exactly one reset, and the ordered events `caption_child_joined`,
`listener_closed`, `reset`, with no success notification.

`PostAnswerCaptionContinuation`, its `NonCancellable` cleanup, and the
`OrdinaryVoiceReplyStage` quarantine/rethrow branch remain byte-identical.
The production source SHA-256 is
`aeabbfa2ff721275f77879b50f8e80f3113f936a7bed6435bf44b9c8ba060dd7`;
the corrected test SHA-256 is
`5ef3f1295c8420af784ed2b8d4428fdf0b7f7ddd709fbe8010e84fa4040c9ac4`.

## Evidence and limits

The retained baseline and corrected receipts record every source/dependency
hash, compiler/test command and log hash. Their SHA-256 values are respectively
`5339df3d7169d4cdf55c20e0dda4f5c1d8b03b686e13a1dd3e644a1a6de00376` and
`1e18912bfd7391fd0bd0efb6428cfc6dd037b7ed73316ec3858f397735183be6`.
The pinned compiler JAR SHA-256 is
`d3e70fb011675e77ef00f1a68918d3cd91eed26058977d1e3ea2a37a293233af`;
coroutines core JAR SHA-256 is
`ad89c2892235e670f222d819cb3d81188143cb19a05b59df9889ae4269f5c70a`.
An initial probe-only Kotlin generic-inference compile error is retained; it did
not modify production/tests and was corrected before the controlled baseline.

These are focused source JVM results. The exact integrated revision still
requires all release JVM/native/build gates and five Android profiles before an
APK can be called verified. Native quarantine resource retention, real-model
inference, physical audio and device performance are not exercised by this probe.

Three isolated negative controls against copies of the same production source
also fail the corrected named regression under `-ea`: swallowing the reset
failure, replacing the native cause with a new same-message exception, and
emitting reset success before the failing reset. The third mutation additionally
fails the suite's existing successful-completion and accepted-input ordering
checks. The repository production file is never modified by these controls.
