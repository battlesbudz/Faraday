# Benchmark document destinations — 9 October 2026

Build 1244 ([run 37970288706](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37970288706))
tested `f783849de9cb6ebb2932eba52b7ebdc596aa02fa`. The Copy-part-2 selector
passed, and test45 reached its cancellation/repeated-save checks before failing
the existing `CATEGORY_OPENABLE` assertion at line 3183. The controlled registry
records the production contract's intent without modifying its categories.

## Cause and repair

The pinned AndroidX Activity 1.10.0 `CreateDocument.createIntent` bytecode sets
`ACTION_CREATE_DOCUMENT`, MIME type and filename, without adding OPENABLE.
This was checked against the cached official [1.10.0 AAR](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.10.0/activity-1.10.0.aar),
whose SHA-256 is `c72301bc7307769cd6c3e0edb8da5837e7dea6fd89a5cdedfcfc87bd581850e1`;
its extracted classes.jar is
`df62a5e8df0818756e10eed86fef04fd3cd6e8437f513c4480d3807980506b7b`.
Executing that exact stock contract with the actual Android 14 framework also
confirms the missing category.

`BenchmarkExportFiles.createDocument` subclasses that contract and adds only
`Intent.CATEGORY_OPENABLE` to the superclass intent. TXT, JSON and CSV launchers
all use the factory. Action, MIME, title, result parsing, cancellation and save
callbacks retain their existing behavior. The Android transport owner already
owns ContentResolver writes and is retained across the release/test DEX boundary.
The original test45 method, selector, category assertion, scenario list, deadlines,
payload ownership and launcher recreation rules are unchanged.

## Focused verification

The existing Android journey probe now verifies all three formats before its
repeated transport checks: three requests on each contract, one recreated
contract, fresh intent identities, action/MIME/title/extension/OPENABLE,
asynchronous results, cancellation, null results and successful URI results.
The 12 intents and result checks total 78 assertions. This helper runs from the
existing mandatory test45; no new scenario or timeout is introduced.

Fresh host execution compiled the complete, unchanged production transport and
instrumentation-probe source against AndroidX Activity 1.10.0 and the real
Android 14 `android-all` framework. All 78 assertions passed. `ContextWrapper(null)`
provides the unused context argument; no Android classes were substituted.
The stock 1.10.0 contract remains a negative control. Removing only the added
category from a temporary production-source copy makes the same helper fail
at its category assertion, as expected.

Fresh Kotlin/Compose compilation also passed for the unchanged exact test45
method, current benchmark production/UI source and probe against cached real
Android, Activity 1.10.0, Compose 1.7.6, Material 3 1.3.1 and UiAutomator 2.3.0 APIs.
That compilation uses signature-only activity/navigation/evidence helper seams,
generated BuildConfig constants and unchanged extracted conversation declarations.
The [source-bound receipt](evidence/benchmark-document-contract-2026-10-09.json)
records source and dependency hashes, checks and limitations.

These host checks do not execute a system document picker, ContentResolver IPC,
Compose lifecycle, Gradle, R8, APKs or devices. The exact combined revision still
requires all release JVM/native/build and five-profile Android gates. Physical
devices, third-party picker compatibility, actual model inference and acoustics
remain outside this focused repair's evidence.
