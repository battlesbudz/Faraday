# Development setup and release handoff

Run commands from the repository root. The hosted workflow is the complete
reference gate; local checks are useful when the required tools/devices exist.
Read [AGENTS.md](../../AGENTS.md) before automated work and
[CONTRIBUTING.md](../../CONTRIBUTING.md) before changing ownership.

## Restore a checkout

Clone/fetch `battlesbudz/Jarvis-OS-V2` through your authorized GitHub access and
check out the agreed branch. Current audio integration uses `audio-pr2`, existing
PR #6. Use a separate worktree and explicit file scope for concurrent development.
A PR CI run tests GitHub's merge candidate; its receipt separately identifies the
branch head. Keep both identities when reviewing a candidate.

```bash
git status --short --branch
python3 scripts/dev.py map
python3 scripts/dev.py doctor
```

The doctor is read-only and reports missing tools. Its successful exit does not
mean an Android build environment is available. Generated `app/build`, Gradle/CMake
state and model weights are not source files and can be rebuilt/redownloaded.
Do not copy private app data or signing material into a checkout.

## Toolchain

| Requirement | Version/notes |
| --- | --- |
| Host JDK | 21, matching CI; LiteRT-LM 0.16.0 host classes require it |
| Gradle | Installed Gradle 8.10.2; this tree has no Gradle wrapper |
| Android SDK | Platform 35, build tools, platform-tools/adb; set `ANDROID_HOME` or local `sdk.dir` |
| Android NDK | `27.2.12479018` |
| CMake | `3.22.1`; also a host C/C++ compiler for native keyword checks |
| Python | 3.10+ for SDK preparation/build/check controllers |
| Emulator for full Android gate | Disposable API 30/35 Google APIs device able to execute `arm64-v8a`; hosted Linux runner provides KVM |
| Network/disk | Dependency/model archive hosts must be reachable; native dependencies and several-GB phone models need free space |

Application Java/Kotlin bytecode targets Java 17; this does not lower the host JDK
requirement. AGP/Kotlin/R8 pins are in the root Gradle files. `gradle.properties`
bounds build workers and gives Kotlin its own heap; use the build performance
artifact to diagnose slow tasks before changing those limits.

On a prepared SDK, install the pinned components with `sdkmanager`:

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.2.12479018' 'cmake;3.22.1'
gradle --version
```

Android Studio can import the root Gradle project. Keep `local.properties` local.
The first build prepares the pinned Sherpa SDK and optional QNN runtime and compiles native
speech/keyword code; subsequent builds reuse generated state and dependency caches.

## Checks

Start with the checks that correspond to the changed behavior:

```bash
python3 scripts/dev.py check
gradle testReleaseUnitTest
```

`dev.py check` runs the SDK-free architecture dependency guard and both Python
helper/harness suites, including nested verification artifact tests. The guard
checks concrete process/activity composition and storage-admission dependencies;
it is not a complete Kotlin parser or package-graph proof. This command does not
run JVM, native or Android tests. A targeted JVM
regression can use `gradle testReleaseUnitTest --tests '<fully qualified test class>'`;
the release gate still requires the complete JVM suite.

The host keyword gate mirrors CI:

```bash
cmake -S app/src/main/cpp/microwakeword -B /tmp/jarvis-keyword-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/jarvis-keyword-native --parallel 2
python3 scripts/check_keyword_models.py /tmp/jarvis-keyword-native/microwakeword_test
```

For a development APK, `gradle assembleDebug` uses Android's debug key and the
same ARM64 native requirements. A debug-signed build cannot update the permanently
signed release installation. Use a disposable device/profile for development.

## Signed release build

Release signing needs an existing keystore and these environment variables:
`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
`ANDROID_KEY_PASSWORD`. CI restores the same permanent key from
`ANDROID_KEYSTORE_BASE64` plus the password/alias secrets. Never commit the key,
print passwords, or generate a replacement key to bypass an update failure.

CI sets `ANDROID_VERSION_CODE`, `ANDROID_VERSION_NAME`, and `GITHUB_SHA` to bind
version/source identity to the run. Local defaults are version code 1, version
name `0.1.0`, and source `unavailable`; a locally built APK is not an exact CI
candidate merely because its filename matches.

With the signing environment configured:

```bash
bash scripts/ci_gradle.sh local-release testReleaseUnitTest assembleRelease assembleReleaseAndroidTest
```

Outputs are `app/build/outputs/apk/release/app-release.apk` and
`app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`. Preserve the
normal APK before rebuilding with `-PcompactApk=true`: both use the same output
path. CI stages `artifact/app-release.apk` and `artifact/app-compact.apk`, checks
parity and signs both. Compact packaging reduces the sideload download; Android
extracts its native libraries at install time. See [APK size audit](../apk-size-audit.md).

Run the complete disposable-emulator gate using the
[verification runbook](../verification/README.md). Its full run clears Jarvis data
and must not be pointed at a personal phone. Interactive snapshots are separate
from the destructive full-run fixture. The local repair gate runs one device;
hosted release acceptance includes both API 30 normal and API 35 compact variants.

## Native and release boundaries

`app/build.gradle.kts` wires SDK extraction and Sherpa's native build into
`preBuild`. Whisper CPU, Piper, Silero and Smart Turn retain the shared Sherpa
ORT 1.27.1 runtime. The optional QNN plugin uses that same ORT and packages its
reviewed SM8650 runtime subset as compressed assets. Moonshine and its separate
namespaced runtime are retired; no generic `pickFirst` or runtime replacement is
used. Existing downloaded Moonshine files and comparison history are preserved.

`voice/SherpaPcmCallback.java` is the concrete native PCM callback ABI.
`scripts/sherpa_jni_profile.py` limits compiled Sherpa sources/symbols.
`app/proguard-rules.pro` preserves native lookups and contracts used by the
separately shrunk release-test APK. Keep these synchronized when moving or renaming
an ABI owner; debug compilation cannot verify this boundary.

CI runs ASR packaging, Piper callback/retired-asset, signing and normal/compact
parity checks. [scripts/README.md](../../scripts/README.md) describes their commands.
Native caches contain generated products only; a cache hit never replaces the
current revision's tests or evidence receipt.

## Install and hand off

Use the [GitHub Releases page](https://github.com/battlesbudz/Jarvis-OS-V2/releases)
for the published standard or compact APK. Both have the same application ID and
permanent signature. A higher version code and matching key allow an in-place
update that preserves private app data; uninstall/clear-data removes models,
conversations and device-local memory. The manifest disables Android backup.

After installation, choose a catalog model, download or import its supported
`.litertlm` bundle, prepare the selected ASR/Piper assets, and use the setup smoke
check. Model integrity/readiness and inference smoke acceptance are distinct.
Phone validation then checks actual recognition, call lifetime, interruption,
context, action receipts and latency; use [voice acceptance](../verification/voice-audio-and-metrics.md).

A release handoff states the source/branch-head identity, numbered GitHub Release,
normal/compact APK hashes, JVM/native gate, both sandbox outcomes, receipt, and
remaining device/model gaps. Evidence artifacts expire after 14 days, so retain
relevant failures/measurements when investigating long-lived problems. No count of
historical passing builds establishes that the current candidate passed.

## Common blockers

| Symptom | Check first |
| --- | --- |
| `UnsupportedClassVersionError` in host tests | JDK 21 is active for Gradle, not only the shell |
| SDK/NDK/CMake missing | Doctor output and the pinned versions above |
| Cold native/dependency build stalls | Network access and native task logs; do not remove required checks |
| Release runner fails before tests | Shared R8/class-loader ABI and callback/package keep rules |
| Emulator cannot install/run shipping APK | ARM64 runtime support, Google APIs image and available KVM |
| APK update rejected | Signature and version code; preserve data while diagnosing |
| Build passes but publication is blocked | Both sandbox jobs and exact-build receipt for this same run/attempt |
