# Gate A1 — Privilege Bootstrap and Shell-Helper Lifecycle

## Implementation record (written before code)

Package and placement confirmed from the live tree:

- App namespace/application ID: `com.battlesbudz.jarvis.v2`.
- Entry points: `app/src/main/java/com/battlesbudz/jarvis/v2/MainActivity.kt`
  and `JarvisRuntime.kt`.
- Accessibility component: `actions/ScreenControlService.kt`, declared in
  `app/src/main/AndroidManifest.xml` as `.actions.ScreenControlService`.
- AIDL placement: `app/src/main/aidl/com/battlesbudz/jarvis/v2/privilege/`.

The charter closes the shell helper surface to these operations only:

1. Create and own a virtual display (A2 consumer).
2. Enable Faraday's declared accessibility service.
3. Grant only permissions enumerated by A7.

The current A7 `ActionControlPolicy.HelperPermissionList` provides a unique,
nonblank string-list contract, while its existing test demonstrates the
symbolic entries `display` and `accessibility`. It does not declare Android
permission constants or define mappings from those symbols to package-manager
grants. A1 therefore records those two names as the inherited symbolic list;
no additional Android permission grant is inferred. A future Android
permission-name allowlist requires an A7/charter amendment before the helper
can grant it.

The narrow AIDL surface is `createVirtualDisplay(name, width, height, densityDpi)`,
`releaseVirtualDisplay(displayId)`, `enableAccessibilityService(component)`,
`grantPermission(permissionName)`, and `getPrivilegeState()`. No shell,
arbitrary command, package targeting, file access, input injection, or generic
capability method is exposed. AIDL is an app-facing contract; the shell helper
must independently enforce the same closed operation and permission allowlists.

## Implementation record (finalized after code)

Files created or changed:

- `app/build.gradle.kts` — enables AIDL generation.
- `app/src/main/aidl/com/battlesbudz/jarvis/v2/privilege/IPrivilegeHelper.aidl` — the five operations recorded above.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootstrap.kt` — pairing state machine, endpoint value validation, default Android pairing transport, recovery decisions, strict helper contract and absent-helper operation gate.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AndroidAdbEndpointDiscovery.kt` — reads the phone's wireless-debugging port and active local IPv4 endpoint.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AndroidAdbPairingTransport.kt` — TLS 1.3 pairing channel, Android TLS exporter, ADB packet framing, SPAKE2 exchange, HKDF/AES-GCM PeerInfo exchange and precise blocked failures.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AdbEd25519.kt`, `AdbSpake2.kt`, and `AdbPairingAuth.kt` — Apache-2.0 Mika adaptations for AOSP Curve25519 SPAKE2 and pairing authentication.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootReceiver.kt` — records a precise blocked state after boot until a helper session is re-established.
- `app/src/main/AndroidManifest.xml` — registers the boot receiver.
- `app/src/test/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootstrapTest.kt` — deterministic pairing, helper policy, recovery and refusal tests.
- `docs/architecture/README.md` — records the new privilege owner.
- `GATE-A1.md` — this final implementation and proof record.

The permission list remains exactly `display` and `accessibility`, inherited symbolically from A7. No Android permission grant was inferred. The AIDL component and names match the recorded surface; no charter correction was needed.

**Transport implemented; A1 is not operational yet.** The default pairing coordinator now uses an AOSP-compatible ADB pairing client: platform TLS 1.3 first, 64-byte TLS exporter value labelled `adb-label\0` appended to the UTF-8 pairing code, Curve25519 SPAKE2 inside TLS, then HKDF-SHA256/AES-128-GCM and the 8,192-byte PeerInfo exchange. TLS exporter access uses the public `android.net.ssl.SSLSockets.exportKeyingMaterial` API (available from API 29; Faraday's minSdk is 30). Pairing failures remain blocked with distinct endpoint-unreachable, TLS-handshake, wrong-code and protocol reasons. The coordinator still accepts an explicitly absent transport for the existing fail-closed contract test.

The SPAKE2, Ed25519 point arithmetic, and pairing-authentication implementation were **adapted from Mika**, not independently clean-implemented. Package names were changed for Faraday; Apache-2.0 attribution and license reference are recorded in `THIRD_PARTY_NOTICES.md` (a full Apache-2.0 text is already included in the app's license assets). The reference was Mika HEAD `2ca391c4c868`: `core/adb/{AdbTls,AdbPairing,Spake2,Ed25519,PairingAuth}.kt` and `app/.../shell/adb/AndroidAdb.kt`. Deterministic checks use Mika's published `spake2-vectors.txt` first oracle fixture (fixed Alice/Bob seeds, role messages, shared 64-byte key and AES-GCM sequence-0 ciphertext), and re-derive Mika's M/N points from the published edwards25519 seed strings. A fake channel exercises the real transport and coordinator without a device socket.

The shell-UID helper launch/channel through `app_process`, the shell-side backend for the five AIDL calls, durable ADB identity storage, runtime integration, automatic heartbeat/recovery wiring, and boot-time session restore remain for the next pass. `MainActivity`, `JarvisRuntime`, and `ScreenControlService` are not wired to live helper state. The transport's TLS identity is currently process-ephemeral; the helper/integration pass must define and own durable identity lifecycle before a paired session can be reused across process restarts. No USB or root route was added.

## Gate tests

The required deterministic gate cases are:

- Pairing-state handling: idle, endpoint discovery, pairing, connected,
  blocked, and recovery transitions reject illegal transitions and preserve
  precise failure reasons.
- Mika SPAKE2 known-answer messages/shared key/AES-GCM ciphertext; M/N point
  re-derivation; fake-channel success through the transport and coordinator;
  wrong-code, unreachable-endpoint and TLS failure blocked reasons.
- Helper round trips: only the declared AIDL operations succeed; unknown
  operations and permission names are refused.
- Watchdog recovery decisions: a responsive helper is retained, a missing
  helper requests consented wireless-debugging recovery, and revoked/unpaired
  privileges report blocked without a main-display fallback.
- Live watchdog seam: a responsive helper remains present, recovery is called
  only after consent, and revoked pairing blocks without invoking recovery.
- Refusal when helper is absent: operation is not invoked and A7's
  `privilegeLossMessage()` is surfaced.

These are gate requirements; no build or tests are run in this task, as
explicitly directed. All newly added transport and watchdog tests are source
coverage only and are not evidence of passing execution.

`PrivilegeBootstrapTest` contains deterministic cases for blocked pairing state
and sensitive-code clearing when secure transport is unavailable; illegal
transition/reason checks; malformed endpoint rejection; fake-backed helper
contract round trips and rejection of an unlisted permission/component;
watchdog recovery decisions; live watchdog consent/revocation behavior; and
refusal without invoking an operation when the helper is absent.
`AdbPairingTransportTest` adds Mika's published Curve25519/AES-GCM oracle,
seed-derived M/N checks, a fake-channel full pairing exchange through the real
transport, and precise wrong-code/unreachable/TLS failures. **Not run**, per
task instruction.

Files added or changed for this completion pass:

- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootstrap.kt` — default transport wiring and precise transport failure mapping.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AndroidAdbPairingTransport.kt` — TLS-first ADB pairing client and Android exporter.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AdbEd25519.kt`, `AdbSpake2.kt`, and `AdbPairingAuth.kt` — Mika adaptations.
- `app/src/test/java/com/battlesbudz/jarvis/v2/privilege/AdbPairingTransportTest.kt` — published vector, seed derivation and fake-channel protocol coverage.
- `THIRD_PARTY_NOTICES.md` — Mika Apache-2.0 adaptation attribution.
- `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeWatchdogController.kt` — watchdog state projection and consent-gated recovery decision controller.
- `app/src/test/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootstrapTest.kt` — watchdog controller tests.
- `GATE-A1.md` — remaining gap and gate record updated.

The full A1 record also includes the attempt-2 files listed above: `app/build.gradle.kts`, `app/src/main/aidl/com/battlesbudz/jarvis/v2/privilege/IPrivilegeHelper.aidl`, `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/AndroidAdbEndpointDiscovery.kt`, `app/src/main/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootReceiver.kt`, `app/src/main/AndroidManifest.xml`, `app/src/test/java/com/battlesbudz/jarvis/v2/privilege/PrivilegeBootstrapTest.kt`, and `docs/architecture/README.md`.

## CI gate — TRANSPORT GREEN 2026-11-22 19:55 EST (Run 207)

- CI run 45076306215 ("Android APK", head `8f710daf`, branch
  `feature/app-control`) attempt 4 completed/success
  (2026-11-23T00:55:14Z). Unit suite VERIFIED GREEN per-object
  (15/15 — `PrivilegeBootstrapTest` + `AdbPairingTransportTest`,
  incl. Mika SPAKE2 oracle and fake-channel pairing). All five
  journey profiles green in latest results; 36-foldable-compact
  verified per-object from evidence artifact 14781368036
  (`summary.md` PASS, `report.json` passed=true, errors=[], 82
  instrumentation tests, 0 failures). Consolidate + publish
  success; prerelease `faraday-feature/app-control-build.1976`
  published with `app-compact.apk` and `app-release.apk`.
- The transport CI gate is complete. What remains is the v6 pass
  (shell-UID helper, durable identity, runtime integration,
  heartbeat/recovery, boot restore) and then device proof.

## Device proof — AWAITING DEVICE PROOF (Fold 6, D-02)

- Complete wireless-debugging pairing with no computer — **AWAITING DEVICE PROOF**.
- Reboot: helper returns or reports a precise blocked state — **AWAITING DEVICE PROOF**.
- Revoke the helper: operation stops instead of falling back — **AWAITING DEVICE PROOF**.

## v6 source pass — PARTIAL; no execution checks run

Source changes in this pass add the exported `.helper` handoff provider and
single-use nonce validation, shell `app_process` entry/backend, persisted RSA
ADB key and peer GUID, helper death-token cleanup, a WorkManager health worker,
boot/package-replacement restore scheduling, runtime foreground/task gates,
and the shell-side permission/display/clipboard checks. The handoff protocol
and shell context are adapted from Mika HEAD `2ca391c4c868`; provenance and
Apache-2.0 attribution are in `THIRD_PARTY_NOTICES.md`. The helper entry point
and WorkManager worker have explicit R8 keeps.

Deterministic source tests added in this pass cover each named handoff reject
reason plus accepted validation, command-line nonce exclusion, and the shell
permission/display/clipboard policy. Existing pairing/watchdog tests remain
untouched. No build, test, git, or publish command was run.

**The v6 runtime path is not yet operational end to end.** `PrivilegeSupervisor`
re-discovers the wireless-debugging endpoint on each consented recovery attempt,
but the repo's pairing transport has no authenticated ADB `exec:` channel, so
it currently records `paired ADB exec channel is unavailable` and does not
launch `HelperHandoff.command`. Therefore the helper cannot be established by
this source pass; no device-proof claim is made. The next code gap is the
paired TLS ADB connect/auth/shell stream and wiring it to the detached launch
command. After that, the Fold 6 cases above remain required. The AIDL clipboard
event shape and the precise event payload schema for `injectInput(Bundle)` are
not specified by the charter; this pass supports only the explicit `tap` and
`key` bundle forms and keeps clipboard access closed until S10 supplies its
single-task lock.

No root path or USB-from-computer fallback is in scope (D-01). No voice
capture, model session, or turn-fencing code is in scope.
