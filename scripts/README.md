# Repository tooling

Start with these commands from the repository root. They need only Python 3.10+
and the standard library:

```bash
python3 scripts/dev.py --help
python3 scripts/dev.py map
python3 scripts/dev.py map conversation
python3 scripts/dev.py doctor
python3 scripts/dev.py check
python3 scripts/check_architecture.py
```

`map` reads the current source tree, lists production packages, shows the ten
largest production source files, and counts the JVM and Android test source
sets. The optional query searches paths and package names. Source size is a
navigation aid; it is not an acceptance threshold.

`doctor` reports tool availability and SDK/signing configuration presence. It
never displays signing values, changes configuration, installs dependencies, or
fails simply because a local Android environment is unavailable. Tool presence
does not validate its version. See [the verification prerequisites](../docs/verification/README.md#local-checks-and-device-exploration)
for the pinned versions and disposable-emulator requirements.

`check` checks architecture dependencies, runs both Python helper suites, and
returns a failure from any of these checks. The hosted workflow's helper-test
step uses this same command. It resolves
the repository from its own file location, so an absolute
invocation also works from another directory. The two underlying commands are:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s scripts/verification -p 'test_*.py'
```

The second discovery is explicit because `scripts/verification` is a namespace
directory; ordinary discovery from `scripts` does not recurse into it. Python
checks cover helper logic and failure injection. They do not run JVM, native,
Android, acoustic or real-model tests and do not establish APK verification.
The build-751 retry regression uses a checked-in minimal synthetic fixture in
`verification/fixtures`, preserving the documented artifact-ID ordering without
depending on a previous developer's scratch directory. It is not CI evidence.

`check_architecture.py` guards the concrete composition and storage-admission
boundaries: feature implementations cannot reference `JarvisRuntime` or
`MainActivity`, and model code cannot read conversation implementation state.
The explicit entry-point allowlists retain the Android lifecycle and public UI
compatibility adapters. Comments/literal text are ignored while executable Kotlin
string interpolations are checked; this is a source
guard, not a Kotlin parser or a complete package dependency analysis. Code review
and compilation remain required. There are no file-length thresholds.

## Build inputs and native preparation

Gradle invokes these scripts as declared build inputs. Keep their existing paths
and command-line contracts stable; moving a helper also requires updating its
Gradle inputs and native cache keys.

| Helper | Responsibility | Main caller |
| --- | --- | --- |
| `prepare_sherpa_sdk.py` | Extract the official pinned Sherpa Kotlin ABI (`classes.jar`). | `app/build.gradle.kts` |
| `build_sherpa.py` | Build the pinned Sherpa JNI/ORT runtime and apply the required JNI profile. | `app/build.gradle.kts` |
| `sherpa_jni_profile.py`, `sherpa_required_symbols.json` | Declare and validate the Sherpa JNI pruning/symbol contract. | `build_sherpa.py`, `check_asr_apk.py`, helper tests |
| `prepare_whisper_qnn.py` | Verify and package the optional QNN plugin/SM8650 runtime as compressed assets without adding another ORT. | `app/build.gradle.kts` |
| `ci_gradle.sh` | Run the caller's Gradle tasks and retain non-secret build-performance diagnostics. | `.github/workflows/android.yml` |

Whisper CPU, Piper, VAD and Smart Turn share Sherpa's source-built ORT 1.27.1.
The optional QNN plugin uses the same runtime; its packaged vendor code is verified
and extracted privately only on supported devices. Moonshine's SDK, runtime and
preparation path are retired. Historical `d2-replay` scripts remain archival.

## Release validation and evidence

The [verification guide](../docs/verification/README.md) and
[feature acceptance map](../docs/verification/features.md) define the full
release contract. These helpers each validate a specific boundary:

| Helper | Responsibility |
| --- | --- |
| `check_keyword_models.py` | Exercise both pinned keyword models with the actual compiled native interpreter. Requires the native test executable as its argument. |
| `check_asr_apk.py` | Validate APK native speech symbols and transitive packaged dependencies. |
| `check_tts_callback.py` | Validate the packaged TTS callback ABI. |
| `check_compact_apk.py` | Compare normal and compact APK contents against the existing variant contract. |
| `apk_size_report.py` | Produce APK component-size evidence and baseline comparisons. |
| `verification/android.py` | Execute named release journeys on a disposable emulator; retain commands, screenshots, UI XML, logs and results. |
| `verification/runtime_gc.py` | Retain the historical API 35 simulated 16 KB collector repair and its bounded proof; dormant for the required stock API 36 image. `android.py` owns APK installation, upgrade and all release phases. |
| `verification/artifacts.py` | Select/download exact-run artifacts using producer-attempt timestamps, revision binding and archive checks. |
| `verification/receipt.py` | Consolidate APK hashes, JVM/model/native results and every required emulator profile and phase before publication. |
| `verification/profiles.json`, `profiles.py` | One device provisioning, artifact-selection and receipt contract for the required matrix. |
| `verification/previous_release.py` | Fetch and checksum an actual earlier published APK for replacement-install upgrade tests. |
| `check_recorded_audio.py` | Execute pinned host Whisper weights on recorded phrase boundaries and quiet endings with scored evidence. |
| `check_page_sizes.py` | Audit every native ELF and direct-load APK alignment, including RELRO writable-page conflicts. |
| `verification/local_gate.py` | Build the signed release/test APKs and run the emulator gate in an already configured repair environment. |
| `verification/repair.py` | Bound local repair/retest attempts and retain failures; stop changes to protected acceptance infrastructure. |

Use each argparse-based helper's `--help` for its exact arguments. Read the
verification guide before invoking the Android controller or local repair gate:
a full Android run clears app data and requires a disposable emulator. Local
repair needs its documented signing and attempt environment; `dev.py check`
does not supply these prerequisites or replace any release gate.

## Targeted investigations

`check_asr_onset.py` and `scripts/d2-replay/` support targeted speech diagnostics.
The replay experiments, inputs and coverage limitations are described in
[d2-replay/README.md](d2-replay/README.md). They are investigation tools with
separate model/audio prerequisites, rather than substitutes for the release
acceptance suite.

For a change, choose the smallest relevant checks first, retain their failure
evidence, then run the required exact-revision release gates. Introductions to
the code and dependency boundaries live in
[docs/architecture/README.md](../docs/architecture/README.md).

### Smart Turn shadow frontend

`python3 scripts/smart-turn/verify_frontend.py --receipt <output.json>` compiles
only the CPU feature extractor and compares all eight independent frozen feature
fixtures. It does not download or execute model weights. The same checks run in
`test_smart_turn_contract.py` with the existing helper gate. Explicit expectation
regeneration is separate: `generate_goldens.py` needs NumPy 2.3.5 and must preserve
all original decoded hashes unless an independently reviewed frontend change
requires new fixtures. See the [recovery contract](../docs/verification/smart-turn-recovery-2026-10-09.md).

## Optional Whisper QNN

`prepare_whisper_qnn.py` consumes the two checksum-pinned public Qualcomm AARs,
packages only precompiled-context v75 runtime gzip assets and preserves notices.
It does not replace Sherpa ORT or download model weights. The final APK gate
checks the embedded compressed objects, ABI, page compatibility and licenses.
Weight-free native checks: `app/src/main/cpp/whisperqnn/tests/verify_frontend.py`
and `verify_decoder.py` (the latter needs the checksum-pinned host ORT1.27.1 and
JDK JNI headers). The optional `scripts/whisper-qnn/run_kotlin_tests.py` takes an
existing dependency cache and performs no downloads. See the [candidate contract](../docs/verification/whisper-qnn-2026-10-11.md).
