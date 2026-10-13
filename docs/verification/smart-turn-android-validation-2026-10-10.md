# Bounded Smart Turn Android validation candidate

This candidate extends existing journey 81 with the real production
`NativeSmartTurnBackend(File)` and JNI session. The endpoint change owns the
journey hook and narrow R8 entry points. This harness alone has not run on Android
and is not an APK verification receipt. The next signed APK/test DEX pair must
have its cross-DEX descriptors audited and pass all required emulator profiles.

## Fixed scope

- One worker and native session; two actual inferences plus one deliberately
  pre-cancelled request, followed by successful reuse of that same session.
- The existing frozen `silence_8s` and `tone_440hz_1s` PCM fixtures are copied into
  test assets and their decoded SHA-256 values are checked before inference.
  The adjacent test-asset manifest records generator provenance, exact source
  copies, and compressed plus decompressed byte counts/SHA-256 values.
  Source assets retain their frozen `.pcm16le.gz` bytes. AAPT expands those
  gzip assets into `.pcm16le` entries in the test APK; the probe opens the
  packaged raw names and checks exact byte counts, EOF and decoded SHA-256
  before inference. Source-copy checks alone do not verify this APK boundary.
  Their expected COMPLETE/CONTINUE sides of the upstream strict `> 0.5` threshold
  come from the [retained host receipt](evidence/smart-turn-host-2026-10-09/receipt.json).
  There is no invented probability tolerance or new semantic-accuracy claim.
- Actual probabilities pass through the production endpoint policy. Independent
  speech eligibility is required: silence's high COMPLETE probability cannot
  shorten a capture with no speech evidence.
- The worker has a 45-second join cap and a 2-second cleanup join cap. Timeout
  performs only Java cancellation/interrupt from the caller. No caller-thread
  JNI operation or concurrent close can conceal a blocking native lock. A live
  worker, timeout, or uncertain cleanup fails the probe. The sole worker closes
  the session after its native operation returns. The journey sends a fixed start
  status first. On observing that status, the controller starts its independent
  55-second deadline (0.1-second queue polling). Failed/malformed/duplicate result,
  missing terminal result, phase exception or this deadline requests an immediate
  owned-app force-stop before lengthy evidence collection. Stop and affirmative
  process-absence checks each have a 10-second command deadline. Unacknowledged
  process absence, including adb errors/empty output, fails verification. These
  are requested termination bounds, not hard real-time native cancellation.
  Delivery of the start status is outside the 55-second clock; if it never arrives,
  the unchanged 900/1200-second main-suite deadline remains the transport fallback.
- This remains part of journey 81. The existing five profiles, named-test
  contract and current main-suite budgets (Fold: 1200 seconds; the other four: 900 seconds) are unchanged.

## Input and artifact boundary

`smart_turn_input.py allocate` first creates a unique attempt directory and an
ownership receipt binding its random nonce, canonical path and filesystem
device/inode. It publishes the directory/nonce to CI only after acknowledging
creation and writing the receipt. Collisions, failed mkdir and uncertain creation
publish no cleanup coordinates and preserve unowned paths. Fetch, the controller,
the local gate and cleanup all require that exact ownership receipt; a directory
argument alone cannot authorize deletion. Cleanup is idempotent after removal.

`smart_turn_input.py fetch` then performs one transfer from the immutable official
Pipecat URL pinned in `SmartTurnModelSpec`, with a 15-second network timeout,
90-second budget checked between reads, 8,679,182-byte cap and exact SHA-256 check. The CI step
has an outer two-minute limit. Socket inactivity and between-read checks alone
are not a strict standalone CLI wall-time bound. It uses a new task-owned directory under
`RUNNER_TEMP`, outside the checkout's build and evidence/upload directories.
Failure removes partial input; no retries or substitute weights are accepted.
The build APK and instrumentation APK are checked for named or renamed copies
of this model before staging. This check requires both APKs to exclude the model.

After the real previous-APK upgrade and fresh candidate reset, `android.py`
stages the model under `/data/local/tmp/jarvis-smart-turn-input-<unique token>`
and verifies device size/hash. Only the main suite receives that exact path.
The probe validates its complete path before reading through a UiAutomation
shell file descriptor into one bounded private temporary file. The shipping
wrapper independently verifies immutable bytes before model initialization.

Only the exact fixed scalar schema enters instrumentation output and `report.json`.
The controller requires the 45,000/2,000-ms caps, all lifecycle booleans, the fixed
coverage labels and finite nonnegative timings; missing or extra fields fail.
Neither PCM/model bytes, features/tensors nor native exception messages are
exported. Evidence collection still pulls only the unique
`/sdcard/Download/jarvis-verification-<token>` directory, never the input tree.

Probe `finally` deletes its private file. After all required phase assertions
and evidence extraction, controller cleanup force-stops the disposable target,
requires a checked fixed acknowledgment of process absence, and requires `pm clear` success to remove any
private copy left by a failed/wedged worker. It separately deletes and verifies
the remote directory and host inputs. Any uncertain location makes verification
fail. The workflow's always-run host cleanup also covers emulator boot failure
before the controller starts; unexpected host files are preserved and fail
cleanup rather than being recursively deleted.
Remote cleanup requires an acknowledged successful directory creation. An
explicit collision never grants ownership; a lost response leaves creation
uncertain, fails verification and preserves the unowned path.

Manual full-gate callers allocate first, then supply `--smart-turn-input-dir` and
`--smart-turn-input-owner`; `local_gate.py` uses `JARVIS_SMART_TURN_INPUT_DIR` and
`JARVIS_SMART_TURN_INPUT_OWNER`. The directory is consumed and removed by the
controller, so each new run needs a newly fetched, verified temporary input.
The local gate establishes that ownership before all other fallible preflight,
so missing signing/baseline/profile/source fields also clean the recognized input.
Never target a personal phone: the existing emulator-only admission guard and
explicit reset flag remain required.

## Checks and limits

Focused helper checks are:

```sh
python3 -m unittest discover -s scripts/verification -p 'test_smart_turn_input.py'
python3 -m unittest discover -s scripts/verification -p 'test_android_phases.py'
```

The first exercises pinned fetch identity, corrupt/truncated/oversized inputs,
APK exclusion, partial-push cleanup, lingering processes, cleanup failures and
scalar receipt rejection. The second preserves upgrade ordering and all
existing assertions while checking that staging follows fresh reset, upgrade
gets no model path, cleanup follows evidence, and main budgets stay unchanged.
Fresh compilation of the whole probe and actual production wrapper/policy
sources against cached Android 14/Kotlin APIs passes without signature stubs.
This does not run JNI, R8, instrumentation or any model weights locally.

The Android result, once obtained, can establish native/wrapper/control-input
compatibility on the declared disposable emulator matrix. It cannot establish
natural-language endpoint accuracy, real microphone behavior, physical Fold
latency, thermals, or final device signoff.

## Settings composition boundary (Build 1264)

Build 1264, run `38047133140`, retained API 30's `StaleObjectException`
at journey 81's `disable.text` read, before the enabled-parent lookup and tap.
The old and new compositions both contained the Disable label; retaining a
label node across replacement did not establish the new control's state.

Journey 81 now observes the initial disabled state through one selector predicate.
After the enabled render, a selector requires the exact resource ID, enabled and
clickable parent, and its exact `Disable Smart Turn` text descendant together.
Readiness uses `UiObject.waitForExists(15_000)`. The one `UiObject.click()` resolves
that same complete selector again, using a temporarily zero selector timeout;
replacement by an ineligible/missing control fails without another readiness
window. The prior global timeout is restored in `finally`. The label's visible
center is the tap point inside the selected button. No `UiObject2` returned by
scrolling or an earlier composition is read or clicked.

Only read-only lookup polls. There is one action call, with no action retry on
missing acknowledgment or exception. The existing Enable-label observation and
preference assertion still establish completion, followed by recreation,
persistence, input/caption-mode, native-probe and preference-cleanup checks.
Migration/default-on and model-download-label checks are unchanged. The initial
disabled-state observation now has a separate 15,000-ms polling window;
enabled-control readiness retains its 15,000-ms argument, final dispatch adds no
selector wait, and the outer suite caps are unchanged. UiAutomator's polling
granularity and synchronous idle/accessibility/input operations are not strict
wall-clock caps.

Thirty source-extracted host cases execute the real cached UiAutomator 2.3.0
selector, query and `UiObject` bytecode against node/clock/input adapters and the
retained failure hierarchy. They cover replaced nodes, old disabled compositions,
wrong labels/parents, hidden/missing controls, one dispatch and timeout restoration.
Six rejected mutations cover relaxed state/label conditions, a second wait,
replayed action and leaked timeout. The full extracted journey, production
settings UI and native-probe/policy sources compile against cached Android/Compose
APIs. These are host/API checks, not Android timing, JNI/model inference, R8/APK,
disk-persistence or device verification. A fresh exact-revision release gate is
still required.
