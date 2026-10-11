# Release verification runbook

Start with [CONTRIBUTING](../../CONTRIBUTING.md), [the architecture map](../architecture/README.md)
and [the feature acceptance map](features.md). Agent changes also follow
[the repository verification skill](../../.agents/skills/jarvis-verify/SKILL.md)
and [AGENTS.md](../../AGENTS.md).

## Authoritative release gate

`.github/workflows/android.yml` defines the gate. Same-repository PR updates and
pushes to `main`, `PR1`, `feature-tools` and `feature/**` are opted in. Work on
`audio-pr2` uses existing PR #6. A feature with an open PR can trigger both a
branch-head build and a separate PR merge-candidate build; they test different
commits. Do not create or merge another PR without Justin's permission.

1. Check native keyword models and Python helpers, run all release JVM tests,
   and build the signed/minified production APK and release instrumentation APK.
2. Build the compact APK; verify native/DEX/asset equivalence, speech packaging,
   Piper callback ABI and signatures. Both variants have increasing build codes
   and use the existing signing identity.
3. Run `.github/workflows/android-sandbox.yml` on the five disposable Android
   profiles in `scripts/verification/profiles.json`: API 30 normal phone,
   API 35 compact phone, API 36 normal phone, API 36 compact foldable and
   API 36 normal 16 KB. Android 11/API 30 is the minimum supported OS;
   the APK declares `minSdk=30`. Android 10/API 29 support and its software
   emulator job/launcher are retired. Historical failed results remain in the
   feature map; removing support is not a claim that those failures were fixed.
   Every current profile uses the existing Ubuntu/KVM x86-64 runner with
   observed ARM64 translation. All retain the 300-second boot, 180-second
   install and 40-minute job limits. The main-suite limit is 1,200 seconds only
   for `36-foldable-compact`; the other four profiles retain 900 seconds. The retired API 29
   2,400-second/90-minute allowances cannot be selected by this contract.

   The foldable uses the SDK's genuine `pixel_fold` hardware definition from
   pinned command-line tools 23.0, build 16111833: a 2208×1840 inner screen,
   a 1080×2092 cover display and a 0–180° hinge. Its verified pinned catalog
   is promoted to `cmdline-tools/latest`, which emulator-runner consumes;
   the catalog and owned X-display receipts are retained. Real fold/unfold
   commands, actual display-size changes and state continuity remain required.
   After a real transition the journey wakes the device and observes keyguard
   dismissal within its existing deadline; it never relaunches the activity
   to restore call/draft state. The actual 16 KB profile is described below.
4. Run every named method in `scripts/verification/scenarios.json`, retain a
   screenshot and UI hierarchy per scenario, then run the separately retained
   external process-loss, upgrade, platform and layout phases. The previous
   numbered user APK is installed and populated before replacement without
   clearing its data; ordinary isolated journeys still use disposable resets. That JSON is the current test contract;
   historical test counts in old reports are not current requirements.
5. Run real recorded-speech inference with the pinned Whisper host runtime,
   audit both APKs for native 16 KB compatibility, and exercise actual prior-APK
   upgrades and platform/lifecycle/layout phases on every required profile.
6. Consolidate exact same-run JVM, APK and emulator evidence, rehash artifact
   bytes, reject failed/skipped/missing/duplicate outcomes, then publish both
   APKs in a numbered GitHub Release. Publication depends on all prior gates.

The independent release test DEX shares the app's class loader.
`app/proguard-rules.pro` therefore preserves the runtime/framework interfaces
referenced by instrumentation, including lazy-layout methods. Recheck this ABI
boundary when moving classes or changing test dependencies. Do not substitute
an unshrunk/debug build for release verification.

## API 36 16 KB coverage

The required `36-16k-normal` profile uses API 36 `google_apis_ps16k` on the
existing x86-64/KVM runner. Official stable revision 7 metadata identifies
Android 16 SDK 36, build `BE2A.250530.026.F3/13894323`, an ARM64
`libndk_translation.so` bridge and `page_shift=14`. The installed candidate
still needs actual runtime verification: the controller requires SDK 36,
`getconf PAGE_SIZE=16384`, the ARM64 ABI and a nonempty/nonzero bridge before
installation. The unchanged native-loading journey independently checks the
page size and every required shared library. An image label alone cannot pass.
All upgrade, main, process/permission recovery, layout and receipt gates remain
required; the KVM boot/job/main limits remain 300 seconds/40 minutes/900 seconds.
The API 36 candidate boots with stock runtime settings, without the historical
API 35 DeviceConfig override or forced recompilation/reboot.

The October 6 approved matrix change replaces API 35-specific 16 KB coverage.
API 35 compact coverage at 4 KB remains required. Android 15 with 16 KB pages
is now an explicit coverage gap, not a repaired or passing profile. No claim of
physical 16 KB ARM64 hardware, OEM behavior, real acoustics/models or device
performance follows from this x86-64 simulated-page test. Revision metadata
establishes feasibility, not successful boot or full release verification.

Every supported profile declares its exact `instrumentation_timeout` in the
same contract used by provisioning, artifact selection and receipts: 1,200
seconds for the named API 36 compact Fold profile and 900 for the other four.
The October 10 Fold allowance follows Build 1254's 76 passing journeys taking
894.318 seconds before the aggregate 900-second timeout interrupted test77.
The suite grew from 48 journeys when 900 seconds was introduced to 82 now.
This is a bounded main-suite capacity change; all named tests, screenshots,
readiness checks and individual assertion/upgrade/lifecycle/layout deadlines
remain required. No sharding, retries or outer job-limit increase is introduced.
See the Build 1254 capacity record in [the feature map](features.md).
Android 10/API 29 was removed from the supported matrix on October 6; its
earlier capacity trial is historical only.

### Historical API 35 collector trial

The former `35-16k-normal` image repeatedly crashed stock Android processes.
The [AOSP kernel compatibility fix](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73)
explains the x86-64 16 KB simulation/UFFD mismatch; the
[ART regression test](https://android.googlesource.com/platform/art/+/8222aa2d2df6273da689f0edd3913e8370c0c1c2)
documents `runtime_native_boot/force_disable_uffd_gc=true` and one reboot.
Builds 1042 and 1050 verified the flag, complete ART regeneration (`returned 80`)
and actual CC selection, then still encountered native system-server faults
before APK installation. The narrowly scoped `runtime_gc.py` helper and its
failure tests remain as historical implementation evidence; the active API 36
profile cannot invoke it. Detailed failed artifacts and collector proof remain
in [the feature acceptance map](features.md).

## Run identity and evidence

Find the run for the exact changed branch head. Record its URL, job conclusions,
`pr_head`, tested `source_commit`, APK SHA-256 values and receipt. On a PR event,
`source_commit` is GitHub's tested merge commit and `pr_head` is the branch revision;
on a push, `source_commit` is the pushed commit and `pr_head` is empty.

`scripts/verification/artifacts.py` selects artifacts using the current run,
source SHA and latest completed logical producer attempt, with timestamp-window
checks. It downloads by artifact ID, validates metadata and safely extracts ZIPs.
Numeric artifact ordering is not provenance. Old failed-attempt artifacts remain
evidence and are never substituted for the current result.

The `jarvis-verification-receipt` artifact contains `receipt.json` and a readable
summary. Emulator evidence includes `report.json`, `instrumentation.txt`, logs,
screenshots and UI XML. Verification artifacts are retained for 14 days; save
needed failure evidence before it expires. APK releases remain the phone handoff.
A prior green run or a successful build alone does not verify a new revision.

## Local checks and device exploration

Python 3.10+ runs the dependency/helper and harness logic suites:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts/verification -p 'test_*.py'
```

A full local Android build needs JDK 21, Gradle 8.10.2, SDK/platform 35,
NDK 27.2.12479018, CMake 3.22.1, adb, a compatible disposable emulator and the
existing signing environment. Android app bytecode targets Java 17; LiteRT host
tests require JDK 21. Hosted CI is the default when local SDK/KVM is unavailable.
Do not commit signing material, print credentials or copy CI signing secrets to
another environment.

Explore an already booted disposable emulator with:

```bash
python3 scripts/verification/android.py --serial emulator-5554 control launch --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control snapshot --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control tap 250 400 --out verification-runs/explore
python3 scripts/verification/android.py --serial emulator-5554 control back --out verification-runs/explore
```

Run the full local device gate using new output directories:

```bash
python3 scripts/verification/android.py --serial emulator-5554 run \
  --apk app/build/outputs/apk/release/app-release.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --previous-apk verification-inputs/jarvis-previous-release/app-release.apk \
  --previous-metadata verification-inputs/jarvis-previous-release/previous-release.json \
  --profile 30-phone-normal \
  --out verification-runs/manual/device \
  --source-commit "$(git rev-parse HEAD)" --allow-emulator-reset
```

The full gate performs a previous-APK upgrade before clearing disposable fixture
data for its isolated regression suite, and refuses non-emulators. Its previous
APK and metadata must come from the checksum-verified release helper.
`local_gate.py` requires `JARVIS_PREVIOUS_APK`, `JARVIS_PREVIOUS_METADATA` and
`JARVIS_EMULATOR_PROFILE` in addition to its existing signing/device environment. Never target a personal
phone with stored conversations/models. Interactive control does not clear data.
Preserve evidence before resetting or stopping the device.

## Diagnose and repair

Classify each failure first: product regression, harness defect or infrastructure.
Read the raw report/logs and inspect screenshots for layout claims. Fix product
regressions in the app; explain harness corrections explicitly and retain every
acceptance assertion. Retry a transient infrastructure failure once when justified.
Do not cancel unrelated runs, weaken a gate or use application changes to hide
missing SDK/ABI/credentials.

The verification skill sets a default budget of three repairs and 60 minutes per
feature investigation. `scripts/verification/repair.py` and `local_gate.py` support
bounded local repair with an installed/authenticated coding CLI and existing
signing/emulator environment. Commands are argv arrays, not shell expressions;
the loop records attempts and stops automatic repairs that modify acceptance
infrastructure. See `python3 scripts/verification/repair.py --help` for options.
Hosted diagnosis and repair are performed by the active Work/Codex session;
GitHub Actions does not continue autonomous coding after that session ends.

For new Android journeys, update `ReleaseJourneyTest.kt` and `scenarios.json`
together. Keep failure cases and preserved behavior explicit in `features.md`.
[Modular-refactor acceptance](modular-refactor.md) describes structural changes.

## Coverage and handoff

The emulator checks production UI, storage and real Android action executors.
Controlled model responses and fake backends test logic, not actual weights.
It does not verify real-model inference/tool selection/approved-memory use,
physical microphone/speaker/Bluetooth/echo/interruption behavior, large real
model transfers or Fold 6 GPU/thermal/latency performance. These remain phone
checks and appear in the receipt's `not_covered` list.

Return the verified branch/commit/run, concise changes, passed checks, remaining
coverage gaps and GitHub Release link. Passing the gate produces a release
candidate for Justin's product signoff; it does not authorize merging a PR.

Historical implementation and failure records remain in [features.md](features.md),
[combined-audio.md](combined-audio.md) and the linked plans/diagnostics. Current
workflow code and scenario JSON take precedence over those historical snapshots.
